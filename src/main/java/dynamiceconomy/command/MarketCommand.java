package dynamiceconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.MarketAccess;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.data.MarketOverrides;
import dynamiceconomy.data.MarketStockData;
import dynamiceconomy.menu.MarketMenu;
import dynamiceconomy.network.MarketSync;
import dynamiceconomy.service.MarketService;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dynamiceconomy.io.CatalogExport;
import dynamiceconomy.io.CatalogImport;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.commands.ReloadCommand;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * {@code /market} opens the market screen. The sell, buy, stock and price subcommands are the Phase 1 test commands,
 * kept for scripting and testing without the screen.
 * Brigadier runs commands on the server thread, which is what {@link MarketService} requires.
 */
public final class MarketCommand {
    private MarketCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        // In block-only mode, players without operator permission have to use a Market Terminal: the subcommands are
        // hidden from them, and plain /market says where to go instead of "unknown command".
        dispatcher.register(Commands.literal("market")
                .executes(MarketCommand::open)
                .then(Commands.literal("sell")
                        .requires(MarketAccess::commandsAllowed)
                        .then(Commands.argument("item", ItemArgument.item(context))
                                .then(Commands.argument("qty", IntegerArgumentType.integer(1))
                                        .executes(ctx -> trade(ctx, true)))))
                .then(Commands.literal("buy")
                        .requires(MarketAccess::commandsAllowed)
                        .then(Commands.argument("item", ItemArgument.item(context))
                                .then(Commands.argument("qty", IntegerArgumentType.integer(1))
                                        .executes(ctx -> trade(ctx, false)))))
                .then(Commands.literal("stock")
                        .requires(MarketAccess::commandsAllowed)
                        .executes(MarketCommand::listStock)
                        .then(Commands.argument("item", ItemArgument.item(context))
                                .executes(MarketCommand::showStock)))
                .then(Commands.literal("price")
                        .requires(MarketAccess::commandsAllowed)
                        .then(Commands.argument("item", ItemArgument.item(context))
                                .executes(MarketCommand::showPrice)))
                .then(withAdminEdits(Commands.literal("admin"), context)
                        .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("setstock")
                                .then(Commands.argument("item", ItemArgument.item(context))
                                        .then(Commands.argument("qty", LongArgumentType.longArg(0))
                                                .executes(MarketCommand::setStock))))
                        .then(Commands.literal("resetprice")
                                .then(Commands.literal("all")
                                        .executes(MarketCommand::resetAllPrices))
                                .then(Commands.argument("item", ItemArgument.item(context))
                                        .executes(MarketCommand::resetPrice)))
                        .then(Commands.literal("reload")
                                .executes(MarketCommand::reload))
                        .then(Commands.literal("stats")
                                .executes(MarketCommand::stats)
                                .then(Commands.argument("item", ItemArgument.item(context))
                                        .executes(MarketCommand::itemStats)))
                        // Writing files on the server's disk is for the owner and console only.
                        .then(Commands.literal("export")
                                .requires(source -> source.hasPermission(Commands.LEVEL_OWNERS))
                                .executes(ctx -> export(ctx, Optional.empty()))
                                .then(Commands.argument("modid", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                BuiltInRegistries.ITEM.keySet().stream().map(ResourceLocation::getNamespace).distinct(), builder))
                                        .executes(ctx -> export(ctx, Optional.of(StringArgumentType.getString(ctx, "modid"))))))
                        .then(Commands.literal("import")
                                .requires(source -> source.hasPermission(Commands.LEVEL_OWNERS))
                                .then(Commands.argument("path", StringArgumentType.string())
                                        .executes(ctx -> importCsv(ctx, false))
                                        .then(Commands.literal("preview")
                                                .executes(ctx -> importCsv(ctx, true)))))));
    }

    /** The in-game catalog editing commands live in their own class; they hang off {@code /market admin}. */
    private static LiteralArgumentBuilder<CommandSourceStack> withAdminEdits(LiteralArgumentBuilder<CommandSourceStack> admin,
                                                                             CommandBuildContext context) {
        AdminCatalogCommands.addTo(admin, context);
        return admin;
    }

    private static Item item(CommandContext<CommandSourceStack> ctx) {
        return ItemArgument.getItem(ctx, "item").getItem();
    }

    private static ResourceLocation id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    private static int open(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (!MarketAccess.commandsAllowed(ctx.getSource())) {
            ctx.getSource().sendFailure(Component.translatable("message.dynamic_economy.block_only"));
            return 0;
        }
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MarketMenu.open(player);
        MarketSync.sendFullState(player);
        return 1;
    }

    private static int trade(CommandContext<CommandSourceStack> ctx, boolean selling) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Item item = item(ctx);
        int qty = IntegerArgumentType.getInteger(ctx, "qty");
        MarketService.TradeResult result = selling
                ? MarketService.trySell(player, item, qty, OptionalLong.empty())
                : MarketService.tryBuy(player, item, qty, OptionalLong.empty());

        if (result.ok()) {
            MarketSync.broadcastRow(player.server, item);
            MarketSync.sendPlayerState(player);
            String stock = describeStock(ctx.getSource().getServer(), item);
            String verb = selling ? "Sold" : "Bought";
            String direction = selling ? "received" : "paid";
            ctx.getSource().sendSuccess(() -> Component.literal("%s %d × %s for %d spurs (first unit %d); you %s %d spurs. Market stock: %s"
                    .formatted(verb, result.quantity(), id(item), result.total(), result.unitPrice(), direction, result.total(), stock)), false);
            return result.quantity();
        }
        ctx.getSource().sendFailure(Component.literal("%s failed: %s".formatted(selling ? "Sell" : "Buy", result.result())));
        return 0;
    }

    private static int listStock(CommandContext<CommandSourceStack> ctx) {
        if (MarketCatalog.INSTANCE.entries().isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("The market catalog is empty"));
            return 0;
        }
        for (CatalogEntry entry : MarketCatalog.INSTANCE.entries()) {
            String stock = describeStock(ctx.getSource().getServer(), entry.item());
            ctx.getSource().sendSuccess(() -> Component.literal("%s: %s".formatted(entry.itemId(), stock)), false);
        }
        return MarketCatalog.INSTANCE.entries().size();
    }

    private static int showStock(CommandContext<CommandSourceStack> ctx) {
        Item item = item(ctx);
        String stock = describeStock(ctx.getSource().getServer(), item);
        String listed = MarketCatalog.INSTANCE.get(item).isPresent() ? "" : " (not listed)";
        ctx.getSource().sendSuccess(() -> Component.literal("%s: %s%s".formatted(id(item), stock, listed)), false);
        return 1;
    }

    private static int showPrice(CommandContext<CommandSourceStack> ctx) {
        Item item = item(ctx);
        Optional<MarketService.Quote> quote = MarketService.quote(ctx.getSource().getServer(), item);
        if (quote.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(id(item) + " is not listed"));
            return 0;
        }
        MarketService.Quote q = quote.get();
        String sell = q.entry().canSell() ? q.sellPrice() + " spurs" : "disabled";
        String buy = q.entry().canBuy() ? q.buyPrice() + " spurs" : "disabled";
        String stock = describeStock(ctx.getSource().getServer(), item);
        String factor = q.entry().dynamic() ? "%.4f".formatted(q.factor()) : "fixed";
        ctx.getSource().sendSuccess(() -> Component.literal("%s — market pays %s, market charges %s, stock %s, price factor %s"
                .formatted(id(item), sell, buy, stock, factor)), false);
        return 1;
    }

    private static int setStock(CommandContext<CommandSourceStack> ctx) {
        Item item = item(ctx);
        long qty = LongArgumentType.getLong(ctx, "qty");
        if (MarketCatalog.INSTANCE.get(item).map(CatalogEntry::unlimitedStock).orElse(false)) {
            ctx.getSource().sendFailure(Component.literal(("%s has unlimited stock in the market catalog, so its stock can't be set. "
                    + "Set \"unlimited_stock\": false in the catalog and reload first.").formatted(id(item))));
            return 0;
        }
        MarketStockData.get(ctx.getSource().getServer()).setStock(id(item), qty);
        MarketSync.broadcastRow(ctx.getSource().getServer(), item);
        String listed = MarketCatalog.INSTANCE.get(item).isPresent() ? "" : " (warning: not listed in the catalog)";
        ctx.getSource().sendSuccess(() -> Component.literal("Set %s stock to %d%s".formatted(id(item), qty, listed)), true);
        return 1;
    }

    private static int resetPrice(CommandContext<CommandSourceStack> ctx) {
        Item item = item(ctx);
        var server = ctx.getSource().getServer();
        boolean moved = MarketStockData.get(server).resetFactor(id(item));
        MarketService.sampleHistory(server);
        MarketSync.broadcastRow(server, item);
        ctx.getSource().sendSuccess(() -> Component.literal(moved
                ? "Reset %s to its base price".formatted(id(item))
                : "%s was already at its base price".formatted(id(item))), true);
        return moved ? 1 : 0;
    }

    private static int resetAllPrices(CommandContext<CommandSourceStack> ctx) {
        var server = ctx.getSource().getServer();
        int count = MarketStockData.get(server).resetAllFactors();
        MarketService.sampleHistory(server);
        MarketSync.broadcastSnapshot(server);
        ctx.getSource().sendSuccess(() -> Component.literal("Reset %d item(s) to their base price".formatted(count)), true);
        return count;
    }

    private static final int TOP_ITEMS_SHOWN = 5;

    /**
     * Lifetime money flow. The market has no balance: payouts to sellers are new money and payments from buyers leave
     * the economy, so the figures are "created" and "removed", never revenue.
     */
    private static int stats(CommandContext<CommandSourceStack> ctx) {
        MarketStockData market = MarketStockData.get(ctx.getSource().getServer());
        describeTotals(ctx.getSource(), "Market since counting began", market.totals());
        List<Map.Entry<ResourceLocation, MarketStockData.Totals>> busiest = market.itemTotalsView().entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<ResourceLocation, MarketStockData.Totals> e) ->
                        e.getValue().paidOut() + e.getValue().collected()).reversed())
                .limit(TOP_ITEMS_SHOWN).toList();
        if (!busiest.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("Busiest items (by money moved):"), false);
            busiest.forEach(e -> ctx.getSource().sendSuccess(() -> Component.literal("  %s: created %,d, removed %,d"
                    .formatted(e.getKey(), e.getValue().paidOut(), e.getValue().collected())), false));
        }
        return 1;
    }

    private static int itemStats(CommandContext<CommandSourceStack> ctx) {
        Item item = item(ctx);
        describeTotals(ctx.getSource(), id(item).toString(), MarketStockData.get(ctx.getSource().getServer()).totals(id(item)));
        return 1;
    }

    private static void describeTotals(CommandSourceStack source, String title, MarketStockData.Totals totals) {
        long net = totals.netCreated();
        List.of(title + ":",
                "  Paid to sellers: %,d spurs for %,d items (new money)".formatted(totals.paidOut(), totals.unitsSold()),
                "  Paid by buyers: %,d spurs for %,d items (removed from the economy)".formatted(totals.collected(), totals.unitsBought()),
                "  Net: %s%,d spurs %s".formatted(net >= 0 ? "+" : "", net, net >= 0 ? "added to the economy" : "removed from the economy"))
                .forEach(line -> source.sendSuccess(() -> Component.literal(line), false));
    }

    /** {@code config/dynamic_economy/}: the only folder export writes to and import reads from. */
    private static Path configRoot() {
        return FMLPaths.CONFIGDIR.get().resolve(DynamicEconomy.MOD_ID);
    }

    private static int export(CommandContext<CommandSourceStack> ctx, Optional<String> modId) {
        if (modId.isPresent() && BuiltInRegistries.ITEM.keySet().stream().noneMatch(id -> id.getNamespace().equals(modId.get()))) {
            ctx.getSource().sendFailure(Component.literal("No items are registered by a mod called \"%s\"".formatted(modId.get())));
            return 0;
        }
        try {
            CatalogExport.Result result = CatalogExport.export(configRoot().resolve("exports"), modId, MarketCatalog.INSTANCE.entries());
            String folder = "config/" + DynamicEconomy.MOD_ID + "/exports/" + result.folder().getFileName();
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "Exported %d items to %s: %d already priced, %d new (templates for %d mod(s))"
                            .formatted(result.total(), folder, result.priced(), result.unpriced(), result.modsWithTemplates())), true);
            return result.total();
        } catch (IOException e) {
            DynamicEconomy.LOGGER.error("Market export failed", e);
            ctx.getSource().sendFailure(Component.literal("Export failed: " + e.getMessage()));
            return 0;
        }
    }

    private static final int REJECTIONS_SHOWN = 15;

    private static int importCsv(CommandContext<CommandSourceStack> ctx, boolean preview) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        String typed = StringArgumentType.getString(ctx, "path");
        Path csv;
        try {
            csv = CatalogImport.resolveInside(configRoot(), typed);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        if (!Files.isRegularFile(csv)) {
            source.sendFailure(Component.literal("No file at config/dynamic_economy/" + typed));
            return 0;
        }

        CatalogImport.Report report;
        try {
            report = CatalogImport.analyze(readText(csv));
        } catch (IOException e) {
            source.sendFailure(Component.literal("Could not read " + typed + ": " + e.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("%s%s: %d entries to write across %d mods, %d rows skipped (no base_price), %d rejected"
                .formatted(preview ? "Preview of " : "Import of ", typed, report.toWrite(), report.byMod().size(),
                        report.skippedBlank(), report.rejected().size())), false);
        for (int i = 0; i < report.rejected().size(); i++) {
            String reason = report.rejected().get(i);
            DynamicEconomy.LOGGER.warn("Market import {}: rejected {}", typed, reason);
            if (i < REJECTIONS_SHOWN) {
                source.sendFailure(Component.literal("  " + reason));
            }
        }
        if (report.rejected().size() > REJECTIONS_SHOWN) {
            source.sendFailure(Component.literal("  ...and %d more; every rejection is in the server log"
                    .formatted(report.rejected().size() - REJECTIONS_SHOWN)));
        }
        if (preview) {
            return report.toWrite();
        }
        if (report.toWrite() == 0) {
            source.sendFailure(Component.literal("Nothing to import, so no files were written"));
            return 0;
        }

        CatalogImport.Written written;
        try {
            written = CatalogImport.write(report, server.getWorldPath(LevelResource.DATAPACK_DIR));
        } catch (IOException e) {
            DynamicEconomy.LOGGER.error("Market import failed while writing", e);
            source.sendFailure(Component.literal("Writing the catalog files failed: " + e.getMessage()));
            return 0;
        }
        String backups = written.backups().isEmpty() ? ""
                : " (backed up %d replaced file(s) as .json.bak)".formatted(written.backups().size());
        source.sendSuccess(() -> Component.literal("Wrote %d catalog file(s) to <world>/datapacks/%s%s"
                .formatted(written.files().size(), CatalogImport.PACK_FOLDER, backups)), true);

        // Admin edits sit above catalog files, so they still win for these items.
        MarketOverrides overrides = MarketOverrides.get(server);
        List<String> edited = report.decoded().stream().map(CatalogEntry::itemId)
                .filter(id -> overrides.itemPatch(id) != null).map(ResourceLocation::toString).toList();
        if (!edited.isEmpty()) {
            source.sendFailure(Component.literal("%d imported item(s) also have in-game admin edits, which still take precedence: %s%s. Use /market admin revert <item> to drop them"
                    .formatted(edited.size(), String.join(", ", edited.subList(0, Math.min(5, edited.size()))), edited.size() > 5 ? ", ..." : "")));
        }

        PackRepository packs = server.getPackRepository();
        if (packs.getSelectedIds().contains(CatalogImport.PACK_ID)) {
            int count = MarketCatalog.INSTANCE.reloadNow(server.getResourceManager());
            DynamicEconomy.catalogReloaded(server);
            source.sendSuccess(() -> Component.literal("Reloaded market catalog: %d entries".formatted(count)), true);
        } else if (written.createdPack()) {
            packs.reload();
            if (packs.isAvailable(CatalogImport.PACK_ID)) {
                List<String> selected = new ArrayList<>(packs.getSelectedIds());
                selected.add(CatalogImport.PACK_ID);
                source.sendSuccess(() -> Component.literal("Enabled datapack %s; reloading data packs".formatted(CatalogImport.PACK_ID)), true);
                ReloadCommand.reloadPacks(selected, source);
            } else {
                source.sendFailure(Component.literal("Created the datapack but couldn't enable it. Run: /datapack enable \"%s\""
                        .formatted(CatalogImport.PACK_ID)));
            }
        } else {
            source.sendFailure(Component.literal("The datapack %s is disabled, so the import isn't live yet. Run: /datapack enable \"%s\""
                    .formatted(CatalogImport.PACK_ID, CatalogImport.PACK_ID)));
        }
        return report.toWrite();
    }

    /** UTF-8, or Windows-1252 for spreadsheets saved as plain "CSV" by Excel, which isn't UTF-8. */
    private static String readText(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("windows-1252"));
        }
    }

    /** "unlimited" for unlimited items (with the stored value kept for if it turns finite), else the stock. */
    private static String describeStock(MinecraftServer server, Item item) {
        long stored = MarketStockData.get(server).getStock(id(item));
        boolean unlimited = MarketCatalog.INSTANCE.get(item).map(CatalogEntry::unlimitedStock).orElse(false);
        return unlimited ? "unlimited (stored %d, used if it becomes finite)".formatted(stored) : String.valueOf(stored);
    }

    /**
     * Re-reads the market JSON from the packs already loaded, without a full /reload. Edits to existing files are
     * picked up; a newly added datapack still needs /reload. Stock is never touched apart from seeding new items.
     */
    private static int reload(CommandContext<CommandSourceStack> ctx) {
        var server = ctx.getSource().getServer();
        int count = MarketCatalog.INSTANCE.reloadNow(server.getResourceManager());
        DynamicEconomy.catalogReloaded(server);
        ctx.getSource().sendSuccess(() -> Component.literal("Reloaded market catalog: %d entries".formatted(count)), true);
        return count;
    }
}
