package dynamiceconomy.gametest;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.MarketConfig;
import dynamiceconomy.catalog.CatalogOverlay;
import dynamiceconomy.data.AuditLog;
import dynamiceconomy.data.MarketBackup;
import dynamiceconomy.data.MarketOverrides;
import dynamiceconomy.data.TradeHistory;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.catalog.FieldSource;
import dynamiceconomy.data.MarketStockData;
import dynamiceconomy.service.MarketDiagnostics;
import dynamiceconomy.service.MarketService;
import dynamiceconomy.service.MarketService.Result;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static dynamiceconomy.gametest.GameTestSupport.*;

/**
 * Admin safety: diagnostics, the audit log, backups and recovery.
 *
 * Shared setup is in {@link GameTestSupport}.
 */
@GameTestHolder(DynamicEconomy.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AdminTests {
    private AdminTests() {
    }

    // ---- Catalog diagnostics and admin safety ------------------------------------------------------------------

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
                "  base_price = 32 [file dynamic_economy:example_catalog]",
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
        helper.assertTrue(setPrice.stream().anyMatch(line -> line.contains("minecraft:oak_log: base_price 8 → 4")), "oak's change shown: " + setPrice);
        List<String> remove = runCommand(helper, "market admin tag minecraft:logs remove preview");
        helper.assertTrue(remove.get(1).contains("1 taken off"), "removing the tag would delist oak: " + remove);
        List<String> markup = runCommand(helper, "market admin tag minecraft:logs set markup 1.0 preview");
        helper.assertTrue(markup.get(1).contains("0 newly listed, 0 taken off, 1 changed"), "a rule without a price lists nothing: " + markup);

        helper.assertValueEqual(MarketOverrides.get(server).snapshot(), before, "no edit was saved");
        helper.assertTrue(MarketCatalog.INSTANCE.get(Items.BIRCH_LOG).isEmpty(), "the live catalog is unchanged");
        helper.assertValueEqual(MarketCatalog.INSTANCE.get(Items.OAK_LOG).map(CatalogEntry::basePrice), Optional.of(8), "oak still at 8");
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

    // ---- Audit history and recovery ----------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void auditLogRotatesAndReadsBackNewestFirst(GameTestHelper helper) {
        try {
            Path dir = Files.createTempDirectory("market-audit");
            Path file = dir.resolve("audit.log");
            try {
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
            } finally {
                deleteTree(dir);
            }
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
            helper.assertTrue(!mine.get(0).buy() && mine.get(0).quantity() == 10 && mine.get(0).total() == 320, "what was sold: " + mine);
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
}
