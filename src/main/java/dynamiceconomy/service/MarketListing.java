package dynamiceconomy.service;

import dynamiceconomy.network.MarketPayloads;

import java.util.Comparator;
import java.util.function.Function;

/**
 * How the market screen sorts and filters its list. Display only: hiding an item changes nothing about whether it
 * can be traded. Kept out of the client package so GameTests can check it.
 */
public final class MarketListing {
    private MarketListing() {
    }

    public enum Sort {
        NAME, CHEAPEST, BEST_PAYOUT, STOCK;

        public Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public enum Filter {
        ALL, CAN_BUY, CAN_SELL, HELD;

        public Filter next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /**
     * Whether a row is shown. "Can buy" means the market sells it and has at least one; "held" means the player holds
     * copies the market would accept.
     */
    public static boolean shows(Filter filter, MarketPayloads.Row row, int held) {
        return switch (filter) {
            case ALL -> true;
            case CAN_BUY -> row.canBuy() && row.hasStock(1);
            case CAN_SELL -> row.canSell();
            case HELD -> held > 0;
        };
    }

    /**
     * The list order. Cheapest puts the lowest buy price first, best payout the highest sell price, and stock the most
     * stocked (unlimited first). Items that can't be bought or sold go last in the price sorts; ties go by name.
     */
    public static Comparator<MarketPayloads.Row> order(Sort sort, Function<MarketPayloads.Row, String> name) {
        Comparator<MarketPayloads.Row> byName = Comparator.comparing(name, String.CASE_INSENSITIVE_ORDER);
        return switch (sort) {
            case NAME -> byName;
            case CHEAPEST -> Comparator.comparing((MarketPayloads.Row row) -> !row.canBuy())
                    .thenComparing(MarketPayloads.Row::exactBuyPrice)
                    .thenComparing(byName);
            case BEST_PAYOUT -> Comparator.comparing((MarketPayloads.Row row) -> !row.canSell())
                    .thenComparing(MarketPayloads.Row::exactSellPrice, Comparator.reverseOrder())
                    .thenComparing(byName);
            case STOCK -> Comparator.comparingLong((MarketPayloads.Row row) -> row.stock().orElse(Long.MAX_VALUE))
                    .reversed()
                    .thenComparing(byName);
        };
    }
}
