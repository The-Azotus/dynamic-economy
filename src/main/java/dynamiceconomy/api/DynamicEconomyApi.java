package dynamiceconomy.api;

import com.google.gson.JsonPrimitive;
import dynamiceconomy.bank.Bank;
import dynamiceconomy.bank.BankTeam;
import dynamiceconomy.bank.WorldBank;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.coin.Coins;
import dynamiceconomy.data.ApiPayments;
import dynamiceconomy.data.AuditLog;
import dynamiceconomy.menu.MarketMenu;
import dynamiceconomy.network.MarketSync;
import dynamiceconomy.service.Leaderboard;
import dynamiceconomy.service.MarketService;
import dynamiceconomy.service.MarketVisibility;
import dynamiceconomy.service.PayoutService;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Dynamic-Economy's add-on API: read market prices, trade for a player, read, take and pay money, and hide items from
 * players. Every method is static.
 * <p>
 * Amounts and prices are in bits (1 bit = 1 Numismatics spur, so they mean the same under either bank). One request
 * moves at most {@link Integer#MAX_VALUE} bits, as the banks take no more at once; balances may be larger.
 * <p>
 * Every method that reads or changes the market or the bank must run on the server thread and throws
 * {@link IllegalStateException} otherwise; the few that may be called from any thread say so. Every check happens
 * before anything changes, so a call that doesn't return {@link MarketResult#OK} changed nothing.
 */
public final class DynamicEconomyApi {
    private DynamicEconomyApi() {
    }

    // ---- Market ----------------------------------------------------------------------------------------------

    /**
     * An item's market terms right now, as every player would trade it. Items hidden from some players are included:
     * use {@link #quote(ServerPlayer, Item)} for what one player sees. Server thread only.
     *
     * @return the terms, or empty if the item isn't listed
     */
    public static Optional<MarketQuote> quote(MinecraftServer server, Item item) {
        requireServerThread(server);
        Objects.requireNonNull(item, "item");
        return MarketService.quote(server, item).map(quote -> MarketQuote.from(item, quote));
    }

    /**
     * An item's market terms right now, as {@code player} sees them. Prices are the same for everyone; only which items
     * a player sees differs. Server thread only.
     *
     * @return the terms, or empty if the item isn't listed or is hidden from the player
     */
    public static Optional<MarketQuote> quote(ServerPlayer player, Item item) {
        requireServerThread(player.server);
        Objects.requireNonNull(item, "item");
        if (MarketVisibility.hidden(player, item)) {
            return Optional.empty();
        }
        return quote(player.server, item);
    }

    /**
     * Every item the market lists, hidden ones included, in no particular order. The list can't be changed, and it
     * doesn't follow later catalog reloads: ask again after one. Server thread only.
     */
    public static List<Item> listedItems(MinecraftServer server) {
        requireServerThread(server);
        return MarketCatalog.INSTANCE.entries().stream().map(CatalogEntry::item).toList();
    }

    /**
     * The player sells {@code quantity} plain copies of {@code item} from their main inventory (hotbar and the 27
     * storage slots) to the market, at the current price, paid into the account they trade with. Exactly as the market
     * screen sells, with the same checks, price movement and trade history, and the item's new price is sent to every
     * player. Server thread only.
     * <p>
     * The server's access mode (market blocks only) isn't checked: the add-on decides who may trade, and where. An
     * add-on that swaps items for money must sell them this way rather than {@link #pay}, so the market's price impact
     * and fees apply and buying N then selling N can never make a profit.
     *
     * @return {@link MarketResult#OK} with the units sold and the total paid; otherwise nothing changed. An item hidden
     *         from the player answers {@link MarketResult#NOT_LISTED}.
     */
    public static TradeResult sell(ServerPlayer player, Item item, int quantity) {
        requireServerThread(player.server);
        Objects.requireNonNull(item, "item");
        return traded(player, item, MarketService.trySell(player, item, quantity, OptionalLong.empty()));
    }

    /**
     * The player buys {@code quantity} of {@code item} from the market at the current price, paid from the account they
     * trade with, into their main inventory. Exactly as the market screen buys: the items must fit, nothing is ever
     * dropped, and the item's new price is sent to every player. Server thread only.
     * <p>
     * The server's access mode (market blocks only) isn't checked: the add-on decides who may trade, and where.
     *
     * @return {@link MarketResult#OK} with the units bought and the total charged; otherwise nothing changed. An item
     *         hidden from the player answers {@link MarketResult#NOT_LISTED}.
     */
    public static TradeResult buy(ServerPlayer player, Item item, int quantity) {
        requireServerThread(player.server);
        Objects.requireNonNull(item, "item");
        return traded(player, item, MarketService.tryBuy(player, item, quantity, OptionalLong.empty()));
    }

    private static TradeResult traded(ServerPlayer player, Item item, MarketService.TradeResult result) {
        if (result.ok()) {
            MarketSync.broadcastRow(player.server, item);
            if (player.containerMenu instanceof MarketMenu) {
                MarketSync.sendTrades(player);
            }
        }
        refreshScreen(player);
        return TradeResult.from(result);
    }

    // ---- Money -----------------------------------------------------------------------------------------------

    /**
     * The balance of the account the player trades with, in bits: their team's when they chose to pay from it,
     * otherwise their own. Server thread only.
     * <p>
     * With Numismatics' bank this is the balance Numismatics shows, at most 1,073,741,823: a balance at that value
     * means at least that much, and the rest can still be spent.
     *
     * @return the balance, or empty if the bank can't be reached
     */
    public static OptionalLong balance(ServerPlayer player) {
        requireServerThread(player.server);
        return balanceOf(WorldBank.of(player.server).activeAccount(player));
    }

    /**
     * The balance of a player's own account, in bits, online or not. A player with no account yet gets one, empty.
     * Server thread only.
     * <p>
     * With Numismatics' bank this is the balance Numismatics shows, at most 1,073,741,823: a balance at that value
     * means at least that much, and the rest can still be spent.
     *
     * @return the balance, or empty if the bank can't be reached
     */
    public static OptionalLong balance(MinecraftServer server, UUID player) {
        requireServerThread(server);
        Objects.requireNonNull(player, "player");
        return balanceOf(WorldBank.of(server).accountOf(player));
    }

    private static OptionalLong balanceOf(Optional<Bank.Account> account) {
        return account.isPresent() ? OptionalLong.of(account.get().balance()) : OptionalLong.empty();
    }

    /**
     * Takes {@code amount} bits from the account the player trades with (their team's when they chose to pay from it),
     * all or nothing. The money is destroyed. Writes one line to the audit log, with {@code api:<reason>} as the
     * actor, and refreshes the player's open market screen. Server thread only.
     *
     * @param reason what the money is for, in the add-on's namespace, e.g. {@code mymod:teleport_fee}; it names the
     *               charge in the audit log
     * @return {@link MarketResult#OK} when taken; {@link MarketResult#INVALID_AMOUNT} for an amount of zero or less;
     *         {@link MarketResult#TOO_LARGE} above {@link Integer#MAX_VALUE}; {@link MarketResult#BANK_UNAVAILABLE} if
     *         the bank can't be reached; {@link MarketResult#NO_FUNDS} if the account can't cover it. Only OK changed
     *         anything.
     */
    public static PaymentResult charge(ServerPlayer player, long amount, ResourceLocation reason) {
        MinecraftServer server = player.server;
        requireServerThread(server);
        Objects.requireNonNull(reason, "reason");
        Bank bank = WorldBank.of(server);
        return charge(server, player.getUUID(), activeTeam(bank, player), bank.activeAccount(player), amount, reason);
    }

    /**
     * Takes {@code amount} bits from a player's own account, online or not, all or nothing. The money is destroyed.
     * Writes one line to the audit log, with {@code api:<reason>} as the actor, and refreshes the player's open market
     * screen if they're online. Server thread only.
     *
     * @param reason what the money is for, in the add-on's namespace; it names the charge in the audit log
     * @return as {@link #charge(ServerPlayer, long, ResourceLocation)}
     */
    public static PaymentResult charge(MinecraftServer server, UUID player, long amount, ResourceLocation reason) {
        requireServerThread(server);
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(reason, "reason");
        return charge(server, player, Optional.empty(), WorldBank.of(server).accountOf(player), amount, reason);
    }

    private static PaymentResult charge(MinecraftServer server, UUID player, Optional<BankTeam> team,
                                        Optional<Bank.Account> account, long amount, ResourceLocation reason) {
        Optional<MarketResult> invalid = checkAmount(amount);
        if (invalid.isPresent()) {
            return PaymentResult.refused(invalid.get(), amount);
        }
        if (account.isEmpty()) {
            return PaymentResult.refused(MarketResult.BANK_UNAVAILABLE, amount);
        }
        // The deduction is itself the funds check: it changes nothing when it returns false.
        if (!account.get().deduct((int) amount)) {
            return PaymentResult.refused(MarketResult.NO_FUNDS, amount);
        }
        AuditLog.record(server, "api:" + reason, null, "api charge", target(server, player, team), "ok", null,
                new JsonPrimitive(amount));
        refreshScreen(server, player);
        return new PaymentResult(MarketResult.OK, amount, false);
    }

    /**
     * Pays {@code amount} bits into the account the player trades with (their team's when they chose to pay from it).
     * If the bank can't be reached, the money is owed instead: it is paid as soon as the bank is back (into that team's
     * account while the team still exists), it survives restarts, and it counts on the leaderboard meanwhile. Writes
     * one line to the audit log, with {@code api:<reason>} as the actor, and refreshes the player's open market screen.
     * Server thread only.
     * <p>
     * This creates money. An add-on that pays for items must not take them and call this: it must {@link #sell} them,
     * so the market's price impact and fees apply and buying N then selling N can never make a profit. Use this for
     * rewards that cost the player nothing the market sells.
     *
     * @param reason what the money is for, in the add-on's namespace, e.g. {@code mymod:quest_reward}; it names the
     *               payment in the audit log
     * @return {@link MarketResult#OK} when paid or owed ({@link PaymentResult#owed()} says which);
     *         {@link MarketResult#INVALID_AMOUNT} for an amount of zero or less; {@link MarketResult#TOO_LARGE} above
     *         {@link Integer#MAX_VALUE}. Only OK changed anything.
     */
    public static PaymentResult pay(ServerPlayer player, long amount, ResourceLocation reason) {
        MinecraftServer server = player.server;
        requireServerThread(server);
        Objects.requireNonNull(reason, "reason");
        return pay(server, player.getUUID(), activeTeam(WorldBank.of(server), player), amount, reason);
    }

    /**
     * Pays {@code amount} bits into a player's own account, online or not, or owes it if the bank can't be reached, as
     * {@link #pay(ServerPlayer, long, ResourceLocation)} does. This creates money: see that method. Server thread only.
     *
     * @param reason what the money is for, in the add-on's namespace; it names the payment in the audit log
     * @return as {@link #pay(ServerPlayer, long, ResourceLocation)}
     */
    public static PaymentResult pay(MinecraftServer server, UUID player, long amount, ResourceLocation reason) {
        requireServerThread(server);
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(reason, "reason");
        return pay(server, player, Optional.empty(), amount, reason);
    }

    private static PaymentResult pay(MinecraftServer server, UUID player, Optional<BankTeam> team, long amount,
                                     ResourceLocation reason) {
        Optional<MarketResult> invalid = checkAmount(amount);
        if (invalid.isPresent()) {
            return PaymentResult.refused(invalid.get(), amount);
        }
        boolean paidNow = PayoutService.pay(server, ApiPayments.get(server).owed(), "API payment " + reason, player,
                team.map(BankTeam::id), amount);
        AuditLog.record(server, "api:" + reason, null, "api pay", target(server, player, team),
                paidNow ? "ok" : "owed: bank unreachable", null, new JsonPrimitive(amount));
        refreshScreen(server, player);
        return new PaymentResult(MarketResult.OK, amount, !paidNow);
    }

    private static Optional<MarketResult> checkAmount(long amount) {
        if (amount <= 0) {
            return Optional.of(MarketResult.INVALID_AMOUNT);
        }
        return amount > Integer.MAX_VALUE ? Optional.of(MarketResult.TOO_LARGE) : Optional.empty();
    }

    /** The team whose account the player trades with, if they chose it. */
    private static Optional<BankTeam> activeTeam(Bank bank, ServerPlayer player) {
        return bank.payingFromTeam(player) ? bank.team(player) : Optional.empty();
    }

    /** The audit log's target: the player by name (when known) and UUID, and the team account if it's one. */
    private static String target(MinecraftServer server, UUID player, Optional<BankTeam> team) {
        String name = Leaderboard.profile(server, player).map(profile -> profile.getName() + " (" + player + ")")
                .orElse(player.toString());
        return team.map(t -> name + ", team " + t.name() + " (" + t.id() + ")").orElse(name);
    }

    // ---- Helpers ---------------------------------------------------------------------------------------------

    /**
     * An amount as chat shows it, in the world bank's unit, e.g. "1,234 bits". Server thread only (it reads which bank
     * the world uses).
     */
    public static Component formatMoney(MinecraftServer server, long amount) {
        requireServerThread(server);
        return PayoutService.money(server, amount);
    }

    /** The bank the world keeps players' money in. Server thread only. */
    public static BankType bankType(MinecraftServer server) {
        requireServerThread(server);
        return BankType.from(WorldBank.kind(server));
    }

    /**
     * Whether the world's bank can be reached. When it can't (a Numismatics world without Numismatics), trades and
     * charges are refused and payments are owed. Server thread only.
     */
    public static boolean bankAvailable(MinecraftServer server) {
        requireServerThread(server);
        return WorldBank.available(server);
    }

    /**
     * Whether {@code item} is money: one of Dynamic-Economy's coins or one of Numismatics'. Coins are never listed, as
     * selling them would create money from nothing. Any thread.
     */
    public static boolean isCoin(Item item) {
        return Coins.isCoin(Objects.requireNonNull(item, "item"));
    }

    // ---- Visibility ------------------------------------------------------------------------------------------

    /**
     * Adds a rule that hides items from players, replacing any rule with the same ID. A hidden item is left out of
     * everything that player is sent (market screen, tooltips, contracts, the player exchange), and trading it
     * answers {@link MarketResult#NOT_LISTED}, as for an unlisted item. Prices and stock stay the same for everyone. An item
     * is hidden when any rule, or ProgressiveStages, hides it.
     * <p>
     * {@code hides} returns true for an item the player must not see. It runs on the server thread, very often (for
     * every listed item each time a player's catalog is sent, and on every trade), so it must be cheap and have no side
     * effects. A rule that throws is removed and logged. When its answers change, call {@link #refreshVisibility}.
     * <p>
     * Any thread, also while mods load. With a server running, every player's catalog is sent again on the next tick.
     *
     * @param id the rule's ID, in the add-on's namespace
     */
    public static void addVisibilityRule(ResourceLocation id, BiPredicate<ServerPlayer, Item> hides) {
        MarketVisibility.addRule(id, hides);
        resendAllCatalogs();
    }

    /**
     * Removes the rule with this ID. Any thread. With a server running, every player's catalog is sent again on the
     * next tick.
     *
     * @return whether there was such a rule
     */
    public static boolean removeVisibilityRule(ResourceLocation id) {
        boolean removed = MarketVisibility.removeRule(id);
        if (removed) {
            resendAllCatalogs();
        }
        return removed;
    }

    /**
     * Call after a visibility rule's answers changed for {@code player}, or for anyone ({@code null}). Their catalog is
     * sent again on the next tick, so many calls in one tick send it once. Does nothing without a running server.
     * Server thread only.
     */
    public static void refreshVisibility(@Nullable ServerPlayer player) {
        MinecraftServer server = player != null ? player.server : ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        requireServerThread(server);
        MarketSync.stagesChanged(player);
    }

    /** Whether {@code item} is hidden from {@code player}, by ProgressiveStages or any rule. Server thread only. */
    public static boolean isHidden(ServerPlayer player, Item item) {
        requireServerThread(player.server);
        return MarketVisibility.hidden(player, Objects.requireNonNull(item, "item"));
    }

    // ---- Internals -------------------------------------------------------------------------------------------

    private static void resendAllCatalogs() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            // Runs at once on the server thread, otherwise queued for it.
            server.execute(() -> MarketSync.stagesChanged(null));
        }
    }

    /** Sends an online player with the market screen open their new balance and held counts. */
    private static void refreshScreen(MinecraftServer server, UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            refreshScreen(online);
        }
    }

    private static void refreshScreen(ServerPlayer player) {
        if (player.containerMenu instanceof MarketMenu) {
            MarketSync.sendPlayerState(player);
        }
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Dynamic-Economy's API must be called on the server thread");
        }
    }
}
