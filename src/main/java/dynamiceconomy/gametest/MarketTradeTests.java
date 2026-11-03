package dynamiceconomy.gametest;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.MarketConfig;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.data.MarketStockData;
import dynamiceconomy.network.MarketPayloads;
import dynamiceconomy.network.MarketSync;
import dynamiceconomy.service.MarketListing;
import dynamiceconomy.service.MarketService;
import dynamiceconomy.service.OrderMath;
import dynamiceconomy.service.MarketService.Result;
import dynamiceconomy.service.MarketService.TradeResult;
import dynamiceconomy.service.PriceModel;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.Function;

import static dynamiceconomy.gametest.GameTestSupport.*;

/**
 * Buying and selling: happy paths, rejections, concurrency, unlimited stock and the player-facing checks.
 *
 * Shared setup is in {@link GameTestSupport}.
 */
@GameTestHolder(DynamicEconomy.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MarketTradeTests {
    private MarketTradeTests() {
    }

    // ---- Happy paths -------------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void sellingTenIronPays320(GameTestHelper helper) {
        withFixedPrice(Items.IRON_INGOT, () -> {
            Fixture f = new Fixture(helper);
            f.setStock(Items.IRON_INGOT, 0);
            f.give(new ItemStack(Items.IRON_INGOT, 10));

            TradeResult result = MarketService.trySell(f.player, Items.IRON_INGOT, 10, ANY_PRICE);

            f.assertResult(result, Result.OK);
            helper.assertValueEqual(f.stock(Items.IRON_INGOT), 10L, "iron stock");
            helper.assertValueEqual(f.balance(), 320, "balance");
            helper.assertValueEqual(f.count(Items.IRON_INGOT), 0L, "iron left in inventory");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void buyingFiveIronCosts240(GameTestHelper helper) {
        withFixedPrice(Items.IRON_INGOT, () -> {
            Fixture f = new Fixture(helper);
            f.setStock(Items.IRON_INGOT, 20);
            f.deposit(1000);

            TradeResult result = MarketService.tryBuy(f.player, Items.IRON_INGOT, 5, ANY_PRICE);

            f.assertResult(result, Result.OK);
            helper.assertValueEqual(f.stock(Items.IRON_INGOT), 15L, "iron stock");
            helper.assertValueEqual(f.balance(), 1000 - 240, "balance");
            helper.assertValueEqual(f.count(Items.IRON_INGOT), 5L, "iron received");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void buyingTopsUpExistingStacksFirst(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.setStock(Items.OAK_LOG, 100);
        f.deposit(1000);
        f.player.getInventory().items.set(5, new ItemStack(Items.OAK_LOG, 60));

        f.assertResult(MarketService.tryBuy(f.player, Items.OAK_LOG, 10, ANY_PRICE), Result.OK);

        helper.assertValueEqual(f.player.getInventory().items.get(5).getCount(), 64, "topped-up stack");
        helper.assertValueEqual(f.count(Items.OAK_LOG), 70L, "logs held");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void sellingTakesOnlyDefaultCopies(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.setStock(Items.IRON_INGOT, 0);
        ItemStack renamed = new ItemStack(Items.IRON_INGOT, 5);
        renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Shiny"));
        f.give(renamed);
        f.give(new ItemStack(Items.IRON_INGOT, 5));

        f.assertResult(MarketService.trySell(f.player, Items.IRON_INGOT, 5, ANY_PRICE), Result.OK);

        helper.assertValueEqual(f.count(Items.IRON_INGOT), 0L, "default iron left");
        helper.assertTrue(f.player.getInventory().items.stream().anyMatch(s -> s.has(DataComponents.CUSTOM_NAME) && s.getCount() == 5),
                "renamed iron must be untouched");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void ignoredComponentsDontStopASaleButBoughtItemsComeFresh(GameTestHelper helper) {
        // Stands in for relics:data (the default), which no mod in the dev runtime adds.
        ResourceLocation customName = ResourceLocation.withDefaultNamespace("custom_name");
        ItemStack renamed = new ItemStack(Items.IRON_INGOT, 5);
        renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Shiny"));
        CatalogEntry iron = MarketCatalog.INSTANCE.get(Items.IRON_INGOT).orElseThrow();
        helper.assertTrue(iron.matches(renamed, Set.of(customName)), "an ignored component doesn't count");
        helper.assertFalse(iron.matches(renamed, Set.of()), "one that isn't ignored does");
        helper.assertTrue(MarketConfig.ignoredComponents().contains(ResourceLocation.fromNamespaceAndPath("relics", "data")),
                "relics:data is ignored by default");
        if (!MarketConfig.SPEC.isLoaded()) {
            helper.succeed();
            return;
        }

        List<? extends String> before = MarketConfig.IGNORED_COMPONENTS.get();
        MarketConfig.IGNORED_COMPONENTS.set(List.of(customName.toString()));
        try {
            Fixture f = new Fixture(helper);
            f.setStock(Items.IRON_INGOT, 10);
            f.deposit(100_000);
            f.give(renamed.copy());
            f.assertResult(MarketService.trySell(f.player, Items.IRON_INGOT, 2, ANY_PRICE), Result.OK);
            helper.assertTrue(f.player.getInventory().items.stream().anyMatch(s -> s.has(DataComponents.CUSTOM_NAME) && s.getCount() == 3),
                    "sold from the renamed stack");
            f.assertResult(MarketService.tryBuy(f.player, Items.IRON_INGOT, 4, ANY_PRICE), Result.OK);
            helper.assertValueEqual(f.count(Items.IRON_INGOT), 4L, "bought iron arrives fresh, in its own stack");
            helper.assertTrue(f.player.getInventory().items.stream().anyMatch(s -> s.has(DataComponents.CUSTOM_NAME) && s.getCount() == 3),
                    "and doesn't join the renamed one");
        } finally {
            MarketConfig.IGNORED_COMPONENTS.set(before);
        }
        helper.succeed();
    }

    // ---- Rejections --------------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void unlistedItemIsRejected(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.deposit(1000);
        f.give(new ItemStack(Items.STONE, 10));

        f.assertRejectedUnchanged(Result.NOT_LISTED, () -> MarketService.trySell(f.player, Items.STONE, 10, ANY_PRICE));
        f.assertRejectedUnchanged(Result.NOT_LISTED, () -> MarketService.tryBuy(f.player, Items.STONE, 1, ANY_PRICE));
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void sellingMoreThanHeldIsRejected(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.give(new ItemStack(Items.IRON_INGOT, 5));

        f.assertRejectedUnchanged(Result.NOT_ENOUGH_ITEMS, () -> MarketService.trySell(f.player, Items.IRON_INGOT, 6, ANY_PRICE));
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void modifiedCopiesAreRejected(GameTestHelper helper) {
        List<Consumer<ItemStack>> modifications = List.of(
                stack -> stack.set(DataComponents.CUSTOM_NAME, Component.literal("Renamed")),
                stack -> stack.set(DataComponents.DAMAGE, 3),
                stack -> stack.enchant(helper.getLevel().registryAccess()
                        .lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 1));
        for (Consumer<ItemStack> modify : modifications) {
            Fixture f = new Fixture(helper);
            ItemStack diamond = new ItemStack(Items.DIAMOND, 3);
            modify.accept(diamond);
            f.give(diamond);

            f.assertRejectedUnchanged(Result.NOT_ENOUGH_ITEMS, () -> MarketService.trySell(f.player, Items.DIAMOND, 1, ANY_PRICE));
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void buyingWithTooLittleMoneyIsRejected(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.setStock(Items.IRON_INGOT, 10);
        f.deposit(24 * 2 - 1);

        f.assertRejectedUnchanged(Result.NO_FUNDS, () -> MarketService.tryBuy(f.player, Items.IRON_INGOT, 2, ANY_PRICE));
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void buyingBeyondStockIsRejected(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.setStock(Items.IRON_INGOT, 3);
        f.deposit(10_000);

        f.assertRejectedUnchanged(Result.NO_STOCK, () -> MarketService.tryBuy(f.player, Items.IRON_INGOT, 4, ANY_PRICE));
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void buyingZeroStockItemIsRejected(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.setStock(Items.DIAMOND, 0);
        f.deposit(10_000);

        f.assertRejectedUnchanged(Result.NO_STOCK, () -> MarketService.tryBuy(f.player, Items.DIAMOND, 1, ANY_PRICE));
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void buyingIntoFullInventoryIsRejected(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.setStock(Items.IRON_INGOT, 100);
        f.deposit(10_000);
        NonNullList<ItemStack> items = f.player.getInventory().items;
        for (int slot = 0; slot < items.size(); slot++) {
            items.set(slot, new ItemStack(Items.DIRT, 64));
        }

        f.assertRejectedUnchanged(Result.NO_SPACE, () -> MarketService.tryBuy(f.player, Items.IRON_INGOT, 1, ANY_PRICE));
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void buyingMoreThanFitsIsRejected(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.setStock(Items.IRON_INGOT, 100);
        f.deposit(10_000);
        NonNullList<ItemStack> items = f.player.getInventory().items;
        for (int slot = 0; slot < items.size(); slot++) {
            items.set(slot, new ItemStack(Items.DIRT, 64));
        }
        items.set(0, new ItemStack(Items.IRON_INGOT, 60));

        f.assertRejectedUnchanged(Result.NO_SPACE, () -> MarketService.tryBuy(f.player, Items.IRON_INGOT, 5, ANY_PRICE));
        f.assertResult(MarketService.tryBuy(f.player, Items.IRON_INGOT, 4, ANY_PRICE), Result.OK);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void oversizedOrdersAreRejected(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        int tooMany = MarketConfig.maxQuantityPerRequest() + 1;
        f.setStock(Items.IRON_INGOT, tooMany);
        f.deposit(1_000_000);
        f.give(new ItemStack(Items.IRON_INGOT, 64));

        f.assertRejectedUnchanged(Result.TOO_LARGE, () -> MarketService.trySell(f.player, Items.IRON_INGOT, tooMany, ANY_PRICE));
        f.assertRejectedUnchanged(Result.TOO_LARGE, () -> MarketService.tryBuy(f.player, Items.IRON_INGOT, tooMany, ANY_PRICE));
        // Totals above the bank's int limit are rejected even when the quantity is allowed.
        helper.assertTrue(MarketService.checkedTotal(Integer.MAX_VALUE, 2).isEmpty(), "int overflow must be rejected");
        helper.assertTrue(MarketService.checkedTotal(Long.MAX_VALUE, 2).isEmpty(), "long overflow must be rejected");
        helper.assertValueEqual(MarketService.checkedTotal(Integer.MAX_VALUE, 1).orElseThrow(), (long) Integer.MAX_VALUE, "max total");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void invalidQuantityIsRejected(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.give(new ItemStack(Items.IRON_INGOT, 5));

        f.assertRejectedUnchanged(Result.INVALID_QUANTITY, () -> MarketService.trySell(f.player, Items.IRON_INGOT, 0, ANY_PRICE));
        f.assertRejectedUnchanged(Result.INVALID_QUANTITY, () -> MarketService.tryBuy(f.player, Items.IRON_INGOT, -1, ANY_PRICE));
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void sellDisabledItemIsRejected(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.give(new ItemStack(Items.NETHER_STAR, 1));

        f.assertRejectedUnchanged(Result.SELL_DISABLED, () -> MarketService.trySell(f.player, Items.NETHER_STAR, 1, ANY_PRICE));
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void staleQuoteIsRejected(GameTestHelper helper) {
        withFixedPrice(Items.IRON_INGOT, () -> {
            Fixture f = new Fixture(helper);
            f.setStock(Items.IRON_INGOT, 10);
            f.deposit(1000);
            f.give(new ItemStack(Items.IRON_INGOT, 5));

            // Quotes are order totals, so a unit price sent for a multi-unit order is stale too.
            f.assertRejectedUnchanged(Result.PRICE_CHANGED, () -> MarketService.trySell(f.player, Items.IRON_INGOT, 1, OptionalLong.of(31)));
            f.assertRejectedUnchanged(Result.PRICE_CHANGED, () -> MarketService.trySell(f.player, Items.IRON_INGOT, 2, OptionalLong.of(32)));
            f.assertRejectedUnchanged(Result.PRICE_CHANGED, () -> MarketService.tryBuy(f.player, Items.IRON_INGOT, 1, OptionalLong.of(47)));
            f.assertResult(MarketService.tryBuy(f.player, Items.IRON_INGOT, 1, OptionalLong.of(48)), Result.OK);
            f.assertResult(MarketService.trySell(f.player, Items.IRON_INGOT, 2, OptionalLong.of(64)), Result.OK);
        });
        helper.succeed();
    }

    // ---- Concurrency and reconciliation ------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void twoBuyersForTheLastItemOnlyOneSucceeds(GameTestHelper helper) {
        Fixture first = new Fixture(helper);
        Fixture second = new Fixture(helper);
        first.setStock(Items.DIAMOND, 1);
        first.deposit(10_000);
        second.deposit(10_000);

        // Both requests arrive in the same tick; the server thread handles them one after the other.
        TradeResult a = MarketService.tryBuy(first.player, Items.DIAMOND, 1, ANY_PRICE);
        TradeResult b = MarketService.tryBuy(second.player, Items.DIAMOND, 1, ANY_PRICE);

        first.assertResult(a, Result.OK);
        second.assertResult(b, Result.NO_STOCK);
        helper.assertValueEqual(first.stock(Items.DIAMOND), 0L, "diamond stock");
        helper.assertValueEqual(second.balance(), 10_000, "losing buyer's balance");
        helper.assertValueEqual(second.count(Items.DIAMOND), 0L, "losing buyer's diamonds");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void hundredRandomTradesReconcile(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.setStock(Items.IRON_INGOT, 200);
        f.deposit(5_000);
        f.give(new ItemStack(Items.IRON_INGOT, 64));
        java.util.Random random = new java.util.Random(42);

        long stockBefore = f.stock(Items.IRON_INGOT);
        long heldBefore = f.count(Items.IRON_INGOT);
        int balanceBefore = f.balance();
        long expectedMoney = 0;
        long netItemsToPlayer = 0;
        int succeeded = 0;
        for (int i = 0; i < 100; i++) {
            int qty = 1 + random.nextInt(40);
            boolean buy = random.nextBoolean();
            TradeResult result = buy
                    ? MarketService.tryBuy(f.player, Items.IRON_INGOT, qty, ANY_PRICE)
                    : MarketService.trySell(f.player, Items.IRON_INGOT, qty, ANY_PRICE);
            if (result.ok()) {
                succeeded++;
                expectedMoney += buy ? -result.total() : result.total();
                netItemsToPlayer += buy ? qty : -qty;
            }
        }

        helper.assertTrue(succeeded > 20, "too few trades succeeded to prove anything: " + succeeded);
        helper.assertValueEqual((long) f.balance(), balanceBefore + expectedMoney, "balance reconciles with trade totals");
        helper.assertValueEqual(f.count(Items.IRON_INGOT), heldBefore + netItemsToPlayer, "items held reconcile");
        helper.assertValueEqual(f.stock(Items.IRON_INGOT), stockBefore - netItemsToPlayer, "stock reconciles");
        helper.succeed();
    }

    // ---- Unlimited stock ---------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void buyingUnlimitedItemNeverRunsOut(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.setStock(Items.COBBLESTONE, 0);
        f.deposit(1000);

        for (int i = 0; i < 3; i++) {
            TradeResult result = MarketService.tryBuy(f.player, Items.COBBLESTONE, 64, ANY_PRICE);
            f.assertResult(result, Result.OK);
            // 1.5 spurs each, rounded up once per order rather than per unit.
            helper.assertValueEqual(result.total(), 96L, "cost of 64 cobblestone");
        }

        helper.assertValueEqual(f.stock(Items.COBBLESTONE), 0L, "stored stock is never touched");
        helper.assertValueEqual(f.count(Items.COBBLESTONE), 192L, "cobblestone received");
        helper.assertValueEqual(f.balance(), 1000 - 3 * 96, "balance");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void sellingUnlimitedItemPaysAndKeepsStoredStock(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.setStock(Items.COBBLESTONE, 7);
        f.give(new ItemStack(Items.COBBLESTONE, 10));

        f.assertResult(MarketService.trySell(f.player, Items.COBBLESTONE, 10, ANY_PRICE), Result.OK);

        // Each unit sold lowers the price slightly, and the total is rounded down once: 1 + 0.9995 + ... = 9.98.
        helper.assertValueEqual(f.balance(), 9, "paid for 10 cobblestone");
        helper.assertValueEqual(f.count(Items.COBBLESTONE), 0L, "cobblestone removed");
        helper.assertValueEqual(f.stock(Items.COBBLESTONE), 7L, "stored stock is never touched");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void unlimitedItemsStillCheckFundsSpaceQuoteAndSize(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.setStock(Items.COBBLESTONE, 0);
        f.deposit(3);
        f.give(new ItemStack(Items.COBBLESTONE, 5));

        f.assertRejectedUnchanged(Result.NO_FUNDS, () -> MarketService.tryBuy(f.player, Items.COBBLESTONE, 3, ANY_PRICE));
        f.assertRejectedUnchanged(Result.PRICE_CHANGED, () -> MarketService.tryBuy(f.player, Items.COBBLESTONE, 1, OptionalLong.of(1)));
        f.assertRejectedUnchanged(Result.PRICE_CHANGED, () -> MarketService.trySell(f.player, Items.COBBLESTONE, 2, OptionalLong.of(3)));
        f.assertRejectedUnchanged(Result.TOO_LARGE,
                () -> MarketService.tryBuy(f.player, Items.COBBLESTONE, MarketConfig.maxQuantityPerRequest() + 1, ANY_PRICE));
        f.assertRejectedUnchanged(Result.INVALID_QUANTITY, () -> MarketService.tryBuy(f.player, Items.COBBLESTONE, 0, ANY_PRICE));
        f.assertRejectedUnchanged(Result.NOT_ENOUGH_ITEMS, () -> MarketService.trySell(f.player, Items.COBBLESTONE, 6, ANY_PRICE));

        f.deposit(10_000);
        NonNullList<ItemStack> items = f.player.getInventory().items;
        for (int slot = 0; slot < items.size(); slot++) {
            items.set(slot, new ItemStack(Items.DIRT, 64));
        }
        f.assertRejectedUnchanged(Result.NO_SPACE, () -> MarketService.tryBuy(f.player, Items.COBBLESTONE, 1, ANY_PRICE));
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void finiteToUnlimitedToFiniteRestoresStock(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.setStock(Items.IRON_INGOT, 33);
        f.deposit(10_000);
        f.give(new ItemStack(Items.IRON_INGOT, 5));
        CatalogEntry finite = MarketCatalog.INSTANCE.get(Items.IRON_INGOT).orElseThrow();
        CatalogEntry unlimited = finite.withUnlimitedStock(true);

        Map<Item, CatalogEntry> original = swapEntry(unlimited);
        try {
            f.market.seed(MarketCatalog.INSTANCE.entries());
            helper.assertTrue(MarketService.availableStock(unlimited, f.market).isEmpty(), "unlimited reports no stock number");
            f.assertResult(MarketService.tryBuy(f.player, Items.IRON_INGOT, 50, ANY_PRICE), Result.OK);
            f.assertResult(MarketService.trySell(f.player, Items.IRON_INGOT, 5, ANY_PRICE), Result.OK);
            helper.assertValueEqual(f.stock(Items.IRON_INGOT), 33L, "stored stock while unlimited");
        } finally {
            MarketCatalog.INSTANCE.replaceForTesting(original);
        }

        helper.assertValueEqual(MarketService.availableStock(finite, f.market), OptionalLong.of(33), "stock is back once finite");
        f.assertRejectedUnchanged(Result.NO_STOCK, () -> MarketService.tryBuy(f.player, Items.IRON_INGOT, 34, ANY_PRICE));
        helper.succeed();
    }

    // ---- Player usability --------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void maxBuyRespectsEveryLimitAndItsQuantityTrades(GameTestHelper helper) {
        PriceModel.Inputs iron = PriceModel.inputs(MarketCatalog.INSTANCE.get(Items.IRON_INGOT).orElseThrow());
        PriceModel.Inputs cobble = PriceModel.inputs(MarketCatalog.INSTANCE.get(Items.COBBLESTONE).orElseThrow());
        helper.assertValueEqual(OrderMath.maxBuy(iron, 1.0, 1_000_000, OptionalLong.of(5), 10_000, 640), 5, "stock limits");
        helper.assertValueEqual(OrderMath.maxBuy(iron, 1.0, 1_000_000, OptionalLong.of(5_000), 70, 640), 70, "room limits");
        helper.assertValueEqual(OrderMath.maxBuy(iron, 1.0, 1_000_000, OptionalLong.of(5_000), 10_000, 640), 640, "order limit");
        helper.assertValueEqual(OrderMath.maxBuy(iron, 1.0, 23, OptionalLong.of(5_000), 10_000, 640), 0, "can't afford one at 24");
        helper.assertValueEqual(OrderMath.maxBuy(cobble, 1.0, 1_000_000, OptionalLong.empty(), 10_000, 640), 640,
                "unlimited stock isn't a limit");
        helper.assertValueEqual(OrderMath.maxBuy(iron, 1.0, 1_000_000, OptionalLong.of(0), 10_000, 640), 0, "out of stock");
        // With money the limit, Max is the largest order the balance covers at its true total, not balance ÷ price.
        for (double factor : List.of(1.0, 0.5, 3.0)) {
            for (long balance : List.of(24L, 1_000L, 7_777L, 15_000L)) {
                int max = OrderMath.maxBuy(iron, factor, balance, OptionalLong.of(5_000), 10_000, 640);
                helper.assertTrue(max == 0 || PriceModel.buy(iron, factor, max).total() <= balance, "max " + max + " is affordable");
                helper.assertTrue(max == 640 || PriceModel.buy(iron, factor, max + 1).total() > balance, "one more isn't: " + max);
            }
        }
        helper.assertValueEqual(OrderMath.sellAll(1_000, 640), 640, "all is capped at the order limit");
        helper.assertValueEqual(OrderMath.sellAll(12, 640), 12, "all held");
        helper.assertValueEqual(OrderMath.sellAll(0, 640), 0, "none held");

        // What Max suggests goes through the real purchase; one more is refused.
        Fixture f = new Fixture(helper);
        f.setStock(Items.IRON_INGOT, 5_000);
        f.deposit(2_000);
        MarketPayloads.PlayerState state = MarketSync.playerState(f.player);
        MarketService.Quote quote = f.quote(Items.IRON_INGOT);
        int max = OrderMath.maxBuy(quote.inputs(), quote.factor(), state.balance(), quote.stock(),
                state.room(key(Items.IRON_INGOT), 64), MarketConfig.maxQuantityPerRequest());
        long cost = PriceModel.buy(quote.inputs(), quote.factor(), max).total();
        f.assertRejectedUnchanged(Result.NO_FUNDS, () -> MarketService.tryBuy(f.player, Items.IRON_INGOT, max + 1, ANY_PRICE));
        f.assertResult(MarketService.tryBuy(f.player, Items.IRON_INGOT, max, OptionalLong.of(cost)), Result.OK);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void shownUnitPricesRoundToTheTradedPrice(GameTestHelper helper) {
        for (Item item : List.of(Items.COBBLESTONE, Items.IRON_INGOT, Items.DIAMOND, Items.NETHER_STAR)) {
            PriceModel.Inputs in = PriceModel.inputs(MarketCatalog.INSTANCE.get(item).orElseThrow());
            for (double factor : List.of(0.2, 0.37, 0.9995, 1.0, 1.7, 5.0)) {
                double s = in.unlimited() ? Math.min(1.0, factor) : factor;
                helper.assertValueEqual(PriceModel.exactUnitSellPrice(in, s).setScale(0, RoundingMode.FLOOR).longValueExact(),
                        PriceModel.unitSellPrice(in, s), "sell " + item + " at " + s);
                helper.assertValueEqual(PriceModel.exactUnitBuyPrice(in, s).setScale(0, RoundingMode.CEILING).longValueExact(),
                        PriceModel.unitBuyPrice(in, s), "buy " + item + " at " + s);
            }
        }
        PriceModel.Inputs cobble = PriceModel.inputs(MarketCatalog.INSTANCE.get(Items.COBBLESTONE).orElseThrow());
        helper.assertTrue(PriceModel.exactUnitSellPrice(cobble, 0.9995).signum() > 0,
                "a cheap item below base still shows a price above 0");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void listSortsAndFiltersWithoutTouchingTheMarket(GameTestHelper helper) {
        PriceModel.Inputs cheap = inputs(2, 0.5, 0.0, false);
        PriceModel.Inputs dear = inputs(100, 0.5, 0.0, false);
        MarketPayloads.Row apple = new MarketPayloads.Row(ResourceLocation.withDefaultNamespace("apple"), true, true,
                OptionalLong.of(0), cheap, 1.0);
        MarketPayloads.Row bread = new MarketPayloads.Row(ResourceLocation.withDefaultNamespace("bread"), true, false,
                OptionalLong.of(50), dear, 1.0);
        MarketPayloads.Row cake = new MarketPayloads.Row(ResourceLocation.withDefaultNamespace("cake"), false, true,
                OptionalLong.empty(), dear, 0.5);
        List<MarketPayloads.Row> rows = List.of(cake, bread, apple);
        java.util.function.Function<MarketPayloads.Row, String> name = row -> row.item().getPath();

        java.util.function.BiFunction<MarketListing.Sort, MarketListing.Filter, List<String>> view = (sort, filter) ->
                rows.stream().filter(row -> MarketListing.shows(filter, row, row == bread ? 3 : 0))
                        .sorted(MarketListing.order(sort, name)).map(name).toList();
        helper.assertValueEqual(view.apply(MarketListing.Sort.NAME, MarketListing.Filter.ALL), List.of("apple", "bread", "cake"), "by name");
        helper.assertValueEqual(view.apply(MarketListing.Sort.CHEAPEST, MarketListing.Filter.ALL), List.of("apple", "cake", "bread"),
                "cheapest first; bread can't be bought so it goes last");
        helper.assertValueEqual(view.apply(MarketListing.Sort.BEST_PAYOUT, MarketListing.Filter.ALL), List.of("bread", "apple", "cake"),
                "best payout first; cake can't be sold so it goes last");
        helper.assertValueEqual(view.apply(MarketListing.Sort.STOCK, MarketListing.Filter.ALL), List.of("cake", "bread", "apple"),
                "unlimited first, then most stock");
        helper.assertValueEqual(view.apply(MarketListing.Sort.NAME, MarketListing.Filter.CAN_BUY), List.of("cake"),
                "buyable means sold and in stock");
        helper.assertValueEqual(view.apply(MarketListing.Sort.NAME, MarketListing.Filter.CAN_SELL), List.of("apple", "bread"), "sellable");
        helper.assertValueEqual(view.apply(MarketListing.Sort.NAME, MarketListing.Filter.HELD), List.of("bread"), "held");
        helper.assertValueEqual(MarketListing.Sort.STOCK.next(), MarketListing.Sort.NAME, "sorts cycle");
        helper.assertValueEqual(MarketListing.Filter.HELD.next(), MarketListing.Filter.ALL, "filters cycle");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void fullyRecoveredFactorsAreDropped(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        long now = MarketStockData.now(helper.getLevel().getServer());
        long longAgo = now - 10_000L * MarketConfig.intervalTicks();
        f.market.setFactor(key(Items.IRON_INGOT), 0.5, longAgo);
        f.market.setFactor(key(Items.DIAMOND), 0.5, now);
        f.market.setFactor(key(Items.STONE), 0.5, longAgo);

        MarketService.pruneRecovered(helper.getLevel().getServer());

        helper.assertTrue(!f.market.factorsView().containsKey(key(Items.IRON_INGOT)), "recovered iron is dropped");
        helper.assertValueEqual(f.factor(Items.IRON_INGOT), 1.0, "and still prices at base");
        helper.assertTrue(f.market.factorsView().containsKey(key(Items.DIAMOND)), "diamond is still recovering");
        helper.assertTrue(f.market.factorsView().containsKey(key(Items.STONE)), "unlisted items are left alone");
        f.market.resetFactor(key(Items.STONE));
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void tradesAreCountedAsMoneyCreatedAndRemoved(GameTestHelper helper) {
        withFixedPrice(Items.IRON_INGOT, () -> {
            Fixture f = new Fixture(helper);
            f.setStock(Items.IRON_INGOT, 100);
            f.deposit(1000);
            f.giveMany(Items.IRON_INGOT, 10);
            MarketStockData.Totals before = f.market.totals();
            MarketStockData.Totals ironBefore = f.market.totals(key(Items.IRON_INGOT));

            f.assertResult(MarketService.trySell(f.player, Items.IRON_INGOT, 10, ANY_PRICE), Result.OK);
            f.assertResult(MarketService.tryBuy(f.player, Items.IRON_INGOT, 5, ANY_PRICE), Result.OK);

            MarketStockData.Totals expected = new MarketStockData.Totals(before.paidOut() + 320, before.collected() + 240,
                    before.unitsSold() + 10, before.unitsBought() + 5);
            helper.assertValueEqual(f.market.totals(), expected, "320 paid out for 10, 240 collected for 5");
            helper.assertValueEqual(f.market.totals(key(Items.IRON_INGOT)), new MarketStockData.Totals(ironBefore.paidOut() + 320,
                    ironBefore.collected() + 240, ironBefore.unitsSold() + 10, ironBefore.unitsBought() + 5), "per item too");
            helper.assertValueEqual(expected.netCreated() - before.netCreated(), 80L, "net 80 bits added to the economy");
        });

        MarketStockData data = new MarketStockData();
        ResourceLocation stone = ResourceLocation.withDefaultNamespace("stone");
        data.recordTrade(stone, false, 3, 30);
        data.recordTrade(stone, true, 1, 15);
        MarketStockData reloaded = MarketStockData.fromTag(data.save(new CompoundTag(), helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        helper.assertValueEqual(reloaded.totals(), data.totals(), "totals saved");
        helper.assertValueEqual(reloaded.totals(stone), new MarketStockData.Totals(30, 15, 3, 1), "item totals saved");

        CompoundTag version3 = data.save(new CompoundTag(), helper.getLevel().registryAccess());
        version3.remove("totals");
        version3.putInt("version", 3);
        MarketStockData old = MarketStockData.fromTag(version3, helper.getLevel().registryAccess());
        helper.assertValueEqual(old.totals(), MarketStockData.Totals.ZERO, "a save from before counting starts at zero");
        helper.succeed();
    }
}
