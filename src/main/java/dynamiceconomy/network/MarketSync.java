package dynamiceconomy.network;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.MarketConfig;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.bank.Bank;
import dynamiceconomy.bank.WorldBank;
import dynamiceconomy.data.MarketStockData;
import dynamiceconomy.data.TradeHistory;
import dynamiceconomy.menu.MarketMenu;
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
        viewers.forEach(MarketSync::sendPlayerState);
    }

    static void handleTradeRequest(MarketPayloads.TradeRequest request, ServerPlayer player) {
        // Only trade from an open market screen; anything else is a stale or forged packet.
        if (!(player.containerMenu instanceof MarketMenu menu) || !menu.stillValid(player)) {
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
                bank.kind(), bank.displayCap());
    }

    private static Optional<MarketPayloads.Row> row(MinecraftServer server, Item item) {
        return MarketService.quote(server, item).map(q -> new MarketPayloads.Row(
                q.entry().itemId(), q.entry().canSell(), q.entry().canBuy(), q.stock(), q.inputs(), q.factor()));
    }

    /**
     * The player's balance and main inventory as the market sees it: sellable copies of each listed item, empty slots,
     * and the room left in stacks of each listed item, counted exactly as {@code MarketService} counts space for a
     * purchase. One pass over the inventory, so the cost doesn't grow with the size of the catalog.
     */
    public static MarketPayloads.PlayerState playerState(ServerPlayer player) {
        long balance = WorldBank.of(player.server).account(player).map(Bank.Account::balance).orElse(0L);
        Map<ResourceLocation, Integer> held = new HashMap<>();
        Map<ResourceLocation, Integer> stackRoom = new HashMap<>();
        int emptySlots = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.isEmpty()) {
                emptySlots++;
                continue;
            }
            MarketCatalog.INSTANCE.get(stack.getItem()).filter(entry -> entry.matches(stack)).ifPresent(entry -> {
                held.merge(entry.itemId(), stack.getCount(), Integer::sum);
                stackRoom.merge(entry.itemId(), Math.max(0, stack.getMaxStackSize() - stack.getCount()), Integer::sum);
            });
        }
        return new MarketPayloads.PlayerState(balance, held, emptySlots, stackRoom);
    }
}
