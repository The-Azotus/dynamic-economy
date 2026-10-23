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
        Fixture f = new Fixture(helper);
        f.setStock(Items.IRON_INGOT, 0);
        f.give(new ItemStack(Items.IRON_INGOT, 10));

        TradeResult result = MarketService.trySell(f.player, Items.IRON_INGOT, 10, ANY_PRICE);

        f.assertResult(result, Result.OK);
        helper.assertValueEqual(f.stock(Items.IRON_INGOT), 10L, "iron stock");
        helper.assertValueEqual(f.balance(), 160, "balance");
        helper.assertValueEqual(f.count(Items.IRON_INGOT), 0L, "iron left in inventory");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void buyingFiveIronCosts120(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.setStock(Items.IRON_INGOT, 20);
        f.deposit(1000);

        TradeResult result = MarketService.tryBuy(f.player, Items.IRON_INGOT, 5, ANY_PRICE);

        f.assertResult(result, Result.OK);
        helper.assertValueEqual(f.stock(Items.IRON_INGOT), 15L, "iron stock");
        helper.assertValueEqual(f.balance(), 1000 - 120, "balance");
        helper.assertValueEqual(f.count(Items.IRON_INGOT), 5L, "iron received");
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
            helper.assertValueEqual(result.total(), 128L, "cost of 64 cobblestone");
        }

        helper.assertValueEqual(f.stock(Items.COBBLESTONE), 0L, "stored stock is never touched");
        helper.assertValueEqual(f.count(Items.COBBLESTONE), 192L, "cobblestone received");
        helper.assertValueEqual(f.balance(), 1000 - 3 * 128, "balance");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void sellingUnlimitedItemPaysAndKeepsStoredStock(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.setStock(Items.COBBLESTONE, 7);
        f.give(new ItemStack(Items.COBBLESTONE, 10));

        f.assertResult(MarketService.trySell(f.player, Items.COBBLESTONE, 10, ANY_PRICE), Result.OK);

        helper.assertValueEqual(f.balance(), 10, "paid 1 spur each");
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

        f.assertRejectedUnchanged(Result.NO_FUNDS, () -> MarketService.tryBuy(f.player, Items.COBBLESTONE, 2, ANY_PRICE));
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
        CatalogEntry unlimited = new CatalogEntry(finite.item(), finite.itemId(), finite.basePrice(), finite.markup(),
                finite.startStock(), finite.canSell(), finite.canBuy(), true);

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
        MarketStockData market = MarketStockData.get(helper.getLevel().getServer());
        CatalogEntry tenTimesOnePointOne = new CatalogEntry(Items.STONE, ResourceLocation.withDefaultNamespace("stone"),
                10, 0.1, 0, true, true, false);
        helper.assertValueEqual(PriceModel.unitPrices(tenTimesOnePointOne, market), new PriceModel.UnitPrices(10, 11), "10 at 10% markup");
        CatalogEntry oddBase = new CatalogEntry(Items.STONE, ResourceLocation.withDefaultNamespace("stone"),
                3, 0.5, 0, true, true, false);
        helper.assertValueEqual(PriceModel.unitPrices(oddBase, market), new PriceModel.UnitPrices(3, 5), "ceil(4.5)");
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
