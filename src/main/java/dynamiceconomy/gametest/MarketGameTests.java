package dynamiceconomy.gametest;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.MarketConfig;
import dynamiceconomy.MarketAccess;
import dynamiceconomy.block.MarketBlocks;
import dynamiceconomy.block.MarketTerminalBlock;
import dynamiceconomy.catalog.CatalogOverlay;
import dynamiceconomy.data.AuditLog;
import dynamiceconomy.data.MarketBackup;
import dynamiceconomy.data.MarketOverrides;
import dynamiceconomy.data.TradeHistory;
import dynamiceconomy.menu.MarketMenu;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.catalog.FieldSource;
import dynamiceconomy.catalog.ItemClasses;
import dynamiceconomy.compat.NumismaticsBridge;
import dynamiceconomy.data.MarketStockData;
import dynamiceconomy.io.CatalogExport;
import dynamiceconomy.io.CatalogImport;
import dynamiceconomy.io.Csv;
import dynamiceconomy.network.MarketPayloads;
import dynamiceconomy.network.MarketSync;
import dynamiceconomy.service.MarketDiagnostics;
import dynamiceconomy.service.MarketListing;
import dynamiceconomy.service.MarketService;
import dynamiceconomy.service.OrderMath;
import dynamiceconomy.service.MarketService.Result;
import dynamiceconomy.service.MarketService.TradeResult;
import dynamiceconomy.service.PriceModel;
import dynamiceconomy.service.PriceSuggestion;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Server-side market tests. Each test runs synchronously inside one tick, so tests sharing the global stock and
 * catalog cannot interleave. Relies on the example catalog pack, which the GameTest server enables like every
 * available pack. Run with {@code gradlew runGameTestServer}.
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
                Optional.of(0.1), CatalogEntry.Overrides.NONE, Optional.empty());
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
                Optional.of(0.1), CatalogEntry.Overrides.NONE, Optional.empty());
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

            // No recorded sources: every value is written, as if every field were set.
            CatalogExport.Result result = CatalogExport.export(root, Optional.empty(), catalog, item -> FieldSource.NONE);
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

            CatalogExport.Result onlyMinecraft = CatalogExport.export(root, Optional.of("minecraft"), catalog, item -> FieldSource.NONE);
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
                iron.overrides(), iron.itemClass()), ironFile, "iron keeps every other field");
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
                "the recipe loads, so its Numismatics ingredient resolves");
        helper.setBlock(net.minecraft.core.BlockPos.ZERO, MarketBlocks.MARKET_TERMINAL.get().defaultBlockState()
                .setValue(MarketTerminalBlock.FACING, net.minecraft.core.Direction.EAST));
        helper.assertBlockProperty(net.minecraft.core.BlockPos.ZERO, MarketTerminalBlock.FACING, net.minecraft.core.Direction.EAST);
        helper.succeed();
    }

    /** What a 3x3 crafting grid makes, if anything. */
    private static Optional<ItemStack> craft(GameTestHelper helper, Item... grid) {
        List<ItemStack> stacks = new ArrayList<>();
        for (Item item : grid) {
            stacks.add(new ItemStack(item));
        }
        CraftingInput input = CraftingInput.of(3, 3, stacks);
        return helper.getLevel().getServer().getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel())
                .map(r -> r.value().assemble(input, helper.getLevel().registryAccess()));
    }

    @GameTest(template = EMPTY)
    public static void terminalCraftsFromCraftingTableAndSpurs(GameTestHelper helper) {
        Item spur = BuiltInRegistries.ITEM.get(ResourceLocation.parse("numismatics:spur"));
        helper.assertTrue(spur != Items.AIR, "Numismatics spur exists");
        Item t = Items.CRAFTING_TABLE;
        Optional<ItemStack> made = craft(helper, spur, spur, spur, spur, t, spur, spur, spur, spur);
        helper.assertTrue(made.isPresent() && made.get().is(MarketBlocks.MARKET_TERMINAL_ITEM.get()) && made.get().getCount() == 1,
                "a crafting table surrounded by 8 spurs makes one terminal, got " + made);

        Item g = Items.GOLD_INGOT, r = Items.REDSTONE;
        Item cog = BuiltInRegistries.ITEM.get(ResourceLocation.parse("numismatics:cog"));
        Item tube = BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:electron_tube"));
        Item casing = BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:brass_casing"));
        Optional<ItemStack> old = craft(helper, g, cog, g, tube, casing, tube, g, r, g);
        helper.assertTrue(old.isEmpty() || !old.get().is(MarketBlocks.MARKET_TERMINAL_ITEM.get()),
                "the old recipe no longer makes a terminal");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void terminalIsATableThatDropsItself(GameTestHelper helper) {
        BlockState state = MarketBlocks.MARKET_TERMINAL.get().defaultBlockState();
        net.minecraft.core.BlockPos pos = helper.absolutePos(net.minecraft.core.BlockPos.ZERO);
        helper.setBlock(net.minecraft.core.BlockPos.ZERO, state);
        helper.assertTrue(!state.isCollisionShapeFullBlock(helper.getLevel(), pos), "the table is not a full block");
        helper.assertTrue(!state.canOcclude(), "neighbouring faces still render");
        helper.assertTrue(!state.requiresCorrectToolForDrops(), "breaks by hand");
        helper.assertTrue(state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE), "axes break it faster");
        List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(state, helper.getLevel(), pos, null);
        helper.assertTrue(drops.size() == 1 && drops.get(0).is(MarketBlocks.MARKET_TERMINAL_ITEM.get()),
                "broken by hand, it drops itself, got " + drops);
        helper.succeed();
    }

    // ---- Item classes (Phase 9) -------------------------------------------------------------------------------

    private static final List<String> BUILT_IN_CLASSES = List.of("common_resource", "common_processed", "farmable",
            "rare_resource", "rare_processed", "rare_loot", "unique");

    private static MarketCatalog.Loaded loadOne(String json) {
        return MarketCatalog.load(List.of(new MarketCatalog.SourceFile(ResourceLocation.fromNamespaceAndPath("test", "classes"), 0,
                "test", JsonParser.parseString(json))));
    }

    @GameTest(template = EMPTY)
    public static void builtInClassesLoadAndNeverAllowARoundTripProfit(GameTestHelper helper) {
        for (String name : BUILT_IN_CLASSES) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(DynamicEconomy.MOD_ID, name);
            helper.assertTrue(ItemClasses.get(id).isPresent(), "built-in class " + name + " loaded");
            for (int base : List.of(1, 16, 8192)) {
                CatalogEntry entry = loadOne("""
                        { "entries": [ { "item": "minecraft:iron_ingot", "base_price": %d, "class": "%s" } ] }"""
                        .formatted(base, name)).entries().get(Items.IRON_INGOT);
                helper.assertTrue(entry != null, name + " decodes");
                PriceModel.Inputs in = PriceModel.inputs(entry);
                helper.assertValueEqual(PriceModel.arbitrageRisk(in), Optional.empty(), name + " passes the round-trip check");
                long profit = bestRoundTripProfit(in);
                helper.assertTrue(profit <= 0, "%s at base %d: a round trip made %d".formatted(name, base, profit));
            }
        }
        List<String> classProblems = MarketCatalog.INSTANCE.files().problems().stream().map(MarketCatalog.Problem::message)
                .filter(message -> message.contains("Market class")).toList();
        helper.assertValueEqual(classProblems, List.of(), "the built-in classes load without warnings");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aClassFillsOnlyTheFieldsNothingElseSets(GameTestHelper helper) {
        MarketCatalog.Loaded loaded = loadOne("""
                { "defaults": { "markup": 0.8 },
                  "entries": [
                    { "item": "minecraft:iron_ingot", "base_price": 16, "class": "rare_resource" },
                    { "item": "minecraft:gold_ingot", "base_price": 32, "class": "rare_resource", "sell_impact": 0.01, "buy_impact": 0.01 },
                    { "item": "minecraft:diamond", "base_price": 256 },
                    { "item": "minecraft:emerald", "base_price": 64, "class": "nope" } ] }""");
        ItemClasses.ItemClass rare = ItemClasses.get(ResourceLocation.fromNamespaceAndPath(DynamicEconomy.MOD_ID, "rare_resource")).orElseThrow();
        double classImpact = rare.values().get("sell_impact").getAsDouble();
        double classFloor = rare.values().get("floor").getAsDouble();

        CatalogEntry iron = loaded.entries().get(Items.IRON_INGOT);
        helper.assertValueEqual(iron.itemClass(), Optional.of(rare.id()), "iron knows its class");
        helper.assertValueEqual(iron.markup(), 0.8, "file defaults beat the class");
        helper.assertValueEqual(iron.overrides().sellImpact(), Optional.of(classImpact), "the class sets what nothing else does");
        helper.assertValueEqual(iron.overrides().floor(), Optional.of(classFloor), "including the floor");
        helper.assertTrue(!loaded.sources().get(Items.IRON_INGOT).containsKey("sell_impact"),
                "class values aren't recorded as set by a catalog layer");

        CatalogEntry gold = loaded.entries().get(Items.GOLD_INGOT);
        helper.assertValueEqual(gold.overrides().sellImpact(), Optional.of(0.01), "the entry beats the class");
        helper.assertValueEqual(gold.overrides().floor(), Optional.of(classFloor), "and keeps the class's other fields");

        CatalogEntry diamond = loaded.entries().get(Items.DIAMOND);
        helper.assertValueEqual(diamond.itemClass(), Optional.empty(), "no class");
        helper.assertValueEqual(diamond.overrides().sellImpact(), Optional.empty(), "server config applies");

        helper.assertTrue(!loaded.entries().containsKey(Items.EMERALD), "an unknown class skips the entry");
        helper.assertTrue(loaded.problems().stream().anyMatch(p -> p.skipped() && p.message().contains("unknown class dynamic_economy:nope")),
                "and says why: " + loaded.problems());
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void badClassFilesAreSkippedOrWarned(GameTestHelper helper) {
        ResourceLocation bad = ResourceLocation.fromNamespaceAndPath("test", "bad");
        ResourceLocation typo = ResourceLocation.fromNamespaceAndPath("test", "typo");
        ResourceLocation ok = ResourceLocation.fromNamespaceAndPath("test", "ok");
        ItemClasses.Loaded classes = ItemClasses.parse(Map.of(
                bad, JsonParser.parseString("{ \"sell_impact\": 0.9 }"),
                typo, JsonParser.parseString("{ \"markup\": 0.6, \"recovry\": 0.1, \"base_price\": 5 }"),
                ok, JsonParser.parseString("{ \"description\": \"fine\", \"markup\": 0.6 }")));
        helper.assertTrue(!classes.classes().containsKey(bad), "an out-of-range value skips the class");
        helper.assertTrue(classes.classes().containsKey(typo), "unknown fields only warn");
        helper.assertValueEqual(classes.classes().get(typo).values().keySet(), Set.of("markup"), "and are dropped");
        helper.assertValueEqual(classes.classes().get(ok).description(), "fine", "description kept");
        helper.assertTrue(classes.problems().stream().anyMatch(p -> p.skipped() && p.message().contains("test:bad")), "bad reported");
        helper.assertTrue(classes.problems().stream().anyMatch(p -> !p.skipped() && p.message().contains("\"recovry\"")), "typo reported");
        helper.assertTrue(classes.problems().stream().anyMatch(p -> p.message().contains("\"base_price\"") && p.message().contains("per item")),
                "base_price explained");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void tagRulesAndEditsCanChangeTheClass(GameTestHelper helper) {
        MarketCatalog.Loaded loaded = loadOne("""
                { "entries": [ { "item": "minecraft:oak_log", "base_price": 2 } ] }""");
        ResourceLocation logs = ResourceLocation.withDefaultNamespace("logs");
        MarketOverrides edits = new MarketOverrides();
        edits.setTagField(logs, "class", new com.google.gson.JsonPrimitive("rare_resource"));
        CatalogOverlay.Result result = CatalogOverlay.apply(loaded.entries(), loaded.sources(), edits.snapshot(), CatalogOverlay::registryTag);
        CatalogEntry oak = result.entries().get(Items.OAK_LOG);
        helper.assertValueEqual(oak.overrides().sellImpact(), Optional.of(0.005), "a tag rule's class fills the fields");
        helper.assertValueEqual(oak.markup(), 1.0, "rare_resource markup");
        helper.assertTrue(!result.entries().containsKey(Items.BIRCH_LOG), "a class alone doesn't list unpriced items");

        edits.setItemField(key(Items.OAK_LOG), "class", new com.google.gson.JsonPrimitive("common_processed"));
        oak = CatalogOverlay.apply(loaded.entries(), loaded.sources(), edits.snapshot(), CatalogOverlay::registryTag).entries().get(Items.OAK_LOG);
        helper.assertValueEqual(oak.markup(), 0.4, "a later class replaces the earlier one's values, not just its name");
        helper.assertValueEqual(oak.overrides().sellImpact(), Optional.of(0.0005), "common_processed impact");

        edits.setItemField(key(Items.OAK_LOG), "markup", number(0.9));
        oak = CatalogOverlay.apply(loaded.entries(), loaded.sources(), edits.snapshot(), CatalogOverlay::registryTag).entries().get(Items.OAK_LOG);
        helper.assertValueEqual(oak.markup(), 0.9, "an edit beats the class");

        edits.setItemField(key(Items.OAK_LOG), "class", new com.google.gson.JsonPrimitive("missing"));
        result = CatalogOverlay.apply(loaded.entries(), loaded.sources(), edits.snapshot(), CatalogOverlay::registryTag);
        helper.assertTrue(result.problems().get(key(Items.OAK_LOG)).contains("unknown class"), "an unknown class is refused");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void classCommandsShowAndEditClasses(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        ResourceLocation iron = key(Items.IRON_INGOT);
        f.market.setStock(iron, 37);
        double factorBefore = f.factor(Items.IRON_INGOT);
        try {
            List<String> classes = runCommand(helper, "market admin classes");
            for (String name : BUILT_IN_CLASSES) {
                helper.assertTrue(classes.stream().anyMatch(line -> line.startsWith("  " + name + " (")), "classes lists " + name + ": " + classes);
            }
            runCommand(helper, "market admin set minecraft:iron_ingot class rare_resource");
            CatalogEntry entry = MarketCatalog.INSTANCE.get(Items.IRON_INGOT).orElseThrow();
            helper.assertValueEqual(entry.itemClass(), Optional.of(ResourceLocation.fromNamespaceAndPath(DynamicEconomy.MOD_ID, "rare_resource")),
                    "set class with a short name");
            List<String> info = runCommand(helper, "market admin info minecraft:iron_ingot");
            helper.assertTrue(info.contains("  class = rare_resource [item edit]"), "info shows the class edit: " + info);
            helper.assertTrue(info.contains("  sell_impact = 0.005 [class rare_resource]"), "and fields from the class: " + info);
            helper.assertTrue(runCommand(helper, "market admin classes").stream().anyMatch(line -> line.startsWith("  rare_resource (1 listed)")),
                    "member count");

            List<String> refused = runCommand(helper, "market admin set minecraft:iron_ingot class no_such_class");
            helper.assertTrue(refused.stream().anyMatch(line -> line.contains("unknown class")), "unknown class refused: " + refused);
            helper.assertValueEqual(MarketCatalog.INSTANCE.get(Items.IRON_INGOT).orElseThrow().itemClass(), entry.itemClass(), "refusal changed nothing");

            runCommand(helper, "market admin reload");
            helper.assertValueEqual(MarketCatalog.INSTANCE.get(Items.IRON_INGOT).orElseThrow().itemClass(), entry.itemClass(), "edit survives reload");
            helper.assertValueEqual(f.market.getStock(iron), 37L, "classes never touch stock");
            helper.assertValueEqual(f.factor(Items.IRON_INGOT), factorBefore, "or the price factor");
        } finally {
            runCommand(helper, "market admin revert minecraft:iron_ingot");
        }
        helper.assertValueEqual(MarketCatalog.INSTANCE.get(Items.IRON_INGOT).orElseThrow().itemClass(), Optional.empty(), "reverted");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void exportLeavesClassValuesBlankSoImportKeepsThemLive(GameTestHelper helper) {
        try {
            MarketCatalog.Loaded loaded = loadOne("""
                    { "entries": [
                        { "item": "minecraft:iron_ingot", "base_price": 16, "class": "rare_resource", "sell_fee": 0.1 },
                        { "item": "minecraft:diamond", "base_price": 256 } ] }""");
            Path root = Files.createTempDirectory("market-class-export");
            CatalogExport.Result result = CatalogExport.export(root, Optional.of("minecraft"), loaded.entries().values(),
                    item -> loaded.sources().getOrDefault(item, FieldSource.NONE));
            String csv = Files.readString(result.folder().resolve("items.csv"));
            Csv.Row ironRow = Csv.parse(csv).rows().stream().filter(row -> row.get("item_id").equals("minecraft:iron_ingot"))
                    .findFirst().orElseThrow();
            java.util.function.Function<String, String> cell = ironRow::get;
            helper.assertValueEqual(cell.apply("class"), "rare_resource", "class column");
            helper.assertValueEqual(cell.apply("sell_fee"), "0.1", "a value the entry sets is written");
            helper.assertValueEqual(cell.apply("markup"), "", "a value from the class is left blank");
            helper.assertValueEqual(cell.apply("sell_impact"), "", "so is sell_impact");
            String diamondRow = csv.lines().filter(line -> line.startsWith("minecraft:diamond,")).findFirst().orElseThrow();
            helper.assertTrue(!diamondRow.contains("0.5"), "a built-in default is left blank too: " + diamondRow);

            CatalogImport.Report same = CatalogImport.analyze(csv);
            helper.assertValueEqual(same.rejected(), List.of(), "imports cleanly");
            helper.assertValueEqual(new java.util.HashSet<>(same.decoded()), new java.util.HashSet<>(loaded.entries().values()),
                    "export then import gives identical entries");

            CatalogImport.Report changed = CatalogImport.analyze(csv.replace(",rare_resource,", ",unique,"));
            CatalogEntry iron = changed.decoded().stream().filter(e -> e.item() == Items.IRON_INGOT).findFirst().orElseThrow();
            helper.assertValueEqual(iron.markup(), 2.0, "changing only the class in the sheet changes the class's fields");
            helper.assertValueEqual(iron.sellFee(), 0.1, "and keeps the entry's own");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        helper.succeed();
    }

    // ---- Price packs and suggestions (Phase 10) ---------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void filesForMissingModsAreSkippedQuietly(GameTestHelper helper) {
        String entries = "\"entries\": [ { \"item\": \"minecraft:iron_ingot\", \"base_price\": 16 } ]";
        MarketCatalog.Loaded present = loadOne("{ \"required_mods\": [\"minecraft\", \"create\"], " + entries + " }");
        helper.assertTrue(present.entries().containsKey(Items.IRON_INGOT), "loads when every mod is installed");
        MarketCatalog.Loaded missing = loadOne("{ \"required_mods\": [\"create\", \"not_a_real_mod\"], " + entries + " }");
        helper.assertTrue(missing.entries().isEmpty(), "skipped when one is missing");
        helper.assertValueEqual(missing.problems(), List.of(), "with no warning");
        MarketCatalog.Loaded bad = loadOne("{ \"required_mods\": \"create\", " + entries + " }");
        helper.assertTrue(bad.entries().isEmpty() && bad.problems().size() == 1 && bad.problems().get(0).skipped(),
                "a malformed list skips the file and says so: " + bad.problems());
        helper.succeed();
    }

    private static CatalogEntry seed(Item item, int price, String itemClass) {
        JsonObject json = new JsonObject();
        json.addProperty("item", key(item).toString());
        json.addProperty("base_price", price);
        if (itemClass != null) {
            json.addProperty("class", itemClass);
        }
        return MarketCatalog.decode(json).result().orElseThrow();
    }

    private static Map<Item, CatalogEntry> seeds(CatalogEntry... entries) {
        Map<Item, CatalogEntry> map = new java.util.LinkedHashMap<>();
        for (CatalogEntry entry : entries) {
            map.put(entry.item(), entry);
        }
        return map;
    }

    private static PriceSuggestion.Path path(Item output, int count, boolean fuel, Item... inputs) {
        List<PriceSuggestion.Slot> slots = new ArrayList<>();
        for (Item input : inputs) {
            slots.add(new PriceSuggestion.Slot(List.of(input), 1));
        }
        return new PriceSuggestion.Path(ResourceLocation.fromNamespaceAndPath("test", key(output).getPath()), "minecraft:crafting",
                output, count, slots, fuel);
    }

    @GameTest(template = EMPTY)
    public static void suggestionsFollowTheCheapestRecipe(GameTestHelper helper) {
        // Stand-in items: what they are doesn't matter, only the recipes given here.
        Item a = Items.IRON_INGOT, r = Items.DIAMOND, b = Items.IRON_BLOCK, c = Items.DIAMOND_BLOCK, d = Items.IRON_NUGGET,
                e = Items.STICK, x = Items.CAKE, f = Items.FURNACE;
        Map<Item, CatalogEntry> catalog = seeds(seed(a, 10, null), seed(r, 100, "rare_resource"), seed(Items.MILK_BUCKET, 20, null),
                seed(Items.BUCKET, 12, null), seed(Items.COAL, 8, null));
        PriceSuggestion.Result result = PriceSuggestion.suggest(List.of(
                path(b, 1, false, a, a),            // 2 × 10 × 1.1 = 22
                path(b, 1, false, a),               // cheaper: 11, and chosen
                path(c, 1, false, b, r),            // (11 + 100) × 1.1 = 122.1, rare input
                path(d, 4, false, a),               // 11 / 4 = 2.75
                path(e, 20, false, a),              // 0.55: under 1 spur
                path(x, 1, false, Items.MILK_BUCKET), // the bucket comes back: (20 − 12) × 1.1 = 8.8
                path(f, 1, true, a)                 // plus an eighth of a coal: (10 + 1) × 1.1 = 12.1
        ), catalog, new java.util.HashMap<>());
        Map<Item, PriceSuggestion.Suggestion> s = result.suggestions();
        helper.assertValueEqual(s.get(b).basePrice(), Optional.of(11), "cheapest recipe wins");
        helper.assertValueEqual(s.get(c).basePrice(), Optional.of(122), "rounded down");
        helper.assertValueEqual(s.get(c).itemClass(), "rare_processed", "a rare input makes it rare");
        helper.assertValueEqual(s.get(b).itemClass(), "common_processed", "otherwise common");
        helper.assertValueEqual(s.get(c).depth(), 2, "two steps from the seeds");
        helper.assertValueEqual(s.get(d).basePrice(), Optional.of(2), "divided by the output count");
        helper.assertValueEqual(s.get(e).basePrice(), Optional.empty(), "worth under 1 spur: left unpriced");
        helper.assertValueEqual(s.get(x).basePrice(), Optional.of(8), "crafting remainders are subtracted");
        helper.assertValueEqual(s.get(f).basePrice(), Optional.of(12), "furnace recipes add fuel");
        helper.assertTrue(!s.containsKey(a), "listed items keep their catalog price");
        helper.assertTrue(result.settled(), "settles");

        // A loop that makes something out of less than itself (1 plank to a door, a door to 2 planks) can't feed back:
        // g isn't repriced from h, which was priced from g, so both settle at their prices from the seed.
        Item g = Items.GOLD_INGOT, h = Items.GOLD_NUGGET;
        PriceSuggestion.Result loop = PriceSuggestion.suggest(List.of(path(g, 1, false, a), path(h, 2, false, g), path(g, 1, false, h)),
                catalog, new java.util.HashMap<>());
        helper.assertTrue(loop.settled(), "the loop settles: " + loop.looping());
        helper.assertValueEqual(loop.suggestions().get(g).basePrice(), Optional.of(11), "g keeps its price from the seed");
        helper.assertValueEqual(loop.suggestions().get(h).basePrice(), Optional.of(6), "h is priced from g: 11 × 1.1 / 2");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void suggestionsPreferCompleteRecipesAndWeighRareInputs(GameTestHelper helper) {
        Item a = Items.IRON_INGOT, r = Items.DIAMOND;
        Map<Item, CatalogEntry> catalog = seeds(seed(a, 10, null), seed(r, 100, "rare_resource"));
        ResourceLocation id = ResourceLocation.parse("test:altar");
        java.util.function.BiFunction<Item, Double, PriceSuggestion.Slot> slot = (item, amount) ->
                new PriceSuggestion.Slot(List.of(item), amount);
        PriceSuggestion.Result result = PriceSuggestion.suggest(List.of(
                // A recipe that may hide inputs can't undercut a complete one, but prices what nothing else makes.
                new PriceSuggestion.Path(id, "test:altar", Items.GOLD_INGOT, 1, List.of(slot.apply(a, 1.0)), false, false),
                new PriceSuggestion.Path(id, "minecraft:crafting", Items.GOLD_INGOT, 1, List.of(slot.apply(a, 10.0)), false, true),
                new PriceSuggestion.Path(id, "test:altar", Items.GOLD_BLOCK, 1, List.of(slot.apply(a, 1.0)), false, false),
                // One whole diamond among much cheaper material: rare.
                new PriceSuggestion.Path(id, "minecraft:crafting", Items.EMERALD, 1, List.of(slot.apply(r, 1.0), slot.apply(a, 100.0)), false, true),
                // Mostly diamond by value: rare.
                new PriceSuggestion.Path(id, "minecraft:crafting", Items.REDSTONE, 1, List.of(slot.apply(r, 0.5), slot.apply(a, 1.0)), false, true),
                // A trace of diamond: common, and so is what it's made into.
                new PriceSuggestion.Path(id, "minecraft:crafting", Items.LAPIS_LAZULI, 1, List.of(slot.apply(r, 0.01), slot.apply(a, 10.0)), false, true),
                new PriceSuggestion.Path(id, "minecraft:crafting", Items.LAPIS_BLOCK, 1, List.of(slot.apply(Items.LAPIS_LAZULI, 9.0)), false, true)
        ), catalog, new java.util.HashMap<>());
        Map<Item, PriceSuggestion.Suggestion> s = result.suggestions();
        helper.assertValueEqual(s.get(Items.GOLD_INGOT).basePrice(), Optional.of(110), "the complete recipe wins though it costs more");
        helper.assertValueEqual(s.get(Items.GOLD_BLOCK).basePrice(), Optional.of(11), "an incomplete recipe still prices what nothing else makes");
        helper.assertTrue(!s.get(Items.GOLD_BLOCK).completeType(), "and is marked for review");
        helper.assertValueEqual(s.get(Items.EMERALD).itemClass(), "rare_processed", "a whole rare item makes it rare");
        helper.assertValueEqual(s.get(Items.REDSTONE).itemClass(), "rare_processed", "half its cost or more is rare");
        helper.assertValueEqual(s.get(Items.LAPIS_LAZULI).itemClass(), "common_processed", "a trace of rare material doesn't count");
        helper.assertValueEqual(s.get(Items.LAPIS_BLOCK).itemClass(), "common_processed", "nor does it add up through a chain");
        helper.assertTrue(result.settled(), "settles");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void chainProfitsFollowSeveralRecipes(GameTestHelper helper) {
        // A cheap stone crushes into an unlisted crushed ore, which smelts into a listed ingot: two steps, so the
        // one-recipe check can't see it.
        Item stone = Items.COBBLESTONE, crushed = Items.RAW_IRON, ingot = Items.IRON_INGOT, gold = Items.GOLD_INGOT;
        Map<Item, CatalogEntry> market = seeds(seed(stone, 1, null), seed(ingot, 30, null), seed(gold, 100, null));
        List<PriceSuggestion.Path> paths = List.of(
                path(crushed, 1, false, stone),
                path(ingot, 1, true, crushed),
                path(ingot, 1, false, gold),         // gold sells for more than an ingot: no profit
                path(stone, 2, false, stone),        // a generator: left to the one-recipe check
                path(stone, 4, false, ingot));       // leads back to the stone: ignored, so values settle
        List<PriceSuggestion.ChainProfit> chains = PriceSuggestion.chainProfits(paths, market);
        helper.assertValueEqual(chains.stream().map(PriceSuggestion.ChainProfit::item).toList(), List.of(stone), "only the stone");
        PriceSuggestion.ChainProfit c = chains.get(0);
        helper.assertValueEqual(c.buyCost().compareTo(new BigDecimal("1.5")), 0, "bought at 1 × 1.5");
        helper.assertValueEqual(c.value().compareTo(new BigDecimal("30")), 0, "worth an ingot");
        helper.assertValueEqual(c.steps().stream().map(PriceSuggestion.Path::output).toList(), List.of(crushed, ingot), "through both steps");
        helper.assertValueEqual(PriceSuggestion.craftProfits(paths, market).stream().filter(p -> p.output() != stone).toList(), List.of(),
                "which the one-recipe check misses");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void suggestionsNeverAllowBuyCraftSellProfit(GameTestHelper helper) {
        // The premium and rounding down keep a suggested price at or below its inputs' cost × (1 + premium), and
        // the premium is below every built-in markup, so buying the inputs at base prices and selling the output loses.
        double smallestMarkup = ItemClasses.all().values().stream().filter(c -> c.sets("markup"))
                .mapToDouble(c -> c.values().get("markup").getAsDouble()).min().orElseThrow();
        helper.assertTrue(PriceSuggestion.PREMIUM < smallestMarkup, "premium below every class markup");
        helper.assertTrue(PriceSuggestion.PREMIUM < CatalogEntry.DEFAULT_MARKUP, "and the default markup");

        // An admin markup below the premium lowers the premium to match, so the suggestion still can't be crafted for profit.
        JsonObject cheap = new JsonObject();
        cheap.addProperty("item", key(Items.IRON_INGOT).toString());
        cheap.addProperty("base_price", 100);
        cheap.addProperty("markup", 0.05);
        Map<Item, CatalogEntry> catalog = seeds(MarketCatalog.decode(cheap).result().orElseThrow());
        helper.assertValueEqual(PriceSuggestion.premium(catalog.values()), 0.05, "premium follows the smallest markup");
        PriceSuggestion.Result result = PriceSuggestion.suggest(List.of(path(Items.IRON_BLOCK, 1, false, Items.IRON_INGOT)), catalog,
                new java.util.HashMap<>());
        helper.assertValueEqual(result.suggestions().get(Items.IRON_BLOCK).basePrice(), Optional.of(105), "100 × 1.05, not 1.1");
        Map<Item, CatalogEntry> withBlock = seeds(catalog.get(Items.IRON_INGOT), seed(Items.IRON_BLOCK, 105, null));
        helper.assertValueEqual(PriceSuggestion.craftProfits(List.of(path(Items.IRON_BLOCK, 1, false, Items.IRON_INGOT)), withBlock),
                List.of(), "buying the ingredient, crafting and selling doesn't profit");
        helper.assertValueEqual(PriceSuggestion.premium(seeds(seed(Items.IRON_INGOT, 10, "common_processed")).values()),PriceSuggestion.PREMIUM,
                "built-in classes keep the full premium");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void machineRecipeAmountsAreCounted(GameTestHelper helper) {
        // A packer and unpacker read with their real amounts settle; read as one in, one out they loop towards zero.
        Map<Item, CatalogEntry> catalog = seeds(seed(Items.IRON_INGOT, 90, null));
        ResourceLocation id = ResourceLocation.parse("test:machine");
        java.util.function.BiFunction<Item, Double, List<PriceSuggestion.Slot>> slot = (item, amount) ->
                List.of(new PriceSuggestion.Slot(List.of(item), amount));
        List<PriceSuggestion.Path> real = List.of(
                new PriceSuggestion.Path(id, "test:packer", Items.IRON_BLOCK, 1, slot.apply(Items.IRON_INGOT, 9.0), false, true),
                new PriceSuggestion.Path(id, "test:unpacker", Items.IRON_NUGGET, 9, slot.apply(Items.IRON_INGOT, 1.0), false, true),
                new PriceSuggestion.Path(id, "test:unpacker", Items.IRON_INGOT, 9, slot.apply(Items.IRON_BLOCK, 1.0), false, true));
        PriceSuggestion.Result result = PriceSuggestion.suggest(real, catalog, new java.util.HashMap<>());
        helper.assertTrue(result.settled(), "settles");
        helper.assertValueEqual(result.suggestions().get(Items.IRON_BLOCK).basePrice(), Optional.of(891), "9 × 90 × 1.1");
        // A catalyst used up only a quarter of the time costs a quarter of its price.
        PriceSuggestion.Result partly = PriceSuggestion.suggest(List.of(new PriceSuggestion.Path(id, "test:machine", Items.IRON_NUGGET, 1,
                slot.apply(Items.IRON_INGOT, 0.25), false, true)), catalog, new java.util.HashMap<>());
        helper.assertValueEqual(partly.suggestions().get(Items.IRON_NUGGET).basePrice(), Optional.of(24), "90 × 0.25 × 1.1 = 24.75");
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        helper.assertTrue(server.getRecipeManager().getRecipes().stream()
                        .allMatch(holder -> dynamiceconomy.compat.MachineRecipes.read(holder.value()).isEmpty()),
                "other mods' recipes are left to the generic reading");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void decorativeVariantModsAreLeftOut(GameTestHelper helper) {
        helper.assertTrue(PriceSuggestion.leftOut(ResourceLocation.parse("chipped:acacia_planks_1")), "Chipped");
        helper.assertTrue(PriceSuggestion.leftOut(ResourceLocation.parse("rechiseled:stone_bricks_1")), "Rechiseled");
        helper.assertTrue(!PriceSuggestion.leftOut(Items.STONE_BRICKS), "anything else is priced as usual");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void suggestUsesTheGamesRecipes(GameTestHelper helper) {
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        Map<Item, CatalogEntry> catalog = seeds(seed(Items.IRON_INGOT, 32, "common_processed"), seed(Items.DIAMOND, 512, "rare_resource"),
                seed(Items.NETHERITE_INGOT, 4000, "rare_processed"), seed(Items.OAK_LOG, 8, "common_resource"),
                seed(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 200, "rare_loot"), seed(Items.COAL, 8, null));
        PriceSuggestion.Result result = PriceSuggestion.suggest(server.getRecipeManager().getRecipes(), server.registryAccess(), catalog);
        Map<Item, PriceSuggestion.Suggestion> s = result.suggestions();
        helper.assertValueEqual(s.get(Items.IRON_BLOCK).basePrice(), Optional.of(316), "9 ingots × 32 × 1.1");
        helper.assertTrue(s.get(Items.IRON_NUGGET).reverse().contains(Items.IRON_INGOT), "nuggets turn back into ingots, and that's noted");

        Item sheet = BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:iron_sheet"));
        helper.assertValueEqual(s.get(sheet).basePrice(), Optional.of(35), "Create pressing: 32 × 1.1");
        helper.assertValueEqual(s.get(sheet).recipeType(), "create:pressing", "from the pressing recipe");
        helper.assertTrue(s.get(sheet).completeType(), "a recipe type that lists everything");

        PriceSuggestion.Suggestion sword = s.get(Items.NETHERITE_SWORD);
        helper.assertTrue(sword != null && sword.recipeType().equals("minecraft:smithing"), "smithing recipes are read: " + sword);
        BigDecimal diamondSword = s.get(Items.DIAMOND_SWORD).cost();
        helper.assertValueEqual(sword.cost().setScale(6, RoundingMode.HALF_UP),
                diamondSword.add(BigDecimal.valueOf(4200)).multiply(BigDecimal.valueOf(1.1)).setScale(6, RoundingMode.HALF_UP),
                "template + diamond sword + netherite ingot, plus the premium");
        helper.assertValueEqual(sword.itemClass(), "rare_processed", "rare inputs");
        helper.assertTrue(result.unusable().keySet().stream().anyMatch(reason -> reason.startsWith("special")), "special recipes skipped");
        helper.assertTrue(result.noRecipe().contains(Items.DIRT), "dirt isn't made by any recipe from these seeds");
        helper.assertTrue(result.settled(), "vanilla and Create recipes settle: " + result.looping());

        try {
            Path root = Files.createTempDirectory("market-suggest");
            CatalogExport.SuggestionResult written = CatalogExport.exportSuggestions(root, Optional.of("minecraft"), catalog.values(),
                    item -> FieldSource.NONE, result);
            String csv = Files.readString(written.folder().resolve("suggested.csv"));
            Csv.Row block = Csv.parse(csv).rows().stream().filter(row -> row.get("item_id").equals("minecraft:iron_block")).findFirst().orElseThrow();
            helper.assertValueEqual(block.get("base_price"), "316", "suggested price written");
            helper.assertValueEqual(block.get("class"), "common_processed", "and class");
            helper.assertValueEqual(block.get("price_source"), "suggested", "marked as a suggestion");
            CatalogImport.Report report = CatalogImport.analyze(csv);
            helper.assertValueEqual(report.rejected(), List.of(), "the suggestion file imports cleanly");
            helper.assertTrue(report.decoded().stream().anyMatch(entry -> entry.item() == Items.IRON_BLOCK && entry.basePrice() == 316),
                    "with the suggested prices");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        helper.succeed();
    }

    /** The built-in price files, read straight from the mod (the GameTest server leaves the pack itself off). */
    private static MarketCatalog.Loaded defaultPrices() {
        return MarketCatalog.load(defaultPriceFiles());
    }

    private static List<MarketCatalog.SourceFile> defaultPriceFiles() {
        try {
            Path folder = net.neoforged.fml.ModList.get().getModFileById(DynamicEconomy.MOD_ID).getFile()
                    .findResource("default_prices", "data", DynamicEconomy.MOD_ID, "market");
            List<MarketCatalog.SourceFile> files = new ArrayList<>();
            try (var paths = Files.list(folder)) {
                for (Path file : paths.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                    String name = file.getFileName().toString().replace(".json", "");
                    files.add(new MarketCatalog.SourceFile(ResourceLocation.fromNamespaceAndPath(DynamicEconomy.MOD_ID, name), 0,
                            "default_prices", JsonParser.parseString(Files.readString(file))));
                }
            }
            return files;
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Every built-in price file, including those for mods the test server doesn't have: their items aren't registered
     * here, so each entry is checked under the loader's rules with a stand-in item.
     */
    @GameTest(template = EMPTY)
    public static void everyDefaultPriceFileFollowsTheRules(GameTestHelper helper) {
        List<String> problems = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int entries = 0;
        for (MarketCatalog.SourceFile file : defaultPriceFiles()) {
            JsonObject obj = file.json().getAsJsonObject();
            java.util.Set<String> mods = new java.util.HashSet<>(List.of("minecraft"));
            if (obj.has("required_mods")) {
                obj.getAsJsonArray("required_mods").forEach(mod -> mods.add(mod.getAsString()));
            } else if (!file.id().getPath().equals("minecraft")) {
                problems.add(file.id() + ": no required_mods");
            }
            JsonObject defaults = obj.has("defaults") ? obj.getAsJsonObject("defaults") : new JsonObject();
            for (JsonElement element : obj.getAsJsonArray("entries")) {
                entries++;
                JsonObject entry = element.getAsJsonObject();
                ResourceLocation id = ResourceLocation.parse(entry.get("item").getAsString());
                if (!seen.add(id.toString())) {
                    problems.add(id + ": listed twice");
                }
                if (!mods.contains(id.getNamespace())) {
                    problems.add(id + ": its mod isn't in " + file.id() + "'s required_mods");
                }
                if (PriceSuggestion.leftOut(id)) {
                    problems.add(id + ": decorative variants are left out of the market");
                }
                JsonObject standIn = dynamiceconomy.catalog.CatalogJson.merge(defaults, entry);
                standIn.addProperty("item", key(Items.STONE).toString());
                var decoded = MarketCatalog.decode(standIn);
                if (decoded.error().isPresent()) {
                    problems.add(id + ": " + decoded.error().get().message());
                    continue;
                }
                CatalogEntry value = decoded.result().orElseThrow();
                if (!value.itemClass().map(c -> c.getNamespace().equals(DynamicEconomy.MOD_ID)).orElse(false)) {
                    problems.add(id + ": no built-in class");
                }
                PriceModel.arbitrageRisk(PriceModel.inputs(value)).ifPresent(risk -> problems.add(id + ": " + risk));
            }
        }
        helper.assertValueEqual(problems, List.of(), "every entry in every built-in file is valid");
        helper.assertTrue(entries > 1000, "all files were read: " + entries);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void defaultPricesLoadCleanlyAndCantBeCraftedForProfit(GameTestHelper helper) {
        MarketCatalog.Loaded loaded = defaultPrices();
        helper.assertValueEqual(loaded.problems().stream().map(MarketCatalog.Problem::message).toList(), List.of(),
                "loads with no warnings; files for mods that aren't installed are skipped quietly");
        helper.assertTrue(loaded.entries().size() > 200, "vanilla alone prices a few hundred items: " + loaded.entries().size());
        // The test server has vanilla and Create (and Numismatics, which has no file); every other file is skipped.
        int expected = defaultPriceFiles().stream().filter(f -> List.of("minecraft", "create").contains(f.id().getPath()))
                .mapToInt(f -> f.json().getAsJsonObject().getAsJsonArray("entries").size()).sum();
        helper.assertValueEqual(loaded.entries().size(), expected, "only the vanilla and Create files load");
        helper.assertTrue(loaded.entries().keySet().stream().allMatch(item -> List.of("minecraft", "create").contains(key(item).getNamespace())),
                "no item from another mod's file");
        for (CatalogEntry entry : loaded.entries().values()) {
            helper.assertTrue(entry.itemClass().map(c -> c.getNamespace().equals(DynamicEconomy.MOD_ID)).orElse(false),
                    entry.itemId() + " has a built-in class");
            helper.assertValueEqual(PriceModel.arbitrageRisk(PriceModel.inputs(entry)), Optional.empty(), entry.itemId() + " round trip");
        }
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        List<String> profits = PriceSuggestion.craftProfits(server.getRecipeManager().getRecipes(), server.registryAccess(),
                loaded.entries()).stream().map(p -> "%s via %s: costs %s, sells for %s".formatted(key(p.output()), p.recipe(),
                p.buyCost().setScale(2, RoundingMode.HALF_UP), p.sellValue().setScale(2, RoundingMode.HALF_UP))).toList();
        helper.assertValueEqual(profits, List.of(), "no listed item can be crafted from bought ingredients and sold at a profit");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void craftProfitsAreFound(GameTestHelper helper) {
        // Sticks at 4 spurs, planks at 1: two planks cost 3 to buy and make four sticks worth 16.
        Map<Item, CatalogEntry> catalog = seeds(seed(Items.OAK_PLANKS, 1, null), seed(Items.STICK, 4, null));
        List<PriceSuggestion.CraftProfit> profits = PriceSuggestion.craftProfits(
                List.of(path(Items.STICK, 4, false, Items.OAK_PLANKS, Items.OAK_PLANKS)), catalog);
        helper.assertValueEqual(profits.size(), 1, "found");
        helper.assertValueEqual(profits.get(0).sellValue().compareTo(BigDecimal.valueOf(16)), 0, "four sticks sell for 16");
        helper.assertValueEqual(profits.get(0).buyCost().compareTo(BigDecimal.valueOf(3)), 0, "two planks cost 3");
        helper.assertValueEqual(profits.get(0).kind(), PriceSuggestion.ProfitKind.PRICE_GAP, "a crafting recipe lists everything");
        // 1 cobblestone making 2 profits at any price; a recipe type the check doesn't fully read may be a false alarm.
        Map<Item, CatalogEntry> stone = seeds(seed(Items.COBBLESTONE, 1, null), seed(Items.STONE, 4, null));
        List<PriceSuggestion.Path> machines = List.of(path(Items.COBBLESTONE, 2, false, Items.COBBLESTONE),
                new PriceSuggestion.Path(ResourceLocation.parse("test:machine"), "othermod:machine", Items.STONE, 1,
                        List.of(new PriceSuggestion.Slot(List.of(Items.COBBLESTONE), 1)), false));
        helper.assertValueEqual(PriceSuggestion.craftProfits(machines, stone).stream().map(PriceSuggestion.CraftProfit::kind).toList(),
                List.of(PriceSuggestion.ProfitKind.MULTIPLIES_INPUT, PriceSuggestion.ProfitKind.MAY_HIDE_INPUTS), "kinds");
        Map<Item, CatalogEntry> fair = seeds(seed(Items.OAK_PLANKS, 2, null), seed(Items.STICK, 1, null));
        helper.assertValueEqual(PriceSuggestion.craftProfits(List.of(path(Items.STICK, 4, false, Items.OAK_PLANKS, Items.OAK_PLANKS)), fair),
                List.of(), "fair prices aren't reported");
        helper.succeed();
    }

    // ---- Economic integrity (Phase 7) -------------------------------------------------------------------------

    /** Selling priced unit by unit, as {@link PriceModel#sell} did before it stopped looping once s stops moving. */
    private static PriceModel.Order referenceSell(PriceModel.Inputs in, double factor, int qty) {
        BigDecimal perUnit = BigDecimal.valueOf(in.basePrice()).multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(in.sellFee())));
        BigDecimal sum = BigDecimal.ZERO;
        double s = factor;
        for (int i = 0; i < qty; i++) {
            sum = sum.add(perUnit.multiply(BigDecimal.valueOf(s)));
            s = PriceModel.afterSale(in, s);
        }
        return referenceOrder(sum.setScale(0, RoundingMode.FLOOR), s);
    }

    /** Buying a finite item priced unit by unit, as {@link PriceModel#buy} did before the same change. */
    private static PriceModel.Order referenceBuy(PriceModel.Inputs in, double factor, int qty) {
        BigDecimal perUnit = BigDecimal.valueOf(in.basePrice()).multiply(BigDecimal.ONE.add(BigDecimal.valueOf(in.markup())));
        BigDecimal sum = BigDecimal.ZERO;
        double s = factor;
        for (int i = 0; i < qty; i++) {
            sum = sum.add(perUnit.multiply(BigDecimal.valueOf(s)));
            s = PriceModel.afterPurchase(in, s);
        }
        return referenceOrder(sum.setScale(0, RoundingMode.CEILING), s);
    }

    private static PriceModel.Order referenceOrder(BigDecimal total, double factorAfter) {
        return total.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0
                ? new PriceModel.Order(Long.MAX_VALUE, factorAfter, true)
                : new PriceModel.Order(total.longValueExact(), factorAfter, false);
    }

    @GameTest(template = EMPTY)
    public static void pricingShortcutMatchesUnitByUnitPricing(GameTestHelper helper) {
        java.util.Random random = new java.util.Random(7);
        int[] bases = {1, 2, 3, 7, 16, 256, 100_000, Integer.MAX_VALUE};
        double[] markups = {0, 0.1, 0.5, 1000};
        double[] fees = {0, 0.1, 0.99};
        double[] impacts = {0, 0.0005, 0.01, 0.3, 0.5};
        double[] floors = {0.001, 0.2, 1.0};
        double[] ceilings = {1.0, 5.0, 1000};
        int[] quantities = {1, 2, 7, 64, 640, 5000};
        for (int i = 0; i < 400; i++) {
            double floor = floors[random.nextInt(floors.length)];
            double ceiling = ceilings[random.nextInt(ceilings.length)];
            double markup = markups[random.nextInt(markups.length)];
            PriceModel.Inputs in = new PriceModel.Inputs(bases[random.nextInt(bases.length)], markup,
                    fees[random.nextInt(fees.length)], markup, false, random.nextInt(4) != 0,
                    impacts[random.nextInt(impacts.length)], impacts[random.nextInt(impacts.length)], floor, ceiling, 0.05);
            double start = switch (random.nextInt(4)) {
                case 0 -> floor;
                case 1 -> ceiling;
                case 2 -> 1.0;
                default -> floor + (ceiling - floor) * random.nextDouble();
            };
            int qty = quantities[random.nextInt(quantities.length)];
            String what = "%s from %s × %d".formatted(in, start, qty);
            helper.assertValueEqual(PriceModel.sell(in, start, qty), referenceSell(in, start, qty), "sell " + what);
            helper.assertValueEqual(PriceModel.buy(in, start, qty), referenceBuy(in, start, qty), "buy " + what);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void smallPricesRoundOncePerOrder(GameTestHelper helper) {
        PriceModel.Inputs one = inputs(1, 0.5, 0.0, false);
        helper.assertValueEqual(PriceModel.sell(one, 1.0, 1).total(), 1L, "one unit at base 1 pays 1");
        helper.assertValueEqual(PriceModel.unitSellPrice(one, PriceModel.afterSale(one, 1.0)), 0L,
                "the next single unit rounds down to 0");
        helper.assertValueEqual(PriceModel.sell(one, 1.0, 64).total(), 63L, "but a stack pays 63: rounding is once per order");
        helper.assertValueEqual(PriceModel.buy(one, 1.0, 1).total(), 2L, "1.5 rounds up to 2");
        helper.assertValueEqual(PriceModel.buy(one, 1.0, 2).total(), 4L, "1.5 + 1.50075 rounds up to 4");
        helper.assertValueEqual(PriceModel.buy(inputs(1, 0.5, 0.0, true), 1.0, 2).total(), 3L,
                "unlimited items don't move, so 2 × 1.5 is exactly 3");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void extremePricesAndFeesStayExact(GameTestHelper helper) {
        PriceModel.Inputs iron = inputs(16, 0.5, 0.0, false);
        helper.assertValueEqual(PriceModel.sell(iron, iron.floor(), 10), new PriceModel.Order(32, iron.floor(), false),
                "at the floor every unit pays exactly floor × base");
        helper.assertValueEqual(PriceModel.buy(iron, iron.ceiling(), 10), new PriceModel.Order(1200, iron.ceiling(), false),
                "at the ceiling every unit costs exactly ceiling × base × 1.5");
        PriceModel.Inputs fixed = new PriceModel.Inputs(16, 0.0, 0.99, 0.0, false, false, 0.0005, 0.0005, 0.2, 5.0, 0.05);
        helper.assertValueEqual(PriceModel.sell(fixed, 1.0, 100).total(), 16L, "a 99% fee keeps 1% of 1,600");
        helper.assertValueEqual(PriceModel.buy(fixed, 1.0, 10).total(), 160L, "markup 0 charges exactly base");
        PriceModel.Inputs dear = new PriceModel.Inputs(16, 1000, 0.0, 1000, false, false, 0.0005, 0.0005, 0.2, 5.0, 0.05);
        helper.assertValueEqual(PriceModel.buy(dear, 1.0, 1).total(), 16_016L, "markup 1000 charges 1001 × base");
        PriceModel.Inputs huge = new PriceModel.Inputs(Integer.MAX_VALUE, 1000, 0.0, 1000, false, false, 0, 0, 0.2, 1000, 0.05);
        helper.assertTrue(PriceModel.buy(huge, 1000, 1_000_000).overflow(), "a total beyond a long is flagged, not wrapped");

        // The largest base price: one unit sold just fits the bank's int, two don't, and buying one doesn't either.
        CatalogEntry listed = MarketCatalog.INSTANCE.get(Items.IRON_INGOT).orElseThrow();
        withEntry(new CatalogEntry(listed.item(), listed.itemId(), Integer.MAX_VALUE, 0.5, 0, true, true, false, 0.0, false,
                Optional.empty(), CatalogEntry.Overrides.NONE, Optional.empty()), () -> {
            Fixture f = new Fixture(helper);
            f.setStock(Items.IRON_INGOT, 5);
            f.give(new ItemStack(Items.IRON_INGOT, 2));
            f.assertRejectedUnchanged(Result.TOO_LARGE, () -> MarketService.trySell(f.player, Items.IRON_INGOT, 2, ANY_PRICE));
            f.assertRejectedUnchanged(Result.TOO_LARGE, () -> MarketService.tryBuy(f.player, Items.IRON_INGOT, 1, ANY_PRICE));
            f.assertResult(MarketService.trySell(f.player, Items.IRON_INGOT, 1, ANY_PRICE), Result.OK);
            // Numismatics shows at most 2^30 − 1 and keeps the rest in a hidden overflow; nothing is lost.
            helper.assertValueEqual(f.balance(), 1_073_741_823, "the displayed balance is capped");
            helper.assertTrue(f.account().deduct(Integer.MAX_VALUE), "but the whole payout can be spent");
            helper.assertValueEqual(f.balance(), 0, "and then the account is empty");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void recoveryEdgeCases(GameTestHelper helper) {
        int interval = 1200;
        helper.assertValueEqual(PriceModel.recovered(0.5, 0, 100_000, 0.0, interval), 0.5, "recovery 0 never moves");
        helper.assertValueEqual(PriceModel.recovered(0.5, 0, interval, 1.0, interval), 1.0, "recovery 1 is back in one step");
        helper.assertValueEqual(PriceModel.recovered(3.0, 0, interval, 1.0, interval), 1.0, "from above too");
        assertClose(helper, PriceModel.recovered(0.5, 0, 3, 0.5, 1), 0.9375, "interval 1 steps every tick");
        helper.assertValueEqual(PriceModel.recovered(1.0, 0, 1_000_000, 0.05, interval), 1.0, "at base it stays at base");
        helper.assertValueEqual(PriceModel.recovered(0.5, 5000, 1000, 0.05, interval), 0.5,
                "a clock that went backwards recovers nothing");
        double below = 0.2;
        double above = 5.0;
        for (long steps = 1; steps <= 3000; steps *= 3) {
            double up = PriceModel.recovered(0.2, 0, steps * interval, 0.05, interval);
            double down = PriceModel.recovered(5.0, 0, steps * interval, 0.05, interval);
            helper.assertTrue(up >= below && up <= 1.0, "rising toward 1.0 without passing it: " + up);
            helper.assertTrue(down <= above && down >= 1.0, "falling toward 1.0 without passing it: " + down);
            below = up;
            above = down;
        }
        helper.assertValueEqual(PriceModel.recovered(0.2, 0, 1_000_000L * interval, 0.05, interval), 1.0, "eventually exactly 1.0");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void roundTripsNeverProfitEitherWay(GameTestHelper helper) {
        CatalogEntry iron = MarketCatalog.INSTANCE.get(Items.IRON_INGOT).orElseThrow();
        // A steep entry reaches the floor or ceiling within one order, so clamped trades are covered too.
        CatalogEntry steep = iron.withPricing(0.0, true, Optional.empty(), new CatalogEntry.Overrides(Optional.of(0.01),
                Optional.of(0.01), Optional.empty(), Optional.empty(), Optional.empty()));
        helper.assertTrue(PriceModel.arbitrageRisk(PriceModel.inputs(steep)).isEmpty(), "the steep entry is a safe config");
        for (CatalogEntry entry : List.of(iron, steep)) {
            withEntry(entry, () -> {
                for (double start : List.of(1.0, MarketConfig.floor(), MarketConfig.ceiling(), 0.7, 2.5)) {
                    for (int qty : List.of(1, 7, 64, 640)) {
                        for (boolean sellFirst : List.of(true, false)) {
                            Fixture f = new Fixture(helper);
                            f.setStock(Items.IRON_INGOT, 5_000);
                            f.deposit(5_000_000);
                            f.giveMany(Items.IRON_INGOT, sellFirst ? qty : 0);
                            f.market.setFactor(entry.itemId(), start, MarketStockData.now(helper.getLevel().getServer()));
                            int before = f.balance();
                            if (sellFirst) {
                                f.assertResult(MarketService.trySell(f.player, Items.IRON_INGOT, qty, ANY_PRICE), Result.OK);
                                f.assertResult(MarketService.tryBuy(f.player, Items.IRON_INGOT, qty, ANY_PRICE), Result.OK);
                            } else {
                                f.assertResult(MarketService.tryBuy(f.player, Items.IRON_INGOT, qty, ANY_PRICE), Result.OK);
                                f.assertResult(MarketService.trySell(f.player, Items.IRON_INGOT, qty, ANY_PRICE), Result.OK);
                            }
                            helper.assertTrue(f.balance() <= before, "%s %d (impact %s) from %s made %d spurs".formatted(
                                    sellFirst ? "selling then buying back" : "buying then reselling", qty,
                                    entry.overrides().sellImpact().orElse(MarketConfig.sellImpact()), start, f.balance() - before));
                        }
                    }
                }
            });
        }
        helper.succeed();
    }

    /** The most a round trip in either order makes (negative: it loses), over a few starting factors and sizes. */
    private static long bestRoundTripProfit(PriceModel.Inputs in) {
        long best = Long.MIN_VALUE;
        for (double start : List.of(in.floor(), 1.0, in.ceiling())) {
            for (int qty : List.of(1, 64, 640, 3000)) {
                PriceModel.Order bought = PriceModel.buy(in, start, qty);
                PriceModel.Order resold = PriceModel.sell(in, bought.factorAfter(), qty);
                PriceModel.Order sold = PriceModel.sell(in, start, qty);
                PriceModel.Order boughtBack = PriceModel.buy(in, sold.factorAfter(), qty);
                best = Math.max(best, Math.max(resold.total() - bought.total(), sold.total() - boughtBack.total()));
            }
        }
        return best;
    }

    @GameTest(template = EMPTY)
    public static void sellingThenBuyingBackIsCheckedForProfit(GameTestHelper helper) {
        // Default settings except buy_impact 0. Before Phase 7 the check only looked at buying then reselling and
        // called this safe, but crashing the price by selling and buying the same items back at the bottom pays.
        PriceModel.Inputs noBuyImpact = new PriceModel.Inputs(16, 0.5, 0.0, 0.5, false, true, MarketConfig.sellImpact(),
                0.0, MarketConfig.floor(), MarketConfig.ceiling(), MarketConfig.recovery());
        PriceModel.Order sold = PriceModel.sell(noBuyImpact, 1.0, 3000);
        PriceModel.Order boughtBack = PriceModel.buy(noBuyImpact, sold.factorAfter(), 3000);
        helper.assertTrue(sold.total() > boughtBack.total(), "the round trip pays: sold 3,000 for %d, bought them back for %d"
                .formatted(sold.total(), boughtBack.total()));
        helper.assertTrue(PriceModel.arbitrageRisk(noBuyImpact).isPresent(), "and it is now reported");
        helper.assertTrue(PriceModel.arbitrageRisk(inputs(16, 0.5, 0.0, false)).isEmpty(), "the default config is still safe");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void configurationsCalledSafeNeverProfit(GameTestHelper helper) {
        int safe = 0;
        int flagged = 0;
        for (double a : List.of(0.0, 0.0005, 0.01, 0.1)) {
            for (double b : List.of(0.0, 0.0005, 0.01, 0.1)) {
                for (double markup : List.of(0.0, 0.1, 0.5)) {
                    for (double fee : List.of(0.0, 0.1)) {
                        for (double[] range : List.of(new double[] {0.2, 5.0}, new double[] {0.9, 1.1}, new double[] {0.001, 1000})) {
                            PriceModel.Inputs in = new PriceModel.Inputs(10_000, markup, fee, markup, false, true, a, b,
                                    range[0], range[1], 0.05);
                            if (PriceModel.arbitrageRisk(in).isPresent()) {
                                flagged++;
                                continue;
                            }
                            safe++;
                            long profit = bestRoundTripProfit(in);
                            helper.assertTrue(profit <= 0, "%s is called safe but a round trip made %d".formatted(in, profit));
                        }
                    }
                }
            }
        }
        helper.assertTrue(safe > 20 && flagged > 20, "the sweep covers both outcomes: %d safe, %d flagged".formatted(safe, flagged));
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void playerStateMatchesTheServerChecks(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.giveMany(Items.IRON_INGOT, 70);
        f.give(new ItemStack(Items.DIAMOND, 3));
        ItemStack renamed = new ItemStack(Items.DIAMOND, 5);
        renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Not for sale"));
        f.give(renamed);
        f.give(new ItemStack(Items.STONE, 12));
        f.give(new ItemStack(Items.COBBLESTONE, 40));

        Map<ResourceLocation, Integer> expected = new java.util.HashMap<>();
        for (CatalogEntry entry : MarketCatalog.INSTANCE.entries()) {
            long count = MarketService.heldCount(f.player, entry);
            if (count > 0) {
                expected.put(entry.itemId(), (int) count);
            }
        }
        MarketPayloads.PlayerState state = MarketSync.playerState(f.player);
        helper.assertValueEqual(state.held(), expected, "one pass over the inventory counts the same");
        helper.assertValueEqual(expected.get(key(Items.DIAMOND)), 3, "the renamed diamonds don't count");
        helper.assertValueEqual(state.emptySlots(), 36 - 6, "36 slots, 6 used");
        // The room the screen works out must be exactly what a purchase checks, or Max would suggest failing orders.
        for (CatalogEntry entry : MarketCatalog.INSTANCE.entries()) {
            helper.assertValueEqual(state.room(entry.itemId(), new ItemStack(entry.item()).getMaxStackSize()),
                    MarketService.spaceFor(f.player.getInventory().items, entry), "room for " + entry.itemId());
        }
        helper.succeed();
    }

    // ---- Player usability (Phase 7) ---------------------------------------------------------------------------

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

            MarketStockData.Totals expected = new MarketStockData.Totals(before.paidOut() + 160, before.collected() + 120,
                    before.unitsSold() + 10, before.unitsBought() + 5);
            helper.assertValueEqual(f.market.totals(), expected, "160 paid out for 10, 120 collected for 5");
            helper.assertValueEqual(f.market.totals(key(Items.IRON_INGOT)), new MarketStockData.Totals(ironBefore.paidOut() + 160,
                    ironBefore.collected() + 120, ironBefore.unitsSold() + 10, ironBefore.unitsBought() + 5), "per item too");
            helper.assertValueEqual(expected.netCreated() - before.netCreated(), 40L, "net 40 spurs added to the economy");
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

    // ---- Catalog diagnostics and admin safety (Phase 7) ---------------------------------------------------------

    private static JsonElement number(double value) {
        return new com.google.gson.JsonPrimitive(value);
    }

    @GameTest(template = EMPTY)
    public static void fieldSourcesFollowEveryLayer(GameTestHelper helper) {
        ResourceLocation woods = ResourceLocation.fromNamespaceAndPath("test", "woods");
        JsonElement file = JsonParser.parseString("""
                { "defaults": { "markup": 0.25 },
                  "entries": [ { "item": "minecraft:oak_log", "base_price": 2, "unlimited_stock": true } ] }""");
        MarketCatalog.Loaded loaded = MarketCatalog.load(List.of(new MarketCatalog.SourceFile(woods, 0, "test", file)));
        Map<String, List<dynamiceconomy.catalog.FieldSource>> oakFile = loaded.sources().get(Items.OAK_LOG);
        helper.assertValueEqual(oakFile.get("base_price"), List.of(new dynamiceconomy.catalog.FieldSource("file test:woods", number(2))),
                "base_price set by the entry");
        helper.assertValueEqual(oakFile.get("markup").get(0).layer(), "file test:woods defaults", "markup inherited from defaults");
        helper.assertTrue(!oakFile.containsKey("sell_fee"), "sell_fee is set nowhere, so it's a built-in default");

        ResourceLocation logs = ResourceLocation.withDefaultNamespace("logs");
        MarketOverrides edits = new MarketOverrides();
        edits.setTagField(logs, "base_price", number(4));
        edits.setTagField(logs, "sell_fee", number(0.1));
        edits.setItemField(key(Items.OAK_LOG), "base_price", number(9));
        CatalogOverlay.Result result = CatalogOverlay.apply(loaded.entries(), loaded.sources(), edits.snapshot(), CatalogOverlay::registryTag);
        Map<String, List<dynamiceconomy.catalog.FieldSource>> oak = result.sources().get(Items.OAK_LOG);
        helper.assertValueEqual(oak.get("base_price").stream().map(dynamiceconomy.catalog.FieldSource::layer).toList(),
                List.of("file test:woods", "tag rule #minecraft:logs", "item edit"), "base_price layers, lowest first");
        helper.assertValueEqual(oak.get("sell_fee").get(0).layer(), "tag rule #minecraft:logs", "sell_fee from the tag rule");
        helper.assertValueEqual(oak.get("markup").get(0).layer(), "file test:woods defaults", "markup untouched by the edits");
        helper.assertValueEqual(result.sources().get(Items.BIRCH_LOG).get("base_price").get(0).layer(), "tag rule #minecraft:logs",
                "birch is listed by the tag rule alone");

        // A later rule removing the item sends the item edit back to the file entry: the first rule's fields drop out.
        edits.removeTag(ResourceLocation.withDefaultNamespace("logs_that_burn"));
        result = CatalogOverlay.apply(loaded.entries(), loaded.sources(), edits.snapshot(), CatalogOverlay::registryTag);
        oak = result.sources().get(Items.OAK_LOG);
        helper.assertValueEqual(oak.get("base_price").stream().map(dynamiceconomy.catalog.FieldSource::layer).toList(),
                List.of("file test:woods", "item edit"), "after a removal, layers restart from the file");
        helper.assertTrue(!oak.containsKey("sell_fee") && result.entries().get(Items.OAK_LOG).sellFee() == 0.0,
                "the removed rule's sell_fee is gone from both the entry and its sources");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void infoShowsWhereEachFieldComesFrom(GameTestHelper helper) {
        List<String> info = runCommand(helper, "market admin info minecraft:iron_ingot");
        for (String expected : List.of(
                "  base_price = 16 [file dynamic_economy:example_catalog]",
                "  markup = 0.5 [file dynamic_economy:example_catalog defaults]",
                "  can_sell = true [default]",
                "  min_buy_markup = 0.5 [same as markup]",
                "  sell_impact = " + java.math.BigDecimal.valueOf(MarketConfig.sellImpact()).stripTrailingZeros().toPlainString()
                        + " [server config]")) {
            helper.assertTrue(info.contains(expected), "info shows \"" + expected + "\": " + info);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void diagnoseFindsPlantedProblemsAndChangesNothing(GameTestHelper helper) {
        JsonElement file = JsonParser.parseString("""
                { "entries": [
                    { "item": "minecraft:iron_ingot",  "base_price": 16, "buy_impact": 0 },
                    { "item": "minecraft:diamond",     "base_price": 256, "can_sell": false },
                    { "item": "minecraft:cobblestone", "base_price": 1, "unlimited_stock": true },
                    { "item": "minecraft:stone",       "base_price": 1, "can_buy": false, "can_sell": false },
                    { "item": "minecraft:gold_ingot",  "base_price": 32 },
                    { "item": "minecraft:sand",        "base_price": 0 },
                    { "item": "minecraft:not_a_real_item", "base_price": 5 }
                ] }""");
        MarketCatalog.Loaded loaded = MarketCatalog.load(List.of(
                new MarketCatalog.SourceFile(ResourceLocation.fromNamespaceAndPath("test", "planted"), 0, "test", file)));
        MarketOverrides edits = new MarketOverrides();
        edits.setItemField(key(Items.IRON_INGOT), "base_price", number(16));
        edits.setItemField(key(Items.DIRT), "markup", number(1.0));
        edits.setTagField(ResourceLocation.fromNamespaceAndPath("test", "no_such_tag"), "markup", number(1.0));
        edits.setTagField(ResourceLocation.fromNamespaceAndPath("test", "huge"), "markup", number(1.0));
        List<Item> hundred = BuiltInRegistries.ITEM.stream().limit(100).toList();
        java.util.function.Function<ResourceLocation, Collection<Item>> tags = tag -> tag.getPath().equals("huge") ? hundred
                : CatalogOverlay.registryTag(tag);
        CatalogOverlay.Result result = CatalogOverlay.apply(loaded.entries(), loaded.sources(), edits.snapshot(), tags);

        MarketStockData market = new MarketStockData();
        long now = MarketStockData.now(helper.getLevel().getServer());
        market.setFactor(key(Items.GOLD_INGOT), MarketConfig.floor(), now);
        market.setStock(ResourceLocation.fromNamespaceAndPath("gone_mod", "widget"), 5);
        Map<ResourceLocation, Long> stockBefore = Map.copyOf(market.view());
        Map<ResourceLocation, MarketStockData.Factor> factorsBefore = Map.copyOf(market.factorsView());
        MarketOverrides.Snapshot editsBefore = edits.snapshot();

        List<MarketDiagnostics.Finding> findings = MarketDiagnostics.run(loaded, result.entries().values(),
                item -> result.sources().getOrDefault(item, dynamiceconomy.catalog.FieldSource.NONE), result.problems(),
                edits.snapshot(), market, now, tags);

        java.util.function.BiConsumer<MarketDiagnostics.Severity, String> expect = (severity, text) -> helper.assertTrue(
                findings.stream().anyMatch(f -> f.severity() == severity && f.message().contains(text)),
                "expected a " + severity + " containing \"" + text + "\" in " + findings);
        expect.accept(MarketDiagnostics.Severity.ERROR, "unknown item minecraft:not_a_real_item");
        expect.accept(MarketDiagnostics.Severity.ERROR, "minecraft:dirt has edits but no base_price");
        expect.accept(MarketDiagnostics.Severity.WARNING, "minecraft:iron_ingot: a round trip can make a profit");
        expect.accept(MarketDiagnostics.Severity.WARNING, "minecraft:diamond can never be bought");
        expect.accept(MarketDiagnostics.Severity.WARNING, "minecraft:gold_ingot is at its price floor");
        expect.accept(MarketDiagnostics.Severity.WARNING, "Tag rule #test:no_such_tag matches no items");
        expect.accept(MarketDiagnostics.Severity.WARNING, "The admin edit of base_price on minecraft:iron_ingot has no effect");
        expect.accept(MarketDiagnostics.Severity.NOTE, "minecraft:cobblestone has unlimited stock and can be sold");
        expect.accept(MarketDiagnostics.Severity.NOTE, "minecraft:stone is listed but can be neither bought nor sold");
        expect.accept(MarketDiagnostics.Severity.NOTE, "Tag rule #test:huge covers 100 items");
        expect.accept(MarketDiagnostics.Severity.NOTE, "1 catalog entries have base_price 0");
        expect.accept(MarketDiagnostics.Severity.NOTE, "1 item(s) with saved market data no longer exist");
        helper.assertValueEqual(findings.get(0).severity(), MarketDiagnostics.Severity.ERROR, "errors come first");

        helper.assertValueEqual(Map.copyOf(market.view()), stockBefore, "diagnosis leaves stock alone");
        helper.assertValueEqual(Map.copyOf(market.factorsView()), factorsBefore, "and prices");
        helper.assertValueEqual(edits.snapshot(), editsBefore, "and edits");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void diagnoseCommandRunsOnTheLiveMarket(GameTestHelper helper) {
        List<String> output = runCommand(helper, "market admin diagnose");
        helper.assertTrue(output.get(0).startsWith("Market check:"), "summary line first: " + output);
        helper.assertTrue(output.stream().anyMatch(line -> line.contains("minecraft:nether_star can never be bought")),
                "the example catalog's nether star can't be sold, so it can never be stocked: " + output);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void tagRulePreviewsChangeNothing(GameTestHelper helper) {
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        MarketOverrides.Snapshot before = MarketOverrides.get(server).snapshot();
        int logs = CatalogOverlay.registryTag(ResourceLocation.withDefaultNamespace("logs")).size();

        List<String> setPrice = runCommand(helper, "market admin tag minecraft:logs setprice 4 preview");
        helper.assertTrue(setPrice.get(1).contains("Of the tag's %d items: %d newly listed, 0 taken off, 1 changed".formatted(logs, logs - 1)),
                "oak is already listed and changes; every other log would be listed: " + setPrice);
        helper.assertTrue(setPrice.stream().anyMatch(line -> line.contains("minecraft:oak_log: base_price 2 → 4")), "oak's change shown: " + setPrice);
        List<String> remove = runCommand(helper, "market admin tag minecraft:logs remove preview");
        helper.assertTrue(remove.get(1).contains("1 taken off"), "removing the tag would delist oak: " + remove);
        List<String> markup = runCommand(helper, "market admin tag minecraft:logs set markup 1.0 preview");
        helper.assertTrue(markup.get(1).contains("0 newly listed, 0 taken off, 1 changed"), "a rule without a price lists nothing: " + markup);

        helper.assertValueEqual(MarketOverrides.get(server).snapshot(), before, "no edit was saved");
        helper.assertTrue(MarketCatalog.INSTANCE.get(Items.BIRCH_LOG).isEmpty(), "the live catalog is unchanged");
        helper.assertValueEqual(MarketCatalog.INSTANCE.get(Items.OAK_LOG).map(CatalogEntry::basePrice), Optional.of(2), "oak still at 2");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void priceResetsCanBePreviewedAndScopedToATag(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        long now = MarketStockData.now(helper.getLevel().getServer());
        f.market.setFactor(key(Items.IRON_INGOT), 0.5, now);
        f.market.setFactor(key(Items.OAK_LOG), 0.6, now);
        Map<ResourceLocation, MarketStockData.Factor> before = Map.copyOf(f.market.factorsView());

        List<String> all = runCommand(helper, "market admin resetprice all preview");
        helper.assertTrue(all.get(0).contains("2 item(s) would go back to base price"), "preview counts both: " + all);
        helper.assertTrue(all.get(1).contains("minecraft:iron_ingot: now 50% of base"), "furthest first: " + all);
        helper.assertValueEqual(Map.copyOf(f.market.factorsView()), before, "the preview reset nothing");

        runCommand(helper, "market admin resetprice tag minecraft:logs");
        helper.assertValueEqual(f.factor(Items.OAK_LOG), 1.0, "oak, a log, is reset");
        helper.assertValueEqual(f.factor(Items.IRON_INGOT), 0.5, "iron, not a log, is untouched");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void pruneListsFirstThenRemovesOnlyMissingItems(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        ResourceLocation gone = ResourceLocation.fromNamespaceAndPath("gone_mod", "widget");
        f.market.setStock(gone, 5);
        f.market.setFactor(gone, 0.5, MarketStockData.now(server));
        MarketOverrides.get(server).setItemField(gone, "base_price", number(3));
        f.setStock(Items.IRON_INGOT, 7);

        List<String> listing = runCommand(helper, "market admin prune");
        helper.assertTrue(listing.contains("  gone_mod:widget"), "the missing item is listed: " + listing);
        helper.assertValueEqual(f.market.getStock(gone), 5L, "listing deletes nothing");

        runCommand(helper, "market admin prune confirm");
        helper.assertTrue(!f.market.view().containsKey(gone) && !f.market.factorsView().containsKey(gone), "its stock and price are gone");
        helper.assertTrue(MarketOverrides.get(server).itemPatch(gone) == null, "and its admin edit");
        helper.assertValueEqual(f.stock(Items.IRON_INGOT), 7L, "items that exist are untouched");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void exampleCatalogIsAnOptionalPack(GameTestHelper helper) {
        net.minecraft.server.packs.repository.Pack pack = helper.getLevel().getServer().getPackRepository()
                .getPack(DynamicEconomy.EXAMPLE_PACK_ID);
        helper.assertTrue(pack != null, "the example catalog is registered as a data pack");
        helper.assertTrue(!pack.getPackSource().shouldAddAutomatically(),
                "and Minecraft won't switch it on by itself; an admin has to enable it");
        helper.assertTrue(MarketCatalog.INSTANCE.sources(Items.IRON_INGOT).get("base_price").get(0).layer()
                .equals("file dynamic_economy:example_catalog"), "the test world's items come from it");
        helper.succeed();
    }

    // ---- Audit history and recovery (Phase 7) -----------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void auditLogRotatesAndReadsBackNewestFirst(GameTestHelper helper) {
        try {
            Path file = Files.createTempDirectory("market-audit").resolve("audit.log");
            for (int i = 0; i < 80; i++) {
                AuditLog.append(file, "{\"n\":" + i + "}", 100, AuditLog.KEEP);
            }
            helper.assertTrue(Files.size(file) <= 100, "the current file stays under the cap");
            for (int i = 1; i < AuditLog.KEEP; i++) {
                helper.assertTrue(Files.exists(file.resolveSibling("audit.log." + i)), "rotated file " + i + " kept");
            }
            helper.assertTrue(!Files.exists(file.resolveSibling("audit.log." + AuditLog.KEEP)), "nothing beyond the limit is kept");
            List<Integer> newest = AuditLog.recent(file, 12).stream().map(entry -> entry.get("n").getAsInt()).toList();
            helper.assertValueEqual(newest, java.util.stream.IntStream.iterate(79, n -> n - 1).limit(12).boxed().toList(),
                    "newest first, reading back into rotated files");
        } catch (java.io.IOException e) {
            throw new GameTestAssertException("audit log test failed: " + e);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void everyAdminChangeIsLoggedOnce(GameTestHelper helper) {
        Path file = AuditLog.file(helper.getLevel().getServer());
        int before = AuditLog.recent(file, 100_000).size();
        List<String> commands = List.of(
                "market admin setstock minecraft:iron_ingot 5",
                "market admin set minecraft:iron_ingot markup 0.75",
                "market admin set minecraft:iron_ingot min_buy_markup 0.9",
                "market admin tag minecraft:logs setprice 4 preview",
                "market admin revert minecraft:iron_ingot",
                "market admin clearstock minecraft:iron_ingot",
                "market admin resetprice all",
                "market admin reload");
        commands.forEach(command -> runCommand(helper, command));

        List<com.google.gson.JsonObject> entries = AuditLog.recent(file, 100_000);
        helper.assertValueEqual(entries.size() - before, 7, "one entry per change; the preview changes nothing and isn't logged");
        List<String> actions = entries.subList(0, 7).stream().map(entry -> entry.get("action").getAsString()).toList();
        helper.assertValueEqual(actions, List.of("reload", "resetprice", "clearstock", "revert", "set min_buy_markup",
                "set markup", "setstock"), "logged in order, newest first");
        helper.assertTrue(entries.get(3 + 0).get("result").getAsString().equals("ok")
                        && entries.get(4).get("result").getAsString().startsWith("refused"),
                "the rule-breaking edit is logged as refused");
        com.google.gson.JsonObject setStock = entries.get(6);
        helper.assertTrue(setStock.get("actor").getAsString().equals("Server") && setStock.get("target").getAsString().equals("minecraft:iron_ingot")
                && setStock.get("after").getAsLong() == 5, "who, what and the new value: " + setStock);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void tradeHistoryKeepsEachPlayersNewestFifty(GameTestHelper helper) {
        TradeHistory data = new TradeHistory();
        UUID player = UUID.randomUUID();
        for (int i = 0; i < 60; i++) {
            data.record(player, new TradeHistory.Trade(i, key(Items.IRON_INGOT), i % 2 == 0, i + 1, i * 10L));
        }
        List<TradeHistory.Trade> recent = data.recent(player);
        helper.assertValueEqual(recent.size(), TradeHistory.CAP, "capped at 50");
        helper.assertValueEqual(recent.get(0).quantity(), 60, "newest first");
        TradeHistory reloaded = TradeHistory.fromTag(data.save(new CompoundTag(), helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        helper.assertValueEqual(reloaded.recent(player), recent, "saved with the world");
        helper.assertTrue(data.recent(UUID.randomUUID()).isEmpty(), "other players have their own (empty) list");

        // Real trades: a completed one is recorded, a rejected one isn't.
        withFixedPrice(Items.IRON_INGOT, () -> {
            Fixture f = new Fixture(helper);
            TradeHistory world = TradeHistory.get(helper.getLevel().getServer());
            f.giveMany(Items.IRON_INGOT, 10);
            f.assertResult(MarketService.trySell(f.player, Items.IRON_INGOT, 10, ANY_PRICE), Result.OK);
            f.assertResult(MarketService.trySell(f.player, Items.IRON_INGOT, 10, ANY_PRICE), Result.NOT_ENOUGH_ITEMS);
            List<TradeHistory.Trade> mine = world.recent(f.player.getUUID());
            helper.assertValueEqual(mine.size(), 1, "only the completed trade");
            helper.assertTrue(!mine.get(0).buy() && mine.get(0).quantity() == 10 && mine.get(0).total() == 160, "what was sold: " + mine);
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void backupChangeRestoreRoundTrips(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        f.setStock(Items.IRON_INGOT, 11);
        f.market.setFactor(key(Items.IRON_INGOT), 0.5, MarketStockData.now(server));
        List<String> made = runCommand(helper, "market admin backup");
        String name = made.get(0).replaceAll(".*as \"([^\"]+)\".*", "$1");

        f.setStock(Items.IRON_INGOT, 99);
        f.market.resetFactor(key(Items.IRON_INGOT));
        runCommand(helper, "market admin set minecraft:iron_ingot markup 0.75");
        try {
            checkRestore(helper, f, name);
        } finally {
            // Other tests price iron at its file markup, so never leave the edit behind, even if this test fails.
            if (MarketOverrides.get(server).itemPatch(key(Items.IRON_INGOT)) != null) {
                runCommand(helper, "market admin revert minecraft:iron_ingot");
            }
        }
        helper.succeed();
    }

    private static void checkRestore(GameTestHelper helper, Fixture f, String name) {
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        List<String> preview = runCommand(helper, "market admin restore " + name + " all");
        helper.assertTrue(preview.stream().anyMatch(line -> line.contains("1 stock level(s), 1 price(s), 1 item edit(s)")),
                "the preview counts what would change: " + preview);
        helper.assertTrue(preview.stream().anyMatch(line -> line.contains("admin action(s) have been logged since then")),
                "and warns about changes made since the backup: " + preview);
        helper.assertValueEqual(f.stock(Items.IRON_INGOT), 99L, "without confirm nothing changes");

        List<String> restored = runCommand(helper, "market admin restore " + name + " all confirm");
        helper.assertValueEqual(f.stock(Items.IRON_INGOT), 11L, "stock restored");
        helper.assertValueEqual(f.factor(Items.IRON_INGOT), 0.5, "price restored as it was, not recovered since");
        helper.assertValueEqual(MarketCatalog.INSTANCE.get(Items.IRON_INGOT).orElseThrow().markup(), 0.5, "the edit is undone");
        try {
            helper.assertTrue(MarketBackup.list(server).stream().anyMatch(n -> n.contains("_auto-restore")),
                    "the state before the restore was saved first: " + restored);
        } catch (java.io.IOException e) {
            throw new GameTestAssertException("couldn't list backups: " + e);
        }
    }

    @GameTest(template = EMPTY)
    public static void damagedBackupsAreRefusedWithoutChangingAnything(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        f.setStock(Items.IRON_INGOT, 21);
        try {
            Files.createDirectories(MarketBackup.folder(server));
            Files.writeString(MarketBackup.folder(server).resolve("broken.json"), "{ not json");
            Files.writeString(MarketBackup.folder(server).resolve("negative.json"),
                    "{ \"format\": 1, \"stock\": { \"minecraft:iron_ingot\": -5, \"minecraft:diamond\": 3 } }");
        } catch (java.io.IOException e) {
            throw new GameTestAssertException("couldn't write test backups: " + e);
        }
        for (String name : List.of("broken", "negative")) {
            List<String> output = runCommand(helper, "market admin restore " + name + " stock confirm");
            helper.assertTrue(output.get(0).contains("can't be restored"), name + " is refused: " + output);
        }
        helper.assertTrue(runCommand(helper, "market admin restore negative stock confirm").stream()
                .anyMatch(line -> line.contains("must be a whole number of at least 0")), "and the reason is given");
        helper.assertValueEqual(f.stock(Items.IRON_INGOT), 21L, "nothing changed, not even the valid diamond line");
        helper.assertTrue(f.stock(Items.DIAMOND) != 3L, "the diamond stock in the damaged backup wasn't applied");
        boolean refused = false;
        try {
            MarketBackup.file(server, "../../level");
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        helper.assertTrue(refused, "a backup name can't be a path");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void exampleCatalogTurnsOffAndOnWithTheDatapackCommand(GameTestHelper helper) {
        List<String> off = runCommand(helper, "datapack disable \"" + DynamicEconomy.EXAMPLE_PACK_ID + "\"");
        boolean goneAfterDisable = MarketCatalog.INSTANCE.get(Items.IRON_INGOT).isEmpty();
        List<String> on = runCommand(helper, "datapack enable \"" + DynamicEconomy.EXAMPLE_PACK_ID + "\"");
        boolean backAfterEnable = MarketCatalog.INSTANCE.get(Items.IRON_INGOT).isPresent();
        helper.assertTrue(goneAfterDisable, "disabling the pack takes the example items off: " + off);
        helper.assertTrue(backAfterEnable, "enabling it lists them again: " + on);
        helper.succeed();
    }

    /** Runs a command at permission 4, the way an operator or the console would, and returns what it printed. */
    private static List<String> runCommand(GameTestHelper helper, String command) {
        List<String> output = new ArrayList<>();
        net.minecraft.commands.CommandSource capture = new net.minecraft.commands.CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                output.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        try {
            server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack().withSource(capture).withPermission(4));
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new GameTestAssertException("/" + command + " was rejected: " + e.getMessage());
        }
        return output;
    }

    @GameTest(template = EMPTY)
    public static void statsCommandReportsTheMoneyFlow(GameTestHelper helper) {
        withFixedPrice(Items.IRON_INGOT, () -> {
            Fixture f = new Fixture(helper);
            f.giveMany(Items.IRON_INGOT, 10);
            f.assertResult(MarketService.trySell(f.player, Items.IRON_INGOT, 10, ANY_PRICE), Result.OK);

            List<String> overall = runCommand(helper, "market admin stats");
            helper.assertTrue(overall.size() >= 4 && overall.get(1).contains("%,d spurs".formatted(f.market.totals().paidOut())),
                    "overall stats show what sellers were paid: " + overall);
            List<String> iron = runCommand(helper, "market admin stats minecraft:iron_ingot");
            MarketStockData.Totals ironTotals = f.market.totals(key(Items.IRON_INGOT));
            helper.assertTrue(iron.get(0).startsWith("minecraft:iron_ingot")
                            && iron.get(1).contains("%,d spurs for %,d items".formatted(ironTotals.paidOut(), ironTotals.unitsSold())),
                    "item stats: " + iron);
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void tagRulesSurviveReloadsWithoutProblems(GameTestHelper helper) {
        runCommand(helper, "market admin tag minecraft:logs setprice 4");
        try {
            helper.assertValueEqual(MarketCatalog.INSTANCE.get(Items.BIRCH_LOG).map(CatalogEntry::basePrice), Optional.of(4),
                    "the rule lists birch logs");
            for (String reload : List.of("market admin reload", "reload")) {
                runCommand(helper, reload);
                helper.assertValueEqual(MarketCatalog.INSTANCE.get(Items.BIRCH_LOG).map(CatalogEntry::basePrice), Optional.of(4),
                        "birch logs still listed after /" + reload);
                helper.assertTrue(MarketCatalog.INSTANCE.problems().isEmpty(), "no problems after /" + reload + ": "
                        + MarketCatalog.INSTANCE.problems());
            }
        } finally {
            runCommand(helper, "market admin tag minecraft:logs revert");
        }
        helper.assertTrue(MarketCatalog.INSTANCE.get(Items.BIRCH_LOG).isEmpty(), "reverting the rule unlists birch logs again");
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

        /** A rejected trade must leave balance, inventory, stock, price factors, history and money counts as they were. */
        void assertRejectedUnchanged(Result expected, java.util.function.Supplier<TradeResult> trade) {
            List<ItemStack> inventoryBefore = new ArrayList<>();
            player.getInventory().items.forEach(s -> inventoryBefore.add(s.copy()));
            Map<ResourceLocation, Long> stockBefore = Map.copyOf(market.view());
            Map<ResourceLocation, MarketStockData.Factor> factorsBefore = Map.copyOf(market.factorsView());
            Map<ResourceLocation, MarketStockData.History> historyBefore = Map.copyOf(market.historyView());
            Map<ResourceLocation, MarketStockData.Totals> itemTotalsBefore = Map.copyOf(market.itemTotalsView());
            MarketStockData.Totals totalsBefore = market.totals();
            int balanceBefore = balance();

            assertResult(trade.get(), expected);

            helper.assertValueEqual(balance(), balanceBefore, "balance after " + expected);
            helper.assertValueEqual(Map.copyOf(market.view()), stockBefore, "stock after " + expected);
            helper.assertValueEqual(Map.copyOf(market.factorsView()), factorsBefore, "price factors after " + expected);
            helper.assertValueEqual(Map.copyOf(market.historyView()), historyBefore, "price history after " + expected);
            helper.assertValueEqual(Map.copyOf(market.itemTotalsView()), itemTotalsBefore, "item money counts after " + expected);
            helper.assertValueEqual(market.totals(), totalsBefore, "money counts after " + expected);
            NonNullList<ItemStack> after = player.getInventory().items;
            for (int slot = 0; slot < after.size(); slot++) {
                helper.assertTrue(ItemStack.matches(after.get(slot), inventoryBefore.get(slot)),
                        "inventory slot " + slot + " changed after " + expected);
            }
        }
    }
}
