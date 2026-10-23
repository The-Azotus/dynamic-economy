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
    private static final Map<ResourceLocation, MarketPayloads.Row> rows = new LinkedHashMap<>();
    private static Map<ResourceLocation, Integer> held = Map.of();
    private static int balance;
    private static Component status = Component.empty();
    private static boolean statusIsError;
    private static int version;
    @Nullable
    private static MarketPayloads.History history;

    private ClientMarketState() {
    }

    public static void applySnapshot(MarketPayloads.Snapshot snapshot) {
        rows.clear();
        snapshot.rows().forEach(row -> rows.put(row.item(), row));
        DynamicEconomy.LOGGER.debug("Market snapshot: {} rows", rows.size());
        version++;
    }

    public static void applyRow(MarketPayloads.Row row) {
        DynamicEconomy.LOGGER.debug("Market row update: {} stock={} sell={} buy={}", row.item(), row.stock(), row.sellPrice(), row.buyPrice());
        rows.put(row.item(), row);
        version++;
    }

    public static void applyPlayerState(MarketPayloads.PlayerState state) {
        DynamicEconomy.LOGGER.debug("Market player state: balance={} held={}", state.balance(), state.held());
        balance = state.balance();
        held = Map.copyOf(state.held());
        version++;
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
            status = Component.translatable("screen.dynamic_economy.price_changed_to", outcome.total());
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
        held = Map.of();
        balance = 0;
        status = Component.empty();
        statusIsError = false;
        version++;
    }

    /** When leaving a server: forget its catalog too. */
    public static void reset() {
        clear();
        rows.clear();
    }

    public static Map<ResourceLocation, MarketPayloads.Row> rows() {
        return rows;
    }

    public static int held(ResourceLocation item) {
        return held.getOrDefault(item, 0);
    }

    public static int balance() {
        return balance;
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
