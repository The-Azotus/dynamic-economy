package dynamiceconomy.gametest;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.catalog.CatalogOverlay;
import dynamiceconomy.data.MarketOverrides;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.catalog.FieldSource;
import dynamiceconomy.catalog.ItemClasses;
import dynamiceconomy.io.CatalogExport;
import dynamiceconomy.io.CatalogImport;
import dynamiceconomy.io.Csv;
import dynamiceconomy.service.MarketService.Result;
import dynamiceconomy.service.PriceModel;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.function.Function;

import static dynamiceconomy.gametest.GameTestSupport.*;

/**
 * The catalog: parsing, export and import, in-game edits and tag rules, and item classes.
 *
 * Shared setup is in {@link GameTestSupport}.
 */
@GameTestHolder(DynamicEconomy.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CatalogTests {
    private CatalogTests() {
    }

    // ---- Catalog parsing ---------------------------------------------------------------------------------------

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

    // ---- Export, import and catalog loading --------------------------------------------------------------------

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
            try {
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
            } finally {
                deleteTree(root);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void importWritesThePackAndBacksUpReplacedFiles(GameTestHelper helper) {
        try {
            Path datapacks = Files.createTempDirectory("market-datapacks");
            try {
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
            } finally {
                deleteTree(datapacks);
            }
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

    /** The one problem whose message names {@code item}. */
    private static String problemAbout(MarketCatalog.Loaded loaded, String item) {
        List<String> found = loaded.problems().stream().map(MarketCatalog.Problem::message)
                .filter(message -> message.contains("minecraft:" + item)).toList();
        if (found.size() != 1) {
            throw new GameTestAssertException("expected one problem about " + item + ", got " + found);
        }
        return found.get(0);
    }

    @GameTest(template = EMPTY)
    public static void configFolderSitsJustAboveTheBuiltIns(GameTestHelper helper) {
        String prices = DynamicEconomy.DEFAULT_PRICES_PACK_ID;
        String example = DynamicEconomy.EXAMPLE_PACK_ID;
        // Each pack i is at 2 * i, so 2 * i + 1 is just above pack i. "other:pack" stands for a pack another mod adds
        // (OpenLoader, KubeJS): neither built in nor a world datapack.
        helper.assertValueEqual(MarketCatalog.configOrder(List.of(prices, "vanilla", "mod_data", "other:pack")), 5,
                "just above the built-ins, below another mod's pack");
        helper.assertValueEqual(MarketCatalog.configOrder(List.of(prices, "vanilla", "mod_data", "other:pack", "file/a", "file/b")), 5,
                "the same with world datapacks: their presence doesn't move the folder");
        helper.assertValueEqual(MarketCatalog.configOrder(List.of(prices, "vanilla", "mod_data", "file/a", "other:pack")), 5,
                "or their order");
        helper.assertValueEqual(MarketCatalog.configOrder(List.of(prices, "vanilla", "mod_data", example)), 5,
                "below the example catalog");
        helper.assertValueEqual(MarketCatalog.configOrder(List.of("file/a", prices, "vanilla", "mod_data")), 7,
                "a world pack moved below the built-ins stays below the folder");
        helper.assertValueEqual(MarketCatalog.configOrder(List.of("vanilla", "mod_data", "file/a", prices)), 7,
                "built-in prices moved above a world pack take the folder with them: it never goes below them");
        // Each mod's own data is a pack of its own after mod_data, and the built-in classes are in mod/dynamic_economy;
        // a mod's optional packs (with a ':', like the example catalog) aren't built in.
        helper.assertValueEqual(MarketCatalog.configOrder(List.of(prices, "vanilla", "mod_data", "mod/neoforge",
                "mod/dynamic_economy", example, "file/a")), 9, "above every mod's own data, below its optional packs");

        // The real pack list: the folder sits above the pack the built-in classes come from, so they can be replaced.
        var resources = helper.getLevel().getServer().getResourceManager();
        List<String> ids = resources.listPacks().map(net.minecraft.server.packs.PackResources::packId).toList();
        int order = MarketCatalog.configOrder(ids);
        String classPack = resources.getResource(ResourceLocation.fromNamespaceAndPath(DynamicEconomy.MOD_ID,
                "market_classes/rare_resource.json")).orElseThrow().sourcePackId();
        helper.assertValueEqual(classPack, "mod/" + DynamicEconomy.MOD_ID, "the built-in classes come from the mod's own pack");
        int classOrder = ids.indexOf(classPack) < 0 ? -1 : 2 * ids.indexOf(classPack);
        helper.assertTrue(classOrder < order, "built-in classes in " + classPack + " sit below the folder in " + ids);
        helper.assertTrue(ids.contains(example), "the test server runs with the example catalog: " + ids);
        for (int i = 0; i < ids.size(); i++) {
            if (ids.get(i).startsWith("file/") || ids.get(i).equals(example)) {
                helper.assertTrue(2 * i > order, ids.get(i) + " sits above the folder in " + ids);
            }
        }
        // The same with a world pack moved to the bottom.
        List<String> moved = new ArrayList<>(ids);
        moved.removeIf(id -> id.startsWith("file/") || id.equals(example));
        moved.add(0, "file/moved");
        int movedClassOrder = moved.indexOf(classPack) < 0 ? -1 : 2 * moved.indexOf(classPack);
        helper.assertTrue(movedClassOrder < MarketCatalog.configOrder(moved),
                "built-in classes in " + classPack + " sit below the folder in " + moved);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void importReportsRowsAHigherFileChanged(GameTestHelper helper) {
        CatalogImport.Report report = CatalogImport.analyze("item_id,base_price\nminecraft:iron_ingot,20\nminecraft:gold_ingot,40\n");
        JsonElement imported = JsonParser.parseString("""
                { "defaults": { "replace": true }, "entries": [
                  { "item": "minecraft:iron_ingot", "base_price": 20 }, { "item": "minecraft:gold_ingot", "base_price": 40 } ] }""");
        MarketCatalog.Loaded loaded = MarketCatalog.load(List.of(
                new MarketCatalog.SourceFile(ResourceLocation.fromNamespaceAndPath("config", "imported_minecraft"), 1, "config", imported),
                sourceFile("world", 2, "{ \"entries\": [ { \"item\": \"minecraft:gold_ingot\", \"markup\": 0.75 } ] }")));
        CatalogImport.Outcome outcome = CatalogImport.outcome(report, item -> Optional.ofNullable(loaded.entries().get(item)));
        helper.assertValueEqual(outcome.changedByHigherFile(), List.of(key(Items.GOLD_INGOT)), "only gold was changed above the import");
        helper.assertValueEqual(outcome.noLongerLoad(), List.of(), "every row still loads");

        CatalogImport.Outcome missing = CatalogImport.outcome(report, item -> Optional.empty());
        helper.assertValueEqual(missing.changedByHigherFile().size(), 2, "a row that isn't in the catalog at all is reported too");
        helper.succeed();
    }

    /** suggested.csv's catalog rows: unchanged ones are left out, unless they come from the file the import rewrites. */
    @GameTest(template = EMPTY)
    public static void importLeavesOutUnchangedListedRows(GameTestHelper helper) {
        MarketCatalog.Loaded loaded = MarketCatalog.load(List.of(
                sourceFile("built_in", 0, """
                        { "entries": [
                          { "item": "minecraft:iron_ingot", "base_price": 10, "class": "rare_resource", "sell_fee": 0.1 },
                          { "item": "minecraft:gold_ingot", "base_price": 40 } ] }"""),
                new MarketCatalog.SourceFile(ResourceLocation.fromNamespaceAndPath("config", "imported_minecraft"), 1, "config",
                        JsonParser.parseString("""
                                { "defaults": { "replace": true }, "entries": [ { "item": "minecraft:copper_ingot", "base_price": 5 } ] }"""))));
        try {
            Path root = Files.createTempDirectory("market-listed-rows");
            try {
                CatalogExport.Result result = CatalogExport.export(root, Optional.of("minecraft"), loaded.entries().values(),
                        item -> loaded.sources().getOrDefault(item, FieldSource.NONE));
                // items.csv as suggested.csv has it: gold edited in the sheet, and one suggestion.
                Csv.Table table = Csv.parse(Files.readString(result.folder().resolve("items.csv")));
                List<String> header = new ArrayList<>(table.headers());
                header.add("price_source");
                StringBuilder csv = new StringBuilder(Csv.line(header));
                for (Csv.Row row : table.rows()) {
                    List<String> cells = new ArrayList<>(table.headers().stream().map(row::get).toList());
                    String item = row.get("item_id");
                    if (item.equals("minecraft:gold_ingot")) {
                        cells.set(table.headers().indexOf("base_price"), "45");
                    } else if (item.equals("minecraft:iron_block")) {
                        cells.set(table.headers().indexOf("base_price"), "100");
                        cells.set(table.headers().indexOf("class"), "common_processed");
                    }
                    cells.add(item.equals("minecraft:iron_block") ? "suggested" : row.get("in_catalog").equals("true") ? "catalog" : "");
                    csv.append(Csv.line(cells));
                }
                Function<Item, Optional<CatalogEntry>> live = item -> Optional.ofNullable(loaded.entries().get(item));
                Function<Item, Map<String, List<FieldSource>>> sources = item -> loaded.sources().getOrDefault(item, FieldSource.NONE);

                CatalogImport.Report toConfig = CatalogImport.analyze(csv.toString(), CatalogImport.alreadyListed(live, sources, true));
                helper.assertValueEqual(toConfig.rejected(), List.of(), "imports cleanly");
                helper.assertValueEqual(toConfig.decoded().stream().map(CatalogEntry::item).collect(java.util.stream.Collectors.toSet()),
                        Set.of(Items.GOLD_INGOT, Items.COPPER_INGOT, Items.IRON_BLOCK),
                        "iron is left out; edited gold, copper from the file being rewritten, and the suggestion stay");
                helper.assertValueEqual(toConfig.skippedListed(), 1, "iron counted as left out");
                helper.assertValueEqual(toConfig.decoded().stream().filter(e -> e.item() == Items.GOLD_INGOT).findFirst().orElseThrow().basePrice(),
                        45, "with the sheet's change");

                CatalogImport.Report toWorld = CatalogImport.analyze(csv.toString(), CatalogImport.alreadyListed(live, sources, false));
                helper.assertValueEqual(toWorld.decoded().stream().map(CatalogEntry::item).collect(java.util.stream.Collectors.toSet()),
                        Set.of(Items.GOLD_INGOT, Items.IRON_BLOCK), "into the world's datapack, the config folder's copper is left out too");

                helper.assertValueEqual(CatalogImport.analyze(csv.toString()).skippedListed(), 0, "without a rule nothing is left out");
                helper.assertValueEqual(CatalogImport.analyze(csv.toString().replace(",catalog\r\n", ",\r\n"),
                        CatalogImport.alreadyListed(live, sources, true)).skippedListed(), 0, "rows not marked catalog are never left out");
            } finally {
                deleteTree(root);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        helper.succeed();
    }

    private static MarketCatalog.SourceFile sourceFile(String name, int order, String json) {
        return new MarketCatalog.SourceFile(ResourceLocation.fromNamespaceAndPath("test", name), order, "pack" + order,
                JsonParser.parseString(json));
    }

    @GameTest(template = EMPTY)
    public static void higherFilesChangeOnlyTheFieldsTheySet(GameTestHelper helper) {
        MarketCatalog.SourceFile builtIn = sourceFile("built_in", 0, """
                { "entries": [
                  { "item": "minecraft:iron_ingot", "base_price": 10, "markup": 0.25, "can_sell": false, "sell_impact": 0.01 },
                  { "item": "minecraft:gold_ingot", "base_price": 40, "markup": 0.25 },
                  { "item": "minecraft:copper_ingot", "base_price": 5, "markup": 0.25 },
                  { "item": "minecraft:lapis_lazuli", "base_price": 3, "markup": 0.25 },
                  { "item": "minecraft:redstone", "base_price": 2 },
                  { "item": "minecraft:quartz", "base_price": 6, "contract_max_size": 16 }
                ] }""");
        MarketCatalog.SourceFile override = sourceFile("override", 1, """
                { "entries": [
                  { "item": "minecraft:iron_ingot", "base_price": 20 },
                  { "item": "minecraft:gold_ingot", "base_price": 50, "replace": true },
                  { "item": "minecraft:copper_ingot", "min_buy_markup": 0.4 },
                  { "item": "minecraft:lapis_lazuli", "min_buy_markup": 0.4, "markup": 0.3 },
                  { "item": "minecraft:redstone", "markup": 2000 },
                  { "item": "minecraft:quartz", "class": "common_resource" },
                  { "item": "minecraft:diamond", "markup": 0.3 },
                  { "item": "minecraft:emerald", "base_price": 9, "replace": "yes" }
                ] }""");
        MarketCatalog.Loaded loaded = MarketCatalog.load(List.of(override, builtIn));

        CatalogEntry iron = loaded.entries().get(Items.IRON_INGOT);
        helper.assertValueEqual(iron.basePrice(), 20, "the higher file's price");
        helper.assertValueEqual(iron.markup(), 0.25, "markup kept from the lower file");
        helper.assertTrue(!iron.canSell(), "can_sell kept from the lower file");
        helper.assertValueEqual(iron.overrides().sellImpact(), Optional.of(0.01), "sell_impact kept from the lower file");
        helper.assertValueEqual(loaded.sources().get(Items.IRON_INGOT).get("base_price").stream().map(FieldSource::layer).toList(),
                List.of("file test:built_in", "file test:override"), "both layers recorded for the changed field");
        helper.assertValueEqual(loaded.sources().get(Items.IRON_INGOT).get("markup").stream().map(FieldSource::layer).toList(),
                List.of("file test:built_in"), "an unchanged field keeps its source");

        CatalogEntry gold = loaded.entries().get(Items.GOLD_INGOT);
        helper.assertValueEqual(gold.basePrice(), 50, "replaced entry's price");
        helper.assertValueEqual(gold.markup(), CatalogEntry.DEFAULT_MARKUP, "replace drops the lower file's markup");
        helper.assertTrue(!loaded.sources().get(Items.GOLD_INGOT).containsKey("replace"), "replace isn't an entry field");

        helper.assertValueEqual(loaded.entries().get(Items.COPPER_INGOT).markup(), 0.25,
                "a change the rules reject leaves the lower entry as it was");
        helper.assertTrue(loaded.entries().get(Items.COPPER_INGOT).minBuyMarkup().isEmpty(), "rejected change not applied");
        helper.assertTrue(!loaded.entries().containsKey(Items.DIAMOND), "with no lower entry, a change needs a price");
        helper.assertTrue(!loaded.entries().containsKey(Items.EMERALD), "replace must be true or false");
        String problems = loaded.problems().stream().map(MarketCatalog.Problem::message).reduce("", (a, b) -> a + "\n" + b);
        for (String expected : List.of("min_buy_markup", "stays", "base_price", "\"replace\" must be true or false")) {
            helper.assertTrue(problems.contains(expected), "expected a problem mentioning \"" + expected + "\" in:" + problems);
        }
        String copper = problemAbout(loaded, "copper_ingot");
        helper.assertTrue(copper.contains("(the entry from test:built_in stays). It conflicts with markup 0.25 from file test:built_in")
                && copper.contains("\"replace\": true"), "a change that's fine on its own names the lower value it conflicts with: " + copper);
        String lapis = problemAbout(loaded, "lapis_lazuli");
        helper.assertTrue(lapis.contains("stays") && !lapis.contains("conflicts"),
                "a change that breaks a rule with its own values gets no hint about lower values: " + lapis);
        helper.assertValueEqual(loaded.entries().get(Items.LAPIS_LAZULI).markup(), 0.25, "lapis unchanged");
        String redstone = problemAbout(loaded, "redstone");
        helper.assertTrue(redstone.contains("stays") && !redstone.contains("conflicts"), "an out-of-range value gets no hint: " + redstone);
        // The new class's contract_min_size 64 clashes with the lower file's contract_max_size 16: name the lower value.
        String quartz = problemAbout(loaded, "quartz");
        helper.assertTrue(quartz.contains("It conflicts with contract_max_size 16 from file test:built_in"),
                "the hint names the value the lower file set, not the one the new class brings: " + quartz);

        // A file's defaults count as set on each of its entries, so they change the lower entry too.
        MarketCatalog.SourceFile withDefaults = sourceFile("with_defaults", 1, """
                { "defaults": { "markup": 0.75 }, "entries": [ { "item": "minecraft:iron_ingot", "base_price": 30 } ] }""");
        CatalogEntry tuned = MarketCatalog.parse(List.of(builtIn, withDefaults)).get(Items.IRON_INGOT);
        helper.assertValueEqual(tuned.markup(), 0.75, "the higher file's defaults apply");
        helper.assertTrue(!tuned.canSell(), "fields neither file's defaults nor entry set are kept");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void importedFilesReplaceWholeEntries(GameTestHelper helper) {
        try {
            Path config = Files.createTempDirectory("market-config");
            try {
                CatalogImport.Report report = CatalogImport.analyze("item_id,base_price\nminecraft:iron_ingot,20\n");
                CatalogImport.Written written = CatalogImport.writeToConfig(report, config.resolve("market"));
                helper.assertTrue(!written.createdPack(), "no pack for the config folder");
                helper.assertValueEqual(written.files(), List.of(config.resolve("market/imported_minecraft.json")), "written into market/");

                MarketCatalog.SourceFile builtIn = sourceFile("built_in", 0, """
                        { "entries": [ { "item": "minecraft:iron_ingot", "base_price": 10, "markup": 0.25 } ] }""");
                MarketCatalog.ConfigFolder folder = MarketCatalog.readConfigFolder(config, 1);
                List<MarketCatalog.SourceFile> files = new ArrayList<>(folder.files());
                files.add(builtIn);
                CatalogEntry iron = MarketCatalog.parse(files).get(Items.IRON_INGOT);
                helper.assertValueEqual(iron.basePrice(), 20, "imported price");
                helper.assertValueEqual(iron.markup(), CatalogEntry.DEFAULT_MARKUP, "a blank cell leaves the field unset");
            } finally {
                deleteTree(config);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void configFolderIsReadLikeAPack(GameTestHelper helper) {
        try {
            Path root = Files.createTempDirectory("market-config");
            try {
                MarketCatalog.ConfigFolder empty = MarketCatalog.readConfigFolder(root, 3);
                helper.assertTrue(empty.files().isEmpty() && empty.classFiles().isEmpty() && empty.problems().isEmpty(), "nothing to read");
                helper.assertTrue(Files.isDirectory(root.resolve("market")) && Files.isDirectory(root.resolve("market_classes")),
                        "both folders created for admins to find");

                Files.createDirectories(root.resolve("market/ores"));
                Files.writeString(root.resolve("market/ores/iron.json"),
                        "{ \"entries\": [ { \"item\": \"minecraft:iron_ingot\", \"base_price\": 20 } ] }");
                Files.writeString(root.resolve("market/Bad Name.json"), "{ \"entries\": [] }");
                Files.writeString(root.resolve("market/broken.json"), "{ not json");
                Files.writeString(root.resolve("market/notes.txt"), "ignored");
                Files.writeString(root.resolve("market_classes/common_resource.json"), "{ \"sell_impact\": 0.001 }");

                MarketCatalog.ConfigFolder folder = MarketCatalog.readConfigFolder(root, 3);
                helper.assertValueEqual(folder.files().stream().map(MarketCatalog.SourceFile::id).toList(),
                        List.of(ResourceLocation.fromNamespaceAndPath("config", "ores/iron")), "market file IDs");
                helper.assertValueEqual(folder.files().get(0).packOrder(), 3, "the folder's priority");
                helper.assertValueEqual(folder.classFiles().keySet(),
                        Set.of(ResourceLocation.fromNamespaceAndPath(DynamicEconomy.MOD_ID, "common_resource")),
                        "a class file takes the mod's namespace, so it replaces the built-in class of that name");
                helper.assertValueEqual(folder.problems().size(), 2, "bad name and unreadable file: " + folder.problems());
                helper.assertValueEqual(MarketCatalog.parse(folder.files()).get(Items.IRON_INGOT).basePrice(), 20, "the file loads");
            } finally {
                deleteTree(root);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        helper.succeed();
    }

    // ---- Admin edits and tag rules -----------------------------------------------------------------------------

    private static CatalogOverlay.Result overlay(MarketOverrides edits) {
        return CatalogOverlay.apply(files(), edits.snapshot(), CatalogOverlay::registryTag);
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

    // ---- Item classes ------------------------------------------------------------------------------------------

    private static final List<String> BUILT_IN_CLASSES = List.of("common_resource", "common_processed", "farmable",
            "rare_resource", "rare_processed", "rare_loot", "unique");

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
            try {
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
            } finally {
                deleteTree(root);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        helper.succeed();
    }
}
