package dynamiceconomy.network;

import com.mojang.authlib.GameProfile;
import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.MarketConfig;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.bank.Bank;
import dynamiceconomy.bank.BankTeam;
import dynamiceconomy.bank.Denomination;
import dynamiceconomy.bank.WorldBank;
import dynamiceconomy.data.ContractBoard;
import dynamiceconomy.data.ExchangeData;
import dynamiceconomy.data.MarketStockData;
import dynamiceconomy.data.TradeHistory;
import dynamiceconomy.menu.MarketMenu;
import dynamiceconomy.service.BankService;
import dynamiceconomy.service.ContractService;
import dynamiceconomy.service.ExchangeService;
import dynamiceconomy.service.Leaderboard;
import dynamiceconomy.service.MarketService;
import dynamiceconomy.service.MarketVisibility;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * Server side: keeps every player's copy of the catalog, and every open market screen, up to date. Each player is
 * sent only the items they can see ({@link MarketVisibility}); when nothing can be hidden, everyone gets the same.
 * Server thread only.
 */
public final class MarketSync {
    /** How often to re-check viewers' balance and held items for changes made outside the market (pickups, bank). */
    private static final int PLAYER_STATE_INTERVAL_TICKS = 20;

    private static final Map<UUID, MarketPayloads.PlayerState> lastSentState = new HashMap<>();
    /** Players whose stages changed, so what they can see may have; their catalog is resent on the next tick. */
    private static final Set<UUID> staleCatalogs = new HashSet<>();
    private static boolean allCatalogsStale;
    /** Fewest ticks between one player's leaderboard requests, since each one reads every bank account. */
    private static final int LEADERBOARD_COOLDOWN_TICKS = 20;
    private static final Map<UUID, Integer> lastLeaderboardRequest = new HashMap<>();
    /** The item whose exchange book each viewer last asked for, resent to them whenever the exchange changes. */
    private static final Map<UUID, ResourceLocation> watchedBooks = new HashMap<>();
    /** Viewers browsing the exchange's listings, who get them again whenever the exchange changes. */
    private static final Set<UUID> watchingListings = new HashSet<>();
    /** The most bytes of listings sent at once, in order of item ID; the rest are left out and the screen says so. */
    private static final int LISTINGS_BUDGET_BYTES = 512 * 1024;

    private MarketSync() {
    }

    public static List<ServerPlayer> viewers(MinecraftServer server) {
        return server.getPlayerList().getPlayers().stream()
                .filter(p -> p.containerMenu instanceof MarketMenu)
                .toList();
    }

    /** Call right after the market screen opens. */
    public static void sendFullState(ServerPlayer player) {
        // A newly opened screen starts empty, so resend even if nothing changed since the last send.
        lastSentState.remove(player.getUUID());
        sendCatalog(player);
        sendPlayerState(player);
        watchedBooks.remove(player.getUUID());
        watchingListings.remove(player.getUUID());
        if (exchangeVisible(player)) {
            sendExchange(player);
        }
    }

    /** After a catalog reload: prices or listings may have changed for everyone. */
    public static void broadcastSnapshot(MinecraftServer server) {
        // Every player keeps the catalog, so item tooltips can show what the market pays.
        if (MarketVisibility.active()) {
            server.getPlayerList().getPlayers().forEach(MarketSync::sendCatalog);
        } else {
            PacketDistributor.sendToAllPlayers(snapshot(server, null));
        }
        viewers(server).forEach(MarketSync::sendPlayerState);
    }

    /** The catalog for one player, e.g. on joining; their tooltips need it even with the market closed. */
    public static void sendCatalog(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, catalogFor(player));
    }

    /**
     * After a player's stages changed, or null for any player's. Their catalog is resent on the next tick, so a burst
     * of changes (logging in, joining a team) sends it once.
     */
    public static void stagesChanged(@Nullable ServerPlayer player) {
        if (player == null) {
            allCatalogsStale = true;
        } else {
            staleCatalogs.add(player.getUUID());
        }
    }

    /** After an item's stock or price changed: every player who can see it gets the new row in the same tick. */
    public static void broadcastRow(MinecraftServer server, Item item) {
        row(server, item).ifPresent(row -> {
            DynamicEconomy.LOGGER.debug("Market row update for {} (stock {}, factor {})", row.item(), row.stock(), row.factor());
            MarketPayloads.RowUpdate update = new MarketPayloads.RowUpdate(List.of(row));
            if (!MarketVisibility.active()) {
                PacketDistributor.sendToAllPlayers(update);
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!MarketVisibility.hidden(player, item)) {
                    PacketDistributor.sendToPlayer(player, update);
                }
            }
        });
    }

    /** At a recovery step only items whose price is away from base can have moved, so send just those. */
    private static void broadcastRecoveredRows(MinecraftServer server) {
        Map<Item, MarketPayloads.Row> rows = new HashMap<>();
        for (ResourceLocation id : MarketStockData.get(server).factorsView().keySet()) {
            BuiltInRegistries.ITEM.getOptional(id).ifPresent(item -> row(server, item).ifPresent(row -> rows.put(item, row)));
        }
        if (rows.isEmpty()) {
            return;
        }
        if (!MarketVisibility.active()) {
            PacketDistributor.sendToAllPlayers(new MarketPayloads.RowUpdate(List.copyOf(rows.values())));
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            List<MarketPayloads.Row> visible = rows.entrySet().stream()
                    .filter(e -> !MarketVisibility.hidden(player, e.getKey()))
                    .map(Map.Entry::getValue)
                    .toList();
            if (!visible.isEmpty()) {
                PacketDistributor.sendToPlayer(player, new MarketPayloads.RowUpdate(visible));
            }
        }
    }

    /** Resends the catalog to players whose stages changed since the last tick. */
    private static void resendStaleCatalogs(MinecraftServer server) {
        if (!allCatalogsStale && staleCatalogs.isEmpty()) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (allCatalogsStale || staleCatalogs.contains(player.getUUID())) {
                sendCatalog(player);
            }
        }
        allCatalogsStale = false;
        staleCatalogs.clear();
    }

    /** Sends a viewer their balance and held counts, but only if they changed since the last send. */
    public static void sendPlayerState(ServerPlayer player) {
        MarketPayloads.PlayerState state = playerState(player);
        if (!state.equals(lastSentState.get(player.getUUID()))) {
            lastSentState.put(player.getUUID(), state);
            PacketDistributor.sendToPlayer(player, state);
        }
    }

    public static void tick(MinecraftServer server) {
        resendStaleCatalogs(server);
        // Recovery steps happen server-wide at interval boundaries; refresh open screens when any price moved.
        long now = MarketStockData.now(server);
        if (now % MarketConfig.intervalTicks() == 0) {
            broadcastRecoveredRows(server);
        }
        if (server.getTickCount() % PLAYER_STATE_INTERVAL_TICKS != 0) {
            return;
        }
        List<ServerPlayer> viewers = viewers(server);
        lastSentState.keySet().removeIf(id -> viewers.stream().noneMatch(v -> v.getUUID().equals(id)));
        lastLeaderboardRequest.keySet().removeIf(id -> viewers.stream().noneMatch(v -> v.getUUID().equals(id)));
        watchedBooks.keySet().removeIf(id -> viewers.stream().noneMatch(v -> v.getUUID().equals(id)));
        watchingListings.removeIf(id -> viewers.stream().noneMatch(v -> v.getUUID().equals(id)));
        viewers.forEach(MarketSync::sendPlayerState);
    }

    public static void handleTradeRequest(MarketPayloads.TradeRequest request, ServerPlayer player) {
        // Only trade from an open market screen; anything else is a stale or forged packet. A Bank Card's can't trade.
        if (!(player.containerMenu instanceof MarketMenu menu) || !menu.stillValid(player) || menu.bankOnly()) {
            return;
        }
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(request.item());
        MarketService.TradeResult result;
        if (item.isEmpty()) {
            result = new MarketService.TradeResult(MarketService.Result.NOT_LISTED, 0, 0, 0);
        } else if (request.buy()) {
            result = MarketService.tryBuy(player, item.get(), request.quantity(), OptionalLong.of(request.quotedTotal()));
        } else {
            result = MarketService.trySell(player, item.get(), request.quantity(), OptionalLong.of(request.quotedTotal()));
        }

        PacketDistributor.sendToPlayer(player, new MarketPayloads.TradeOutcome(
                request.buy(), request.item(), result.result(), result.quantity(), result.total()));
        if (item.isPresent()) {
            if (result.ok()) {
                broadcastRow(player.server, item.get());
                sendTrades(player);
            } else {
                // A rejected trade usually means the client's view is stale (price, stock), so refresh just this row,
                // unless the player can't see it.
                if (!MarketVisibility.hidden(player, item.get())) {
                    row(player.server, item.get()).ifPresent(row ->
                            PacketDistributor.sendToPlayer(player, new MarketPayloads.RowUpdate(List.of(row))));
                }
            }
        }
        sendPlayerState(player);
    }

    /** Deposit or withdraw from the Bank tab: any open market screen, including a Bank Card's. */
    static void handleBankRequest(MarketPayloads.BankRequest request, ServerPlayer player) {
        if (!(player.containerMenu instanceof MarketMenu menu) || !menu.stillValid(player)) {
            return;
        }
        BankService.Outcome outcome = request.deposit() ? BankService.depositAll(player)
                : request.coin().isPresent() ? BankService.withdrawCoins(player, request.coin().get(), (int) Math.min(request.amount(), Integer.MAX_VALUE))
                : BankService.withdraw(player, request.amount());
        PacketDistributor.sendToPlayer(player, new MarketPayloads.BankOutcome(request.deposit(), outcome.result(), outcome.amount()));
        sendPlayerState(player);
    }

    /** The Bank tab's choice of account: the team's or the player's own. */
    static void handlePayFromRequest(MarketPayloads.PayFromRequest request, ServerPlayer player) {
        if (player.containerMenu instanceof MarketMenu menu && menu.stillValid(player)) {
            WorldBank.of(player.server).payFromTeam(player, request.team());
            sendPlayerState(player);
        }
    }

    static void handleTradesRequest(ServerPlayer player) {
        if (player.containerMenu instanceof MarketMenu menu && menu.stillValid(player)) {
            sendTrades(player);
        }
    }

    /** The player's own recent trades; nobody else's are ever sent. */
    private static void sendTrades(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new MarketPayloads.Trades(System.currentTimeMillis(),
                TradeHistory.get(player.server).recent(player.getUUID())));
    }

    /** The leaderboard as this player sees it, ranked now. Read only on request: when the tab opens. */
    static void handleLeaderboardRequest(ServerPlayer player) {
        if (!(player.containerMenu instanceof MarketMenu menu) || !menu.stillValid(player) || !MarketConfig.leaderboardEnabled()) {
            return;
        }
        int now = player.server.getTickCount();
        Integer last = lastLeaderboardRequest.get(player.getUUID());
        if (last != null && now >= last && now - last < LEADERBOARD_COOLDOWN_TICKS) {
            return;
        }
        lastLeaderboardRequest.put(player.getUUID(), now);
        Leaderboard.View view = Leaderboard.viewFor(player);
        List<MarketPayloads.LeaderboardLine> lines = view.top().stream()
                .map(entry -> new MarketPayloads.LeaderboardLine(entry.name(), entry.balance(), entry.id().equals(player.getUUID())))
                .toList();
        PacketDistributor.sendToPlayer(player, new MarketPayloads.Leaderboard(lines, view.ownRank(), view.ranked()));
    }

    static void handleContractsRequest(ServerPlayer player) {
        if (player.containerMenu instanceof MarketMenu menu && menu.stillValid(player) && !menu.bankOnly()) {
            sendContracts(player);
        }
    }

    /** Claim, deliver or abandon from the Contracts tab of an open market screen (not a Bank Card's). */
    static void handleContractRequest(MarketPayloads.ContractRequest request, ServerPlayer player) {
        if (!(player.containerMenu instanceof MarketMenu menu) || !menu.stillValid(player) || menu.bankOnly()) {
            return;
        }
        ContractService.Outcome outcome = switch (request.action()) {
            case CLAIM -> ContractService.claim(player, request.id());
            case DELIVER -> ContractService.deliver(player, request.id(), request.quantity());
            case ABANDON -> ContractService.abandon(player, request.id());
        };
        PacketDistributor.sendToPlayer(player, new MarketPayloads.ContractOutcome(outcome.action(), outcome.item(),
                outcome.result(), outcome.quantity(), outcome.amount()));
        if (outcome.ok()) {
            broadcastContracts(player.server);
        } else {
            sendContracts(player);
        }
        sendPlayerState(player);
    }

    /** After the board changed: everyone with a market screen open gets it as they see it. */
    public static void broadcastContracts(MinecraftServer server) {
        viewers(server).stream().filter(p -> !((MarketMenu) p.containerMenu).bankOnly()).forEach(MarketSync::sendContracts);
    }

    public static void sendContracts(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, contractsFor(player));
    }

    /** The board as {@code player} sees it. */
    public static MarketPayloads.Contracts contractsFor(ServerPlayer player) {
        ContractBoard board = ContractBoard.get(player.server);
        List<MarketPayloads.ContractLine> lines = ContractService.view(player, board).stream()
                .map(view -> new MarketPayloads.ContractLine(view.contract().id(), view.contract().item(), view.contract().units(),
                        view.contract().delivered(), view.reward(), view.contract().deposit(), view.contract().status(), view.mine(),
                        view.holderName()))
                .toList();
        return new MarketPayloads.Contracts(lines, board.daysLeft(), board.claimedBy(player.getUUID()), MarketConfig.maxActiveContracts());
    }

    /**
     * The Exchange tab's state, and an item's book if asked for, which is then resent whenever the exchange changes.
     * Works with the exchange turned off while the player has items waiting in their box.
     */
    static void handleExchangeRequest(MarketPayloads.ExchangeRequest request, ServerPlayer player) {
        if (!(player.containerMenu instanceof MarketMenu menu) || !menu.stillValid(player) || menu.bankOnly()) {
            return;
        }
        request.book().ifPresentOrElse(item -> watchedBooks.put(player.getUUID(), item), () -> watchedBooks.remove(player.getUUID()));
        if (request.listings()) {
            watchingListings.add(player.getUUID());
        } else {
            watchingListings.remove(player.getUUID());
        }
        sendExchange(player);
        sendBook(player);
        sendListings(player);
    }

    /** List an item from the Exchange tab of an open market screen (not a Bank Card's). */
    static void handleListItem(MarketPayloads.ListItem request, ServerPlayer player) {
        if (!(player.containerMenu instanceof MarketMenu menu) || !menu.stillValid(player) || menu.bankOnly()) {
            return;
        }
        replyExchange(player, ExchangeService.list(player, request.slot(), request.expected(), request.price(), request.quantity()));
    }

    /** Post, cancel or collect from the Exchange tab of an open market screen (not a Bank Card's). */
    static void handleExchangeAction(MarketPayloads.ExchangeAction request, ServerPlayer player) {
        if (!(player.containerMenu instanceof MarketMenu menu) || !menu.stillValid(player) || menu.bankOnly()) {
            return;
        }
        ExchangeService.Outcome outcome = switch (request.action()) {
            case POST -> request.item().flatMap(BuiltInRegistries.ITEM::getOptional)
                    .map(item -> ExchangeService.post(player, item, request.buy(), request.price(), request.quantity()))
                    .orElse(new ExchangeService.Outcome(ExchangeService.Action.POST, MarketService.Result.NOT_LISTED,
                            BuiltInRegistries.ITEM.getDefaultKey(), 0, 0, 0, 0));
            case CANCEL -> ExchangeService.cancel(player, request.orderId());
            case COLLECT -> request.item().isEmpty() && request.orderId() > 0
                    ? ExchangeService.collectParcel(player, request.orderId())
                    : ExchangeService.collect(player, request.item());
            case BUY_LISTING -> ExchangeService.buyListing(player, request.orderId(), request.quantity(), request.price());
            // Listing comes as its own packet, with the item the player saw.
            case LIST -> new ExchangeService.Outcome(ExchangeService.Action.LIST, MarketService.Result.NOT_LISTED,
                    BuiltInRegistries.ITEM.getDefaultKey(), 0, 0, 0, 0);
        };
        replyExchange(player, outcome);
    }

    /** Tells the player how an exchange request went, and refreshes whoever's screen it changed. */
    private static void replyExchange(ServerPlayer player, ExchangeService.Outcome outcome) {
        PacketDistributor.sendToPlayer(player, new MarketPayloads.ExchangeOutcome(outcome.action(), outcome.item(), outcome.result(),
                outcome.quantity(), outcome.filled(), outcome.amount()));
        if (outcome.ok() && outcome.action() != ExchangeService.Action.COLLECT) {
            broadcastExchange(player.server);
        } else {
            sendExchange(player);
            sendBook(player);
        }
        sendPlayerState(player);
    }

    /** After the exchange changed: everyone with a market screen open gets it, and the book they're looking at. */
    public static void broadcastExchange(MinecraftServer server) {
        for (ServerPlayer player : viewers(server)) {
            if (!((MarketMenu) player.containerMenu).bankOnly() && exchangeVisible(player)) {
                sendExchange(player);
                sendBook(player);
                sendListings(player);
            }
        }
    }

    /** Whether the player can use the Exchange tab: while it's on, or while they have items waiting in their box. */
    private static boolean exchangeVisible(ServerPlayer player) {
        return MarketConfig.exchangeEnabled() || ExchangeData.get(player.server).hasBox(player.getUUID());
    }

    public static void sendExchange(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, exchangeFor(player));
    }

    private static void sendBook(ServerPlayer player) {
        ResourceLocation item = watchedBooks.get(player.getUUID());
        if (item != null) {
            ExchangeService.Book book = ExchangeService.book(player, ExchangeData.get(player.server), item, MarketPayloads.ExchangeBook.DEPTH);
            PacketDistributor.sendToPlayer(player, new MarketPayloads.ExchangeBook(book.item(), book.asks(), book.bids()));
        }
    }

    /** Every listing the player may see, if they're browsing them, up to {@link #LISTINGS_BUDGET_BYTES}. */
    private static void sendListings(ServerPlayer player) {
        if (!watchingListings.contains(player.getUUID())) {
            return;
        }
        ExchangeData data = ExchangeData.get(player.server);
        int expiry = MarketConfig.orderExpiryDays();
        long today = ExchangeService.today(player.server);
        List<MarketPayloads.ListingLine> lines = new ArrayList<>();
        int bytes = 0;
        boolean more = false;
        for (ExchangeData.Listing listing : ExchangeService.visibleListings(player, data)) {
            bytes += ExchangeService.encodedSize(listing.stack(), player.server.registryAccess()) + 64;
            if (bytes > LISTINGS_BUDGET_BYTES) {
                more = true;
                break;
            }
            lines.add(listingLine(player, listing, expiry, today));
        }
        PacketDistributor.sendToPlayer(player, new MarketPayloads.ExchangeListings(lines, more));
    }

    private static MarketPayloads.ListingLine listingLine(ServerPlayer viewer, ExchangeData.Listing l, int expiry, long today) {
        String seller = Leaderboard.profile(viewer.server, l.owner()).map(GameProfile::getName).orElse("?");
        return new MarketPayloads.ListingLine(l.id(), l.stack().copy(), l.price(), l.quantity(), l.sold(), seller,
                l.owner().equals(viewer.getUUID()), expiry > 0 ? Math.max(0, l.postedDay() + expiry - today) : -1);
    }

    /** The exchange as {@code player} sees it. */
    public static MarketPayloads.Exchange exchangeFor(ServerPlayer player) {
        ExchangeData data = ExchangeData.get(player.server);
        int expiry = MarketConfig.orderExpiryDays();
        long today = ExchangeService.today(player.server);
        List<MarketPayloads.OrderLine> orders = ExchangeService.ordersOf(player.getUUID(), data).stream()
                .map(o -> new MarketPayloads.OrderLine(o.id(), o.item(), o.buy(), o.price(), o.quantity(), o.filled(),
                        expiry > 0 ? Math.max(0, o.postedDay() + expiry - today) : -1))
                .toList();
        List<MarketPayloads.ListingLine> listings = ExchangeService.listingsOf(player.getUUID(), data).stream()
                .map(l -> listingLine(player, l, expiry, today))
                .toList();
        List<MarketPayloads.BoxLine> box = data.box(player.getUUID()).entrySet().stream()
                .map(e -> new MarketPayloads.BoxLine(e.getKey(), e.getValue()))
                .toList();
        List<MarketPayloads.ParcelLine> parcels = data.parcels(player.getUUID()).stream()
                .map(p -> new MarketPayloads.ParcelLine(p.id(), p.stack().copy(), p.count()))
                .toList();
        return new MarketPayloads.Exchange(ExchangeService.summaries(player, data), orders, listings, box, parcels,
                MarketConfig.maxOpenOrders(), MarketConfig.maxOrderSize(), MarketConfig.exchangeTax(), expiry);
    }

    static void handleHistoryRequest(MarketPayloads.HistoryRequest request, ServerPlayer player) {
        if (!(player.containerMenu instanceof MarketMenu menu) || !menu.stillValid(player)) {
            return;
        }
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(request.item());
        if (item.isEmpty() || MarketVisibility.hidden(player, item.get())) {
            return;
        }
        MarketStockData.History history = MarketStockData.get(player.server).history(request.item());
        PacketDistributor.sendToPlayer(player, new MarketPayloads.History(request.item(), MarketStockData.now(player.server),
                history.ticks(), history.factors()));
    }

    /** The catalog as {@code player} may see it. */
    public static MarketPayloads.Snapshot catalogFor(ServerPlayer player) {
        return snapshot(player.server, MarketVisibility.active() ? player : null);
    }

    /** Every listed item, or only those {@code viewer} can see. */
    private static MarketPayloads.Snapshot snapshot(MinecraftServer server, @Nullable ServerPlayer viewer) {
        List<MarketPayloads.Row> rows = new ArrayList<>();
        for (CatalogEntry entry : MarketCatalog.INSTANCE.entries()) {
            if (viewer == null || !MarketVisibility.hidden(viewer, entry.item())) {
                row(server, entry.item()).ifPresent(rows::add);
            }
        }
        Bank bank = WorldBank.of(server);
        return new MarketPayloads.Snapshot(rows, MarketConfig.maxQuantityPerRequest(), MarketConfig.leaderboardEnabled(),
                MarketConfig.contractsEnabled(), MarketConfig.exchangeEnabled(), bank.kind(), bank.displayCap(), bank.coins());
    }

    private static Optional<MarketPayloads.Row> row(MinecraftServer server, Item item) {
        return MarketService.quote(server, item).map(q -> new MarketPayloads.Row(
                q.entry().itemId(), q.entry().canSell(), q.entry().canBuy(), q.stock(), q.inputs(), q.factor()));
    }

    /**
     * The player's balance and main inventory as the market and exchange see it: tradeable copies of each item, listed
     * or not, empty slots, and the room left in stacks of each of those. One pass over the inventory, so the cost
     * doesn't grow with the size of the catalog.
     */
    public static MarketPayloads.PlayerState playerState(ServerPlayer player) {
        Bank bank = WorldBank.of(player.server);
        long balance = bank.activeAccount(player).map(Bank.Account::balance).orElse(0L);
        String team = bank.team(player).map(BankTeam::name).orElse("");
        List<Denomination> coins = bank.coins();
        Map<ResourceLocation, Integer> held = new HashMap<>();
        Map<ResourceLocation, Integer> stackRoom = new HashMap<>();
        Set<ResourceLocation> ignored = MarketConfig.ignoredComponents();
        int emptySlots = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.isEmpty()) {
                emptySlots++;
                continue;
            }
            Item item = stack.getItem();
            if (ExchangeService.tradeable(item) && CatalogEntry.plain(stack, item, ignored)) {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                held.merge(id, stack.getCount(), Integer::sum);
                stackRoom.merge(id, Math.max(0, stack.getMaxStackSize() - stack.getCount()), Integer::sum);
            }
            // Coins are never tradeable, so their ids can't clash with an item counted above.
            for (Denomination coin : coins) {
                if (BankService.isPlain(stack, coin)) {
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(coin.item());
                    held.merge(id, stack.getCount(), Integer::sum);
                    stackRoom.merge(id, Math.max(0, stack.getMaxStackSize() - stack.getCount()), Integer::sum);
                }
            }
        }
        return new MarketPayloads.PlayerState(balance, held, emptySlots, stackRoom, team, bank.payingFromTeam(player));
    }
}
