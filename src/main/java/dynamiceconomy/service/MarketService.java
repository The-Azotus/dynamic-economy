package dynamiceconomy.service;

import dynamiceconomy.MarketConfig;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.compat.NumismaticsBridge;
import dynamiceconomy.data.MarketStockData;
import net.minecraft.core.NonNullList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * The only code that changes market stock or player money.
 * <p>
 * Every check runs before anything changes, and once the first change is made no remaining step can fail. That holds
 * because everything runs on the server thread, so nothing else can touch the inventory, stock or bank in between.
 * Only the player's main inventory (hotbar plus the 27 storage slots) is read or written; armor, offhand and modded
 * slots are ignored.
 */
public final class MarketService {
    private MarketService() {
    }

    public enum Result {
        OK, NOT_LISTED, SELL_DISABLED, BUY_DISABLED, NOT_ENOUGH_ITEMS, NO_STOCK, NO_FUNDS, NO_SPACE, PRICE_CHANGED,
        TOO_LARGE,
        /** Quantity was zero or negative. */
        INVALID_QUANTITY,
        /** The Numismatics bank API is missing or changed. */
        BANK_UNAVAILABLE
    }

    /**
     * {@code unitPrice} (what the first unit traded at) and {@code total} are in spurs, and are zero unless the trade
     * got as far as pricing.
     */
    public record TradeResult(Result result, int quantity, long unitPrice, long total) {
        static TradeResult fail(Result result) {
            return new TradeResult(result, 0, 0, 0);
        }

        public boolean ok() {
            return result == Result.OK;
        }
    }

    /**
     * An item's current market terms.
     *
     * @param factor    the supply/demand factor right now, recovery applied
     * @param sellPrice what the market pays for the next unit
     * @param buyPrice  what the market charges for the next unit
     * @param stock     what the market can sell; empty means unlimited
     */
    public record Quote(CatalogEntry entry, PriceModel.Inputs inputs, double factor, long sellPrice, long buyPrice,
                        OptionalLong stock) {
    }

    public static Optional<Quote> quote(MinecraftServer server, Item item) {
        MarketStockData market = MarketStockData.get(server);
        long now = MarketStockData.now(server);
        return MarketCatalog.INSTANCE.get(item).map(entry -> {
            PriceModel.Inputs inputs = PriceModel.inputs(entry);
            double factor = currentFactor(entry, inputs, market, now);
            return new Quote(entry, inputs, factor, PriceModel.unitSellPrice(inputs, factor),
                    PriceModel.unitBuyPrice(inputs, factor), availableStock(entry, market));
        });
    }

    /** The factor an item trades at right now: stored value, recovered to {@code now}, with the model's limits. */
    public static double currentFactor(CatalogEntry entry, PriceModel.Inputs inputs, MarketStockData market, long now) {
        double stored = market.factor(entry.itemId(), now, inputs.recovery(), MarketConfig.intervalTicks());
        return PriceModel.effectiveFactor(inputs, stored);
    }

    /**
     * What the market can sell of an item: empty for unlimited items, otherwise the stored stock. Unlimited is kept
     * out of band rather than as a sentinel number so it can never leak into stock arithmetic.
     */
    public static OptionalLong availableStock(CatalogEntry entry, MarketStockData market) {
        return entry.unlimitedStock() ? OptionalLong.empty() : OptionalLong.of(market.getStock(entry.itemId()));
    }

    /**
     * The player sells {@code qty} of {@code item} to the market. For an unlimited item the player is paid normally,
     * the items leave the game and stored stock is not touched.
     *
     * @param expectedTotal the total the player was shown; empty to accept the current price
     */
    public static TradeResult trySell(ServerPlayer player, Item item, int qty, OptionalLong expectedTotal) {
        requireServerThread(player);
        Optional<CatalogEntry> listing = MarketCatalog.INSTANCE.get(item);
        if (listing.isEmpty()) {
            return TradeResult.fail(Result.NOT_LISTED);
        }
        CatalogEntry entry = listing.get();
        if (!entry.canSell()) {
            return TradeResult.fail(Result.SELL_DISABLED);
        }
        Result quantityCheck = checkQuantity(qty);
        if (quantityCheck != Result.OK) {
            return TradeResult.fail(quantityCheck);
        }

        MarketStockData market = MarketStockData.get(player.server);
        long now = MarketStockData.now(player.server);
        PriceModel.Inputs inputs = PriceModel.inputs(entry);
        double factor = currentFactor(entry, inputs, market, now);
        long unitPrice = PriceModel.unitSellPrice(inputs, factor);
        PriceModel.Order order = PriceModel.sell(inputs, factor, qty);
        long stock = market.getStock(entry.itemId());
        if (!fitsBank(order) || (!entry.unlimitedStock() && stock > Long.MAX_VALUE - qty)) {
            return new TradeResult(Result.TOO_LARGE, 0, unitPrice, 0);
        }
        if (expectedTotal.isPresent() && expectedTotal.getAsLong() != order.total()) {
            return new TradeResult(Result.PRICE_CHANGED, 0, unitPrice, order.total());
        }

        NonNullList<ItemStack> items = player.getInventory().items;
        if (countMatching(items, entry) < qty) {
            return new TradeResult(Result.NOT_ENOUGH_ITEMS, 0, unitPrice, order.total());
        }
        Optional<NumismaticsBridge.Account> account = NumismaticsBridge.account(player);
        if (account.isEmpty()) {
            return TradeResult.fail(Result.BANK_UNAVAILABLE);
        }

        // Nothing has changed yet. Everything below always succeeds.
        removeMatching(items, entry, qty);
        player.getInventory().setChanged();
        if (!entry.unlimitedStock()) {
            market.setStock(entry.itemId(), stock + qty);
        }
        updateFactor(entry, market, factor, order, now);
        account.get().deposit((int) order.total());
        return new TradeResult(Result.OK, qty, unitPrice, order.total());
    }

    /**
     * The player buys {@code qty} of {@code item} from the market. An unlimited item never runs out of stock, and
     * its stored stock is not touched.
     *
     * @param expectedTotal the total the player was shown; empty to accept the current price
     */
    public static TradeResult tryBuy(ServerPlayer player, Item item, int qty, OptionalLong expectedTotal) {
        requireServerThread(player);
        Optional<CatalogEntry> listing = MarketCatalog.INSTANCE.get(item);
        if (listing.isEmpty()) {
            return TradeResult.fail(Result.NOT_LISTED);
        }
        CatalogEntry entry = listing.get();
        if (!entry.canBuy()) {
            return TradeResult.fail(Result.BUY_DISABLED);
        }
        Result quantityCheck = checkQuantity(qty);
        if (quantityCheck != Result.OK) {
            return TradeResult.fail(quantityCheck);
        }

        MarketStockData market = MarketStockData.get(player.server);
        long now = MarketStockData.now(player.server);
        PriceModel.Inputs inputs = PriceModel.inputs(entry);
        double factor = currentFactor(entry, inputs, market, now);
        long unitPrice = PriceModel.unitBuyPrice(inputs, factor);
        PriceModel.Order order = PriceModel.buy(inputs, factor, qty);
        if (!fitsBank(order)) {
            return new TradeResult(Result.TOO_LARGE, 0, unitPrice, 0);
        }
        if (expectedTotal.isPresent() && expectedTotal.getAsLong() != order.total()) {
            return new TradeResult(Result.PRICE_CHANGED, 0, unitPrice, order.total());
        }

        OptionalLong stock = availableStock(entry, market);
        if (stock.isPresent() && stock.getAsLong() < qty) {
            return new TradeResult(Result.NO_STOCK, 0, unitPrice, order.total());
        }
        NonNullList<ItemStack> items = player.getInventory().items;
        if (spaceFor(items, entry) < qty) {
            return new TradeResult(Result.NO_SPACE, 0, unitPrice, order.total());
        }
        Optional<NumismaticsBridge.Account> account = NumismaticsBridge.account(player);
        if (account.isEmpty()) {
            return TradeResult.fail(Result.BANK_UNAVAILABLE);
        }
        // The deduction is itself the funds check: it changes nothing when it returns false.
        if (!account.get().deduct((int) order.total())) {
            return new TradeResult(Result.NO_FUNDS, 0, unitPrice, order.total());
        }

        // Money has been taken. Everything below always succeeds: stock and space were checked above.
        if (stock.isPresent()) {
            market.setStock(entry.itemId(), stock.getAsLong() - qty);
        }
        updateFactor(entry, market, factor, order, now);
        insert(items, entry, qty);
        player.getInventory().setChanged();
        return new TradeResult(Result.OK, qty, unitPrice, order.total());
    }

    /** Writes the factor an order left behind. Unchanged factors aren't rewritten, so their recovery isn't reset. */
    private static void updateFactor(CatalogEntry entry, MarketStockData market, double before, PriceModel.Order order,
                                     long now) {
        if (entry.dynamic() && order.factorAfter() != before) {
            market.setFactor(entry.itemId(), order.factorAfter(), now);
        }
    }

    /** Whether an order total fits in the {@code int} the bank accepts. */
    private static boolean fitsBank(PriceModel.Order order) {
        return !order.overflow() && order.total() <= Integer.MAX_VALUE;
    }

    /** {@code unitPrice × qty}, or empty if it would not fit in the {@code int} the bank accepts. */
    public static OptionalLong checkedTotal(long unitPrice, int qty) {
        try {
            long total = Math.multiplyExact(unitPrice, (long) qty);
            return total > Integer.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(total);
        } catch (ArithmeticException e) {
            return OptionalLong.empty();
        }
    }

    private static Result checkQuantity(int qty) {
        if (qty <= 0) {
            return Result.INVALID_QUANTITY;
        }
        return qty > MarketConfig.maxQuantityPerRequest() ? Result.TOO_LARGE : Result.OK;
    }

    private static void requireServerThread(ServerPlayer player) {
        if (!player.server.isSameThread()) {
            throw new IllegalStateException("Market transactions must run on the server thread");
        }
    }

    /** How many copies of a listed item the player could sell right now. */
    public static long heldCount(ServerPlayer player, CatalogEntry entry) {
        return countMatching(player.getInventory().items, entry);
    }

    static long countMatching(NonNullList<ItemStack> items, CatalogEntry entry) {
        long count = 0;
        for (ItemStack stack : items) {
            if (entry.matches(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static void removeMatching(NonNullList<ItemStack> items, CatalogEntry entry, int qty) {
        int remaining = qty;
        for (int slot = 0; slot < items.size() && remaining > 0; slot++) {
            ItemStack stack = items.get(slot);
            if (entry.matches(stack)) {
                int taken = Math.min(remaining, stack.getCount());
                stack.shrink(taken);
                if (stack.isEmpty()) {
                    items.set(slot, ItemStack.EMPTY);
                }
                remaining -= taken;
            }
        }
    }

    /**
     * How many default copies of the item fit in the main inventory. Counted here rather than with
     * {@code Inventory.add}, which in creative mode reports success after deleting what did not fit.
     */
    static long spaceFor(NonNullList<ItemStack> items, CatalogEntry entry) {
        int maxStack = new ItemStack(entry.item()).getMaxStackSize();
        long space = 0;
        for (ItemStack stack : items) {
            if (stack.isEmpty()) {
                space += maxStack;
            } else if (entry.matches(stack)) {
                space += Math.max(0, maxStack - stack.getCount());
            }
        }
        return space;
    }

    /** Tops up existing stacks first, then fills empty slots. The caller has checked {@link #spaceFor}. */
    private static void insert(NonNullList<ItemStack> items, CatalogEntry entry, int qty) {
        int maxStack = new ItemStack(entry.item()).getMaxStackSize();
        int remaining = qty;
        for (int slot = 0; slot < items.size() && remaining > 0; slot++) {
            ItemStack stack = items.get(slot);
            if (!stack.isEmpty() && entry.matches(stack) && stack.getCount() < maxStack) {
                int added = Math.min(remaining, maxStack - stack.getCount());
                stack.grow(added);
                remaining -= added;
            }
        }
        for (int slot = 0; slot < items.size() && remaining > 0; slot++) {
            if (items.get(slot).isEmpty()) {
                int added = Math.min(remaining, maxStack);
                items.set(slot, new ItemStack(entry.item(), added));
                remaining -= added;
            }
        }
        if (remaining > 0) {
            throw new IllegalStateException("Inventory space check and insert disagree; " + remaining + " items lost");
        }
    }
}
