package dynamiceconomy.service;

import dynamiceconomy.bank.Bank;
import dynamiceconomy.bank.CoinMath;
import dynamiceconomy.bank.Denomination;
import dynamiceconomy.bank.WorldBank;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * Moves money between a player's coins and their active account in the world's bank (their own, or their team's if
 * they chose it): the only way coins become money and back. The market itself never pays or takes coins.
 * <p>
 * Same rules as {@link MarketService}: server thread only, every check before anything changes, and once something has
 * changed no remaining step can fail. Only the main inventory is read or written, only plain coins (no renamed or
 * otherwise changed copies) count, and nothing is ever dropped on the ground.
 */
public final class BankService {
    private BankService() {
    }

    public enum Result {
        OK,
        /** The main inventory holds none of the bank's coins. */
        NO_COINS,
        NO_FUNDS,
        /** The coins wouldn't all fit in the main inventory. */
        NO_SPACE,
        /** More than one transaction can carry ({@code Integer.MAX_VALUE}). */
        TOO_LARGE,
        /** Zero or negative. */
        INVALID_AMOUNT,
        /** The world's bank can't be reached, or its coins aren't known. */
        BANK_UNAVAILABLE
    }

    /** {@code amount} is in bits (= spurs): what was deposited or withdrawn, or 0 if nothing was. */
    public record Outcome(Result result, long amount) {
        static Outcome fail(Result result) {
            return new Outcome(result, 0);
        }

        public boolean ok() {
            return result == Result.OK;
        }
    }

    /** Pays every plain coin of the bank's coin set in the player's main inventory into their active account. */
    public static Outcome depositAll(ServerPlayer player) {
        requireServerThread(player);
        Bank bank = WorldBank.of(player.server);
        List<Denomination> coins = bank.coins();
        Optional<Bank.Account> account = bank.activeAccount(player);
        if (account.isEmpty() || coins.isEmpty()) {
            return Outcome.fail(Result.BANK_UNAVAILABLE);
        }
        NonNullList<ItemStack> items = player.getInventory().items;
        long total = 0;
        for (ItemStack stack : items) {
            Denomination coin = coinOf(coins, stack);
            if (coin != null) {
                total += (long) coin.value() * stack.getCount();
            }
        }
        if (total == 0) {
            return Outcome.fail(Result.NO_COINS);
        }

        // Nothing has changed yet. Everything below always succeeds: a deposit can't fail.
        for (int slot = 0; slot < items.size(); slot++) {
            if (coinOf(coins, items.get(slot)) != null) {
                items.set(slot, ItemStack.EMPTY);
            }
        }
        player.getInventory().setChanged();
        // A main inventory can hold more than one transaction's worth (36 stacks of Omegas), so pay it in parts.
        for (long left = total; left > 0; left -= Integer.MAX_VALUE) {
            account.get().deposit((int) Math.min(left, Integer.MAX_VALUE));
        }
        return new Outcome(Result.OK, total);
    }

    /**
     * Takes {@code amount} from the player's active account and gives it to them as the fewest coins, only if they all
     * fit in the main inventory.
     */
    public static Outcome withdraw(ServerPlayer player, long amount) {
        requireServerThread(player);
        if (amount <= 0) {
            return Outcome.fail(Result.INVALID_AMOUNT);
        }
        if (amount > Integer.MAX_VALUE) {
            return Outcome.fail(Result.TOO_LARGE);
        }
        Bank bank = WorldBank.of(player.server);
        List<Denomination> coins = bank.coins();
        Optional<Bank.Account> account = bank.activeAccount(player);
        if (account.isEmpty() || coins.isEmpty()) {
            return Outcome.fail(Result.BANK_UNAVAILABLE);
        }
        return pay(player, account.get(), coins, amount, CoinMath.breakdown(coins, amount));
    }

    /**
     * Takes {@code count} of one coin's worth from the player's active account and gives exactly that many of that
     * coin, only if they all fit in the main inventory. {@code coin} must be one of the bank's coins.
     */
    public static Outcome withdrawCoins(ServerPlayer player, Item coin, int count) {
        requireServerThread(player);
        Bank bank = WorldBank.of(player.server);
        List<Denomination> coins = bank.coins();
        Optional<Bank.Account> account = bank.activeAccount(player);
        if (account.isEmpty() || coins.isEmpty()) {
            return Outcome.fail(Result.BANK_UNAVAILABLE);
        }
        int index = -1;
        for (int i = 0; i < coins.size(); i++) {
            if (coins.get(i).item() == coin) {
                index = i;
            }
        }
        if (count <= 0 || index < 0) {
            return Outcome.fail(Result.INVALID_AMOUNT);
        }
        long amount = (long) coins.get(index).value() * count;
        if (amount > Integer.MAX_VALUE) {
            return Outcome.fail(Result.TOO_LARGE);
        }
        long[] counts = new long[coins.size()];
        counts[index] = count;
        return pay(player, account.get(), coins, amount, counts);
    }

    /** Takes {@code amount} (at most {@code Integer.MAX_VALUE}) and gives {@code counts} of each coin, worth as much. */
    private static Outcome pay(ServerPlayer player, Bank.Account account, List<Denomination> coins, long amount, long[] counts) {
        NonNullList<ItemStack> items = player.getInventory().items;
        if (!CoinMath.fits(coins, counts, emptySlots(items), coin -> stackRoom(items, coin), BankService::maxStackSize)) {
            return Outcome.fail(Result.NO_SPACE);
        }
        // The deduction is itself the funds check: it changes nothing when it returns false.
        if (!account.deduct((int) amount)) {
            return Outcome.fail(Result.NO_FUNDS);
        }

        // Money has been taken. Everything below always succeeds: the room was checked above.
        for (int i = 0; i < coins.size(); i++) {
            insert(items, coins.get(i), counts[i]);
        }
        player.getInventory().setChanged();
        return new Outcome(Result.OK, amount);
    }

    /** The coin a stack is, if it's a plain coin of this set. */
    @Nullable
    private static Denomination coinOf(List<Denomination> coins, ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        for (Denomination coin : coins) {
            if (isPlain(stack, coin)) {
                return coin;
            }
        }
        return null;
    }

    /** Whether {@code stack} is {@code coin} with nothing changed about it, so it stacks with a fresh one. */
    public static boolean isPlain(ItemStack stack, Denomination coin) {
        return stack.is(coin.item()) && ItemStack.isSameItemSameComponents(stack, new ItemStack(coin.item()));
    }

    public static int maxStackSize(Denomination coin) {
        return new ItemStack(coin.item()).getMaxStackSize();
    }

    public static int emptySlots(NonNullList<ItemStack> items) {
        int empty = 0;
        for (ItemStack stack : items) {
            if (stack.isEmpty()) {
                empty++;
            }
        }
        return empty;
    }

    /** Room left in the existing plain stacks of {@code coin}. */
    public static int stackRoom(NonNullList<ItemStack> items, Denomination coin) {
        int room = 0;
        for (ItemStack stack : items) {
            if (isPlain(stack, coin)) {
                room += Math.max(0, stack.getMaxStackSize() - stack.getCount());
            }
        }
        return room;
    }

    /** Tops up existing stacks first, then fills empty slots. The caller has checked the room. */
    private static void insert(NonNullList<ItemStack> items, Denomination coin, long count) {
        int maxStack = maxStackSize(coin);
        long remaining = count;
        for (int slot = 0; slot < items.size() && remaining > 0; slot++) {
            ItemStack stack = items.get(slot);
            if (isPlain(stack, coin) && stack.getCount() < maxStack) {
                int added = (int) Math.min(remaining, maxStack - stack.getCount());
                stack.grow(added);
                remaining -= added;
            }
        }
        for (int slot = 0; slot < items.size() && remaining > 0; slot++) {
            if (items.get(slot).isEmpty()) {
                int added = (int) Math.min(remaining, maxStack);
                items.set(slot, new ItemStack(coin.item(), added));
                remaining -= added;
            }
        }
        if (remaining > 0) {
            throw new IllegalStateException("Coin room check and insert disagree; " + remaining + " coins lost");
        }
    }

    private static void requireServerThread(ServerPlayer player) {
        if (!player.server.isSameThread()) {
            throw new IllegalStateException("Bank transactions must run on the server thread");
        }
    }
}
