package dynamiceconomy.client;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.network.MarketPayloads;
import dynamiceconomy.service.MarketService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What the client knows about the market, filled only from server packets. Display only: every trade is re-checked
 * on the server. The catalog rows are kept for the whole session so item tooltips can show market prices.
 * Render thread only. Deliberately uses no client-only classes, so the network code can reference it on both sides.
 */
public final class ClientMarketState {
    /**
     * The largest balance Numismatics shows. Anything above it is kept in a hidden overflow that can still be spent,
     * so a balance at this value means "at least this much".
     */
    public static final int DISPLAYED_BALANCE_CAP = 1_073_741_823;
    /** The server's default order limit, used until the first snapshot arrives. */
    private static final int DEFAULT_MAX_QUANTITY = 640;

    private static final Map<ResourceLocation, MarketPayloads.Row> rows = new LinkedHashMap<>();
    private static MarketPayloads.PlayerState player = emptyPlayer();
    private static int maxQuantity = DEFAULT_MAX_QUANTITY;
    private static Component status = Component.empty();
    private static boolean statusIsError;
    private static int version;
    @Nullable
    private static MarketPayloads.History history;
    /** The last trade request sent, so a "price changed" reply can show the old total next to the new one. */
    @Nullable
    private static MarketPayloads.TradeRequest lastRequest;

    private ClientMarketState() {
    }

    private static MarketPayloads.PlayerState emptyPlayer() {
        return new MarketPayloads.PlayerState(0, Map.of(), 0, Map.of());
    }

    public static void applySnapshot(MarketPayloads.Snapshot snapshot) {
        rows.clear();
        snapshot.rows().forEach(row -> rows.put(row.item(), row));
        maxQuantity = snapshot.maxQuantity();
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
                Map.copyOf(state.stackRoom()));
        version++;
    }

    /** Remembers a request as it is sent. */
    public static void sent(MarketPayloads.TradeRequest request) {
        lastRequest = request;
    }

    public static void applyOutcome(MarketPayloads.TradeOutcome outcome) {
        DynamicEconomy.LOGGER.debug("Market trade outcome: {}", outcome);
        Component itemName = BuiltInRegistries.ITEM.get(outcome.item()).getDescription();
        if (outcome.result() == MarketService.Result.OK) {
            status = Component.translatable(outcome.buy() ? "screen.dynamic_economy.bought" : "screen.dynamic_economy.sold",
                    outcome.quantity(), itemName, outcome.total());
            statusIsError = false;
        } else if (outcome.result() == MarketService.Result.PRICE_CHANGED) {
            // The server also sent the refreshed row, so the button already shows the new total.
            MarketPayloads.TradeRequest request = lastRequest;
            status = request != null && request.buy() == outcome.buy() && request.item().equals(outcome.item())
                    ? Component.translatable("screen.dynamic_economy.price_changed_from_to",
                            PriceFormat.spurs(request.quotedTotal()), PriceFormat.spurs(outcome.total()))
                    : Component.translatable("screen.dynamic_economy.price_changed_to", PriceFormat.spurs(outcome.total()));
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
        status = Component.empty();
        statusIsError = false;
        version++;
    }

    /** When leaving a server: forget its catalog too. */
    public static void reset() {
        clear();
        rows.clear();
        maxQuantity = DEFAULT_MAX_QUANTITY;
    }

    public static Map<ResourceLocation, MarketPayloads.Row> rows() {
        return rows;
    }

    public static int held(ResourceLocation item) {
        return player.held().getOrDefault(item, 0);
    }

    /** Room in the main inventory for {@code item}, counted as the server counts it for a purchase. */
    public static long room(ResourceLocation item, int maxStackSize) {
        return player.room(item, maxStackSize);
    }

    /** The balance as Numismatics displays it; see {@link #DISPLAYED_BALANCE_CAP}. */
    public static int balance() {
        return player.balance();
    }

    /** Whether the balance looks enough for {@code total}. A capped balance may hide more, so it always looks enough. */
    public static boolean looksAffordable(long total) {
        return player.balance() >= DISPLAYED_BALANCE_CAP || player.balance() >= total;
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
