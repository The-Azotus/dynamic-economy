package dynamiceconomy.gametest;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.MarketConfig;
import dynamiceconomy.MarketAccess;
import dynamiceconomy.bank.Bank;
import dynamiceconomy.bank.BankKind;
import dynamiceconomy.bank.BankSwitch;
import dynamiceconomy.bank.BankTeam;
import dynamiceconomy.bank.BankTeams;
import dynamiceconomy.bank.OwnTeams;
import dynamiceconomy.bank.CoinMath;
import dynamiceconomy.bank.Denomination;
import dynamiceconomy.bank.OwnBank;
import dynamiceconomy.bank.WorldBank;
import dynamiceconomy.block.MarketBlocks;
import dynamiceconomy.block.MarketTerminalBlock;
import dynamiceconomy.catalog.CatalogOverlay;
import dynamiceconomy.coin.Coin;
import dynamiceconomy.coin.Coins;
import dynamiceconomy.data.AuditLog;
import dynamiceconomy.data.ContractBoard;
import dynamiceconomy.data.ExchangeData;
import dynamiceconomy.data.MarketBackup;
import dynamiceconomy.data.MarketOverrides;
import dynamiceconomy.data.TradeHistory;
import dynamiceconomy.menu.MarketMenu;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.catalog.FieldSource;
import dynamiceconomy.catalog.ItemClasses;
import dynamiceconomy.compat.CreateRecipes;
import dynamiceconomy.compat.FtbTeamsBridge;
import dynamiceconomy.compat.NumismaticsBridge;
import dynamiceconomy.compat.StagesBridge;
import dynamiceconomy.data.MarketStockData;
import dynamiceconomy.io.CatalogExport;
import dynamiceconomy.io.CatalogImport;
import dynamiceconomy.io.Csv;
import dynamiceconomy.network.MarketPayloads;
import dynamiceconomy.network.MarketSync;
import dynamiceconomy.service.ContractPricing;
import dynamiceconomy.service.ContractService;
import dynamiceconomy.service.ExchangeService;
import dynamiceconomy.service.MarketDiagnostics;
import dynamiceconomy.service.MarketListing;
import dynamiceconomy.service.BankService;
import dynamiceconomy.service.MarketService;
import dynamiceconomy.service.OrderMath;
import dynamiceconomy.service.Leaderboard;
import dynamiceconomy.service.MarketVisibility;
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
        // Spurs with Numismatics, our Bits without: exactly one of the two recipes loads.
        boolean numismatics = net.neoforged.fml.ModList.get().isLoaded(NumismaticsBridge.MOD_ID);
        var recipes = helper.getLevel().getServer().getRecipeManager();
        helper.assertValueEqual(recipes.byKey(id).isPresent(), numismatics, "the spur recipe loads with Numismatics");
        helper.assertValueEqual(recipes.byKey(id.withSuffix("_from_bits")).isPresent(), !numismatics, "the Bit recipe without it");
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
        if (skippedWithout(helper, NumismaticsBridge.MOD_ID) || skippedWithout(helper, CreateRecipes.MOD_ID)) {
            return;
        }
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
        MarketCatalog.Loaded present = loadOne("{ \"required_mods\": [\"minecraft\", \"dynamic_economy\"], " + entries + " }");
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

    private static boolean remainderIsItself(Item item) {
        ItemStack left = item.getCraftingRemainingItem(new ItemStack(item));
        return !left.isEmpty() && left.getItem() == item;
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
                path(c, 1, false, b, r),            // (10 + 100) × 1.1 = 121: the premium once, not on b too; rare input
                path(d, 4, false, a),               // 11 / 4 = 2.75
                path(e, 20, false, a),              // 0.55: under 1 spur
                path(x, 1, false, Items.MILK_BUCKET), // the bucket comes back: (20 − 12) × 1.1 = 8.8
                path(f, 1, true, a)                 // plus an eighth of a coal: (10 + 1) × 1.1 = 12.1
        ), catalog, new java.util.HashMap<>());
        Map<Item, PriceSuggestion.Suggestion> s = result.suggestions();
        helper.assertValueEqual(s.get(b).basePrice(), Optional.of(11), "cheapest recipe wins");
        helper.assertValueEqual(s.get(c).basePrice(), Optional.of(121), "the premium is added once, at the end");
        helper.assertValueEqual(s.get(c).itemClass(), "rare_processed", "a rare input makes it rare");
        helper.assertValueEqual(s.get(b).itemClass(), "common_processed", "otherwise common");
        helper.assertValueEqual(s.get(c).depth(), 2, "two steps from the seeds");
        helper.assertValueEqual(s.get(d).basePrice(), Optional.of(2), "divided by the output count");
        helper.assertValueEqual(s.get(e).basePrice(), Optional.empty(), "worth under 1 spur: left unpriced");
        helper.assertValueEqual(s.get(x).basePrice(), Optional.of(8), "crafting remainders are subtracted");
        helper.assertValueEqual(s.get(f).basePrice(), Optional.of(12), "furnace recipes add fuel");
        helper.assertTrue(!s.containsKey(a), "listed items keep their catalog price");
        helper.assertTrue(result.settled(), "settles");

        // However long the chain, the premium is added once: buying an early item, processing it many times and
        // selling the result can't outgrow the markup.
        Item[] chain = {Items.COPPER_INGOT, Items.COPPER_BLOCK, Items.CUT_COPPER, Items.CUT_COPPER_STAIRS, Items.CUT_COPPER_SLAB};
        List<PriceSuggestion.Path> steps = new ArrayList<>(List.of(path(chain[0], 1, false, a)));
        for (int i = 1; i < chain.length; i++) {
            steps.add(path(chain[i], 1, false, chain[i - 1]));
        }
        PriceSuggestion.Result longChain = PriceSuggestion.suggest(steps, catalog, new java.util.HashMap<>());
        helper.assertValueEqual(longChain.suggestions().get(chain[chain.length - 1]).basePrice(), Optional.of(11),
                "five steps from a 10-spur seed: 11, not 10 × 1.1^5");

        // A loop that makes something out of less than itself (1 plank to a door, a door to 2 planks) can't feed back:
        // g isn't repriced from h, which was priced from g, so both settle at their prices from the seed.
        Item g = Items.GOLD_INGOT, h = Items.GOLD_NUGGET;
        PriceSuggestion.Result loop = PriceSuggestion.suggest(List.of(path(g, 1, false, a), path(h, 2, false, g), path(g, 1, false, h)),
                catalog, new java.util.HashMap<>());
        helper.assertTrue(loop.settled(), "the loop settles: " + loop.looping());
        // One h crafts back into an 11-spur g, but h costs only 5 to make from bought g (10 / 2), so at most 5 x 1.4 = 7.
        helper.assertValueEqual(loop.suggestions().get(h).basePrice(), Optional.of(7), "h is raised only as far as it costs to make");
        helper.assertValueEqual(loop.suggestions().get(g).basePrice(), Optional.of(11), "and g, two h selling for 14, isn't raised");
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

        // An item only an incomplete recipe makes still lowers a complete recipe that uses it: the gold block's
        // crafting recipe from that cheap emerald beats the one from a 100-spur diamond.
        Map<Item, CatalogEntry> late = seeds(seed(Items.DIAMOND, 100, null), seed(Items.COBBLESTONE, 1, null));
        PriceSuggestion.Result lateResult = PriceSuggestion.suggest(List.of(
                new PriceSuggestion.Path(id, "minecraft:crafting", Items.GOLD_BLOCK, 1, List.of(slot.apply(Items.DIAMOND, 1.0)), false, true),
                new PriceSuggestion.Path(id, "test:altar", Items.EMERALD, 1, List.of(slot.apply(Items.COBBLESTONE, 2.0)), false, false),
                new PriceSuggestion.Path(id, "minecraft:crafting", Items.GOLD_BLOCK, 1, List.of(slot.apply(Items.EMERALD, 1.0)), false, true)
        ), late, new java.util.HashMap<>());
        helper.assertValueEqual(lateResult.suggestions().get(Items.GOLD_BLOCK).basePrice(), Optional.of(2), "2 cobblestone × 1.1, not 110");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void suggestionsAreRaisedToWhatTheyProcessInto(GameTestHelper helper) {
        // A route makes nuggets worth 11 each (90 / 9 x 1.1), but 9 nuggets craft into a 160-spur ingot: bought at 11
        // they'd profit, so the nugget is raised until 9 of them cost at least an ingot.
        Item stone = Items.COBBLESTONE, nugget = Items.IRON_NUGGET, ingot = Items.IRON_INGOT;
        Map<Item, CatalogEntry> catalog = seeds(seed(stone, 90, null), seed(ingot, 160, null));
        ResourceLocation id = ResourceLocation.parse("test:craft");
        PriceSuggestion.Result result = PriceSuggestion.suggest(List.of(
                path(nugget, 9, false, stone),
                new PriceSuggestion.Path(id, "minecraft:crafting", ingot, 1, List.of(new PriceSuggestion.Slot(List.of(nugget), 9)), false)),
                catalog, new java.util.HashMap<>());
        PriceSuggestion.Suggestion s = result.suggestions().get(nugget);
        helper.assertValueEqual(s.basePrice(), Optional.of(13), "160 / 9 / 1.4 = 12.7, rounded up");
        helper.assertTrue(s.raised(), "and marked as raised");
        Map<Item, CatalogEntry> market = seeds(catalog.get(stone), catalog.get(ingot), PriceSuggestion.asEntry(nugget, 13, s.itemClass()).orElseThrow());
        List<Item> heads = PriceSuggestion.chainProfits(List.of(
                new PriceSuggestion.Path(id, "minecraft:crafting", ingot, 1, List.of(new PriceSuggestion.Slot(List.of(nugget), 9)), false),
                path(nugget, 9, false, stone)), market).stream().map(PriceSuggestion.ChainProfit::item).toList();
        helper.assertValueEqual(heads, List.of(stone), "nuggets no longer profit; the stone seed is what's left to fix");

        // What is made from a raised item is priced from its raised price: a gold nugget made from 9 iron nuggets costs
        // 9 x 13 = 117 (the premium is in the nuggets' price already), not 9 x 11 from their cheaper route.
        PriceSuggestion.Result made = PriceSuggestion.suggest(List.of(
                path(nugget, 9, false, stone),
                new PriceSuggestion.Path(id, "minecraft:crafting", ingot, 1, List.of(new PriceSuggestion.Slot(List.of(nugget), 9)), false),
                new PriceSuggestion.Path(id, "minecraft:crafting", Items.GOLD_NUGGET, 1, List.of(new PriceSuggestion.Slot(List.of(nugget), 9)), false)),
                catalog, new java.util.HashMap<>());
        helper.assertValueEqual(made.suggestions().get(Items.GOLD_NUGGET).basePrice(), Optional.of(117), "priced from the raised nuggets");
        helper.assertTrue(made.suggestions().get(nugget).raised(), "and the nugget is still marked as raised");

        // Made for 1.22 (10 / 9 x 1.1), a nugget can't be raised to what 9 of them craft into (32) without selling for more
        // than it costs to make: it keeps its price, and buying it is reported as a chain profit instead.
        Map<Item, CatalogEntry> cheap = seeds(seed(stone, 10, null), seed(ingot, 32, null));
        PriceSuggestion.Suggestion capped = PriceSuggestion.suggest(List.of(
                path(nugget, 9, false, stone),
                new PriceSuggestion.Path(id, "minecraft:crafting", ingot, 1, List.of(new PriceSuggestion.Slot(List.of(nugget), 9)), false)),
                cheap, new java.util.HashMap<>()).suggestions().get(nugget);
        helper.assertValueEqual(capped.basePrice(), Optional.of(1), "kept at its cost");
        helper.assertTrue(!capped.raised(), "not raised");
        Map<Item, CatalogEntry> cheapMarket = seeds(cheap.get(stone), cheap.get(ingot), PriceSuggestion.asEntry(nugget, 1, capped.itemClass()).orElseThrow());
        helper.assertTrue(PriceSuggestion.chainProfits(List.of(
                new PriceSuggestion.Path(id, "minecraft:crafting", ingot, 1, List.of(new PriceSuggestion.Slot(List.of(nugget), 9)), false)),
                cheapMarket).stream().anyMatch(p -> p.item() == nugget), "so buying it is reported");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void sequencedAssemblyInputsAreRead(GameTestHelper helper) {
        if (skippedWithout(helper, CreateRecipes.MOD_ID)) {
            return;
        }
        // Create's precision mechanism: a golden sheet, then 5 loops of deploying a cogwheel, a large cogwheel and an iron
        // nugget onto the half-made mechanism, which succeeds most of the time.
        var holder = helper.getLevel().getServer().getRecipeManager()
                .byKey(ResourceLocation.parse("create:sequenced_assembly/precision_mechanism")).orElseThrow();
        dynamiceconomy.compat.MachineRecipes.Read read = dynamiceconomy.compat.CreateRecipes.sequencedAssembly(holder.value()).orElseThrow();
        Map<Item, Double> amounts = new java.util.HashMap<>();
        read.inputs().forEach(input -> input.options().forEach(item -> amounts.merge(item, input.amount(), Double::sum)));
        Item mechanism = BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:precision_mechanism"));
        Item sheet = BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:golden_sheet"));
        Item cogwheel = BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:cogwheel"));
        Item incomplete = BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:incomplete_precision_mechanism"));
        helper.assertValueEqual(read.item(), mechanism, "makes the mechanism");
        helper.assertValueEqual(amounts.get(sheet), 1.0, "starts from one golden sheet");
        helper.assertValueEqual(amounts.get(cogwheel), 5.0, "deploys a cogwheel each loop");
        helper.assertValueEqual(amounts.get(Items.IRON_NUGGET), 5.0, "and an iron nugget");
        helper.assertTrue(!amounts.containsKey(incomplete), "the half-made mechanism isn't an input");
        helper.assertTrue(read.expected() > 0.5 && read.expected() < 1, "made at its success chance, not every time");
        helper.assertTrue(!read.fluids(), "uses no fluids");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void energyConversionsAreNotComplete(GameTestHelper helper) {
        // The particle collider turns netherrack into netherite scrap: priced as complete, it raised a charcoal block to 404M.
        helper.assertTrue(!PriceSuggestion.completeMachineRecipe("oritech:particle_collision", false), "the particle collider hides its cost");
        helper.assertTrue(!PriceSuggestion.completeMachineRecipe("mekanism:sawing", false), "nor does Mekanism's sawmill, which makes more wood than it takes");
        helper.assertTrue(PriceSuggestion.completeMachineRecipe("oritech:foundry", false), "other machines without fluids are complete");
        helper.assertTrue(!PriceSuggestion.completeMachineRecipe("oritech:foundry", true), "machines using fluids aren't");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void partlyUsedInputsAreCreditedOneRun(GameTestHelper helper) {
        // A quarry uses its drill up one run in ten. Credited with all ten runs, the drill would be raised to 10x what one
        // run makes, and chains of such inputs multiply without bound; it gets one run's worth.
        Item stone = Items.COBBLESTONE, drill = Items.IRON_PICKAXE, diamond = Items.DIAMOND;
        Map<Item, CatalogEntry> catalog = seeds(seed(stone, 1, null), seed(diamond, 512, null));
        PriceSuggestion.Path quarry = new PriceSuggestion.Path(ResourceLocation.parse("test:quarry"), "test:quarry", diamond, 0.1,
                List.of(new PriceSuggestion.Slot(List.of(drill), 0.1)), false, true);
        // The drill costs 30 stone (33 spurs), so it can be raised as far as 42 without selling for more than it costs to make.
        PriceSuggestion.Path craftDrill = new PriceSuggestion.Path(ResourceLocation.parse("test:drill"), "minecraft:crafting", drill, 1,
                List.of(new PriceSuggestion.Slot(List.of(stone), 30)), false);
        PriceSuggestion.Suggestion s = PriceSuggestion.suggest(List.of(craftDrill, quarry),
                catalog, new java.util.HashMap<>()).suggestions().get(drill);
        helper.assertValueEqual(s.basePrice(), Optional.of(37), "one run makes 51.2 spurs of diamond; 51.2 / 1.4 = 36.6, rounded up");
        helper.assertValueEqual(s.raisedVia(), List.of(quarry), "raised through the quarry");
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

        // Two recipes that each double the other's item: a value never rests on itself, so neither grows without end.
        Map<Item, CatalogEntry> clayMarket = seeds(seed(Items.CLAY_BALL, 1, null));
        List<PriceSuggestion.ChainProfit> doubling = PriceSuggestion.chainProfits(List.of(
                path(Items.BRICK, 2, false, Items.CLAY_BALL), path(Items.CLAY_BALL, 2, false, Items.BRICK)), clayMarket);
        helper.assertValueEqual(doubling, List.of(), "a loop through another item doesn't count");

        // Many generators in a row multiply without end: each step here doubles, so chains stop at 6 recipes (2^6 = 64).
        List<Item> line = List.of(Items.OAK_LOG, Items.OAK_PLANKS, Items.STICK, Items.LADDER, Items.CHEST, Items.BARREL,
                Items.LECTERN, Items.BOOKSHELF, Items.CRAFTING_TABLE, Items.BOWL);
        List<PriceSuggestion.Path> doublers = new ArrayList<>();
        for (int i = 0; i + 1 < line.size(); i++) {
            doublers.add(path(line.get(i + 1), 2, false, line.get(i)));
        }
        Map<Item, CatalogEntry> lineMarket = seeds(line.stream().map(item -> seed(item, 1, null)).toArray(CatalogEntry[]::new));
        PriceSuggestion.ChainProfit log = PriceSuggestion.chainProfits(doublers, lineMarket).stream()
                .filter(p -> p.item() == Items.OAK_LOG).findFirst().orElseThrow();
        helper.assertValueEqual(log.steps().size(), 6, "the chain stops at 6 recipes");
        helper.assertValueEqual(log.value().compareTo(new BigDecimal("64")), 0, "so a log is worth 2^6 of what the chain ends in, not 2^9");

        // A knife the crafting grid hands back isn't worth what it helps cut.
        Item knife = BuiltInRegistries.ITEM.stream().filter(i -> remainderIsItself(i)).findFirst().orElse(null);
        if (knife != null) {
            Map<Item, CatalogEntry> toolMarket = seeds(seed(knife, 1, null), seed(Items.BREAD, 1000, null), seed(Items.WHEAT, 1, null));
            List<PriceSuggestion.ChainProfit> tool = PriceSuggestion.chainProfits(List.of(
                    path(Items.BREAD, 1, false, knife, Items.WHEAT)), toolMarket);
            helper.assertTrue(tool.stream().noneMatch(p -> p.item() == knife), "a returned tool isn't credited");
        }
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

        if (net.neoforged.fml.ModList.get().isLoaded(CreateRecipes.MOD_ID)) {
            Item sheet = BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:iron_sheet"));
            helper.assertValueEqual(s.get(sheet).basePrice(), Optional.of(35), "Create pressing: 32 × 1.1");
            helper.assertValueEqual(s.get(sheet).recipeType(), "create:pressing", "from the pressing recipe");
            helper.assertTrue(s.get(sheet).completeType(), "a recipe type that lists everything");
        }

        PriceSuggestion.Suggestion sword = s.get(Items.NETHERITE_SWORD);
        helper.assertTrue(sword != null && sword.recipeType().equals("minecraft:smithing"), "smithing recipes are read: " + sword);
        BigDecimal diamondSword = s.get(Items.DIAMOND_SWORD).cost();
        helper.assertValueEqual(sword.cost().setScale(6, RoundingMode.HALF_UP),
                diamondSword.add(BigDecimal.valueOf(4620)).setScale(6, RoundingMode.HALF_UP),
                "the diamond sword's cost plus the template and netherite ingot with the premium (4200 × 1.1), not a second premium on the sword");
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
        // The test server has vanilla, Create and Numismatics; every file for another mod is skipped.
        java.util.function.Predicate<String> installed = mod -> net.neoforged.fml.ModList.get().isLoaded(mod);
        int expected = defaultPriceFiles().stream().filter(f -> {
                    com.google.gson.JsonArray mods = f.json().getAsJsonObject().getAsJsonArray("required_mods");
                    return mods == null || mods.asList().stream().allMatch(mod -> installed.test(mod.getAsString()));
                }).mapToInt(f -> f.json().getAsJsonObject().getAsJsonArray("entries").size()).sum();
        helper.assertValueEqual(loaded.entries().size(), expected, "only the files for installed mods load");
        helper.assertTrue(loaded.entries().keySet().stream().allMatch(item -> installed.test(key(item).getNamespace())),
                "no item from a mod that isn't installed");
        for (CatalogEntry entry : loaded.entries().values()) {
            helper.assertTrue(entry.itemClass().map(c -> c.getNamespace().equals(DynamicEconomy.MOD_ID)).orElse(false),
                    entry.itemId() + " has a built-in class");
            helper.assertValueEqual(PriceModel.arbitrageRisk(PriceModel.inputs(entry)), Optional.empty(), entry.itemId() + " round trip");
        }
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        // Recipes that may hide inputs (Create filling a bottle with unpriced honey) are listed for review by hand, not errors.
        List<String> profits = PriceSuggestion.craftProfits(server.getRecipeManager().getRecipes(), server.registryAccess(),
                loaded.entries()).stream().filter(p -> p.kind() != PriceSuggestion.ProfitKind.MAY_HIDE_INPUTS)
                .map(p -> "%s via %s: costs %s, sells for %s".formatted(key(p.output()), p.recipe(),
                p.buyCost().setScale(2, RoundingMode.HALF_UP), p.sellValue().setScale(2, RoundingMode.HALF_UP))).toList();
        helper.assertValueEqual(profits, List.of(), "no listed item can be crafted from bought ingredients and sold at a profit");
        List<String> chains = PriceSuggestion.chainProfits(PriceSuggestion.paths(server.getRecipeManager().getRecipes(),
                        server.registryAccess()), loaded.entries()).stream().filter(PriceSuggestion.ChainProfit::complete)
                .map(c -> "%s costs %s, processes into %s via %s".formatted(key(c.item()), c.buyCost().setScale(2, RoundingMode.HALF_UP),
                        c.value().setScale(2, RoundingMode.HALF_UP), c.steps().stream().map(PriceSuggestion.Path::recipe).toList())).toList();
        helper.assertValueEqual(chains, List.of(), "nor bought and processed through several recipes at a profit");
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
            // Numismatics shows at most 2^30 − 1 and keeps the rest in a hidden overflow; nothing is lost. Our bank
            // shows it all.
            helper.assertValueEqual(f.balance(), (int) Math.min(Integer.MAX_VALUE, f.bank().displayCap()),
                    "the displayed balance is capped at the bank's cap");
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
        // Unlisted items count too (since Phase 17), for the exchange.
        helper.assertTrue(MarketCatalog.INSTANCE.get(Items.STONE).isEmpty(), "stone isn't listed in the test catalog");
        expected.put(key(Items.STONE), 12);
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
            helper.assertTrue(overall.size() >= 4 && overall.get(1).contains("%,d %s".formatted(f.market.totals().paidOut(), f.bank().kind().unit())),
                    "overall stats show what sellers were paid: " + overall);
            List<String> iron = runCommand(helper, "market admin stats minecraft:iron_ingot");
            MarketStockData.Totals ironTotals = f.market.totals(key(Items.IRON_INGOT));
            helper.assertTrue(iron.get(0).startsWith("minecraft:iron_ingot")
                            && iron.get(1).contains("%,d %s for %,d items".formatted(ironTotals.paidOut(), f.bank().kind().unit(), ironTotals.unitsSold())),
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

    // ---- Stage locks (Phase 12) -------------------------------------------------------------------------------
    // ProgressiveStages isn't in the test environment; these lock items through MarketVisibility's test hook, which
    // every check consults exactly as it does the real locks.

    @GameTest(template = EMPTY)
    public static void lockedItemIsHiddenAndUntradeableForOnePlayerOnly(GameTestHelper helper) {
        Fixture locked = new Fixture(helper);
        Fixture free = new Fixture(helper);
        locked.setStock(Items.IRON_INGOT, 50);
        locked.deposit(10_000);
        free.deposit(10_000);
        locked.giveMany(Items.IRON_INGOT, 10);
        free.giveMany(Items.IRON_INGOT, 10);
        MarketVisibility.setTestLocks((player, item) -> player == locked.player
                && (item == Items.IRON_INGOT || item == Items.NETHER_STAR));
        try {
            locked.assertRejectedUnchanged(Result.NOT_LISTED, () -> MarketService.trySell(locked.player, Items.IRON_INGOT, 5, ANY_PRICE));
            locked.assertRejectedUnchanged(Result.NOT_LISTED, () -> MarketService.tryBuy(locked.player, Items.IRON_INGOT, 1, ANY_PRICE));
            // A locked item answers as an unlisted one, never with its own reason (the nether star can't be sold).
            locked.assertRejectedUnchanged(Result.NOT_LISTED, () -> MarketService.trySell(locked.player, Items.NETHER_STAR, 1, ANY_PRICE));
            helper.assertTrue(rowIds(MarketSync.catalogFor(locked.player)).stream().noneMatch(id -> id.equals(id(Items.IRON_INGOT))),
                    "locked player's catalog has no iron");
            helper.assertTrue(rowIds(MarketSync.catalogFor(locked.player)).contains(id(Items.DIAMOND)),
                    "locked player still sees unlocked items");

            helper.assertTrue(rowIds(MarketSync.catalogFor(free.player)).contains(id(Items.IRON_INGOT)), "other player sees iron");
            free.assertResult(MarketService.trySell(free.player, Items.IRON_INGOT, 5, ANY_PRICE), Result.OK);
            free.assertResult(MarketService.tryBuy(free.player, Items.IRON_INGOT, 1, ANY_PRICE), Result.OK);
        } finally {
            MarketVisibility.setTestLocks(null);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void unlockingShowsTheItemAgainAtTheSamePrice(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.giveMany(Items.IRON_INGOT, 5);
        MarketPayloads.Row before = MarketSync.catalogFor(f.player).rows().stream()
                .filter(row -> row.item().equals(id(Items.IRON_INGOT))).findFirst().orElseThrow();
        MarketVisibility.setTestLocks((player, item) -> item == Items.IRON_INGOT);
        try {
            helper.assertFalse(rowIds(MarketSync.catalogFor(f.player)).contains(id(Items.IRON_INGOT)), "iron hidden while locked");
        } finally {
            MarketVisibility.setTestLocks(null);
        }
        MarketPayloads.Row after = MarketSync.catalogFor(f.player).rows().stream()
                .filter(row -> row.item().equals(id(Items.IRON_INGOT))).findFirst().orElseThrow();
        helper.assertValueEqual(after, before, "iron row after unlocking (prices are the same for everyone)");
        f.assertResult(MarketService.trySell(f.player, Items.IRON_INGOT, 5, ANY_PRICE), Result.OK);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void withoutProgressiveStagesNothingIsHidden(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        helper.assertFalse(StagesBridge.isAvailable(), "ProgressiveStages is not in the test environment");
        helper.assertFalse(MarketVisibility.active(), "nothing can be hidden");
        helper.assertFalse(StagesBridge.isLocked(f.player, Items.IRON_INGOT), "no item is locked");
        helper.assertValueEqual(MarketSync.catalogFor(f.player).rows().size(), MarketCatalog.INSTANCE.entries().size(),
                "every listed item is sent");
        helper.succeed();
    }

    // ---- Leaderboard (Phase 13) ---------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void leaderboardRanksRichestFirstAndSkipsWhoItShould(GameTestHelper helper) {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID carol = UUID.randomUUID();
        UUID broke = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        Map<UUID, GameProfile> known = Map.of(alice, new GameProfile(alice, "alice"), bob, new GameProfile(bob, "Bob"),
                carol, new GameProfile(carol, "carol"), broke, new GameProfile(broke, "broke"),
                admin, new GameProfile(admin, "admin"));
        Map<UUID, Long> balances = Map.of(alice, 500L, bob, 900L, carol, 500L, broke, 0L, stranger, 10_000L, admin, 99_999L);

        List<Leaderboard.Entry> ranked = Leaderboard.rank(balances, id -> Optional.ofNullable(known.get(id)),
                profile -> profile.getName().equals("admin"));

        helper.assertValueEqual(ranked.stream().map(Leaderboard.Entry::name).toList(), List.of("Bob", "alice", "carol"),
                "richest first, ties by name; no empty, unknown or excluded accounts");
        helper.assertValueEqual(ranked.get(0).balance(), 900L, "exact balance");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void leaderboardReadsPersonalAccountsAtTheirBankBalance(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        UUID bankerId = UUID.randomUUID();
        // Only Numismatics' bank has shared accounts.
        Optional<Bank.Account> banker = f.bank().kind() == BankKind.NUMISMATICS
                ? NumismaticsBridge.sharedAccountForTest(bankerId) : Optional.empty();
        helper.assertTrue(f.bank().canListAccounts(), "the bank's account list is readable");
        f.deposit(1234);
        banker.ifPresent(account -> account.deposit(999));
        try {
            Map<UUID, Long> balances = f.bank().playerBalances();
            helper.assertValueEqual(balances.get(f.player.getUUID()), (long) f.balance(), "listed balance is the bank balance");
            helper.assertFalse(balances.containsKey(bankerId), "a shared Blaze Banker account is never listed");

            // With only this player known by name, every other account (test leftovers, the banker) stays off.
            Leaderboard.View view = Leaderboard.viewFor(f.player, id -> id.equals(f.player.getUUID())
                    ? Optional.of(f.player.getGameProfile()) : Optional.empty());
            helper.assertValueEqual(view.ranked(), 1, "players ranked");
            helper.assertValueEqual(view.ownRank(), 1, "own rank");
            helper.assertValueEqual(view.top().get(0).id(), f.player.getUUID(), "the player's line");
            helper.assertValueEqual(view.top().get(0).balance(), 1234L, "ranked at the bank balance");
        } finally {
            // The GameTest world persists: leave both accounts empty, which keeps them off every later board.
            f.account().deduct(f.balance());
            banker.ifPresent(account -> account.deduct(Math.toIntExact(account.balance())));
        }
        helper.succeed();
    }

    private static Set<ResourceLocation> rowIds(MarketPayloads.Snapshot snapshot) {
        Set<ResourceLocation> ids = new java.util.HashSet<>();
        snapshot.rows().forEach(row -> ids.add(row.item()));
        return ids;
    }

    private static ResourceLocation id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    // ---- Coins --------------------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void coinsAreWorthEightOfTheOneBelow(GameTestHelper helper) {
        helper.assertValueEqual(Coin.BIT.value(), 1, "a Bit is one bit, the same as a spur");
        for (Coin coin : Coin.values()) {
            coin.next().ifPresent(next -> helper.assertValueEqual(next.value(), coin.value() * 8, next + " is 8 " + coin));
            helper.assertValueEqual(Coins.item(coin).coin(), coin, "registered as " + coin.id());
            helper.assertTrue(Coins.isCoin(Coins.item(coin)), coin + " is a coin");
        }
        helper.assertValueEqual(Coin.CROWN.value(), 4_096, "Crown");
        helper.assertValueEqual(Coin.SUN.value(), 32_768, "Sun");
        helper.assertValueEqual(Coin.OMEGA.value(), 2_097_152, "Omega");
        helper.assertFalse(Coins.isCoin(Items.GOLD_NUGGET), "other items aren't coins");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void coinsCanNeverBeListed(GameTestHelper helper) {
        List<String> ids = new ArrayList<>(List.of("dynamic_economy:bit", "dynamic_economy:omega"));
        if (net.neoforged.fml.ModList.get().isLoaded(NumismaticsBridge.MOD_ID)) {
            ids.addAll(List.of("numismatics:spur", "numismatics:sun"));
        }
        for (String id : ids) {
            MarketCatalog.Loaded loaded = loadOne("{ \"entries\": [ { \"item\": \"" + id + "\", \"base_price\": 1 } ] }");
            helper.assertTrue(loaded.entries().isEmpty(), id + " isn't listed from a file");
            helper.assertTrue(loaded.problems().size() == 1 && loaded.problems().get(0).message().contains("coin"),
                    "and the file says why: " + loaded.problems());
        }

        // Nor by an admin edit or a tag rule that prices it.
        Item bit = Coins.item(Coin.BIT);
        MarketOverrides edits = new MarketOverrides();
        edits.setItemField(key(bit), "base_price", number(1));
        ResourceLocation coinTag = ResourceLocation.fromNamespaceAndPath("test", "coins");
        edits.setTagField(coinTag, "base_price", number(5));
        CatalogOverlay.Result result = CatalogOverlay.apply(Map.of(), Map.of(), edits.snapshot(),
                tag -> tag.equals(coinTag) ? List.of(Coins.item(Coin.CHIP)) : CatalogOverlay.registryTag(tag));
        helper.assertTrue(result.entries().isEmpty(), "edits list no coin: " + result.entries().keySet());
        helper.assertTrue(result.problems().containsKey(key(bit)) && result.problems().containsKey(key(Coins.item(Coin.CHIP))),
                "and each is reported: " + result.problems());

        Fixture f = new Fixture(helper);
        f.give(new ItemStack(bit, 8));
        f.assertRejectedUnchanged(Result.NOT_LISTED, () -> MarketService.trySell(f.player, bit, 8, ANY_PRICE));
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void coinsChangeEightForOneOnlyWithTheModsBank(GameTestHelper helper) {
        boolean own = WorldBank.kind(helper.getLevel().getServer()) == BankKind.OWN;
        helper.assertValueEqual(Coins.inUse(helper.getLevel()), own, "coins are in use only with our bank");
        for (Coin coin : Coin.values()) {
            if (coin.next().isEmpty()) {
                continue;
            }
            Item small = Coins.item(coin);
            Item big = Coins.item(coin.next().get());
            Optional<ItemStack> up = craft(helper, small, small, small, small, small, small, small, small, Items.AIR);
            Optional<ItemStack> down = craft(helper, big, Items.AIR, Items.AIR, Items.AIR, Items.AIR, Items.AIR, Items.AIR, Items.AIR, Items.AIR);
            if (own) {
                helper.assertTrue(up.isPresent() && up.get().is(big) && up.get().getCount() == 1, "8 " + coin + " make one " + big + ": " + up);
                helper.assertTrue(down.isPresent() && down.get().is(small) && down.get().getCount() == 8, "and back into 8: " + down);
            } else {
                helper.assertTrue(up.isEmpty() && down.isEmpty(), "uncraftable with Numismatics' bank: " + up + ", " + down);
            }
        }
        // Seven coins of one kind make nothing: the exchange never rounds.
        Item bit = Coins.item(Coin.BIT);
        helper.assertTrue(craft(helper, bit, bit, bit, bit, bit, bit, bit, Items.AIR, Items.AIR).isEmpty(), "7 Bits make nothing");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void terminalCraftsFromBitsWithoutNumismatics(GameTestHelper helper) {
        if (net.neoforged.fml.ModList.get().isLoaded(NumismaticsBridge.MOD_ID)) {
            DynamicEconomy.LOGGER.info("GameTest skipped: Numismatics is installed, so the terminal takes spurs");
            helper.succeed();
            return;
        }
        Item b = Coins.item(Coin.BIT);
        Optional<ItemStack> made = craft(helper, b, b, b, b, Items.CRAFTING_TABLE, b, b, b, b);
        helper.assertTrue(made.isPresent() && made.get().is(MarketBlocks.MARKET_TERMINAL_ITEM.get()) && made.get().getCount() == 1,
                "a crafting table surrounded by 8 Bits makes one terminal, got " + made);
        helper.succeed();
    }

    // ---- Bank tab -----------------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void withdrawalsUseTheFewestCoins(GameTestHelper helper) {
        List<Denomination> ours = new OwnBank().coins();
        helper.assertValueEqual(ours.get(0).item(), Coins.item(Coin.OMEGA), "ours run from Omega");
        helper.assertValueEqual(ours.get(ours.size() - 1).item(), Coins.item(Coin.BIT), "down to Bit");
        // Numismatics' values, on stand-in items: only the values matter here.
        List<Denomination> numismatics = List.of(new Denomination(Items.STONE, 4096), new Denomination(Items.DIRT, 512),
                new Denomination(Items.SAND, 64), new Denomination(Items.GRAVEL, 16), new Denomination(Items.CLAY, 8),
                new Denomination(Items.FLINT, 1));
        for (List<Denomination> coins : List.of(ours, numismatics)) {
            int limit = 20_000;
            int[] fewest = fewestCoins(coins, limit);
            for (int amount = 0; amount <= limit; amount++) {
                long[] counts = CoinMath.breakdown(coins, amount);
                helper.assertValueEqual(CoinMath.value(coins, counts), (long) amount, "exactly " + amount);
                helper.assertValueEqual(java.util.Arrays.stream(counts).sum(), (long) fewest[amount], "fewest coins for " + amount);
            }
        }
        long[] mixed = CoinMath.breakdown(ours, 4_096 + 3 * 512 + 7);
        helper.assertValueEqual(java.util.Arrays.toString(mixed), "[0, 0, 0, 1, 3, 0, 0, 7]", "a Crown, 3 Marks and 7 Bits");
        helper.succeed();
    }

    /** The fewest coins for every amount up to {@code limit}, worked out the slow, sure way. */
    private static int[] fewestCoins(List<Denomination> coins, int limit) {
        int[] fewest = new int[limit + 1];
        for (int amount = 1; amount <= limit; amount++) {
            fewest[amount] = Integer.MAX_VALUE;
            for (Denomination coin : coins) {
                if (coin.value() <= amount && fewest[amount - coin.value()] + 1 < fewest[amount]) {
                    fewest[amount] = fewest[amount - coin.value()] + 1;
                }
            }
        }
        return fewest;
    }

    @GameTest(template = EMPTY)
    public static void depositThenWithdrawReturnsTheSameValue(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        List<Denomination> coins = f.bank().coins();
        helper.assertFalse(coins.isEmpty(), "the world's bank has coins");
        // A few of every coin, worth well within a test balance.
        long carried = 0;
        for (int i = 0; i < coins.size(); i++) {
            int count = coins.size() - i;
            f.give(new ItemStack(coins.get(i).item(), count));
            carried += (long) count * coins.get(i).value();
        }
        int before = f.balance();
        BankService.Outcome in = BankService.depositAll(f.player);
        helper.assertTrue(in.ok() && in.amount() == carried, "deposited what was carried: " + in);
        helper.assertValueEqual((long) f.balance(), before + carried, "balance");
        helper.assertValueEqual(coinValue(f, coins), 0L, "no coins left");

        BankService.Outcome out = BankService.withdraw(f.player, carried);
        helper.assertTrue(out.ok() && out.amount() == carried, "withdrew it all: " + out);
        helper.assertValueEqual(f.balance(), before, "balance back where it was");
        helper.assertValueEqual(coinValue(f, coins), carried, "and exactly the same value in coins");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void depositTakesOnlyPlainCoinsFromTheMainInventory(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Denomination smallest = smallestCoin(f);
        ItemStack renamed = new ItemStack(smallest.item(), 5);
        renamed.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("keepsake"));
        f.give(renamed);
        f.player.getInventory().offhand.set(0, new ItemStack(smallest.item(), 7));
        f.assertRejectedUnchanged(BankService.Result.NO_COINS, () -> BankService.depositAll(f.player));
        f.give(new ItemStack(smallest.item(), 3));
        BankService.Outcome outcome = BankService.depositAll(f.player);
        helper.assertTrue(outcome.ok() && outcome.amount() == 3L * smallest.value(), "only the 3 plain coins: " + outcome);
        helper.assertTrue(f.player.getInventory().items.stream().anyMatch(stack -> stack.is(smallest.item())
                && stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME) && stack.getCount() == 5), "the renamed coins stay");
        helper.assertValueEqual(f.player.getInventory().offhand.get(0).getCount(), 7, "and so does the offhand");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void withdrawRefusesWhatItCantDoAndChangesNothing(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Denomination smallest = smallestCoin(f);
        f.assertRejectedUnchanged(BankService.Result.NO_FUNDS, () -> BankService.withdraw(f.player, 10));
        f.assertRejectedUnchanged(BankService.Result.INVALID_AMOUNT, () -> BankService.withdraw(f.player, 0));
        f.assertRejectedUnchanged(BankService.Result.TOO_LARGE, () -> BankService.withdraw(f.player, Integer.MAX_VALUE + 1L));

        f.deposit(1000);
        // A full inventory: the coins wouldn't fit, so nothing is taken and nothing is dropped.
        f.giveMany(Items.COBBLESTONE, 36 * 64);
        f.assertRejectedUnchanged(BankService.Result.NO_SPACE, () -> BankService.withdraw(f.player, 9));
        // One free slot fits one kind of coin, but not two.
        f.player.getInventory().items.set(0, ItemStack.EMPTY);
        f.assertRejectedUnchanged(BankService.Result.NO_SPACE, () -> BankService.withdraw(f.player, smallest.value() + coinAbove(f, smallest).value()));
        BankService.Outcome one = BankService.withdraw(f.player, smallest.value());
        helper.assertTrue(one.ok(), "one coin fits the free slot: " + one);
        // A part-filled stack of that coin is room too, even with no slot free.
        BankService.Outcome topUp = BankService.withdraw(f.player, smallest.value());
        helper.assertTrue(topUp.ok() && f.count(smallest.item()) == 2, "tops up the stack: " + topUp);
        helper.assertValueEqual(f.balance(), 1000 - 2 * smallest.value(), "paid for both");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void withdrawingOneCoinGivesExactlyThatCoin(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Denomination smallest = smallestCoin(f);
        Denomination above = coinAbove(f, smallest);
        f.assertRejectedUnchanged(BankService.Result.NO_FUNDS, () -> BankService.withdrawCoins(f.player, smallest.item(), 1));
        f.assertRejectedUnchanged(BankService.Result.INVALID_AMOUNT, () -> BankService.withdrawCoins(f.player, smallest.item(), 0));
        f.assertRejectedUnchanged(BankService.Result.INVALID_AMOUNT, () -> BankService.withdrawCoins(f.player, Items.DIAMOND, 1));
        f.assertRejectedUnchanged(BankService.Result.TOO_LARGE, () -> BankService.withdrawCoins(f.player, above.item(), Integer.MAX_VALUE));

        // Enough small coins to make the one above: the fewest-coins withdrawal would hand that over instead.
        int count = above.value() / smallest.value();
        f.deposit(above.value());
        BankService.Outcome out = BankService.withdrawCoins(f.player, smallest.item(), count);
        helper.assertTrue(out.ok() && out.amount() == above.value(), "paid what the coins are worth: " + out);
        helper.assertValueEqual(f.count(smallest.item()), (long) count, "exactly that many of that coin");
        helper.assertValueEqual(f.count(above.item()), 0L, "none of the coin above");
        helper.assertValueEqual(f.balance(), 0, "the balance paid for them");

        // No room: nothing is taken and nothing is dropped.
        f.deposit(above.value());
        f.player.getInventory().items.clear();
        f.giveMany(Items.COBBLESTONE, 36 * 64);
        f.assertRejectedUnchanged(BankService.Result.NO_SPACE, () -> BankService.withdrawCoins(f.player, above.item(), 1));
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void bankCardOpensOnlyTheBank(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        MarketMenu menu = MarketMenu.forBankCard();
        helper.assertTrue(menu.bankOnly(), "bank-only");
        helper.assertFalse(menu.stillValid(f.player), "not without a card");
        f.give(new ItemStack(Coins.BANK_CARD.get()));
        helper.assertTrue(menu.stillValid(f.player), "valid while the player carries a card");

        // A trade sent from a Bank Card's screen is ignored, however it's dressed up.
        f.setStock(Items.IRON_INGOT, 5);
        f.deposit(10_000);
        f.player.containerMenu = menu;
        try {
            MarketSync.handleTradeRequest(new MarketPayloads.TradeRequest(true, key(Items.IRON_INGOT), 1,
                    f.quote(Items.IRON_INGOT).buyPrice()), f.player);
        } finally {
            f.player.containerMenu = f.player.inventoryMenu;
        }
        helper.assertValueEqual(f.balance(), 10_000, "nothing was paid");
        helper.assertValueEqual(f.count(Items.IRON_INGOT), 0L, "nothing was bought");
        helper.assertFalse(MarketMenu.forCommand().bankOnly(), "the full market isn't bank-only");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void bankCardCraftsFromACoinWorthEight(GameTestHelper helper) {
        boolean own = WorldBank.kind(helper.getLevel().getServer()) == BankKind.OWN;
        Item chip = Coins.item(Coin.CHIP);
        Optional<ItemStack> fromChip = craft(helper, Items.PAPER, Items.GOLD_INGOT, chip, Items.AIR, Items.AIR, Items.AIR,
                Items.AIR, Items.AIR, Items.AIR);
        helper.assertTrue(fromChip.isPresent() && fromChip.get().is(Coins.BANK_CARD.get()), "paper, gold and a Chip: " + fromChip);
        if (net.neoforged.fml.ModList.get().isLoaded(NumismaticsBridge.MOD_ID)) {
            Item bevel = BuiltInRegistries.ITEM.get(ResourceLocation.parse("numismatics:bevel"));
            Optional<ItemStack> fromBevel = craft(helper, Items.PAPER, Items.GOLD_INGOT, bevel, Items.AIR, Items.AIR, Items.AIR,
                    Items.AIR, Items.AIR, Items.AIR);
            helper.assertTrue(fromBevel.isPresent() && fromBevel.get().is(Coins.BANK_CARD.get()), "or a Bevel: " + fromBevel);
        }
        helper.assertTrue(own || net.neoforged.fml.ModList.get().isLoaded(NumismaticsBridge.MOD_ID),
                "with Numismatics' bank, Numismatics is there to give a Bevel");
        helper.succeed();
    }

    private static Denomination smallestCoin(Fixture f) {
        List<Denomination> coins = f.bank().coins();
        return coins.get(coins.size() - 1);
    }

    private static Denomination coinAbove(Fixture f, Denomination coin) {
        List<Denomination> coins = f.bank().coins();
        return coins.get(coins.indexOf(coin) - 1);
    }

    /** What the plain coins of {@code coins} in the main inventory are worth. */
    private static long coinValue(Fixture f, List<Denomination> coins) {
        long total = 0;
        for (ItemStack stack : f.player.getInventory().items) {
            for (Denomination coin : coins) {
                if (BankService.isPlain(stack, coin)) {
                    total += (long) stack.getCount() * coin.value();
                }
            }
        }
        return total;
    }

    // ---- Team accounts ------------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void teamAccountsAreSeparateAndNeverLoseMoney(GameTestHelper helper) {
        OwnBank bank = new OwnBank();
        UUID team = UUID.randomUUID();
        UUID heir = UUID.randomUUID();
        bank.account(heir).deposit(5);
        bank.teamAccount(team).deposit(1000);
        helper.assertValueEqual(bank.teamAccount(team).balance(), 1000L, "the team's balance");
        helper.assertFalse(bank.playerBalances().containsKey(team), "a team isn't a player, so it's off the leaderboard");
        helper.assertValueEqual(bank.account(team).balance(), 0L, "and its ID has no personal account");

        helper.assertValueEqual(bank.closeTeam(team, heir), 1000L, "closing pays out the whole balance");
        helper.assertValueEqual(bank.account(heir).balance(), 1005L, "to the heir's own account");
        helper.assertTrue(bank.teamBalances().isEmpty(), "and the team's account is gone");
        helper.assertValueEqual(bank.closeTeam(team, heir), 0L, "closing an empty team pays nothing");

        // Saved with the world, choices included.
        bank.teamAccount(team).deposit(7);
        OwnBank loaded = OwnBank.fromTag(bank.save(new net.minecraft.nbt.CompoundTag(), helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        helper.assertValueEqual(loaded.teamBalances(), bank.teamBalances(), "team balances survive a save");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void ownTeamsKeepTheirMembers(GameTestHelper helper) {
        OwnTeams teams = new OwnTeams();
        UUID owner = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        helper.assertTrue(OwnTeams.validName("Gold_Diggers-2") && !OwnTeams.validName("two words") && !OwnTeams.validName(""),
                "names are one word");
        BankTeam team = teams.create("Miners", owner);
        helper.assertTrue(teams.byName("miners").isPresent(), "names match ignoring case");
        helper.assertFalse(teams.invited(second, team.id()), "not invited yet");
        teams.invite(team.id(), second);
        teams.join(team.id(), second);
        helper.assertValueEqual(teams.teamOf(second).map(BankTeam::id), Optional.of(team.id()), "joined");
        helper.assertValueEqual(teams.teamOf(owner).orElseThrow().members(), Set.of(owner, second), "both are members");

        OwnTeams loaded = OwnTeams.fromTag(teams.save(new net.minecraft.nbt.CompoundTag(), helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        helper.assertValueEqual(loaded.teamOf(second), teams.teamOf(second), "saved with the world");

        helper.assertTrue(teams.remove(owner).isEmpty(), "the owner leaving doesn't end the team");
        helper.assertValueEqual(teams.teamOf(second).orElseThrow().owner(), second, "the next member becomes owner");
        BankTeam ended = teams.remove(second).orElseThrow();
        helper.assertValueEqual(ended.id(), team.id(), "the last member leaving ends it");
        helper.assertValueEqual(ended.owner(), second, "and they're the one paid its balance");
        helper.assertTrue(teams.byName("Miners").isEmpty(), "the team is gone");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void ftbTeamsProvidesTheTeamsWhenInstalled(GameTestHelper helper) {
        boolean installed = net.neoforged.fml.ModList.get().isLoaded(FtbTeamsBridge.MOD_ID);
        helper.assertValueEqual(FtbTeamsBridge.isAvailable(), installed, "its API matches 2101.1.11 when installed");
        helper.assertValueEqual(BankTeams.fromFtbTeams(), installed, "and then its parties are the teams");
        // A player in no party has only FTB Teams' personal team, which isn't a shared account.
        Fixture f = new Fixture(helper);
        helper.assertTrue(FtbTeamsBridge.teamOf(f.player.getUUID()).isEmpty(), "no party, no team");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void teamMembersShareOneAccount(GameTestHelper helper) {
        Fixture a = new Fixture(helper);
        Fixture b = new Fixture(helper);
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        String name = "t" + UUID.randomUUID().toString().substring(0, 8);
        List<String> created = runCommandAs(helper, a.player, "bank team create " + name);
        if (WorldBank.kind(server) != BankKind.OWN) {
            helper.assertValueEqual(created, List.of("command.dynamic_economy.team.numismatics"), "no teams with Numismatics' bank");
            helper.succeed();
            return;
        }
        if (BankTeams.fromFtbTeams()) {
            helper.assertValueEqual(created, List.of("command.dynamic_economy.team.ftb"), "FTB Teams parties are the teams");
            helper.succeed();
            return;
        }
        helper.assertValueEqual(created, List.of("command.dynamic_economy.team.created"), "created");
        helper.assertValueEqual(runCommandAs(helper, a.player, "bank team create other"), List.of("command.dynamic_economy.team.already_in"),
                "one team at a time");
        helper.assertValueEqual(runCommandAs(helper, b.player, "bank team join " + name), List.of("command.dynamic_economy.team.not_invited"),
                "joining needs an invitation");
        OwnTeams teams = OwnTeams.get(server);
        BankTeam team = teams.byName(name).orElseThrow();
        teams.invite(team.id(), b.player.getUUID());
        helper.assertValueEqual(runCommandAs(helper, b.player, "bank team join " + name), List.of("command.dynamic_economy.team.joined"), "joined");

        OwnBank bank = OwnBank.get(server);
        bank.teamAccount(team.id()).deposit(1000);
        a.setStock(Items.IRON_INGOT, 5);
        try {
            // A uses the team's account: a purchase is paid from it, and A's own money is untouched.
            bank.payFromTeam(a.player, true);
            helper.assertTrue(bank.payingFromTeam(a.player), "A pays from the team");
            TradeResult bought = MarketService.tryBuy(a.player, Items.IRON_INGOT, 1, ANY_PRICE);
            a.assertResult(bought, Result.OK);
            helper.assertValueEqual(bank.teamAccount(team.id()).balance(), 1000 - bought.total(), "the team paid");
            helper.assertValueEqual(a.balance(), 0, "not A");
            // B didn't choose it: B's withdrawal comes from B's own account, which is empty.
            b.assertRejectedUnchanged(BankService.Result.NO_FUNDS, () -> BankService.withdraw(b.player, 1));
            bank.payFromTeam(b.player, true);
            BankService.Outcome withdrawn = BankService.withdraw(b.player, 8);
            helper.assertTrue(withdrawn.ok(), "B can withdraw from the team once chosen: " + withdrawn);
        } finally {
            bank.payFromTeam(a.player, false);
            bank.payFromTeam(b.player, false);
        }

        long left = bank.teamAccount(team.id()).balance();
        helper.assertValueEqual(runCommandAs(helper, a.player, "bank team leave"), List.of("command.dynamic_economy.team.left"), "A leaves");
        helper.assertFalse(bank.payingFromTeam(a.player), "out of the team, A is back on their own account");
        helper.assertValueEqual(runCommandAs(helper, b.player, "bank team leave"), List.of("command.dynamic_economy.team.ended"),
                "B, the last member, ends it");
        helper.assertValueEqual((long) b.balance(), left, "and receives everything the team had");
        helper.assertFalse(bank.teamBalances().containsKey(team.id()), "nothing is left behind");
        helper.succeed();
    }

    /** The first translation key in a message: failures arrive wrapped in a red, empty component. */
    private static Optional<String> translationKey(Component message) {
        if (message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents key) {
            return Optional.of(key.getKey());
        }
        return message.getSiblings().stream().map(MarketGameTests::translationKey).flatMap(Optional::stream).findFirst();
    }

    /** Runs a command as {@code player}, returning its messages as translation keys, so tests don't depend on wording. */
    private static List<String> runCommandAs(GameTestHelper helper, ServerPlayer player, String command) {
        List<String> output = new ArrayList<>();
        net.minecraft.commands.CommandSource capture = new net.minecraft.commands.CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                output.add(translationKey(message).orElse(message.getString()));
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
            server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack().withSource(capture).withEntity(player));
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new GameTestAssertException("/" + command + " was rejected: " + e.getMessage());
        }
        return output;
    }

    // ---- Switching banks ----------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void drainingTakesWhatADisplayCapHides(GameTestHelper helper) {
        // A stand-in for a Numismatics account: it shows at most the cap but can pay out everything.
        long cap = NumismaticsBridge.DISPLAYED_BALANCE_CAP;
        for (long start : new long[] {0, 7, cap - 1, cap, cap + 1, Integer.MAX_VALUE, 5_000_000_000L}) {
            long[] total = {start};
            Bank.Account account = new Bank.Account() {
                @Override
                public long balance() {
                    return Math.min(total[0], cap);
                }

                @Override
                public void deposit(int amount) {
                    total[0] += amount;
                }

                @Override
                public boolean deduct(int amount) {
                    if (amount > total[0]) {
                        return false;
                    }
                    total[0] -= amount;
                    return true;
                }
            };
            long[] paid = {0};
            long moved = BankSwitch.drain(account, cap, amount -> paid[0] += amount);
            helper.assertValueEqual(moved, start, "all of " + start + " is taken");
            helper.assertValueEqual(paid[0], start, "and all of it is paid on");
            helper.assertValueEqual(total[0], 0L, "leaving nothing behind");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void switchingBanksMovesEveryBalanceAndBack(GameTestHelper helper) {
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        BankKind start = WorldBank.kind(server);
        if (NumismaticsBridge.bank().isEmpty()) {
            BankSwitch.Result refused = BankSwitch.plan(server, BankKind.NUMISMATICS);
            helper.assertTrue(refused.plan().isEmpty() && refused.problem().contains("Numismatics isn't installed"),
                    "no switch without Numismatics: " + refused.problem());
            helper.assertValueEqual(WorldBank.kind(server), start, "and the bank stays");
            helper.succeed();
            return;
        }
        helper.assertValueEqual(start, BankKind.NUMISMATICS, "the dev world uses Numismatics' bank");
        helper.assertTrue(BankSwitch.plan(server, BankKind.NUMISMATICS).plan().isEmpty(), "no switch to the bank in use");

        Fixture rich = new Fixture(helper);
        Fixture modest = new Fixture(helper);
        // More than Numismatics displays: part of it sits in its hidden overflow.
        rich.deposit(Integer.MAX_VALUE);
        rich.deposit(Integer.MAX_VALUE);
        modest.deposit(1234);
        long richTotal = 2L * Integer.MAX_VALUE;
        OwnBank own = OwnBank.get(server);
        UUID goneTeam = UUID.randomUUID();
        try {
            BankSwitch.Plan plan = BankSwitch.plan(server, BankKind.OWN).plan().orElseThrow();
            helper.assertValueEqual(plan.balances().get(modest.player.getUUID()), 1234L, "the plan lists each balance");
            helper.assertTrue(BankSwitch.switchTo(server, BankKind.OWN).plan().isPresent(), "switched");
            helper.assertValueEqual(WorldBank.kind(server), BankKind.OWN, "the world records the new bank");
            helper.assertValueEqual(own.account(rich.player.getUUID()).balance(), richTotal, "every bit moved, the hidden part too");
            helper.assertValueEqual(own.account(modest.player.getUUID()).balance(), 1234L, "each to the same player");
            helper.assertValueEqual(NumismaticsBridge.accountById(rich.player.getUUID()).orElseThrow().balance(), 0L,
                    "nothing left behind in Numismatics' bank");

            // A team account whose team is gone can't go to Numismatics' bank: it stays put rather than vanish.
            own.teamAccount(goneTeam).deposit(50);
            BankSwitch.Plan back = BankSwitch.plan(server, BankKind.NUMISMATICS).plan().orElseThrow();
            helper.assertValueEqual(back.lostTeams().get(goneTeam), 50L, "the lost team is reported");
        } finally {
            if (WorldBank.kind(server) != BankKind.NUMISMATICS) {
                helper.assertTrue(BankSwitch.switchTo(server, BankKind.NUMISMATICS).plan().isPresent(), "switched back");
            }
        }
        helper.assertValueEqual(WorldBank.kind(server), BankKind.NUMISMATICS, "back on Numismatics' bank");
        helper.assertValueEqual(own.teamBalances().get(goneTeam), 50L, "the lost team's balance waited in the mod's bank");
        helper.assertValueEqual(own.account(rich.player.getUUID()).balance(), 0L, "the players' balances moved back");
        Bank.Account richAccount = rich.account();
        helper.assertTrue(richAccount.deduct(Integer.MAX_VALUE) && richAccount.deduct(Integer.MAX_VALUE)
                && richAccount.balance() == 0, "all of it, overflow included");
        helper.assertValueEqual(modest.balance(), 1234, "to the same players");
        own.closeTeam(goneTeam, modest.player.getUUID());
        own.drain(modest.player.getUUID());
        modest.account().deduct(1234);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void bankAdminCommandShowsBeforeItSwitches(GameTestHelper helper) {
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        BankKind start = WorldBank.kind(server);
        List<String> info = runCommand(helper, "market admin bank");
        helper.assertTrue(info.get(0).startsWith("This world uses " + BankSwitch.describe(start)), "says which bank: " + info);
        BankKind other = start == BankKind.OWN ? BankKind.NUMISMATICS : BankKind.OWN;
        List<String> preview = runCommand(helper, "market admin bank switch " + other.id());
        if (NumismaticsBridge.bank().isPresent()) {
            helper.assertTrue(preview.get(0).startsWith("Switching from"), "a preview first: " + preview);
            helper.assertTrue(preview.stream().anyMatch(line -> line.startsWith("Coins don't move")), "with the coin warning");
        } else {
            helper.assertTrue(preview.get(0).contains("Numismatics isn't installed"), "nothing to switch to: " + preview);
        }
        helper.assertValueEqual(WorldBank.kind(server), start, "a preview changes nothing");
        helper.succeed();
    }

    // ---- Banks --------------------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void worldUsesTheBankItsModsSuggest(GameTestHelper helper) {
        // The dev world had a market before banks were recorded, so it uses Numismatics'; a fresh world without
        // Numismatics (the run without optional mods) uses ours.
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        BankKind expected = NumismaticsBridge.bank().isPresent() ? BankKind.NUMISMATICS : BankKind.OWN;
        helper.assertValueEqual(WorldBank.kind(server), expected, "the world's bank");
        helper.assertValueEqual(WorldBank.of(server).kind(), expected, "and the bank used is that one");
        helper.assertTrue(WorldBank.available(server), "and it can be reached");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void ownBankDeductsAllOrNothing(GameTestHelper helper) {
        OwnBank bank = new OwnBank();
        UUID owner = UUID.randomUUID();
        Bank.Account account = bank.account(owner);
        helper.assertValueEqual(account.balance(), 0L, "a new account is empty");
        helper.assertFalse(bank.playerBalances().containsKey(owner), "and not listed");
        account.deposit(100);
        helper.assertFalse(account.deduct(101), "can't deduct more than the balance");
        helper.assertValueEqual(account.balance(), 100L, "and a failed deduction changes nothing");
        helper.assertTrue(account.deduct(100), "can deduct exactly the balance");
        helper.assertValueEqual(account.balance(), 0L, "leaving nothing");
        helper.assertFalse(bank.playerBalances().containsKey(owner), "an emptied account is forgotten");
        helper.assertTrue(account.deduct(0), "deducting nothing always works");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void ownBankHoldsMoreThanAnInt(GameTestHelper helper) {
        OwnBank bank = new OwnBank();
        UUID owner = UUID.randomUUID();
        Bank.Account account = bank.account(owner);
        for (int i = 0; i < 3; i++) {
            account.deposit(Integer.MAX_VALUE);
        }
        long expected = 3L * Integer.MAX_VALUE;
        helper.assertValueEqual(account.balance(), expected, "balances are longs");
        helper.assertValueEqual(bank.displayCap(), Long.MAX_VALUE, "and all of it is shown");
        helper.assertTrue(account.deduct(Integer.MAX_VALUE), "spent a transaction at a time");
        helper.assertValueEqual(account.balance(), expected - Integer.MAX_VALUE, "exactly");

        // Saved and loaded back with the world.
        OwnBank loaded = OwnBank.fromTag(bank.save(new net.minecraft.nbt.CompoundTag(), helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        helper.assertValueEqual(loaded.account(owner).balance(), expected - Integer.MAX_VALUE, "the balance survives a save");
        helper.assertValueEqual(loaded.playerBalances(), bank.playerBalances(), "as does every account");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void ownBankSavesOnlyWhenChanged(GameTestHelper helper) {
        OwnBank bank = new OwnBank();
        Bank.Account account = bank.account(UUID.randomUUID());
        account.balance();
        account.deduct(5);
        helper.assertFalse(bank.isDirty(), "reading or a refused deduction changes nothing to save");
        account.deposit(5);
        helper.assertTrue(bank.isDirty(), "a deposit is saved");
        helper.succeed();
    }

    // ---- Contracts ----------------------------------------------------------------------------------------------

    /**
     * An open contract for {@code units} of a listed item, offering {@code unitReward} each before the cap, with its
     * promised reward worked out as a board would. Tests use boards of their own, never the world's.
     */
    private static ContractBoard.Contract openContract(GameTestHelper helper, ContractBoard board, Item item, int units,
                                                      BigDecimal unitReward, long deposit) {
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        CatalogEntry entry = MarketCatalog.INSTANCE.get(item).orElseThrow();
        PriceModel.Inputs inputs = PriceModel.inputs(entry);
        double factor = MarketService.currentFactor(entry, inputs, MarketStockData.get(server), MarketStockData.now(server));
        long reward = ContractPricing.payout(unitReward, ContractPricing.lowestUnitBuyPrice(entry, inputs, factor), units);
        return new ContractBoard.Contract(board.takeId(), id(item), units, unitReward, reward, deposit, 0, Optional.empty(),
                Optional.empty(), ContractBoard.Status.OPEN);
    }

    /** A board of its own holding these contracts, replaced in 5 days. */
    private static ContractBoard board(GameTestHelper helper, java.util.function.Function<ContractBoard, List<ContractBoard.Contract>> contracts) {
        ContractBoard board = new ContractBoard();
        board.observe(ContractBoard.worldDay(helper.getLevel().getServer()));
        board.replace(contracts.apply(board), 5);
        return board;
    }

    private static ContractBoard.Contract contract(ContractBoard board, int id) {
        return board.find(id).orElseThrow();
    }

    @GameTest(template = EMPTY)
    public static void onlyOnePlayerCanClaimAContract(GameTestHelper helper) {
        Fixture first = new Fixture(helper);
        Fixture second = new Fixture(helper);
        first.deposit(1000);
        second.deposit(1000);
        ContractBoard board = board(helper, b -> List.of(
                openContract(helper, b, Items.OAK_LOG, 64, new BigDecimal("2.5"), 32),
                openContract(helper, b, Items.COBBLESTONE, 64, new BigDecimal("1.25"), 16)));
        int logs = board.contracts().get(0).id();
        int cobble = board.contracts().get(1).id();

        ContractService.Outcome claimed = ContractService.claim(first.player, board, logs);
        helper.assertValueEqual(claimed.result(), Result.OK, "first claim");
        helper.assertValueEqual(claimed.amount(), 32L, "the deposit taken");
        helper.assertValueEqual(first.balance(), 1000 - 32, "first player's balance after the deposit");
        helper.assertValueEqual(contract(board, logs).holder(), Optional.of(first.player.getUUID()), "locked to the first player");

        helper.assertValueEqual(ContractService.claim(second.player, board, logs).result(), Result.CONTRACT_TAKEN, "second claim");
        helper.assertValueEqual(second.balance(), 1000, "a refused claim takes nothing");
        helper.assertValueEqual(ContractService.claim(first.player, board, logs).result(), Result.CONTRACT_TAKEN, "claiming it again");
        helper.assertValueEqual(first.balance(), 1000 - 32, "and that takes nothing either");
        helper.assertValueEqual(ContractService.deliver(second.player, board, logs, 1).result(), Result.NOT_YOUR_CONTRACT, "others can't deliver");
        helper.assertValueEqual(ContractService.abandon(second.player, board, logs).result(), Result.NOT_YOUR_CONTRACT, "or abandon it");

        // One active contract each by default.
        helper.assertValueEqual(ContractService.claim(first.player, board, cobble).result(), Result.CONTRACT_LIMIT, "over the limit");
        helper.assertValueEqual(first.balance(), 1000 - 32, "a refused claim takes nothing");
        helper.assertValueEqual(ContractService.claim(second.player, board, cobble).result(), Result.OK, "the other contract is still open");
        helper.assertValueEqual(ContractService.claim(first.player, board, 999_999).result(), Result.CONTRACT_EXPIRED, "an unknown contract");

        Fixture broke = new Fixture(helper);
        ContractBoard other = board(helper, b -> List.of(openContract(helper, b, Items.OAK_LOG, 64, new BigDecimal("2.5"), 32)));
        helper.assertValueEqual(ContractService.claim(broke.player, other, other.contracts().get(0).id()).result(), Result.NO_FUNDS,
                "no money for the deposit");
        helper.assertValueEqual(other.contracts().get(0).status(), ContractBoard.Status.OPEN, "and it stays open");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void partialAndFullDeliveryPayExactly(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.deposit(1000);
        // Oak logs: base 2, unlimited, markup 0.5, so the market never sells one for less than 3. 2.5 each is below that.
        ContractBoard board = board(helper, b -> List.of(openContract(helper, b, Items.OAK_LOG, 64, new BigDecimal("2.5"), 32)));
        int id = board.contracts().get(0).id();
        helper.assertValueEqual(board.contracts().get(0).reward(), 160L, "64 × 2.5");
        helper.assertValueEqual(ContractService.deliver(f.player, board, id, 1).result(), Result.NOT_YOUR_CONTRACT, "claim first");
        helper.assertValueEqual(ContractService.claim(f.player, board, id).result(), Result.OK, "claimed");

        helper.assertValueEqual(ContractService.deliver(f.player, board, id, 1).result(), Result.NOT_ENOUGH_ITEMS, "none held");
        f.player.getInventory().offhand.set(0, new ItemStack(Items.OAK_LOG, 64));
        ItemStack renamed = new ItemStack(Items.OAK_LOG, 64);
        renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Special"));
        f.give(renamed);
        helper.assertValueEqual(ContractService.deliver(f.player, board, id, 1).result(), Result.NOT_ENOUGH_ITEMS,
                "offhand and renamed copies don't count");
        f.giveMany(Items.OAK_LOG, 64);
        long stockBefore = f.stock(Items.OAK_LOG);

        ContractService.Outcome part = ContractService.deliver(f.player, board, id, 40);
        helper.assertValueEqual(part.result(), Result.OK, "first batch");
        helper.assertValueEqual(part.amount(), 0L, "a batch pays nothing until the last unit");
        helper.assertValueEqual(f.balance(), 1000 - 32, "balance after the first batch");
        helper.assertValueEqual(f.count(Items.OAK_LOG), 24L, "40 handed over");
        helper.assertValueEqual(contract(board, id).delivered(), 40, "counted");
        helper.assertValueEqual(ContractService.deliver(f.player, board, id, 25).result(), Result.CONTRACT_FULL, "more than is left");
        helper.assertValueEqual(ContractService.deliver(f.player, board, id, 0).result(), Result.INVALID_QUANTITY, "nothing");
        helper.assertValueEqual(f.count(Items.OAK_LOG), 24L, "refusals take nothing");

        ContractService.Outcome last = ContractService.deliver(f.player, board, id, 24);
        helper.assertValueEqual(last.result(), Result.OK, "last batch");
        helper.assertValueEqual(last.amount(), 160L + 32, "the reward plus the deposit back");
        helper.assertValueEqual(f.balance(), 1000 + 160, "net: exactly the reward");
        helper.assertValueEqual(f.count(Items.OAK_LOG), 0L, "every plain log in the main inventory went");
        helper.assertTrue(f.player.getInventory().offhand.get(0).getCount() == 64, "the offhand is untouched");
        helper.assertValueEqual(f.stock(Items.OAK_LOG), stockBefore, "delivered items don't join market stock");
        helper.assertValueEqual(contract(board, id).status(), ContractBoard.Status.COMPLETED, "done");
        helper.assertValueEqual(board.rewardsPaid(), 160L, "counted as paid out");
        helper.assertValueEqual(ContractService.deliver(f.player, board, id, 1).result(), Result.CONTRACT_TAKEN, "nothing more to deliver");
        helper.assertValueEqual(ContractService.abandon(f.player, board, id).result(), Result.CONTRACT_TAKEN, "or abandon");
        f.player.getInventory().offhand.set(0, ItemStack.EMPTY);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void abandoningOrExpiryPaysForDeliveredUnitsAndKeepsTheDeposit(GameTestHelper helper) {
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        Fixture quitter = new Fixture(helper);
        quitter.deposit(1000);
        ContractBoard board = board(helper, b -> List.of(openContract(helper, b, Items.OAK_LOG, 64, new BigDecimal("2.5"), 32)));
        int id = board.contracts().get(0).id();
        ContractService.claim(quitter.player, board, id);
        quitter.giveMany(Items.OAK_LOG, 10);
        ContractService.deliver(quitter.player, board, id, 10);
        ContractService.Outcome gaveUp = ContractService.abandon(quitter.player, board, id);
        helper.assertValueEqual(gaveUp.result(), Result.OK, "abandoned");
        helper.assertValueEqual(gaveUp.amount(), 25L, "10 × 2.5");
        helper.assertValueEqual(quitter.balance(), 1000 - 32 + 25, "paid for 10; the deposit is lost");
        helper.assertValueEqual(contract(board, id).status(), ContractBoard.Status.ABANDONED, "closed");
        helper.assertValueEqual(board.depositsLost(), 32L, "the deposit counted as kept");
        helper.assertValueEqual(ContractService.claim(quitter.player, board, id).result(), Result.CONTRACT_TAKEN, "can't be claimed again");

        Fixture late = new Fixture(helper);
        late.deposit(1000);
        ContractBoard expiring = board(helper, b -> List.of(openContract(helper, b, Items.OAK_LOG, 64, new BigDecimal("2.5"), 32)));
        int lateId = expiring.contracts().get(0).id();
        ContractService.claim(late.player, expiring, lateId);
        late.giveMany(Items.OAK_LOG, 10);
        ContractService.deliver(late.player, expiring, lateId, 10);
        expiring.observe(ContractBoard.worldDay(server) + 5);
        helper.assertTrue(expiring.due(), "five days later the board is due");
        ContractService.refresh(server, expiring, net.minecraft.util.RandomSource.create(7));
        helper.assertValueEqual(late.balance(), 1000 - 32 + 25, "paid for 10 at expiry, offline or not; the deposit is lost");
        List<ContractBoard.Notice> notices = expiring.takeNotices(late.player.getUUID());
        helper.assertValueEqual(notices, List.of(new ContractBoard.Notice(late.player.getUUID(), id(Items.OAK_LOG), 10, 64, 25, 32)),
                "an offline holder is told when they next log in");
        helper.assertValueEqual(expiring.takeNotices(late.player.getUUID()), List.of(), "once");
        helper.assertTrue(expiring.find(lateId).isEmpty(), "the old contract is gone");
        helper.assertValueEqual(ContractService.deliver(late.player, expiring, lateId, 1).result(), Result.CONTRACT_EXPIRED, "and can't be delivered to");
        helper.assertValueEqual(expiring.claimedBy(late.player.getUUID()), 0, "nothing held on the new board");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void boardExpiresOnTheRightDayAfterSleepingAndARestart(GameTestHelper helper) {
        // The day count, fed by hand: sleeping jumps days, setting the time back doesn't count.
        ContractBoard board = new ContractBoard();
        board.observe(10);
        board.replace(List.of(), 5);
        helper.assertValueEqual(board.expiresDay(), 15L, "boards turn over at multiples of 5");
        helper.assertValueEqual(board.daysLeft(), 5L, "5 days left");
        board.observe(12);
        helper.assertValueEqual(board.day(), 12L, "a night slept through counts");
        board.observe(3);
        helper.assertValueEqual(board.day(), 12L, "setting the time back doesn't");
        board.observe(4);
        helper.assertValueEqual(board.day(), 13L, "days after that count again");
        helper.assertFalse(board.due(), "not due on day 13");
        ContractBoard restarted = ContractBoard.fromTag(board.save(new CompoundTag(), helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        helper.assertValueEqual(restarted.day(), 13L, "the day count survives a restart");
        helper.assertValueEqual(restarted.expiresDay(), 15L, "as does the expiry");
        restarted.observe(5);
        helper.assertFalse(restarted.due(), "day 14");
        restarted.observe(6);
        helper.assertTrue(restarted.due(), "due on day 15");

        // The same with the world's clock, as sleeping moves it; put back before the tick ends.
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        net.minecraft.server.level.ServerLevel overworld = server.overworld();
        long time = overworld.getDayTime();
        Fixture f = new Fixture(helper);
        f.deposit(1000);
        ContractBoard live = board(helper, b -> List.of(
                openContract(helper, b, Items.OAK_LOG, 64, new BigDecimal("2.5"), 32),
                openContract(helper, b, Items.COBBLESTONE, 64, new BigDecimal("1.25"), 16)));
        long left = live.daysLeft();
        try {
            overworld.setDayTime(time + (left - 1) * 24000L);
            helper.assertValueEqual(ContractService.claim(f.player, live, live.contracts().get(0).id()).result(), Result.OK,
                    "the day before expiry");
            overworld.setDayTime(time + left * 24000L);
            helper.assertValueEqual(ContractService.claim(f.player, live, live.contracts().get(1).id()).result(), Result.CONTRACT_EXPIRED,
                    "on the expiry day, even before the board is replaced");
            helper.assertValueEqual(ContractService.abandon(f.player, live, live.contracts().get(0).id()).result(), Result.CONTRACT_EXPIRED,
                    "the held one too");
        } finally {
            overworld.setDayTime(time);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void buyingFromTheMarketToFillAContractNeverProfits(GameTestHelper helper) {
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        // Unlimited: logs never cost less than 3 each. A contract asking 11 × base each is capped just below.
        Fixture logs = new Fixture(helper);
        logs.deposit(10_000);
        ContractBoard logBoard = board(helper, b -> List.of(openContract(helper, b, Items.OAK_LOG, 64, new BigDecimal(22), 10)));
        int logId = logBoard.contracts().get(0).id();
        ContractService.claim(logs.player, logBoard, logId);
        logs.assertResult(MarketService.tryBuy(logs.player, Items.OAK_LOG, 64, ANY_PRICE), Result.OK);
        helper.assertValueEqual(ContractService.deliver(logs.player, logBoard, logId, 64).result(), Result.OK, "logs delivered");
        helper.assertTrue(logs.balance() < 10_000, "buying logs to fill a contract lost money: " + logs.balance());

        // Finite and dynamic: iron at its price floor, the cheapest it can ever be bought.
        Fixture iron = new Fixture(helper);
        iron.deposit(100_000);
        long stock = iron.stock(Items.IRON_INGOT);
        CatalogEntry ironEntry = MarketCatalog.INSTANCE.get(Items.IRON_INGOT).orElseThrow();
        iron.setStock(Items.IRON_INGOT, 1000);
        iron.market.setFactor(id(Items.IRON_INGOT), PriceModel.inputs(ironEntry).floor(), MarketStockData.now(server));
        try {
            ContractBoard ironBoard = board(helper, b -> List.of(openContract(helper, b, Items.IRON_INGOT, 50, new BigDecimal(176), 10)));
            int ironId = ironBoard.contracts().get(0).id();
            ContractService.claim(iron.player, ironBoard, ironId);
            iron.assertResult(MarketService.tryBuy(iron.player, Items.IRON_INGOT, 50, ANY_PRICE), Result.OK);
            helper.assertValueEqual(ContractService.deliver(iron.player, ironBoard, ironId, 50).result(), Result.OK, "iron delivered");
            helper.assertTrue(iron.balance() < 100_000, "buying iron at its floor to fill a contract lost money: " + iron.balance());
        } finally {
            iron.setStock(Items.IRON_INGOT, stock);
            iron.market.resetAllFactors();
        }

        // A fixed price: the cap sits one bit under the market's price.
        withFixedPrice(Items.IRON_INGOT, () -> {
            Fixture fixed = new Fixture(helper);
            fixed.deposit(100_000);
            fixed.setStock(Items.IRON_INGOT, 1000);
            try {
                ContractBoard fixedBoard = board(helper, b -> List.of(openContract(helper, b, Items.IRON_INGOT, 50, new BigDecimal(176), 10)));
                int fixedId = fixedBoard.contracts().get(0).id();
                ContractService.claim(fixed.player, fixedBoard, fixedId);
                fixed.assertResult(MarketService.tryBuy(fixed.player, Items.IRON_INGOT, 50, ANY_PRICE), Result.OK);
                helper.assertValueEqual(ContractService.deliver(fixed.player, fixedBoard, fixedId, 50).result(), Result.OK, "delivered");
                helper.assertValueEqual(fixed.balance(), 100_000 - 1, "one bit short of breaking even");
            } finally {
                fixed.setStock(Items.IRON_INGOT, stock);
            }
        });

        // Every shape of item, every factor it can trade at, every order size: the payout is below the purchase.
        net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(15);
        for (int i = 0; i < 400; i++) {
            boolean unlimited = random.nextBoolean();
            boolean dynamic = random.nextInt(4) != 0;
            double markup = random.nextInt(300) / 100.0;
            Optional<Double> minBuyMarkup = unlimited && markup > 0 && random.nextBoolean()
                    ? Optional.of(Math.max(0.01, markup * random.nextDouble())) : Optional.empty();
            double floor = 0.01 + random.nextDouble() * 0.99;
            double ceiling = 1 + random.nextDouble() * 9;
            int base = 1 + random.nextInt(random.nextBoolean() ? 10 : 5000);
            CatalogEntry entry = new CatalogEntry(Items.IRON_INGOT, id(Items.IRON_INGOT), base, markup, 0, true, true, unlimited,
                    0.0, dynamic, minBuyMarkup, new CatalogEntry.Overrides(Optional.of(0.003), Optional.of(0.003), Optional.empty(),
                    Optional.of(floor), Optional.of(ceiling)), Optional.empty());
            PriceModel.Inputs in = PriceModel.inputs(entry);
            double stored = floor + random.nextDouble() * (ceiling - floor);
            double factor = PriceModel.effectiveFactor(in, stored);
            Optional<BigDecimal> lowest = ContractPricing.lowestUnitBuyPrice(entry, in, factor);
            BigDecimal greedy = BigDecimal.valueOf(base).multiply(BigDecimal.valueOf(100));
            for (int units : List.of(1, 2, 7, 64, 333)) {
                long paid = ContractPricing.payout(greedy, lowest, units);
                long cost = PriceModel.buy(in, factor, units).total();
                if (paid >= cost) {
                    helper.fail("contract pays %d for %d units that cost %d to buy (%s, factor %s)".formatted(paid, units, cost, in, factor));
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void boardsOfferOnlyItemsWorthAContract(GameTestHelper helper) {
        CatalogEntry logs = MarketCatalog.INSTANCE.get(Items.OAK_LOG).orElseThrow();
        CatalogEntry cobble = MarketCatalog.INSTANCE.get(Items.COBBLESTONE).orElseThrow()
                .withContract(new CatalogEntry.ContractTerms(Optional.of(0), Optional.empty(), Optional.empty(), Optional.empty()));
        CatalogEntry star = MarketCatalog.INSTANCE.get(Items.NETHER_STAR).orElseThrow();
        CatalogEntry iron = MarketCatalog.INSTANCE.get(Items.IRON_INGOT).orElseThrow();
        CatalogEntry diamond = new CatalogEntry(Items.DIAMOND, id(Items.DIAMOND), 256, 0.5, 0, true, false, false, 0.0, true,
                Optional.empty(), CatalogEntry.Overrides.NONE, Optional.empty())
                .withContract(new CatalogEntry.ContractTerms(Optional.of(5), Optional.of(4), Optional.of(16), Optional.of(0.5)));
        List<CatalogEntry> entries = List.of(logs, cobble, star, iron, diamond);
        MarketStockData market = MarketStockData.get(helper.getLevel().getServer());
        market.resetAllFactors();
        long now = MarketStockData.now(helper.getLevel().getServer());

        java.util.Set<ResourceLocation> seen = new java.util.HashSet<>();
        int[] ids = {1};
        for (int seed = 0; seed < 40; seed++) {
            List<ContractBoard.Contract> picked = ContractService.generate(entries, market, now,
                    net.minecraft.util.RandomSource.create(seed), 8, 0.2, () -> ids[0]++);
            helper.assertValueEqual(picked.stream().map(ContractBoard.Contract::item).distinct().count(), (long) picked.size(),
                    "no item twice on one board");
            for (ContractBoard.Contract c : picked) {
                seen.add(c.item());
                CatalogEntry entry = entries.stream().filter(e -> e.itemId().equals(c.item())).findFirst().orElseThrow();
                PriceModel.Inputs in = PriceModel.inputs(entry);
                helper.assertTrue(c.reward() > PriceModel.sell(in, 1.0, c.units()).total(), "pays more than selling: " + c);
                helper.assertValueEqual(c.deposit(), ContractPricing.deposit(c.reward(), 0.2), "deposit is a fifth of the reward");
                helper.assertTrue(c.reward() + c.deposit() <= Integer.MAX_VALUE, "fits one payment");
                helper.assertTrue(c.units() >= entry.contract().effectiveMinSize() && c.units() <= entry.contract().effectiveMaxSize(),
                        "size within the item's range: " + c);
                helper.assertValueEqual(c.status(), ContractBoard.Status.OPEN, "starts open");
            }
        }
        helper.assertValueEqual(seen, java.util.Set.of(id(Items.OAK_LOG), id(Items.DIAMOND)),
                "only logs (unlimited) and the diamond the market doesn't sell are worth a contract");
        helper.assertTrue(ContractPricing.whyNotOffered(cobble, PriceModel.inputs(cobble), 1.0, 0.2).orElse("").contains("contract_weight is 0"),
                "weight 0 is the reason given");
        helper.assertTrue(ContractPricing.whyNotOffered(star, PriceModel.inputs(star), 1.0, 0.2).orElse("").contains("can_sell"),
                "so is can_sell false");
        helper.assertTrue(ContractPricing.whyNotOffered(iron, PriceModel.inputs(iron), 1.0, 0.2).orElse("").contains("as little as"),
                "and a buy price that can fall too low");

        CatalogEntry unique = loadOne("""
                { "entries": [ { "item": "minecraft:nether_star", "base_price": 8192, "class": "unique" } ] }""")
                .entries().get(Items.NETHER_STAR);
        helper.assertValueEqual(unique.contract().effectiveWeight(), 0, "the unique class is kept off the board");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void lockedItemsContractsAreHiddenButAHeldOneStaysDeliverable(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.deposit(1000);
        ContractBoard board = board(helper, b -> List.of(
                openContract(helper, b, Items.OAK_LOG, 64, new BigDecimal("2.5"), 32),
                openContract(helper, b, Items.COBBLESTONE, 64, new BigDecimal("1.25"), 16)));
        int logs = board.contracts().get(0).id();
        MarketVisibility.setTestLocks((player, item) -> player == f.player && item == Items.OAK_LOG);
        try {
            helper.assertValueEqual(ContractService.view(f.player, board).stream().map(v -> v.contract().item()).toList(),
                    List.of(id(Items.COBBLESTONE)), "the locked item's contract is hidden");
            helper.assertValueEqual(ContractService.claim(f.player, board, logs).result(), Result.NOT_LISTED, "and can't be claimed");
            helper.assertValueEqual(f.balance(), 1000, "nothing taken");
        } finally {
            MarketVisibility.setTestLocks(null);
        }
        helper.assertValueEqual(ContractService.claim(f.player, board, logs).result(), Result.OK, "claimed while unlocked");
        f.giveMany(Items.OAK_LOG, 64);
        MarketVisibility.setTestLocks((player, item) -> player == f.player && item == Items.OAK_LOG);
        try {
            helper.assertTrue(ContractService.view(f.player, board).stream().anyMatch(v -> v.contract().id() == logs && v.mine()),
                    "a held contract stays in view after the stage is lost");
            helper.assertValueEqual(ContractService.deliver(f.player, board, logs, 64).result(), Result.OK, "and can be delivered");
        } finally {
            MarketVisibility.setTestLocks(null);
        }
        helper.assertValueEqual(f.balance(), 1000 + 160, "paid in full");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void contractSettingsLoadFromClassesAndEntries(GameTestHelper helper) {
        for (String field : List.of("contract_weight", "contract_min_size", "contract_max_size", "contract_premium")) {
            helper.assertTrue(ItemClasses.FIELDS.contains(field), "classes may set " + field);
        }
        MarketCatalog.Loaded loaded = loadOne("""
                { "entries": [
                  { "item": "minecraft:oak_log", "base_price": 2, "contract_weight": 3, "contract_min_size": 8,
                    "contract_max_size": 32, "contract_premium": 0.5 },
                  { "item": "minecraft:cobblestone", "base_price": 1, "contract_min_size": 9, "contract_max_size": 8 },
                  { "item": "minecraft:diamond", "base_price": 1, "contract_weight": -1 },
                  { "item": "minecraft:iron_ingot", "base_price": 16, "class": "farmable" }
                ] }""");
        CatalogEntry logs = loaded.entries().get(Items.OAK_LOG);
        helper.assertValueEqual(logs.contract(), new CatalogEntry.ContractTerms(Optional.of(3), Optional.of(8), Optional.of(32),
                Optional.of(0.5)), "entry fields");
        helper.assertValueEqual(ContractPricing.unitReward(logs), new BigDecimal("3.0"), "2 × 1.5 per unit");
        helper.assertFalse(loaded.entries().containsKey(Items.COBBLESTONE), "min above max is skipped");
        helper.assertFalse(loaded.entries().containsKey(Items.DIAMOND), "a negative weight is skipped");
        helper.assertTrue(loaded.problems().stream().anyMatch(p -> p.message().contains("contract_min_size")), "and reported");
        CatalogEntry iron = loaded.entries().get(Items.IRON_INGOT);
        helper.assertValueEqual(iron.contract().effectiveMinSize(), 64, "the class's size");
        helper.assertValueEqual(iron.contract().effectiveMaxSize(), 512, "both ends");
        helper.assertValueEqual(iron.contract().effectivePremium(), MarketConfig.contractPremium(), "and the config's premium");
        JsonObject json = dynamiceconomy.catalog.CatalogJson.toJson(logs);
        helper.assertValueEqual(json.get("contract_premium").getAsDouble(), 0.5, "written back out");
        helper.assertValueEqual(MarketCatalog.decode(json).result().map(CatalogEntry::contract), Optional.of(logs.contract()),
                "and read back the same");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void contractBoardSurvivesASave(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.deposit(1000);
        ContractBoard board = board(helper, b -> List.of(
                openContract(helper, b, Items.OAK_LOG, 64, new BigDecimal("2.5"), 32),
                openContract(helper, b, Items.COBBLESTONE, 48, new BigDecimal("1.25"), 12)));
        ContractService.claim(f.player, board, board.contracts().get(0).id());
        f.giveMany(Items.OAK_LOG, 5);
        ContractService.deliver(f.player, board, board.contracts().get(0).id(), 5);
        board.addNotice(new ContractBoard.Notice(f.player.getUUID(), id(Items.IRON_INGOT), 3, 10, 30, 6));
        board.owe(new ContractBoard.Payout(f.player.getUUID(), Optional.of(UUID.randomUUID()), 77));
        ContractBoard loaded = ContractBoard.fromTag(board.save(new CompoundTag(), helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        helper.assertValueEqual(loaded.contracts(), board.contracts(), "contracts, holders and progress");
        helper.assertValueEqual(loaded.owed(), board.owed(), "money owed");
        helper.assertValueEqual(loaded.takeNotices(f.player.getUUID()), board.takeNotices(f.player.getUUID()), "notices");
        helper.assertTrue(loaded.takeId() > board.contracts().get(1).id(), "IDs never repeat");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void contractMoneyGoesBackToTheTeamThatPaidTheDeposit(GameTestHelper helper) {
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        if (WorldBank.kind(server) != BankKind.OWN || BankTeams.fromFtbTeams()) {
            // Team accounts need the mod's own bank and its own teams: the run without Numismatics and FTB Teams.
            DynamicEconomy.LOGGER.info("GameTest skipped: it needs the mod's own bank and teams");
            helper.succeed();
            return;
        }
        Fixture f = new Fixture(helper);
        UUID player = f.player.getUUID();
        OwnTeams teams = OwnTeams.get(server);
        BankTeam team = teams.create("c" + UUID.randomUUID().toString().substring(0, 8), player);
        OwnBank bank = OwnBank.get(server);
        bank.teamAccount(team.id()).deposit(1000);
        bank.payFromTeam(f.player, true);
        try {
            ContractBoard board = board(helper, b -> List.of(
                    openContract(helper, b, Items.OAK_LOG, 64, new BigDecimal("2.5"), 32),
                    openContract(helper, b, Items.COBBLESTONE, 64, new BigDecimal("1.25"), 16)));
            int logs = board.contracts().get(0).id();
            helper.assertValueEqual(ContractService.claim(f.player, board, logs).result(), Result.OK, "claimed with the team's money");
            helper.assertValueEqual(bank.teamAccount(team.id()).balance(), 1000L - 32, "the team paid the deposit");
            f.giveMany(Items.OAK_LOG, 64);
            ContractService.deliver(f.player, board, logs, 64);
            helper.assertValueEqual(bank.teamAccount(team.id()).balance(), 1000L + 160, "the reward and deposit went to the team");
            helper.assertValueEqual(f.balance(), 0, "not to the player");

            int cobble = board.contracts().get(1).id();
            ContractService.claim(f.player, board, cobble);
            f.giveMany(Items.COBBLESTONE, 10);
            ContractService.deliver(f.player, board, cobble, 10);
            bank.payFromTeam(f.player, false);
            helper.assertTrue(teams.remove(player).isPresent(), "the team ends");
            helper.assertValueEqual(ContractService.abandon(f.player, board, cobble).amount(), 12L, "10 × 1.25");
            helper.assertValueEqual(f.balance(), 12, "with the team gone, the player is paid");
        } finally {
            bank.payFromTeam(f.player, false);
            teams.remove(player);
            bank.closeTeam(team.id(), player);
            bank.drain(player);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void builtInPricesPutUnlimitedAndUnbuyableItemsOnTheBoardButNoUniqueOnes(GameTestHelper helper) {
        java.util.Map<String, Integer> offered = new java.util.TreeMap<>();
        int total = 0;
        for (CatalogEntry entry : defaultPrices().entries().values()) {
            PriceModel.Inputs in = PriceModel.inputs(entry);
            if (ContractPricing.whyNotOffered(entry, in, 1.0, MarketConfig.depositFraction()).isPresent()) {
                continue;
            }
            String itemClass = entry.itemClass().map(ItemClasses::shortName).orElse("none");
            helper.assertFalse(itemClass.equals("unique"), "unique items are never offered: " + entry.itemId());
            helper.assertTrue(entry.unlimitedStock() || !entry.canBuy() || !entry.dynamic(),
                    "only items whose buy price can't fall far are worth a contract: " + entry.itemId());
            offered.merge(itemClass, 1, Integer::sum);
            total++;
        }
        DynamicEconomy.LOGGER.info("Built-in prices loaded here: {} items can appear on a contract board, by class {}", total, offered);
        helper.assertTrue(total > 0, "some built-in items can be offered");
        helper.succeed();
    }

    /** Ends a test that needs {@code mod} as passed when it isn't installed, noting it in the log. */
    private static boolean skippedWithout(GameTestHelper helper, String mod) {
        if (net.neoforged.fml.ModList.get().isLoaded(mod)) {
            return false;
        }
        DynamicEconomy.LOGGER.info("GameTest skipped: it needs {}, which isn't installed", mod);
        helper.succeed();
        return true;
    }

    // ---- Exchange -----------------------------------------------------------------------------------------------

    /**
     * Runs {@code body} with the exchange on, a 5% tax, 10 open orders each, no expiry and orders of up to 6,400, then
     * puts the config back. Tests use exchanges of their own ({@code new ExchangeData()}), never the world's.
     */
    private static void withExchange(Runnable body) {
        boolean enabled = MarketConfig.EXCHANGE_ENABLED.get();
        double tax = MarketConfig.EXCHANGE_TAX.get();
        int orders = MarketConfig.MAX_OPEN_ORDERS.get();
        int expiry = MarketConfig.ORDER_EXPIRY_DAYS.get();
        int size = MarketConfig.MAX_ORDER_SIZE.get();
        MarketConfig.EXCHANGE_ENABLED.set(true);
        MarketConfig.EXCHANGE_TAX.set(0.05);
        MarketConfig.MAX_OPEN_ORDERS.set(10);
        MarketConfig.ORDER_EXPIRY_DAYS.set(0);
        MarketConfig.MAX_ORDER_SIZE.set(6400);
        try {
            body.run();
        } finally {
            MarketConfig.EXCHANGE_ENABLED.set(enabled);
            MarketConfig.EXCHANGE_TAX.set(tax);
            MarketConfig.MAX_OPEN_ORDERS.set(orders);
            MarketConfig.ORDER_EXPIRY_DAYS.set(expiry);
            MarketConfig.MAX_ORDER_SIZE.set(size);
        }
    }

    private static ExchangeService.Outcome post(Fixture f, ExchangeData data, Item item, boolean buy, long price, int qty) {
        return ExchangeService.post(f.player, data, item, buy, price, qty, 0);
    }

    private static ExchangeService.Outcome posted(GameTestHelper helper, Fixture f, ExchangeData data, Item item, boolean buy, long price, int qty) {
        ExchangeService.Outcome outcome = post(f, data, item, buy, price, qty);
        helper.assertValueEqual(outcome.result(), Result.OK, "posting " + (buy ? "buy " : "sell ") + qty + " × " + id(item) + " at " + price);
        return outcome;
    }

    private static long boxed(ExchangeData data, Fixture f, Item item) {
        return data.box(f.player.getUUID()).getOrDefault(id(item), 0L);
    }

    @GameTest(template = EMPTY)
    public static void exchangeTradesAtTheWaitingOrdersPriceLessTax(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            Fixture seller = new Fixture(helper);
            Fixture buyer = new Fixture(helper);
            seller.give(new ItemStack(Items.COBBLESTONE, 10));
            buyer.deposit(100);

            ExchangeService.Outcome ask = posted(helper, seller, data, Items.COBBLESTONE, false, 5, 10);
            helper.assertValueEqual(ask.filled(), 0, "nothing to trade with yet");
            helper.assertValueEqual(seller.count(Items.COBBLESTONE), 0L, "the order holds the items");
            helper.assertValueEqual(data.find(ask.orderId()).orElseThrow().remaining(), 10, "the order waits on the book");

            ExchangeService.Outcome bid = posted(helper, buyer, data, Items.COBBLESTONE, true, 7, 4);
            helper.assertValueEqual(bid.filled(), 4, "all four traded at once");
            helper.assertValueEqual(bid.amount(), 20L, "at the waiting seller's price, 5 each");
            helper.assertValueEqual(bid.orderId(), 0L, "nothing left to wait");
            helper.assertValueEqual(buyer.balance(), 80, "the buyer paid 20; the 8 bid above the ask came straight back");
            helper.assertValueEqual(boxed(data, buyer, Items.COBBLESTONE), 4L, "bought units wait in the box");
            helper.assertValueEqual(buyer.count(Items.COBBLESTONE), 0L, "not in the inventory until collected");
            helper.assertValueEqual(seller.balance(), 19, "the seller got 20 less the 5% tax, rounded up to 1");
            helper.assertValueEqual(data.find(ask.orderId()).orElseThrow().remaining(), 6, "six still for sale");
            helper.assertValueEqual(data.taxCollected(), 1L, "tax destroyed");
            helper.assertValueEqual(data.volume(), 20L, "value traded");
            // Test players aren't online, so the seller is told at their next login.
            helper.assertValueEqual(data.takeNotices(seller.player.getUUID()),
                    List.of(new ExchangeData.Notice(seller.player.getUUID(), id(Items.COBBLESTONE), ExchangeData.NoticeKind.SOLD, 4, 19)),
                    "the seller's notice");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void exchangeFillsBestPriceFirstThenOldestFirst(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            Fixture dear = new Fixture(helper);
            Fixture early = new Fixture(helper);
            Fixture late = new Fixture(helper);
            Fixture buyer = new Fixture(helper);
            for (Fixture seller : List.of(dear, early, late)) {
                seller.give(new ItemStack(Items.COBBLESTONE, 5));
            }
            buyer.deposit(1000);
            long dearOrder = posted(helper, dear, data, Items.COBBLESTONE, false, 6, 5).orderId();
            long earlyOrder = posted(helper, early, data, Items.COBBLESTONE, false, 5, 5).orderId();
            long lateOrder = posted(helper, late, data, Items.COBBLESTONE, false, 5, 5).orderId();

            // 12 units: 5 from each seller at 5 (the earlier first), then 2 at 6.
            ExchangeService.Outcome bid = posted(helper, buyer, data, Items.COBBLESTONE, true, 6, 12);
            helper.assertValueEqual(bid.filled(), 12, "all twelve traded");
            helper.assertValueEqual(bid.amount(), 62L, "25 + 25 + 12");
            helper.assertValueEqual(buyer.balance(), 1000 - 62, "the buyer paid what they traded at, not their bid");
            helper.assertValueEqual(early.balance(), 23, "25 less a tax of 1.25, rounded up to 2");
            helper.assertValueEqual(late.balance(), 23, "the same");
            helper.assertValueEqual(dear.balance(), 11, "12 less a tax of 0.6, rounded up to 1");
            helper.assertTrue(data.find(earlyOrder).isEmpty() && data.find(lateOrder).isEmpty(), "both cheaper orders are gone");
            helper.assertValueEqual(data.find(dearOrder).orElseThrow().remaining(), 3, "three left at 6");
            helper.assertValueEqual(boxed(data, buyer, Items.COBBLESTONE), 12L, "the buyer's box");

            // Time priority on its own: two bids at one price, a seller fills the older first.
            Fixture first = new Fixture(helper);
            Fixture second = new Fixture(helper);
            Fixture seller = new Fixture(helper);
            first.deposit(100);
            second.deposit(100);
            seller.give(new ItemStack(Items.OAK_LOG, 3));
            posted(helper, first, data, Items.OAK_LOG, true, 4, 2);
            long secondOrder = posted(helper, second, data, Items.OAK_LOG, true, 4, 2).orderId();
            posted(helper, seller, data, Items.OAK_LOG, false, 4, 3);
            helper.assertValueEqual(boxed(data, first, Items.OAK_LOG), 2L, "the older bid filled completely");
            helper.assertValueEqual(boxed(data, second, Items.OAK_LOG), 1L, "the newer one got the last unit");
            helper.assertValueEqual(data.find(secondOrder).orElseThrow().remaining(), 1, "and still wants one");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void anIncomingSellOrderGetsTheHigherWaitingBid(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            Fixture buyer = new Fixture(helper);
            Fixture seller = new Fixture(helper);
            buyer.deposit(100);
            seller.give(new ItemStack(Items.COBBLESTONE, 10));
            long bidOrder = posted(helper, buyer, data, Items.COBBLESTONE, true, 8, 10).orderId();
            helper.assertValueEqual(buyer.balance(), 20, "the bid holds 80");

            ExchangeService.Outcome ask = posted(helper, seller, data, Items.COBBLESTONE, false, 5, 10);
            helper.assertValueEqual(ask.filled(), 10, "it traded at once");
            helper.assertValueEqual(ask.amount(), 76L, "at the bid's 8 each: 80 less 4 tax");
            helper.assertValueEqual(seller.balance(), 76, "paid into the seller's bank");
            helper.assertValueEqual(buyer.balance(), 20, "the buyer's held money paid for it");
            helper.assertValueEqual(boxed(data, buyer, Items.COBBLESTONE), 10L, "the buyer's box");
            helper.assertTrue(data.find(bidOrder).isEmpty(), "the bid is filled");
            helper.assertTrue(data.orders().isEmpty(), "nothing waits");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void cancellingReturnsExactlyWhatTheOrderHolds(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            Fixture buyer = new Fixture(helper);
            Fixture seller = new Fixture(helper);
            Fixture other = new Fixture(helper);
            buyer.deposit(100);
            seller.give(new ItemStack(Items.COBBLESTONE, 14));
            long bidOrder = posted(helper, buyer, data, Items.COBBLESTONE, true, 5, 10).orderId();
            posted(helper, seller, data, Items.COBBLESTONE, false, 5, 4);
            helper.assertValueEqual(buyer.balance(), 50, "the bid held 50; 20 of it paid for four");

            helper.assertValueEqual(ExchangeService.cancel(other.player, data, bidOrder).result(), Result.NOT_YOUR_ORDER, "someone else's order");
            ExchangeService.Outcome cancelled = ExchangeService.cancel(buyer.player, data, bidOrder);
            helper.assertValueEqual(cancelled.result(), Result.OK, "cancelling");
            helper.assertValueEqual(cancelled.amount(), 30L, "the 6 units still wanted held 30");
            helper.assertValueEqual(buyer.balance(), 80, "100 less the 20 the four cost");
            helper.assertValueEqual(ExchangeService.cancel(buyer.player, data, bidOrder).result(), Result.ORDER_GONE, "cancelling twice");
            helper.assertValueEqual(buyer.balance(), 80, "the second cancel returned nothing");

            long askOrder = posted(helper, seller, data, Items.COBBLESTONE, false, 9, 10).orderId();
            helper.assertValueEqual(seller.count(Items.COBBLESTONE), 0L, "all ten held");
            ExchangeService.Outcome returned = ExchangeService.cancel(seller.player, data, askOrder);
            helper.assertValueEqual(returned.quantity(), 10, "ten returned");
            helper.assertValueEqual(boxed(data, seller, Items.COBBLESTONE), 10L, "to the collection box");
            helper.assertTrue(data.orders().isEmpty(), "no orders left");
        });
        helper.succeed();
    }

    /** A post that must be refused and change nothing: balance, main inventory, the book or any box. */
    private static void assertPostRejected(GameTestHelper helper, Fixture f, ExchangeData data, Result expected,
                                           java.util.function.Supplier<ExchangeService.Outcome> post) {
        List<ItemStack> before = new ArrayList<>();
        f.player.getInventory().items.forEach(s -> before.add(s.copy()));
        int balance = f.balance();
        List<ExchangeData.Order> orders = List.copyOf(data.orders());
        Map<UUID, Map<ResourceLocation, Long>> boxes = Map.copyOf(data.boxes());
        helper.assertValueEqual(post.get().result(), expected, "post result");
        helper.assertValueEqual(f.balance(), balance, "balance after " + expected);
        helper.assertValueEqual(List.copyOf(data.orders()), orders, "orders after " + expected);
        helper.assertValueEqual(Map.copyOf(data.boxes()), boxes, "boxes after " + expected);
        NonNullList<ItemStack> after = f.player.getInventory().items;
        for (int slot = 0; slot < after.size(); slot++) {
            helper.assertTrue(ItemStack.matches(after.get(slot), before.get(slot)), "inventory slot " + slot + " changed after " + expected);
        }
    }

    @GameTest(template = EMPTY)
    public static void rejectedExchangeOrdersChangeNothing(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            Fixture f = new Fixture(helper);
            f.deposit(100);
            f.give(new ItemStack(Items.COBBLESTONE, 4));
            ItemStack renamed = new ItemStack(Items.COBBLESTONE, 1);
            renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Special"));
            f.give(renamed);

            MarketConfig.EXCHANGE_ENABLED.set(false);
            assertPostRejected(helper, f, data, Result.EXCHANGE_CLOSED, () -> post(f, data, Items.COBBLESTONE, true, 1, 1));
            MarketConfig.EXCHANGE_ENABLED.set(true);
            assertPostRejected(helper, f, data, Result.NOT_LISTED, () -> post(f, data, Coins.item(Coin.BIT), false, 1, 1));
            assertPostRejected(helper, f, data, Result.NOT_LISTED, () -> post(f, data, Items.AIR, true, 1, 1));
            assertPostRejected(helper, f, data, Result.INVALID_QUANTITY, () -> post(f, data, Items.COBBLESTONE, true, 1, 0));
            assertPostRejected(helper, f, data, Result.INVALID_PRICE, () -> post(f, data, Items.COBBLESTONE, true, 0, 1));
            assertPostRejected(helper, f, data, Result.TOO_LARGE, () -> post(f, data, Items.COBBLESTONE, true, 1, 6401));
            assertPostRejected(helper, f, data, Result.TOO_LARGE, () -> post(f, data, Items.COBBLESTONE, true, Integer.MAX_VALUE, 2));
            assertPostRejected(helper, f, data, Result.NO_FUNDS, () -> post(f, data, Items.COBBLESTONE, true, 11, 10));
            // The renamed copy doesn't count.
            assertPostRejected(helper, f, data, Result.NOT_ENOUGH_ITEMS, () -> post(f, data, Items.COBBLESTONE, false, 1, 5));

            MarketVisibility.setTestLocks((player, item) -> player == f.player && item == Items.COBBLESTONE);
            try {
                assertPostRejected(helper, f, data, Result.NOT_LISTED, () -> post(f, data, Items.COBBLESTONE, false, 1, 1));
            } finally {
                MarketVisibility.setTestLocks(null);
            }

            MarketConfig.MAX_OPEN_ORDERS.set(2);
            posted(helper, f, data, Items.OAK_LOG, true, 1, 1);
            posted(helper, f, data, Items.OAK_LOG, true, 1, 1);
            assertPostRejected(helper, f, data, Result.ORDER_LIMIT, () -> post(f, data, Items.OAK_LOG, true, 1, 1));
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aPlayersOwnOrdersNeverTradeWithEachOther(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            Fixture f = new Fixture(helper);
            Fixture other = new Fixture(helper);
            f.deposit(100);
            f.give(new ItemStack(Items.COBBLESTONE, 10));
            other.deposit(100);
            long ask = posted(helper, f, data, Items.COBBLESTONE, false, 5, 10).orderId();
            ExchangeService.Outcome bid = posted(helper, f, data, Items.COBBLESTONE, true, 6, 10);
            helper.assertValueEqual(bid.filled(), 0, "no trade with their own ask");
            helper.assertValueEqual(data.taxCollected(), 0L, "so no tax");
            helper.assertValueEqual(f.balance(), 40, "the bid holds 60");

            // Anyone else can still buy from that ask.
            posted(helper, other, data, Items.COBBLESTONE, true, 5, 3);
            helper.assertValueEqual(data.find(ask).orElseThrow().remaining(), 7, "three sold to the other player");
            helper.assertValueEqual(f.balance(), 40 + 15 - 1, "paid 15 less tax");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void unlistedItemsCanBeTraded(GameTestHelper helper) {
        withExchange(() -> {
            helper.assertTrue(MarketCatalog.INSTANCE.get(Items.SPONGE).isEmpty(), "sponge isn't listed in the test catalog");
            ExchangeData data = new ExchangeData();
            Fixture seller = new Fixture(helper);
            Fixture buyer = new Fixture(helper);
            seller.give(new ItemStack(Items.SPONGE, 2));
            buyer.deposit(10);
            posted(helper, seller, data, Items.SPONGE, false, 3, 2);
            helper.assertValueEqual(posted(helper, buyer, data, Items.SPONGE, true, 3, 2).filled(), 2, "traded");
            helper.assertValueEqual(boxed(data, buyer, Items.SPONGE), 2L, "the sponges wait in the box");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void lockedItemsAreHiddenOnTheExchange(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            Fixture seller = new Fixture(helper);
            Fixture locked = new Fixture(helper);
            seller.give(new ItemStack(Items.COBBLESTONE, 5));
            posted(helper, seller, data, Items.COBBLESTONE, false, 5, 5);
            MarketVisibility.setTestLocks((player, item) -> player == locked.player && item == Items.COBBLESTONE);
            try {
                helper.assertTrue(ExchangeService.summaries(locked.player, data).isEmpty(), "the locked player sees no cobblestone");
                ExchangeService.Book book = ExchangeService.book(locked.player, data, id(Items.COBBLESTONE), 8);
                helper.assertTrue(book.asks().isEmpty() && book.bids().isEmpty(), "nor its book");
                helper.assertValueEqual(ExchangeService.summaries(seller.player, data).size(), 1, "others still see it");
            } finally {
                MarketVisibility.setTestLocks(null);
            }
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void collectingMovesOnlyWhatFits(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            Fixture f = new Fixture(helper);
            helper.assertValueEqual(ExchangeService.collect(f.player, data, Optional.empty()).result(), Result.NOT_ENOUGH_ITEMS, "an empty box");
            data.addToBox(f.player.getUUID(), id(Items.COBBLESTONE), 100);
            for (int slot = 0; slot < 35; slot++) {
                f.give(new ItemStack(Items.DIRT, 64));
            }

            ExchangeService.Outcome first = ExchangeService.collect(f.player, data, Optional.empty());
            helper.assertValueEqual(first.result(), Result.OK, "the free slot");
            helper.assertValueEqual(first.quantity(), 64, "one stack fits");
            helper.assertValueEqual(f.count(Items.COBBLESTONE), 64L, "in the inventory");
            helper.assertValueEqual(boxed(data, f, Items.COBBLESTONE), 36L, "the rest stays");
            helper.assertValueEqual(ExchangeService.collect(f.player, data, Optional.empty()).result(), Result.NO_SPACE, "no room left");
            helper.assertValueEqual(boxed(data, f, Items.COBBLESTONE), 36L, "still there");

            f.clearInventory();
            ExchangeService.Outcome rest = ExchangeService.collect(f.player, data, Optional.of(id(Items.COBBLESTONE)));
            helper.assertValueEqual(rest.quantity(), 36, "the rest");
            helper.assertTrue(data.box(f.player.getUUID()).isEmpty(), "the box is empty");
            helper.assertTrue(data.boxes().isEmpty(), "and forgotten");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void expiryAndClosingReturnWhatOrdersHold(GameTestHelper helper) {
        withExchange(() -> {
            net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
            ExchangeData data = new ExchangeData();
            Fixture buyer = new Fixture(helper);
            Fixture seller = new Fixture(helper);
            buyer.deposit(100);
            seller.give(new ItemStack(Items.COBBLESTONE, 4));
            ExchangeService.post(buyer.player, data, Items.COBBLESTONE, true, 3, 5, 0);
            ExchangeService.post(seller.player, data, Items.COBBLESTONE, false, 9, 4, 1);
            helper.assertValueEqual(buyer.balance(), 85, "the bid holds 15");

            helper.assertValueEqual(ExchangeService.expire(server, data, 100, 0), 0, "0 days means never");
            helper.assertValueEqual(ExchangeService.expire(server, data, 2, 3), 0, "nothing is 3 days old on day 2");
            helper.assertValueEqual(ExchangeService.expire(server, data, 3, 3), 1, "the bid expires on day 3");
            helper.assertValueEqual(buyer.balance(), 100, "its money is back");
            helper.assertValueEqual(ExchangeService.expire(server, data, 4, 3), 1, "the ask on day 4");
            helper.assertValueEqual(boxed(data, seller, Items.COBBLESTONE), 4L, "its items wait in the box");
            helper.assertValueEqual(data.takeNotices(buyer.player.getUUID()).size(), 1, "the buyer is told");
            helper.assertValueEqual(data.takeNotices(seller.player.getUUID()).size(), 1, "and the seller");

            // Turning the exchange off closes everything the same way.
            ExchangeService.post(buyer.player, data, Items.OAK_LOG, true, 2, 10, 5);
            ExchangeService.collect(seller.player, data, Optional.empty());
            ExchangeService.post(seller.player, data, Items.COBBLESTONE, false, 9, 4, 5);
            ExchangeService.closeAll(server, data);
            helper.assertTrue(data.orders().isEmpty(), "every order closed");
            helper.assertValueEqual(buyer.balance(), 100, "money back");
            helper.assertValueEqual(boxed(data, seller, Items.COBBLESTONE), 4L, "items in the box");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void theExchangeSurvivesARestartMidOrder(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            Fixture seller = new Fixture(helper);
            Fixture buyer = new Fixture(helper);
            Fixture bidder = new Fixture(helper);
            seller.give(new ItemStack(Items.COBBLESTONE, 13));
            buyer.deposit(100);
            bidder.deposit(100);
            posted(helper, seller, data, Items.COBBLESTONE, false, 5, 10);
            posted(helper, buyer, data, Items.COBBLESTONE, true, 7, 4);
            long bid = posted(helper, bidder, data, Items.COBBLESTONE, true, 4, 3).orderId();

            ExchangeData restarted = ExchangeData.fromTag(data.save(new CompoundTag(), helper.getLevel().registryAccess()),
                    helper.getLevel().registryAccess());
            helper.assertValueEqual(restarted.orders(), data.orders(), "orders");
            helper.assertValueEqual(restarted.boxes(), data.boxes(), "boxes");
            helper.assertValueEqual(restarted.taxCollected(), data.taxCollected(), "tax");
            helper.assertValueEqual(restarted.volume(), data.volume(), "volume");
            helper.assertValueEqual(restarted.takeNotices(seller.player.getUUID()), data.takeNotices(seller.player.getUUID()), "notices");

            // Trading carries on where it stopped, and new orders never reuse an ID.
            ExchangeService.Outcome more = posted(helper, seller, restarted, Items.COBBLESTONE, false, 4, 3);
            helper.assertValueEqual(more.filled(), 3, "the waiting bid is still there");
            helper.assertTrue(restarted.find(bid).isEmpty(), "and now filled");
            helper.assertValueEqual(restarted.box(bidder.player.getUUID()), Map.of(id(Items.COBBLESTONE), 3L), "the bidder's box");
            long next = restarted.takeId();
            helper.assertTrue(data.orders().stream().allMatch(o -> o.id() < next), "IDs keep counting up");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 200)
    public static void hundredsOfRandomExchangeRequestsReconcile(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            List<Fixture> players = List.of(new Fixture(helper), new Fixture(helper), new Fixture(helper));
            List<Item> items = List.of(Items.COBBLESTONE, Items.OAK_LOG);
            for (Fixture f : players) {
                f.deposit(1000);
                f.give(new ItemStack(Items.COBBLESTONE, 64));
                f.give(new ItemStack(Items.OAK_LOG, 64));
            }
            java.util.Random random = new java.util.Random(17);
            for (int step = 0; step < 400; step++) {
                Fixture f = players.get(random.nextInt(players.size()));
                Item item = items.get(random.nextInt(items.size()));
                switch (random.nextInt(5)) {
                    case 0, 1, 2 -> post(f, data, item, random.nextBoolean(), 1 + random.nextInt(10), 1 + random.nextInt(20));
                    case 3 -> {
                        List<ExchangeData.Order> mine = ExchangeService.ordersOf(f.player.getUUID(), data);
                        if (!mine.isEmpty()) {
                            ExchangeService.cancel(f.player, data, mine.get(random.nextInt(mine.size())).id());
                        }
                    }
                    default -> ExchangeService.collect(f.player, data, Optional.empty());
                }
                assertExchangeReconciles(helper, data, players, items, "step " + step);
            }
            helper.assertTrue(data.volume() > 0, "some trades happened");
            for (Fixture f : players) {
                ExchangeService.ordersOf(f.player.getUUID(), data).forEach(o -> ExchangeService.cancel(f.player, data, o.id()));
                ExchangeService.collect(f.player, data, Optional.empty());
            }
            assertExchangeReconciles(helper, data, players, items, "after cancelling everything");
            helper.assertTrue(data.orders().isEmpty(), "no orders left");
        });
        helper.succeed();
    }

    /**
     * Money: balances, plus what buy orders hold, plus the tax destroyed, is what the players started with. Items:
     * inventories, plus what sell orders hold, plus collection boxes, is what they started with.
     */
    private static void assertExchangeReconciles(GameTestHelper helper, ExchangeData data, List<Fixture> players, List<Item> items, String when) {
        long money = players.stream().mapToLong(Fixture::balance).sum();
        long held = data.orders().stream().mapToLong(ExchangeData.Order::heldMoney).sum();
        helper.assertValueEqual(money + held + data.taxCollected(), 1000L * players.size(), "money at " + when);
        for (Item item : items) {
            long carried = players.stream().mapToLong(f -> f.count(item)).sum();
            long selling = data.orders().stream().filter(o -> !o.buy() && o.item().equals(id(item))).mapToLong(ExchangeData.Order::remaining).sum();
            long boxes = players.stream().mapToLong(f -> boxed(data, f, item)).sum();
            helper.assertValueEqual(carried + selling + boxes, 64L * players.size(), id(item) + " at " + when);
        }
    }

    // ---- Fixture ------------------------------------------------------------------------------------------------

    /**
     * A fresh player with an empty inventory and a new, empty account in the world's bank (Numismatics' starter coins
     * default to 0).
     */
    private static final class Fixture {
        final GameTestHelper helper;
        final ServerPlayer player;
        final MarketStockData market;

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            this.player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                    new GameProfile(UUID.randomUUID(), "market-test"), ClientInformation.createDefault());
            this.market = MarketStockData.get(helper.getLevel().getServer());
            helper.assertTrue(WorldBank.available(player.server), "the world's bank must be reachable");
            // Every test starts from base prices; tests share the market but each runs within a single tick.
            market.resetAllFactors();
            int starting = balance();
            if (starting > 0) {
                account().deduct(starting);
            }
        }

        Bank bank() {
            return WorldBank.of(player.server);
        }

        Bank.Account account() {
            return bank().account(player).orElseThrow();
        }

        /** Test balances stay well within an int. */
        int balance() {
            return Math.toIntExact(account().balance());
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
        /** A bank request that must be refused and change nothing: balance, main inventory or offhand. */
        void assertRejectedUnchanged(BankService.Result expected, java.util.function.Supplier<BankService.Outcome> request) {
            List<ItemStack> before = new ArrayList<>();
            player.getInventory().items.forEach(s -> before.add(s.copy()));
            ItemStack offhandBefore = player.getInventory().offhand.get(0).copy();
            int balanceBefore = balance();
            BankService.Outcome outcome = request.get();
            helper.assertValueEqual(outcome.result(), expected, "bank result");
            helper.assertValueEqual(outcome.amount(), 0L, "nothing moved");
            helper.assertValueEqual(balance(), balanceBefore, "balance after " + expected);
            NonNullList<ItemStack> after = player.getInventory().items;
            for (int slot = 0; slot < after.size(); slot++) {
                helper.assertTrue(ItemStack.matches(after.get(slot), before.get(slot)), "inventory slot " + slot + " changed after " + expected);
            }
            helper.assertTrue(ItemStack.matches(player.getInventory().offhand.get(0), offhandBefore), "offhand changed after " + expected);
        }

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
