package dynamiceconomy.gametest;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.MarketConfig;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.data.MarketStockData;
import dynamiceconomy.network.MarketPayloads;
import dynamiceconomy.network.MarketSync;
import dynamiceconomy.service.MarketService;
import dynamiceconomy.service.MarketService.Result;
import dynamiceconomy.service.MarketService.TradeResult;
import dynamiceconomy.service.PriceModel;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

import static dynamiceconomy.gametest.GameTestSupport.*;

/**
 * Prices: the pricing maths, supply and demand, price history and economic integrity (no profitable round trips).
 *
 * Shared setup is in {@link GameTestSupport}.
 */
@GameTestHolder(DynamicEconomy.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PricingTests {
    private PricingTests() {
    }

    // ---- Pricing -----------------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void pricesUseExactDecimalRounding(GameTestHelper helper) {
        PriceModel.Inputs tenAtTenPercent = inputs(10, 0.1, 0.0, false);
        helper.assertValueEqual(PriceModel.unitBuyPrice(tenAtTenPercent, 1.0), 11L, "10 at 10% markup charges 11, not 12");
        helper.assertValueEqual(PriceModel.unitSellPrice(tenAtTenPercent, 1.0), 10L, "10 at 10% markup pays 10");
        PriceModel.Inputs oddBase = inputs(3, 0.5, 0.0, false);
        helper.assertValueEqual(PriceModel.unitBuyPrice(oddBase, 1.0), 5L, "ceil(4.5)");
        helper.succeed();
    }

    // ---- Supply and demand -------------------------------------------------------------------------------------

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
            helper.assertValueEqual(f.balance(), 288, "10 × 32 less a 10% fee");
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

    private static void assertClose(GameTestHelper helper, double actual, double expected, String what) {
        helper.assertTrue(Math.abs(actual - expected) < 1e-12, what + ": expected " + expected + " but was " + actual);
    }

    // ---- Price history -----------------------------------------------------------------------------------------

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

    // ---- Economic integrity ------------------------------------------------------------------------------------

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

    @GameTest(template = EMPTY)
    public static void sellingThenBuyingBackIsCheckedForProfit(GameTestHelper helper) {
        // Default settings except buy_impact 0. A check that only looks at buying then reselling calls this safe, but
        // crashing the price by selling and buying the same items back at the bottom pays.
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
        // Unlisted items count too, for the exchange.
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
}
