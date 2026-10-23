package dynamiceconomy.gametest;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.MarketConfig;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.compat.NumismaticsBridge;
import dynamiceconomy.data.MarketStockData;
import dynamiceconomy.service.MarketService;
import dynamiceconomy.service.MarketService.Result;
import dynamiceconomy.service.MarketService.TradeResult;
import dynamiceconomy.service.PriceModel;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Server-side market tests. Each test runs synchronously inside one tick, so tests sharing the global stock and
 * catalog cannot interleave. Relies on {@code test_catalog.json}. Run with {@code gradlew runGameTestServer}.
 */
@GameTestHolder(DynamicEconomy.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MarketGameTests {
    private static final String EMPTY = "empty";
    private static final OptionalLong ANY_PRICE = OptionalLong.empty();

    private MarketGameTests() {
    }

    // ---- Happy paths -------------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void sellingTenIronPays160(GameTestHelper helper) {
        withFixedPrice(Items.IRON_INGOT, () -> {
            Fixture f = new Fixture(helper);
            f.setStock(Items.IRON_INGOT, 0);
            f.give(new ItemStack(Items.IRON_INGOT, 10));

            TradeResult result = MarketService.trySell(f.player, Items.IRON_INGOT, 10, ANY_PRICE);

            f.assertResult(result, Result.OK);
            helper.assertValueEqual(f.stock(Items.IRON_INGOT), 10L, "iron stock");
            helper.assertValueEqual(f.balance(), 160, "balance");
            helper.assertValueEqual(f.count(Items.IRON_INGOT), 0L, "iron left in inventory");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void buyingFiveIronCosts120(GameTestHelper helper) {
        withFixedPrice(Items.IRON_INGOT, () -> {
            Fixture f = new Fixture(helper);
            f.setStock(Items.IRON_INGOT, 20);
            f.deposit(1000);

            TradeResult result = MarketService.tryBuy(f.player, Items.IRON_INGOT, 5, ANY_PRICE);

            f.assertResult(result, Result.OK);
            helper.assertValueEqual(f.stock(Items.IRON_INGOT), 15L, "iron stock");
            helper.assertValueEqual(f.balance(), 1000 - 120, "balance");
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

    // ---- Rejections: each must change nothing -------------------------------------------------------------------

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
            f.assertRejectedUnchanged(Result.PRICE_CHANGED, () -> MarketService.trySell(f.player, Items.IRON_INGOT, 1, OptionalLong.of(15)));
            f.assertRejectedUnchanged(Result.PRICE_CHANGED, () -> MarketService.trySell(f.player, Items.IRON_INGOT, 2, OptionalLong.of(16)));
            f.assertRejectedUnchanged(Result.PRICE_CHANGED, () -> MarketService.tryBuy(f.player, Items.IRON_INGOT, 1, OptionalLong.of(23)));
            f.assertResult(MarketService.tryBuy(f.player, Items.IRON_INGOT, 1, OptionalLong.of(24)), Result.OK);
            f.assertResult(MarketService.trySell(f.player, Items.IRON_INGOT, 2, OptionalLong.of(32)), Result.OK);
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

    // ---- Unlimited stock (cobblestone: market pays 1, charges 2) -----------------------------------------------

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

    /** Runs {@code body} with an item's supply/demand turned off, so it trades at fixed base prices. */
    private static void withFixedPrice(Item item, Runnable body) {
        CatalogEntry entry = MarketCatalog.INSTANCE.get(item).orElseThrow();
        withEntry(entry.withPricing(entry.sellFee(), false, entry.minBuyMarkup(), entry.overrides()), body);
    }

    /** Runs {@code body} with one listing replaced, then puts the real catalog back. */
    private static void withEntry(CatalogEntry entry, Runnable body) {
        Map<Item, CatalogEntry> original = swapEntry(entry);
        try {
            body.run();
        } finally {
            MarketCatalog.INSTANCE.replaceForTesting(original);
        }
    }

    /** Replaces one listing in the live catalog and returns the whole previous catalog to restore. */
    private static Map<Item, CatalogEntry> swapEntry(CatalogEntry entry) {
        Map<Item, CatalogEntry> swapped = new java.util.LinkedHashMap<>();
        MarketCatalog.INSTANCE.entries().forEach(e -> swapped.put(e.item(), e));
        swapped.put(entry.item(), entry);
        return MarketCatalog.INSTANCE.replaceForTesting(swapped);
    }

    // ---- Catalog parsing --------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void fileDefaultsApplyUnlessEntryOverrides(GameTestHelper helper) {
        JsonElement file = JsonParser.parseString("""
                {
                  "defaults": { "markup": 0.25, "unlimited_stock": true, "can_sell": false, "item": "minecraft:bedrock" },
                  "entries": [
                    { "item": "minecraft:stone", "base_price": 4 },
                    { "item": "minecraft:dirt",  "base_price": 2, "markup": 1.0, "unlimited_stock": false, "can_sell": true },
                    { "item": "minecraft:sand",  "base_price": 0 },
                    { "base_price": 5 },
                    { "item": "minecraft:not_a_real_item", "base_price": 5 }
                  ]
                }""");
        Map<Item, CatalogEntry> parsed = MarketCatalog.parse(Map.of(ResourceLocation.fromNamespaceAndPath("test", "defaults"), file));

        helper.assertValueEqual(parsed.keySet(), java.util.Set.of(Items.STONE, Items.DIRT), "entries kept");
        CatalogEntry stone = parsed.get(Items.STONE);
        helper.assertValueEqual(stone.markup(), 0.25, "stone markup from defaults");
        helper.assertTrue(stone.unlimitedStock() && !stone.canSell(), "stone flags from defaults");
        CatalogEntry dirt = parsed.get(Items.DIRT);
        helper.assertValueEqual(dirt.markup(), 1.0, "dirt markup overridden");
        helper.assertTrue(!dirt.unlimitedStock() && dirt.canSell(), "dirt flags overridden");
        helper.assertTrue(!parsed.containsKey(Items.BEDROCK), "item in defaults is ignored");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void badDefaultsSkipTheWholeFile(GameTestHelper helper) {
        JsonElement file = JsonParser.parseString("""
                { "defaults": 5, "entries": [ { "item": "minecraft:stone", "base_price": 4 } ] }""");
        helper.assertTrue(MarketCatalog.parse(Map.of(ResourceLocation.fromNamespaceAndPath("test", "bad"), file)).isEmpty(),
                "a file with malformed defaults loads nothing");
        helper.succeed();
    }

    // ---- Pricing ------------------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void pricesUseExactDecimalRounding(GameTestHelper helper) {
        PriceModel.Inputs tenAtTenPercent = inputs(10, 0.1, 0.0, false);
        helper.assertValueEqual(PriceModel.unitBuyPrice(tenAtTenPercent, 1.0), 11L, "10 at 10% markup charges 11, not 12");
        helper.assertValueEqual(PriceModel.unitSellPrice(tenAtTenPercent, 1.0), 10L, "10 at 10% markup pays 10");
        PriceModel.Inputs oddBase = inputs(3, 0.5, 0.0, false);
        helper.assertValueEqual(PriceModel.unitBuyPrice(oddBase, 1.0), 5L, "ceil(4.5)");
        helper.succeed();
    }

    // ---- Supply and demand ------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void splittingAnOrderNeverBeatsOneOrder(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.setStock(Items.IRON_INGOT, 1000);
        f.deposit(100_000);
        PriceModel.Inputs inputs = PriceModel.inputs(MarketCatalog.INSTANCE.get(Items.IRON_INGOT).orElseThrow());

        f.giveMany(Items.IRON_INGOT, 50);
        long splitPayout = 0;
        for (int i = 0; i < 50; i++) {
            TradeResult one = MarketService.trySell(f.player, Items.IRON_INGOT, 1, ANY_PRICE);
            f.assertResult(one, Result.OK);
            splitPayout += one.total();
        }
        double afterSplitSale = f.factor(Items.IRON_INGOT);
        f.market.resetAllFactors();
        f.giveMany(Items.IRON_INGOT, 50);
        TradeResult bulkSale = MarketService.trySell(f.player, Items.IRON_INGOT, 50, ANY_PRICE);
        f.assertResult(bulkSale, Result.OK);
        helper.assertTrue(splitPayout <= bulkSale.total(), "split sales paid " + splitPayout + " > one order " + bulkSale.total());
        helper.assertValueEqual(f.factor(Items.IRON_INGOT), afterSplitSale, "factor after selling 50 either way");
        helper.assertValueEqual(bulkSale.total(), PriceModel.sell(inputs, 1.0, 50).total(), "server total matches the shared model");

        f.market.resetAllFactors();
        f.clearInventory();
        long splitCost = 0;
        for (int i = 0; i < 50; i++) {
            TradeResult one = MarketService.tryBuy(f.player, Items.IRON_INGOT, 1, ANY_PRICE);
            f.assertResult(one, Result.OK);
            splitCost += one.total();
        }
        double afterSplitBuy = f.factor(Items.IRON_INGOT);
        f.market.resetAllFactors();
        TradeResult bulkBuy = MarketService.tryBuy(f.player, Items.IRON_INGOT, 50, ANY_PRICE);
        f.assertResult(bulkBuy, Result.OK);
        helper.assertTrue(splitCost >= bulkBuy.total(), "split purchases cost " + splitCost + " < one order " + bulkBuy.total());
        helper.assertValueEqual(f.factor(Items.IRON_INGOT), afterSplitBuy, "factor after buying 50 either way");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void tenThousandUnitsStopAtFloorAndCeiling(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.setStock(Items.IRON_INGOT, 0);
        for (int remaining = 10_000; remaining > 0; ) {
            int qty = Math.min(640, remaining);
            f.clearInventory();
            f.giveMany(Items.IRON_INGOT, qty);
            f.assertResult(MarketService.trySell(f.player, Items.IRON_INGOT, qty, ANY_PRICE), Result.OK);
            remaining -= qty;
            helper.assertTrue(f.factor(Items.IRON_INGOT) < 1.0, "selling lowers the factor");
        }
        helper.assertValueEqual(f.factor(Items.IRON_INGOT), MarketConfig.floor(), "factor after dumping 10,000");

        f.market.resetAllFactors();
        f.setStock(Items.IRON_INGOT, 20_000);
        f.deposit(2_000_000);
        for (int remaining = 10_000; remaining > 0; ) {
            int qty = Math.min(640, remaining);
            f.clearInventory();
            f.assertResult(MarketService.tryBuy(f.player, Items.IRON_INGOT, qty, ANY_PRICE), Result.OK);
            remaining -= qty;
            helper.assertTrue(f.factor(Items.IRON_INGOT) > 1.0, "buying raises the factor");
        }
        helper.assertValueEqual(f.factor(Items.IRON_INGOT), MarketConfig.ceiling(), "factor after buying 10,000");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void factorRecoversInIntervalSteps(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        ResourceLocation iron = BuiltInRegistries.ITEM.getKey(Items.IRON_INGOT);
        long now = MarketStockData.now(helper.getLevel().getServer());
        int interval = MarketConfig.intervalTicks();
        double recovery = MarketConfig.recovery();

        f.market.setFactor(iron, 0.5, now - 3L * interval);
        assertClose(helper, f.factor(Items.IRON_INGOT), 1 + (0.5 - 1) * Math.pow(1 - recovery, 3), "low factor after 3 intervals");
        f.market.setFactor(iron, 2.0, now - 2L * interval);
        assertClose(helper, f.factor(Items.IRON_INGOT), 1 + (2.0 - 1) * Math.pow(1 - recovery, 2), "high factor after 2 intervals");
        f.market.setFactor(iron, 0.5, now);
        helper.assertValueEqual(f.factor(Items.IRON_INGOT), 0.5, "no time passed");

        // Steps happen at boundaries of game time, not after a full interval since the last trade.
        helper.assertValueEqual(PriceModel.recovered(0.5, 0, interval - 1L, recovery, interval), 0.5, "no boundary crossed");
        assertClose(helper, PriceModel.recovered(0.5, interval - 1L, interval, recovery, interval),
                1 + (0.5 - 1) * (1 - recovery), "one boundary crossed");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void buyingThenSellingNeverProfits(GameTestHelper helper) {
        for (Item item : List.of(Items.IRON_INGOT, Items.DIAMOND)) {
            for (double start : List.of(1.0, MarketConfig.floor(), MarketConfig.ceiling(), 0.7, 2.5)) {
                for (int qty : List.of(1, 7, 64, 640)) {
                    Fixture f = new Fixture(helper);
                    f.setStock(item, 5_000);
                    f.deposit(5_000_000);
                    f.market.setFactor(BuiltInRegistries.ITEM.getKey(item), start, MarketStockData.now(helper.getLevel().getServer()));
                    int before = f.balance();

                    f.assertResult(MarketService.tryBuy(f.player, item, qty, ANY_PRICE), Result.OK);
                    f.assertResult(MarketService.trySell(f.player, item, qty, ANY_PRICE), Result.OK);

                    helper.assertTrue(f.balance() <= before, "round trip of %d %s from factor %s made %d spurs"
                            .formatted(qty, item, start, f.balance() - before));
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void sameTickTradesPriceAsIfSequential(GameTestHelper helper) {
        Fixture first = new Fixture(helper);
        Fixture second = new Fixture(helper);
        first.setStock(Items.IRON_INGOT, 100);
        first.deposit(10_000);
        second.deposit(10_000);
        PriceModel.Inputs inputs = PriceModel.inputs(MarketCatalog.INSTANCE.get(Items.IRON_INGOT).orElseThrow());

        TradeResult a = MarketService.tryBuy(first.player, Items.IRON_INGOT, 10, ANY_PRICE);
        TradeResult b = MarketService.tryBuy(second.player, Items.IRON_INGOT, 10, ANY_PRICE);

        PriceModel.Order expectedA = PriceModel.buy(inputs, 1.0, 10);
        PriceModel.Order expectedB = PriceModel.buy(inputs, expectedA.factorAfter(), 10);
        helper.assertValueEqual(a.total(), expectedA.total(), "first buyer pays from the starting factor");
        helper.assertValueEqual(b.total(), expectedB.total(), "second buyer pays from where the first left it");
        helper.assertValueEqual(first.factor(Items.IRON_INGOT), PriceModel.buy(inputs, 1.0, 20).factorAfter(),
                "factor matches one order of 20");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void buyingUnlimitedItemNeverMovesItsPrice(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.deposit(100_000);
        for (int remaining = 10_000; remaining > 0; ) {
            int qty = Math.min(640, remaining);
            f.clearInventory();
            TradeResult result = MarketService.tryBuy(f.player, Items.COBBLESTONE, qty, ANY_PRICE);
            f.assertResult(result, Result.OK);
            helper.assertValueEqual(result.total(), (3L * qty + 1) / 2, "every cobblestone costs 1.5, total rounded up");
            remaining -= qty;
        }
        helper.assertValueEqual(f.factor(Items.COBBLESTONE), 1.0, "factor after buying 10,000");
        helper.assertValueEqual(f.quote(Items.COBBLESTONE).buyPrice(), 2L, "one-unit price after buying 10,000");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void sellingUnlimitedItemNeverDropsBuyPriceToBase(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        for (int remaining = 10_000; remaining > 0; ) {
            int qty = Math.min(640, remaining);
            f.clearInventory();
            f.giveMany(Items.COBBLESTONE, qty);
            f.assertResult(MarketService.trySell(f.player, Items.COBBLESTONE, qty, ANY_PRICE), Result.OK);
            remaining -= qty;
        }
        helper.assertValueEqual(f.factor(Items.COBBLESTONE), MarketConfig.floor(), "selling still lowers the factor");
        helper.assertValueEqual(f.quote(Items.COBBLESTONE).buyPrice(), 2L, "default min_buy_markup keeps the buy price fixed");

        // With a lower min_buy_markup the buy price may fall with supply, but never to base.
        CatalogEntry cobble = MarketCatalog.INSTANCE.get(Items.COBBLESTONE).orElseThrow();
        CatalogEntry flexible = new CatalogEntry(cobble.item(), cobble.itemId(), 100, 0.5, 0, true, true, true, 0.0, true,
                Optional.of(0.1), CatalogEntry.Overrides.NONE);
        withEntry(flexible, () -> {
            long now = MarketStockData.now(helper.getLevel().getServer());
            f.market.setFactor(cobble.itemId(), 0.8, now);
            helper.assertValueEqual(f.quote(Items.COBBLESTONE).buyPrice(), 120L, "100 × 0.8 × 1.5");
            f.market.setFactor(cobble.itemId(), MarketConfig.floor(), now);
            helper.assertValueEqual(f.quote(Items.COBBLESTONE).buyPrice(), 110L, "held at 100 × (1 + 0.1)");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void buyingThenSellingUnlimitedItemNeverProfits(GameTestHelper helper) {
        CatalogEntry cobble = MarketCatalog.INSTANCE.get(Items.COBBLESTONE).orElseThrow();
        CatalogEntry flexible = new CatalogEntry(cobble.item(), cobble.itemId(), 100, 0.5, 0, true, true, true, 0.0, true,
                Optional.of(0.1), CatalogEntry.Overrides.NONE);
        for (CatalogEntry entry : List.of(cobble, flexible)) {
            withEntry(entry, () -> {
                for (double start : List.of(1.0, 0.6, MarketConfig.floor())) {
                    for (int qty : List.of(1, 64, 640)) {
                        Fixture f = new Fixture(helper);
                        f.deposit(1_000_000);
                        f.market.setFactor(entry.itemId(), start, MarketStockData.now(helper.getLevel().getServer()));
                        int before = f.balance();
                        f.assertResult(MarketService.tryBuy(f.player, Items.COBBLESTONE, qty, ANY_PRICE), Result.OK);
                        f.assertResult(MarketService.trySell(f.player, Items.COBBLESTONE, qty, ANY_PRICE), Result.OK);
                        helper.assertTrue(f.balance() <= before, "round trip of %d cobblestone (base %d) from %s made %d"
                                .formatted(qty, entry.basePrice(), start, f.balance() - before));
                    }
                }
            });
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void fixedPriceAndSellFee(GameTestHelper helper) {
        CatalogEntry iron = MarketCatalog.INSTANCE.get(Items.IRON_INGOT).orElseThrow();
        withEntry(iron.withPricing(0.1, false, Optional.empty(), CatalogEntry.Overrides.NONE), () -> {
            Fixture f = new Fixture(helper);
            f.giveMany(Items.IRON_INGOT, 100);
            f.assertResult(MarketService.trySell(f.player, Items.IRON_INGOT, 10, ANY_PRICE), Result.OK);
            helper.assertValueEqual(f.balance(), 144, "10 × 16 less a 10% fee");
            f.assertResult(MarketService.trySell(f.player, Items.IRON_INGOT, 90, ANY_PRICE), Result.OK);
            helper.assertValueEqual(f.factor(Items.IRON_INGOT), 1.0, "dynamic: false keeps the factor at 1.0");
            helper.assertValueEqual(f.market.factorsView().containsKey(iron.itemId()), false, "nothing stored");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void resetPriceRestoresBase(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        long now = MarketStockData.now(helper.getLevel().getServer());
        ResourceLocation iron = BuiltInRegistries.ITEM.getKey(Items.IRON_INGOT);
        ResourceLocation diamond = BuiltInRegistries.ITEM.getKey(Items.DIAMOND);
        f.market.setFactor(iron, 0.5, now);
        f.market.setFactor(diamond, 1.5, now);

        helper.assertTrue(f.market.resetFactor(iron), "iron had moved");
        helper.assertValueEqual(f.factor(Items.IRON_INGOT), 1.0, "iron reset");
        helper.assertValueEqual(f.factor(Items.DIAMOND), 1.5, "diamond untouched");
        helper.assertValueEqual(f.market.resetAllFactors(), 1, "reset all");
        helper.assertValueEqual(f.factor(Items.DIAMOND), 1.0, "diamond reset");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void pricingFieldsAreValidated(GameTestHelper helper) {
        JsonElement file = JsonParser.parseString("""
                { "entries": [
                    { "item": "minecraft:stone",     "base_price": 10, "min_buy_markup": 0 },
                    { "item": "minecraft:dirt",      "base_price": 10, "markup": 0.5, "min_buy_markup": 0.6 },
                    { "item": "minecraft:sand",      "base_price": 10, "markup": 0 },
                    { "item": "minecraft:gravel",    "base_price": 10, "markup": 0.5, "min_buy_markup": 0.2, "sell_fee": 0.05,
                      "sell_impact": 0.01, "buy_impact": 0.005, "recovery": 0.2, "floor": 0.5, "ceiling": 2, "dynamic": false },
                    { "item": "minecraft:clay_ball", "base_price": 10, "sell_fee": 1.0 },
                    { "item": "minecraft:flint",     "base_price": 10, "floor": 0 },
                    { "item": "minecraft:glass",     "base_price": 10, "ceiling": 0.5 }
                ] }""");
        Map<Item, CatalogEntry> parsed = MarketCatalog.parse(Map.of(ResourceLocation.fromNamespaceAndPath("test", "pricing"), file));

        helper.assertValueEqual(parsed.keySet(), java.util.Set.of(Items.SAND, Items.GRAVEL), "only valid entries kept");
        PriceModel.Inputs sand = PriceModel.inputs(parsed.get(Items.SAND));
        helper.assertValueEqual(sand.minBuyMarkup(), 0.0, "markup 0 with no explicit min_buy_markup is allowed");
        CatalogEntry gravel = parsed.get(Items.GRAVEL);
        helper.assertValueEqual(gravel.overrides(), new CatalogEntry.Overrides(Optional.of(0.01), Optional.of(0.005),
                Optional.of(0.2), Optional.of(0.5), Optional.of(2.0)), "overrides read");
        helper.assertTrue(!gravel.dynamic() && gravel.sellFee() == 0.05, "dynamic and sell_fee read");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void arbitrageRiskIsDetected(GameTestHelper helper) {
        helper.assertTrue(PriceModel.arbitrageRisk(inputs(16, 0.5, 0.0, false)).isEmpty(), "default config is safe");
        PriceModel.Inputs lopsided = new PriceModel.Inputs(16, 0.5, 0.0, 0.5, false, true, 0.0005, 0.001, 0.2, 5.0, 0.05);
        helper.assertTrue(PriceModel.arbitrageRisk(lopsided).isPresent(), "buy impact outweighing sell impact");
        PriceModel.Inputs thinMarkup = new PriceModel.Inputs(16, 0.001, 0.0, 0.001, false, true, 0.3, 0.2, 0.2, 5.0, 0.05);
        helper.assertTrue(PriceModel.arbitrageRisk(thinMarkup).isPresent(), "buy impact above markup");
        helper.succeed();
    }

    private static PriceModel.Inputs inputs(int base, double markup, double sellFee, boolean unlimited) {
        return new PriceModel.Inputs(base, markup, sellFee, markup, unlimited, true, MarketConfig.sellImpact(),
                MarketConfig.buyImpact(), MarketConfig.floor(), MarketConfig.ceiling(), MarketConfig.recovery());
    }

    private static void assertClose(GameTestHelper helper, double actual, double expected, String what) {
        helper.assertTrue(Math.abs(actual - expected) < 1e-12, what + ": expected " + expected + " but was " + actual);
    }

    // ---- Fixture ------------------------------------------------------------------------------------------------

    /** A fresh player with an empty inventory and a new, empty bank account (Numismatics' starter coins default to 0). */
    private static final class Fixture {
        final GameTestHelper helper;
        final ServerPlayer player;
        final MarketStockData market;

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            this.player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                    new GameProfile(UUID.randomUUID(), "market-test"), ClientInformation.createDefault());
            this.market = MarketStockData.get(helper.getLevel().getServer());
            helper.assertTrue(NumismaticsBridge.isAvailable(), "Numismatics bridge must be available");
            // Every test starts from base prices; tests share the market but each runs within a single tick.
            market.resetAllFactors();
            int starting = balance();
            if (starting > 0) {
                account().deduct(starting);
            }
        }

        NumismaticsBridge.Account account() {
            return NumismaticsBridge.account(player).orElseThrow();
        }

        int balance() {
            return account().balance();
        }

        void deposit(int spurs) {
            account().deposit(spurs);
        }

        void give(ItemStack stack) {
            NonNullList<ItemStack> items = player.getInventory().items;
            for (int slot = 0; slot < items.size(); slot++) {
                if (items.get(slot).isEmpty()) {
                    items.set(slot, stack);
                    return;
                }
            }
            throw new IllegalStateException("Test inventory is full");
        }

        void giveMany(Item item, int count) {
            for (int left = count; left > 0; left -= 64) {
                give(new ItemStack(item, Math.min(64, left)));
            }
        }

        void clearInventory() {
            player.getInventory().items.clear();
        }

        double factor(Item item) {
            CatalogEntry entry = MarketCatalog.INSTANCE.get(item).orElseThrow();
            return MarketService.currentFactor(entry, PriceModel.inputs(entry), market, MarketStockData.now(player.server));
        }

        MarketService.Quote quote(Item item) {
            return MarketService.quote(player.server, item).orElseThrow();
        }

        long count(Item item) {
            return player.getInventory().items.stream()
                    .filter(s -> ItemStack.isSameItemSameComponents(s, new ItemStack(item)))
                    .mapToLong(ItemStack::getCount).sum();
        }

        long stock(Item item) {
            return market.getStock(BuiltInRegistries.ITEM.getKey(item));
        }

        void setStock(Item item, long amount) {
            market.setStock(BuiltInRegistries.ITEM.getKey(item), amount);
        }

        void assertResult(TradeResult result, Result expected) {
            helper.assertValueEqual(result.result(), expected, "trade result");
        }

        void assertRejectedUnchanged(Result expected, java.util.function.Supplier<TradeResult> trade) {
            List<ItemStack> inventoryBefore = new ArrayList<>();
            player.getInventory().items.forEach(s -> inventoryBefore.add(s.copy()));
            Map<ResourceLocation, Long> stockBefore = Map.copyOf(market.view());
            int balanceBefore = balance();

            assertResult(trade.get(), expected);

            helper.assertValueEqual(balance(), balanceBefore, "balance after " + expected);
            helper.assertValueEqual(Map.copyOf(market.view()), stockBefore, "stock after " + expected);
            NonNullList<ItemStack> after = player.getInventory().items;
            for (int slot = 0; slot < after.size(); slot++) {
                helper.assertTrue(ItemStack.matches(after.get(slot), inventoryBefore.get(slot)),
                        "inventory slot " + slot + " changed after " + expected);
            }
        }
    }
}
