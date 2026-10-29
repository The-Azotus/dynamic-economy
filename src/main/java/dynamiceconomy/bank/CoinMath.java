package dynamiceconomy.bank;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Turning an amount into coins and checking it fits, the same way on the server (which pays out) and the client (which
 * previews). Pure arithmetic: no inventory or bank is touched.
 */
public final class CoinMath {
    private CoinMath() {
    }

    /**
     * How many of each coin make exactly {@code amount}, as few coins as possible, largest first. {@code coins} must be
     * largest first and include a coin worth 1, so every amount can be made. Greedy, which gives the fewest coins for
     * both coin sets (powers of 8; Numismatics' 1, 8, 16, 64, 512, 4096); a GameTest checks this.
     */
    public static long[] breakdown(List<Denomination> coins, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Negative amount: " + amount);
        }
        long[] counts = new long[coins.size()];
        long left = amount;
        for (int i = 0; i < coins.size(); i++) {
            counts[i] = left / coins.get(i).value();
            left -= counts[i] * coins.get(i).value();
        }
        if (left != 0) {
            throw new IllegalArgumentException("No coin worth 1 to make " + amount);
        }
        return counts;
    }

    /**
     * Whether {@code counts} of {@code coins} fit in a main inventory with {@code emptySlots} empty slots and
     * {@code stackRoom} room left in its existing stacks of each coin: each kind tops up its own stacks first, then
     * takes whole empty slots.
     */
    public static boolean fits(List<Denomination> coins, long[] counts, int emptySlots, ToIntFunction<Denomination> stackRoom,
                               ToIntFunction<Denomination> maxStackSize) {
        long slotsNeeded = 0;
        for (int i = 0; i < coins.size(); i++) {
            long overflow = Math.max(0, counts[i] - stackRoom.applyAsInt(coins.get(i)));
            int maxStack = maxStackSize.applyAsInt(coins.get(i));
            slotsNeeded += (overflow + maxStack - 1) / maxStack;
        }
        return slotsNeeded <= emptySlots;
    }

    /** What {@code counts} of {@code coins} are worth. */
    public static long value(List<Denomination> coins, long[] counts) {
        long total = 0;
        for (int i = 0; i < coins.size(); i++) {
            total += counts[i] * coins.get(i).value();
        }
        return total;
    }
}
