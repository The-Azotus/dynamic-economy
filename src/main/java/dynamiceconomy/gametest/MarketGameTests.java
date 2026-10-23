package dynamiceconomy.gametest;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.MarketConfig;
import dynamiceconomy.MarketAccess;
import dynamiceconomy.block.MarketBlocks;
import dynamiceconomy.block.MarketTerminalBlock;
import dynamiceconomy.catalog.CatalogOverlay;
import dynamiceconomy.data.MarketOverrides;
import dynamiceconomy.menu.MarketMenu;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.compat.NumismaticsBridge;
import dynamiceconomy.data.MarketStockData;
import dynamiceconomy.io.CatalogExport;
import dynamiceconomy.io.CatalogImport;
import dynamiceconomy.io.Csv;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
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

    // ---- Export, import and catalog loading -------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void csvReaderHandlesSpreadsheetQuirks(GameTestHelper helper) {
        // BOM, semicolons, header in another order and case, a quoted field with the delimiter, quotes and a line
        // break, a decimal comma, an unknown column and a blank line.
        String text = "\uFEFFMarkup;Item_ID;Notes;base_price;display_name\r\n"
                + "0,25;minecraft:stone;whatever;5;\"Stone; \"\"smooth\"\"\nsecond line\"\r\n"
                + "\r\n"
                + ";minecraft:dirt;;7;Dirt\r\n";
        Csv.Table table = Csv.parse(text);
        helper.assertValueEqual(table.delimiter(), ';', "delimiter detected");
        helper.assertValueEqual(table.headers(), List.of("markup", "item_id", "notes", "base_price", "display_name"), "headers");
        helper.assertValueEqual(table.rows().size(), 2, "blank line ignored");
        helper.assertValueEqual(table.rows().get(0).get("display_name"), "Stone; \"smooth\"\nsecond line", "quoted field");
        helper.assertValueEqual(table.rows().get(1).line(), 5, "line numbers count the embedded line break");

        CatalogImport.Report report = CatalogImport.analyze(text);
        helper.assertValueEqual(report.toWrite(), 2, "both rows import");
        CatalogEntry stone = report.decoded().stream().filter(e -> e.item() == Items.STONE).findFirst().orElseThrow();
        helper.assertValueEqual(stone.markup(), 0.25, "decimal comma read");
        CatalogEntry dirt = report.decoded().stream().filter(e -> e.item() == Items.DIRT).findFirst().orElseThrow();
        helper.assertValueEqual(dirt.markup(), CatalogEntry.DEFAULT_MARKUP, "blank markup falls back to the default");

        String commas = "item_id,base_price,display_name\nminecraft:stone,5,\"Stone, smooth\"\n";
        helper.assertValueEqual(Csv.parse(commas).rows().get(0).get("display_name"), "Stone, smooth", "comma file");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void importRejectsBadRowsWithReasons(GameTestHelper helper) {
        String text = """
                item_id,base_price,markup,min_buy_markup,unlimited_stock,can_sell
                minecraft:stone,5,,,,
                minecraft:dirt,,,,,
                minecraft:not_a_real_item,5,,,,
                minecraft:sand,abc,,,,
                minecraft:gravel,0,,,,
                minecraft:clay_ball,-5,,,,
                minecraft:flint,1.5,,,,
                minecraft:glass,5,0.5,0.9,true,
                minecraft:stone,6,,,,
                minecraft:brick,5,,,maybe,
                Not An Id,5,,,,
                minecraft:bone,5,,,,no
                """;
        CatalogImport.Report report = CatalogImport.analyze(text);
        helper.assertValueEqual(report.toWrite(), 2, "stone and bone import");
        helper.assertValueEqual(report.skippedBlank(), 1, "dirt has no price");
        helper.assertValueEqual(report.rejected().size(), 9, "rejections: " + report.rejected());
        String all = String.join("\n", report.rejected());
        for (String expected : List.of("line 4", "unknown item", "line 5", "line 6", "line 7", "line 8", "min_buy_markup",
                "duplicate of line 2", "must be true or false", "not a valid item ID")) {
            helper.assertTrue(all.contains(expected), "expected a rejection mentioning \"" + expected + "\" in:\n" + all);
        }
        CatalogEntry bone = report.decoded().stream().filter(e -> e.item() == Items.BONE).findFirst().orElseThrow();
        helper.assertTrue(!bone.canSell(), "\"no\" reads as false");
        helper.assertTrue(CatalogImport.analyze("name,price\nx,1\n").rejected().get(0).contains("item_id"), "missing columns");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void exportThenImportGivesTheSameCatalog(GameTestHelper helper) {
        try {
            Path root = Files.createTempDirectory("market-export");
            // Include an override and a min_buy_markup so the extra columns are covered too.
            CatalogEntry iron = MarketCatalog.INSTANCE.get(Items.IRON_INGOT).orElseThrow();
            CatalogEntry tuned = iron.withPricing(0.05, true, Optional.empty(), new CatalogEntry.Overrides(
                    Optional.of(0.001), Optional.empty(), Optional.of(0.1), Optional.empty(), Optional.of(3.0)));
            CatalogEntry cobble = MarketCatalog.INSTANCE.get(Items.COBBLESTONE).orElseThrow()
                    .withPricing(0.0, true, Optional.of(0.25), CatalogEntry.Overrides.NONE);
            List<CatalogEntry> catalog = new ArrayList<>(MarketCatalog.INSTANCE.entries());
            catalog.replaceAll(e -> e.item() == Items.IRON_INGOT ? tuned : e.item() == Items.COBBLESTONE ? cobble : e);

            CatalogExport.Result result = CatalogExport.export(root, Optional.empty(), catalog);
            long registered = BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR).count();
            helper.assertValueEqual((long) result.total(), registered, "every item but air exported");
            helper.assertValueEqual(result.priced(), catalog.size(), "already priced");
            helper.assertValueEqual(result.unpriced(), result.total() - catalog.size(), "new");

            CatalogImport.Report report = CatalogImport.analyze(Files.readString(result.folder().resolve("items.csv")));
            helper.assertValueEqual(report.rejected(), List.of(), "an unchanged export imports cleanly");
            helper.assertValueEqual(report.skippedBlank(), result.unpriced(), "unpriced rows are skipped");
            helper.assertValueEqual(new java.util.HashSet<>(report.decoded()), new java.util.HashSet<>(catalog), "entries identical");

            // Templates load without error, and every entry is skipped as unpriced until someone prices it.
            Path templates = result.folder().resolve("templates");
            Path minecraft = templates.resolve("minecraft.json");
            helper.assertTrue(Files.exists(minecraft), "a template for minecraft");
            Map<Item, CatalogEntry> loaded = MarketCatalog.parse(Map.of(ResourceLocation.fromNamespaceAndPath("test", "template"),
                    JsonParser.parseString(Files.readString(minecraft))));
            helper.assertTrue(loaded.isEmpty(), "unpriced template entries are skipped");

            CatalogExport.Result onlyMinecraft = CatalogExport.export(root, Optional.of("minecraft"), catalog);
            long minecraftItems = BuiltInRegistries.ITEM.keySet().stream()
                    .filter(id -> id.getNamespace().equals("minecraft") && !id.getPath().equals("air")).count();
            helper.assertValueEqual((long) onlyMinecraft.total(), minecraftItems, "modid filter");
            helper.assertTrue(!onlyMinecraft.folder().equals(result.folder()), "a second export never reuses a folder");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void importWritesThePackAndBacksUpReplacedFiles(GameTestHelper helper) {
        try {
            Path datapacks = Files.createTempDirectory("market-datapacks");
            CatalogImport.Report first = CatalogImport.analyze("item_id,base_price\nminecraft:stone,5\nminecraft:dirt,2\n");
            CatalogImport.Written written = CatalogImport.write(first, datapacks);
            Path pack = datapacks.resolve(CatalogImport.PACK_FOLDER);
            Path file = pack.resolve("data/dynamic_economy/market/imported_minecraft.json");
            helper.assertTrue(written.createdPack(), "pack created");
            helper.assertTrue(Files.exists(pack.resolve("pack.mcmeta")), "pack.mcmeta written");
            helper.assertValueEqual(written.files(), List.of(file), "one file for one mod");
            Map<Item, CatalogEntry> loaded = MarketCatalog.parse(Map.of(ResourceLocation.fromNamespaceAndPath("test", "imported"),
                    JsonParser.parseString(Files.readString(file))));
            helper.assertValueEqual(new java.util.HashSet<>(loaded.values()), new java.util.HashSet<>(first.decoded()), "written file loads back");

            CatalogImport.Report second = CatalogImport.analyze("item_id,base_price\nminecraft:stone,9\n");
            CatalogImport.Written again = CatalogImport.write(second, datapacks);
            helper.assertTrue(!again.createdPack(), "pack already existed");
            Path backup = file.resolveSibling("imported_minecraft.json.bak");
            helper.assertValueEqual(again.backups(), List.of(backup), "backup made");
            helper.assertTrue(Files.readString(backup).contains("minecraft:dirt"), "backup holds the previous file");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void importPathsCannotLeaveTheConfigFolder(GameTestHelper helper) {
        Path root = Path.of("config", "dynamic_economy");
        for (String bad : List.of("../server.properties", "exports/../../x.csv", "/etc/passwd", "C:/x.csv", "C:x.csv",
                "\\\\server\\share\\x.csv", "exports\\..\\..\\x.csv", "")) {
            try {
                CatalogImport.resolveInside(root, bad);
                throw new GameTestAssertException("accepted \"" + bad + "\"");
            } catch (IllegalArgumentException expected) {
                // Rejected, as it should be.
            }
        }
        Path ok = CatalogImport.resolveInside(root, "exports/2026-10-23_09-30-00/items.csv");
        helper.assertTrue(ok.startsWith(root.toAbsolutePath().normalize()), "a normal path resolves inside the folder");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void unpricedEntriesAreSkipped(GameTestHelper helper) {
        JsonElement file = JsonParser.parseString("""
                { "defaults": { "base_price": 0 },
                  "entries": [
                    { "item": "minecraft:stone" },
                    { "item": "minecraft:dirt", "base_price": -3 },
                    { "item": "minecraft:sand", "base_price": 4 }
                  ] }""");
        Map<Item, CatalogEntry> parsed = MarketCatalog.parse(Map.of(ResourceLocation.fromNamespaceAndPath("test", "unpriced"), file));
        helper.assertValueEqual(parsed.keySet(), java.util.Set.of(Items.SAND), "only the priced entry loads");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void higherPriorityPackWins(GameTestHelper helper) {
        JsonElement cheap = JsonParser.parseString("{ \"entries\": [ { \"item\": \"minecraft:iron_ingot\", \"base_price\": 10 } ] }");
        JsonElement dear = JsonParser.parseString("{ \"entries\": [ { \"item\": \"minecraft:iron_ingot\", \"base_price\": 20 } ] }");
        // The world pack's file sorts first by name but must still win over the mod's built-in file.
        Map<Item, CatalogEntry> parsed = MarketCatalog.parse(List.of(
                new MarketCatalog.SourceFile(ResourceLocation.fromNamespaceAndPath("dynamic_economy", "test_catalog"), 0, "mod_data", cheap),
                new MarketCatalog.SourceFile(ResourceLocation.fromNamespaceAndPath("dynamic_economy", "imported_minecraft"), 1, "file/x", dear)));
        helper.assertValueEqual(parsed.get(Items.IRON_INGOT).basePrice(), 20, "higher pack wins");
        Map<Item, CatalogEntry> samePack = MarketCatalog.parse(List.of(
                new MarketCatalog.SourceFile(ResourceLocation.fromNamespaceAndPath("x", "b"), 0, "p", cheap),
                new MarketCatalog.SourceFile(ResourceLocation.fromNamespaceAndPath("x", "a"), 0, "p", dear)));
        helper.assertValueEqual(samePack.get(Items.IRON_INGOT).basePrice(), 10, "within a pack, the file sorting last wins");
        helper.succeed();
    }

    // ---- Price history ----------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void historyKeepsOnePointPerIntervalAndIsCapped(GameTestHelper helper) {
        MarketStockData data = new MarketStockData();
        ResourceLocation stone = ResourceLocation.withDefaultNamespace("stone");
        int interval = 1200;
        data.recordHistory(stone, 1.0, 0, interval);
        helper.assertValueEqual(data.history(stone).size(), 0, "an item at base price keeps no history");
        data.recordHistory(stone, 0.9, 0, interval);
        data.recordHistory(stone, 0.8, 10, interval);
        helper.assertValueEqual(data.history(stone).size(), 1, "same interval replaces the point");
        helper.assertValueEqual(data.history(stone).factors()[0], 0.8f, "with the newer value");
        data.recordHistory(stone, 0.8, 1300, interval);
        helper.assertValueEqual(data.history(stone).size(), 1, "an unchanged value adds nothing");
        for (int i = 0; i < 100; i++) {
            data.recordHistory(stone, 0.5 + i * 0.001, 2400L + i * interval, interval);
        }
        MarketStockData.History history = data.history(stone);
        helper.assertValueEqual(history.size(), MarketStockData.HISTORY_CAP, "capped");
        helper.assertValueEqual(history.ticks()[history.size() - 1], 2400L + 99L * interval, "newest kept");

        data.setStock(stone, 42);
        data.setFactor(stone, 0.75, 5000);
        MarketStockData reloaded = MarketStockData.fromTag(data.save(new CompoundTag(), helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        helper.assertValueEqual(reloaded.getStock(stone), 42L, "stock saved");
        helper.assertValueEqual(reloaded.factorsView().get(stone), new MarketStockData.Factor(0.75, 5000), "factor saved");
        helper.assertTrue(java.util.Arrays.equals(reloaded.history(stone).ticks(), history.ticks())
                && java.util.Arrays.equals(reloaded.history(stone).factors(), history.factors()), "history saved");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void tradesAddToPriceHistory(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        ResourceLocation iron = BuiltInRegistries.ITEM.getKey(Items.IRON_INGOT);
        f.giveMany(Items.IRON_INGOT, 100);
        f.assertResult(MarketService.trySell(f.player, Items.IRON_INGOT, 100, ANY_PRICE), Result.OK);
        MarketStockData.History history = f.market.history(iron);
        helper.assertTrue(history.size() > 0, "a sale adds a point");
        helper.assertValueEqual(history.factors()[history.size() - 1], (float) f.factor(Items.IRON_INGOT), "latest point is the factor now");
        helper.succeed();
    }

    // ---- Admin edits and tag rules ----------------------------------------------------------------------------

    /** The catalog files as loaded in the test world (which has no admin edits of its own). */
    private static Map<Item, CatalogEntry> files() {
        Map<Item, CatalogEntry> files = new java.util.LinkedHashMap<>();
        MarketCatalog.INSTANCE.entries().forEach(e -> files.put(e.item(), e));
        return files;
    }

    private static CatalogOverlay.Result overlay(MarketOverrides edits) {
        return CatalogOverlay.apply(files(), edits.snapshot(), CatalogOverlay::registryTag);
    }

    private static ResourceLocation key(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    @GameTest(template = EMPTY)
    public static void adminEditsChangeOnlyWhatTheySet(GameTestHelper helper) {
        MarketOverrides edits = new MarketOverrides();
        CatalogEntry ironFile = MarketCatalog.INSTANCE.get(Items.IRON_INGOT).orElseThrow();

        edits.setItemField(key(Items.IRON_INGOT), "base_price", new com.google.gson.JsonPrimitive(20));
        edits.setItemField(key(Items.STONE), "base_price", new com.google.gson.JsonPrimitive(5));
        edits.setItemField(key(Items.DIRT), "markup", new com.google.gson.JsonPrimitive(1.0));
        CatalogOverlay.Result result = overlay(edits);

        CatalogEntry iron = result.entries().get(Items.IRON_INGOT);
        helper.assertValueEqual(iron.basePrice(), 20, "iron price edited");
        helper.assertValueEqual(new CatalogEntry(iron.item(), iron.itemId(), ironFile.basePrice(), iron.markup(), iron.startStock(),
                iron.canSell(), iron.canBuy(), iron.unlimitedStock(), iron.sellFee(), iron.dynamic(), iron.minBuyMarkup(),
                iron.overrides()), ironFile, "iron keeps every other field");
        helper.assertValueEqual(result.entries().get(Items.STONE).basePrice(), 5, "setprice lists an unlisted item");
        helper.assertTrue(!result.entries().containsKey(Items.DIRT), "an unlisted item needs a price to be listed");
        helper.assertTrue(result.problems().containsKey(key(Items.DIRT)), "and that is reported");

        edits.unsetItemField(key(Items.DIRT), "markup");
        edits.removeItem(key(Items.IRON_INGOT));
        result = overlay(edits);
        helper.assertTrue(!result.entries().containsKey(Items.IRON_INGOT), "remove delists");
        helper.assertTrue(result.problems().isEmpty(), "no problems left: " + result.problems());

        edits.revertItem(key(Items.IRON_INGOT));
        helper.assertValueEqual(overlay(edits).entries().get(Items.IRON_INGOT), ironFile, "revert restores the file entry");

        // A bad edit is reported and the item keeps its file entry rather than vanishing.
        edits.setItemField(key(Items.IRON_INGOT), "min_buy_markup", new com.google.gson.JsonPrimitive(0.9));
        result = overlay(edits);
        helper.assertTrue(result.problems().get(key(Items.IRON_INGOT)).contains("min_buy_markup"), "bad edit reported");
        helper.assertValueEqual(result.entries().get(Items.IRON_INGOT), ironFile, "falls back to the file entry");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void tagRulesApplyToEveryMemberAndItemEditsWin(GameTestHelper helper) {
        ResourceLocation logs = ResourceLocation.withDefaultNamespace("logs");
        Collection<Item> allLogs = CatalogOverlay.registryTag(logs);
        helper.assertTrue(allLogs.size() > 10, "minecraft:logs has many items");
        MarketOverrides edits = new MarketOverrides();

        edits.setTagField(logs, "base_price", new com.google.gson.JsonPrimitive(4));
        CatalogOverlay.Result result = overlay(edits);
        for (Item log : allLogs) {
            helper.assertValueEqual(result.entries().get(log).basePrice(), 4, key(log) + " priced by the tag rule");
        }
        helper.assertTrue(result.entries().get(Items.OAK_LOG).unlimitedStock(), "oak keeps its file settings apart from the price");
        helper.assertTrue(!result.entries().get(Items.BIRCH_LOG).unlimitedStock(), "birch, unlisted before, gets the defaults");

        // Pricing one log by hand beats the tag rule, even after a later rule removes the tag.
        edits.setItemField(key(Items.BIRCH_LOG), "base_price", new com.google.gson.JsonPrimitive(9));
        edits.removeTag(ResourceLocation.withDefaultNamespace("logs_that_burn"));
        result = overlay(edits);
        helper.assertValueEqual(result.entries().get(Items.BIRCH_LOG).basePrice(), 9, "item edit wins");
        helper.assertTrue(!result.entries().containsKey(Items.SPRUCE_LOG), "the removal rule delists other burnable logs");
        helper.assertTrue(result.entries().containsKey(Items.CRIMSON_STEM), "nether stems aren't burnable logs, so they stay at 4");

        // A rule without a price only changes items that are already listed.
        edits.revertTag(ResourceLocation.withDefaultNamespace("logs_that_burn"));
        edits.setTagField(ResourceLocation.withDefaultNamespace("planks"), "markup", new com.google.gson.JsonPrimitive(1.0));
        result = overlay(edits);
        helper.assertTrue(!result.entries().containsKey(Items.OAK_PLANKS), "unpriced planks stay unlisted");
        helper.assertTrue(result.problems().isEmpty(), "and nothing is reported: " + result.problems());

        edits.revertTag(logs);
        result = overlay(edits);
        helper.assertTrue(!result.entries().containsKey(Items.SPRUCE_LOG), "reverting the rule unlists logs it had listed");
        helper.assertValueEqual(result.entries().get(Items.OAK_LOG), MarketCatalog.INSTANCE.get(Items.OAK_LOG).orElseThrow(), "oak is back to its file entry");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void adminEditsAreSavedWithTheWorld(GameTestHelper helper) {
        MarketOverrides edits = new MarketOverrides();
        edits.setItemField(key(Items.IRON_INGOT), "base_price", new com.google.gson.JsonPrimitive(20));
        edits.removeItem(key(Items.DIAMOND));
        edits.setTagField(ResourceLocation.withDefaultNamespace("logs"), "base_price", new com.google.gson.JsonPrimitive(4));
        edits.removeTag(ResourceLocation.withDefaultNamespace("wool"));
        MarketOverrides reloaded = MarketOverrides.fromTag(edits.save(new CompoundTag(), helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        helper.assertValueEqual(reloaded.snapshot(), edits.snapshot(), "edits and tag-rule order survive a save");
        helper.succeed();
    }

    // ---- Access and the terminal ------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void blockOnlyModeKeepsCommandsForOperators(GameTestHelper helper) {
        helper.assertTrue(MarketAccess.commandsAllowed(MarketConfig.AccessMode.COMMAND_AND_BLOCK, false), "everyone, by default");
        helper.assertTrue(!MarketAccess.commandsAllowed(MarketConfig.AccessMode.BLOCK_ONLY, false), "players need the block");
        helper.assertTrue(MarketAccess.commandsAllowed(MarketConfig.AccessMode.BLOCK_ONLY, true), "operators keep commands");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void terminalScreenClosesWhenYouWalkAwayOrItBreaks(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        net.minecraft.core.BlockPos pos = helper.absolutePos(net.minecraft.core.BlockPos.ZERO);
        helper.setBlock(net.minecraft.core.BlockPos.ZERO, MarketBlocks.MARKET_TERMINAL.get().defaultBlockState());
        MarketMenu menu = MarketMenu.forTerminal(f.player, pos);

        f.player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 3.5);
        helper.assertTrue(menu.stillValid(f.player), "valid next to the terminal");
        f.player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 20.5);
        helper.assertTrue(!menu.stillValid(f.player), "closes 20 blocks away");
        f.player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 3.5);
        helper.setBlock(net.minecraft.core.BlockPos.ZERO, Blocks.AIR);
        helper.assertTrue(!menu.stillValid(f.player), "closes when the terminal is broken");
        helper.assertTrue(MarketMenu.forCommand().stillValid(f.player), "a command-opened screen is valid in the default mode");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void terminalHasRecipeAndFacing(GameTestHelper helper) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(DynamicEconomy.MOD_ID, "market_terminal");
        helper.assertTrue(helper.getLevel().getServer().getRecipeManager().byKey(id).isPresent(),
                "the recipe loads, so its Create and Numismatics ingredients resolve");
        helper.setBlock(net.minecraft.core.BlockPos.ZERO, MarketBlocks.MARKET_TERMINAL.get().defaultBlockState()
                .setValue(MarketTerminalBlock.FACING, net.minecraft.core.Direction.EAST));
        helper.assertBlockProperty(net.minecraft.core.BlockPos.ZERO, MarketTerminalBlock.FACING, net.minecraft.core.Direction.EAST);
        helper.succeed();
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
