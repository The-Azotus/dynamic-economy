package dynamiceconomy.client;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.bank.BankKind;
import dynamiceconomy.bank.CoinMath;
import dynamiceconomy.bank.Denomination;
import dynamiceconomy.coin.Coins;
import dynamiceconomy.service.BankService;
import dynamiceconomy.network.MarketPayloads;
import dynamiceconomy.service.MarketService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the client knows about the market, filled only from server packets. Display only: every trade is re-checked
 * on the server. The catalog rows are kept for the whole session so item tooltips can show market prices.
 * Render thread only. Deliberately uses no client-only classes, so the network code can reference it on both sides.
 */
public final class ClientMarketState {
    /** The server's default order limit, used until the first snapshot arrives. */
    private static final int DEFAULT_MAX_QUANTITY = 640;

    private static final Map<ResourceLocation, MarketPayloads.Row> rows = new LinkedHashMap<>();
    private static MarketPayloads.PlayerState player = emptyPlayer();
    private static int maxQuantity = DEFAULT_MAX_QUANTITY;
    private static boolean leaderboardEnabled = true;
    private static boolean contractsEnabled = true;
    private static boolean exchangeEnabled;
    /** The exchange, once the screen has asked for it (or the server sent it with the screen, when there's a box). */
    @Nullable
    private static MarketPayloads.Exchange exchange;
    /** The book of the item the Exchange tab last asked about. */
    @Nullable
    private static MarketPayloads.ExchangeBook exchangeBook;
    /** Every listing, once the Exchange tab has asked to browse them. */
    @Nullable
    private static MarketPayloads.ExchangeListings exchangeListings;
    /** The contract board, once the screen has asked for it. */
    @Nullable
    private static MarketPayloads.Contracts contracts;
    private static BankKind bank = BankKind.OWN;
    private static long balanceCap = Long.MAX_VALUE;
    /** The coins the world's bank moves, largest first. */
    private static List<Denomination> coins = List.of();
    /** The leaderboard, once the screen has asked for it. */
    @Nullable
    private static MarketPayloads.Leaderboard leaderboard;
    private static Component status = Component.empty();
    private static boolean statusIsError;
    private static int version;
    @Nullable
    private static MarketPayloads.History history;
    /** The last trade request sent, so a "price changed" reply can show the old total next to the new one. */
    @Nullable
    private static MarketPayloads.TradeRequest lastRequest;
    /** This player's recent trades, once the screen has asked for them. */
    @Nullable
    private static MarketPayloads.Trades trades;

    private ClientMarketState() {
    }

    private static MarketPayloads.PlayerState emptyPlayer() {
        return new MarketPayloads.PlayerState(0, Map.of(), 0, Map.of(), "", false);
    }

    public static void applySnapshot(MarketPayloads.Snapshot snapshot) {
        rows.clear();
        snapshot.rows().forEach(row -> rows.put(row.item(), row));
        maxQuantity = snapshot.maxQuantity();
        leaderboardEnabled = snapshot.leaderboardEnabled();
        contractsEnabled = snapshot.contractsEnabled();
        exchangeEnabled = snapshot.exchangeEnabled();
        bank = snapshot.bank();
        balanceCap = snapshot.balanceCap();
        coins = List.copyOf(snapshot.coins());
        DynamicEconomy.LOGGER.debug("Market snapshot: {} rows, order limit {}", rows.size(), maxQuantity);
        version++;
    }

    public static void applyRow(MarketPayloads.Row row) {
        DynamicEconomy.LOGGER.debug("Market row update: {} stock={} sell={} buy={}", row.item(), row.stock(), row.sellPrice(), row.buyPrice());
        rows.put(row.item(), row);
        version++;
    }

    public static void applyPlayerState(MarketPayloads.PlayerState state) {
        DynamicEconomy.LOGGER.debug("Market player state: balance={} held={} emptySlots={}", state.balance(), state.held(), state.emptySlots());
        player = new MarketPayloads.PlayerState(state.balance(), Map.copyOf(state.held()), state.emptySlots(),
                Map.copyOf(state.stackRoom()), state.team(), state.usingTeam());
        version++;
    }

    public static void applyTrades(MarketPayloads.Trades received) {
        trades = received;
        version++;
    }

    public static void applyLeaderboard(MarketPayloads.Leaderboard received) {
        leaderboard = received;
        version++;
    }

    public static void applyContracts(MarketPayloads.Contracts received) {
        contracts = received;
        version++;
    }

    /** The contract board, or null until it's been received. */
    @Nullable
    public static MarketPayloads.Contracts contracts() {
        return contracts;
    }

    /** Whether the server offers contracts. */
    public static boolean contractsEnabled() {
        return contractsEnabled;
    }

    public static void applyExchange(MarketPayloads.Exchange received) {
        exchange = received;
        version++;
    }

    public static void applyExchangeBook(MarketPayloads.ExchangeBook received) {
        exchangeBook = received;
        version++;
    }

    public static void applyExchangeListings(MarketPayloads.ExchangeListings received) {
        exchangeListings = received;
        version++;
    }

    /** Every listing the player may see, or null until they've been received. */
    @Nullable
    public static MarketPayloads.ExchangeListings exchangeListings() {
        return exchangeListings;
    }

    /** The exchange, or null until it's been received. */
    @Nullable
    public static MarketPayloads.Exchange exchange() {
        return exchange;
    }

    /** The book for {@code item}, if that's the one last received. */
    @Nullable
    public static MarketPayloads.ExchangeBook exchangeBook(ResourceLocation item) {
        return exchangeBook != null && exchangeBook.item().equals(item) ? exchangeBook : null;
    }

    /** Whether the server runs the exchange. */
    public static boolean exchangeEnabled() {
        return exchangeEnabled;
    }

    public static void applyExchangeOutcome(MarketPayloads.ExchangeOutcome outcome) {
        DynamicEconomy.LOGGER.debug("Exchange outcome: {}", outcome);
        // A listing is named as listed (a custom name, say); anything else by its item.
        Component itemName = outcome.listed().isEmpty()
                ? BuiltInRegistries.ITEM.get(outcome.item()).getDescription()
                : outcome.listed().getHoverName();
        Component amount = PriceFormat.money(PriceFormat.spurs(outcome.amount()));
        if (outcome.result() == MarketService.Result.OK) {
            status = switch (outcome.action()) {
                case POST -> outcome.filled() == 0
                        ? Component.translatable("screen.dynamic_economy.exchange.posted", outcome.quantity(), itemName)
                        : outcome.filled() < outcome.quantity()
                        ? Component.translatable("screen.dynamic_economy.exchange.posted_part", outcome.quantity(), itemName, outcome.filled(), amount)
                        : Component.translatable("screen.dynamic_economy.exchange.filled", outcome.quantity(), itemName, amount);
                case CANCEL -> !outcome.listed().isEmpty()
                        ? Component.translatable("screen.dynamic_economy.exchange.cancelled_listing", outcome.quantity(), itemName)
                        : outcome.amount() > 0
                        ? Component.translatable("screen.dynamic_economy.exchange.cancelled_money", itemName, amount)
                        : Component.translatable("screen.dynamic_economy.exchange.cancelled_items", outcome.quantity(), itemName);
                case COLLECT -> Component.translatable("screen.dynamic_economy.exchange.collected", outcome.quantity());
                case LIST -> Component.translatable("screen.dynamic_economy.exchange.listed", outcome.quantity(), itemName);
                case BUY_LISTING -> Component.translatable("screen.dynamic_economy.exchange.bought_listing", outcome.quantity(), itemName, amount);
            };
            statusIsError = false;
        } else if (outcome.result() == MarketService.Result.NOT_LISTED) {
            // Any item but coins can be traded here, so "not on the market" would mislead.
            status = Component.translatable("screen.dynamic_economy.exchange.not_tradeable");
            statusIsError = true;
        } else {
            status = Component.translatable("screen.dynamic_economy.result." + outcome.result().name().toLowerCase(Locale.ROOT));
            statusIsError = true;
        }
        version++;
    }

    public static void applyContractOutcome(MarketPayloads.ContractOutcome outcome) {
        DynamicEconomy.LOGGER.debug("Contract outcome: {}", outcome);
        Component itemName = BuiltInRegistries.ITEM.get(outcome.item()).getDescription();
        Component amount = PriceFormat.money(PriceFormat.spurs(outcome.amount()));
        if (outcome.result() == MarketService.Result.OK) {
            status = switch (outcome.action()) {
                case CLAIM -> Component.translatable("screen.dynamic_economy.contracts.claimed", itemName, amount);
                case DELIVER -> outcome.amount() > 0
                        ? Component.translatable("screen.dynamic_economy.contracts.completed", itemName, amount)
                        : Component.translatable("screen.dynamic_economy.contracts.delivered", outcome.quantity(), itemName);
                case ABANDON -> Component.translatable("screen.dynamic_economy.contracts.abandoned", itemName, amount);
            };
            statusIsError = false;
        } else {
            status = Component.translatable("screen.dynamic_economy.result." + outcome.result().name().toLowerCase(Locale.ROOT));
            statusIsError = true;
        }
        version++;
    }

    /** The leaderboard, or null until it's been received. */
    @Nullable
    public static MarketPayloads.Leaderboard leaderboard() {
        return leaderboard;
    }

    /** Whether the server has the leaderboard turned on. */
    public static boolean leaderboardEnabled() {
        return leaderboardEnabled;
    }

    /** The player's recent trades, newest first, or null until they've been received. */
    @Nullable
    public static MarketPayloads.Trades trades() {
        return trades;
    }

    /** Remembers a request as it is sent. */
    public static void sent(MarketPayloads.TradeRequest request) {
        lastRequest = request;
    }

    public static void applyBankOutcome(MarketPayloads.BankOutcome outcome) {
        DynamicEconomy.LOGGER.debug("Bank outcome: {}", outcome);
        if (outcome.result() == BankService.Result.OK) {
            status = Component.translatable(outcome.deposit() ? "screen.dynamic_economy.bank.deposited" : "screen.dynamic_economy.bank.withdrew",
                    PriceFormat.money(PriceFormat.spurs(outcome.amount())));
            statusIsError = false;
        } else {
            status = Component.translatable("screen.dynamic_economy.bank.result." + outcome.result().name().toLowerCase(Locale.ROOT));
            statusIsError = true;
        }
        version++;
    }

    public static void applyOutcome(MarketPayloads.TradeOutcome outcome) {
        DynamicEconomy.LOGGER.debug("Market trade outcome: {}", outcome);
        Component itemName = BuiltInRegistries.ITEM.get(outcome.item()).getDescription();
        if (outcome.result() == MarketService.Result.OK) {
            status = Component.translatable(outcome.buy() ? "screen.dynamic_economy.bought" : "screen.dynamic_economy.sold",
                    outcome.quantity(), itemName, PriceFormat.money(PriceFormat.spurs(outcome.total())));
            statusIsError = false;
        } else if (outcome.result() == MarketService.Result.PRICE_CHANGED) {
            // The server also sent the refreshed row, so the button already shows the new total.
            MarketPayloads.TradeRequest request = lastRequest;
            status = request != null && request.buy() == outcome.buy() && request.item().equals(outcome.item())
                    ? Component.translatable("screen.dynamic_economy.price_changed_from_to",
                            PriceFormat.money(PriceFormat.spurs(request.quotedTotal())),
                            PriceFormat.money(PriceFormat.spurs(outcome.total())))
                    : Component.translatable("screen.dynamic_economy.price_changed_to",
                            PriceFormat.money(PriceFormat.spurs(outcome.total())));
            statusIsError = true;
        } else {
            status = Component.translatable("screen.dynamic_economy.result." + outcome.result().name().toLowerCase(Locale.ROOT));
            statusIsError = true;
        }
        version++;
    }

    /** Only the most recently requested item's history is kept: the screen shows one at a time. */
    public static void applyHistory(MarketPayloads.History received) {
        history = received;
        version++;
    }

    /** The history for {@code item}, if that's the one last received. */
    @Nullable
    public static MarketPayloads.History history(ResourceLocation item) {
        return history != null && history.item().equals(item) ? history : null;
    }

    /** When the market screen closes. The catalog is kept: item tooltips use it with the screen closed. */
    public static void clear() {
        history = null;
        player = emptyPlayer();
        lastRequest = null;
        trades = null;
        leaderboard = null;
        contracts = null;
        exchange = null;
        exchangeBook = null;
        exchangeListings = null;
        status = Component.empty();
        statusIsError = false;
        version++;
    }

    /** When leaving a server: forget its catalog too. */
    public static void reset() {
        clear();
        rows.clear();
        maxQuantity = DEFAULT_MAX_QUANTITY;
        leaderboardEnabled = true;
        contractsEnabled = true;
        exchangeEnabled = false;
        bank = BankKind.OWN;
        balanceCap = Long.MAX_VALUE;
        coins = List.of();
    }

    public static Map<ResourceLocation, MarketPayloads.Row> rows() {
        return rows;
    }

    public static int held(ResourceLocation item) {
        return player.held().getOrDefault(item, 0);
    }

    /** Every item the main inventory holds tradeable copies of, coins left out. */
    public static List<ResourceLocation> heldItems() {
        return player.held().keySet().stream().filter(id -> !Coins.isCoin(BuiltInRegistries.ITEM.get(id))).toList();
    }

    /** Room in the main inventory for {@code item}, counted as the server counts it for a purchase. */
    public static long room(ResourceLocation item, int maxStackSize) {
        return player.room(item, maxStackSize);
    }

    /** The balance as the bank displays it; see {@link #balanceCap()}. */
    public static long balance() {
        return player.balance();
    }

    /** The player's team's name, or "" when they're in none (or the bank has no team accounts). */
    public static String team() {
        return player.team();
    }

    /** Whether {@link #balance()} and every trade use the team's account. */
    public static boolean usingTeam() {
        return player.usingTeam();
    }

    /** The world's bank, which names the money unit. */
    public static BankKind bank() {
        return bank;
    }

    /**
     * The largest balance the bank shows. Numismatics keeps anything above it in a hidden overflow that can still be
     * spent, so a balance at this value means "at least this much". {@link Long#MAX_VALUE} for our own bank.
     */
    public static long balanceCap() {
        return balanceCap;
    }

    /** The coins the world's bank moves, largest first; empty until the server says, or if it can't find them. */
    public static List<Denomination> coins() {
        return coins;
    }

    /** How many plain copies of {@code coin} the main inventory holds. */
    public static int held(Denomination coin) {
        return held(BuiltInRegistries.ITEM.getKey(coin.item()));
    }

    /** What every coin in the main inventory is worth: what "deposit all" would pay in. */
    public static long carriedCoinValue() {
        long total = 0;
        for (Denomination coin : coins) {
            total += (long) held(coin) * coin.value();
        }
        return total;
    }

    /** Whether {@code counts} of {@link #coins()} would fit in the main inventory, counted as the server counts it. */
    public static boolean coinsFit(long[] counts) {
        return CoinMath.fits(coins, counts, player.emptySlots(),
                coin -> player.stackRoom().getOrDefault(BuiltInRegistries.ITEM.getKey(coin.item()), 0),
                coin -> new net.minecraft.world.item.ItemStack(coin.item()).getMaxStackSize());
    }

    /** Whether {@code balance} is at the cap, so it may hide more. */
    public static boolean capped(long balance) {
        return balance >= balanceCap;
    }

    /** Whether the balance looks enough for {@code total}. A capped balance may hide more, so it always looks enough. */
    public static boolean looksAffordable(long total) {
        return capped(player.balance()) || player.balance() >= total;
    }

    /** The server's limit on one order's quantity. */
    public static int maxQuantity() {
        return maxQuantity;
    }

    public static Component status() {
        return status;
    }

    public static boolean statusIsError() {
        return statusIsError;
    }

    /** Bumped on every change so the screen knows when to rebuild its filtered list. */
    public static int version() {
        return version;
    }
}
