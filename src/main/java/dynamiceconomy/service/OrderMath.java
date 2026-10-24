package dynamiceconomy.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.OptionalLong;

/**
 * Quantities for the market screen's "All" and "Max" buttons. They only suggest a quantity: the trade itself still
 * goes through {@link MarketService}, which checks everything again. Pure, so the client and tests use the same code.
 */
public final class OrderMath {
    private OrderMath() {
    }

    /** "All": every copy held, up to the order limit. */
    public static int sellAll(long held, int maxQuantity) {
        return (int) Math.max(0, Math.min(held, maxQuantity));
    }

    /**
     * "Max": the most one order can buy. Bounded by the order limit, the market's stock (empty means unlimited), the
     * room in the inventory, and the balance. The balance is checked against the order's true total, since every unit
     * bought can raise the price; totals only grow with quantity, so a binary search finds the largest that fits.
     */
    public static int maxBuy(PriceModel.Inputs in, double factor, long balance, OptionalLong stock, long room, int maxQuantity) {
        long upper = Math.min(maxQuantity, room);
        if (stock.isPresent()) {
            upper = Math.min(upper, stock.getAsLong());
        }
        int low = 0;
        int high = (int) Math.max(0, upper);
        while (low < high) {
            int mid = low + (high - low + 1) / 2;
            if (affordable(in, factor, mid, balance)) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    /** Whether the balance covers {@code qty} and the total fits in what the bank accepts. */
    public static boolean affordable(PriceModel.Inputs in, double factor, int qty, long balance) {
        PriceModel.Order order = PriceModel.buy(in, factor, qty);
        return !order.overflow() && order.total() <= Integer.MAX_VALUE && order.total() <= balance;
    }

    /** An order's average price per unit, to two decimals. */
    public static BigDecimal average(long total, int qty) {
        return BigDecimal.valueOf(total).divide(BigDecimal.valueOf(Math.max(1, qty)), 2, RoundingMode.HALF_UP);
    }
}
