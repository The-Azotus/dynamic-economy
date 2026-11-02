package dynamiceconomy.gametest;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.catalog.FieldSource;
import dynamiceconomy.catalog.ItemClasses;
import dynamiceconomy.compat.CreateRecipes;
import dynamiceconomy.io.CatalogExport;
import dynamiceconomy.io.CatalogImport;
import dynamiceconomy.io.Csv;
import dynamiceconomy.service.MarketService.Result;
import dynamiceconomy.service.PriceModel;
import dynamiceconomy.service.PriceSuggestion;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;

import static dynamiceconomy.gametest.GameTestSupport.*;

/**
 * The built-in price packs and /market admin suggest.
 *
 * Shared setup is in {@link GameTestSupport}.
 */
@GameTestHolder(DynamicEconomy.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PriceSuggestionTests {
    private PriceSuggestionTests() {
    }

    // ---- Price packs and suggestions ---------------------------------------------------------------------------

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
}
