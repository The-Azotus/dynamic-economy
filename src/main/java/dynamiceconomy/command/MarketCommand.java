package dynamiceconomy.command;

import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.bank.WorldBank;
import dynamiceconomy.MarketAccess;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.service.PriceSuggestion;
import dynamiceconomy.catalog.CatalogOverlay;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.data.AuditLog;
import dynamiceconomy.data.MarketBackup;
import dynamiceconomy.data.MarketOverrides;
import dynamiceconomy.data.MarketStockData;
import dynamiceconomy.io.CatalogExport;
import dynamiceconomy.io.CatalogImport;
import dynamiceconomy.menu.MarketMenu;
import dynamiceconomy.network.MarketSync;
import dynamiceconomy.service.MarketDiagnostics;
import dynamiceconomy.service.MarketService;
import dynamiceconomy.service.MarketVisibility;
import dynamiceconomy.service.PriceModel;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.ReloadCommand;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
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
import java.util.Set;
import java.util.SortedSet;
import java.util.stream.Collectors;

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
                .then(ContractCommands.player())
                .then(withAdminEdits(Commands.literal("admin"), context)
                        .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("setstock")
                                .then(Commands.argument("item", ItemArgument.item(context))
                                        .then(Commands.argument("qty", LongArgumentType.longArg(0))
                                                .executes(MarketCommand::setStock))))
                        .then(Commands.literal("resetprice")
                                .then(Commands.literal("all")
                                        .executes(MarketCommand::resetAllPrices)
                                        .then(Commands.literal("preview")
                                                .executes(ctx -> previewReset(ctx, "every item", id -> true))))
                                .then(Commands.literal("tag")
                                        .then(Commands.argument("tag", ResourceLocationArgument.id())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggestResource(
                                                        BuiltInRegistries.ITEM.getTagNames().map(TagKey::location), builder))
                                                .executes(ctx -> resetTagPrices(ctx, false))
                                                .then(Commands.literal("preview")
                                                        .executes(ctx -> resetTagPrices(ctx, true)))))
                                .then(Commands.argument("item", ItemArgument.item(context))
                                        .executes(MarketCommand::resetPrice)))
                        .then(Commands.literal("prune")
                                .executes(ctx -> prune(ctx, false))
                                .then(Commands.literal("confirm")
                                        .executes(ctx -> prune(ctx, true))))
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
                        .then(Commands.literal("suggest")
                                .requires(source -> source.hasPermission(Commands.LEVEL_OWNERS))
                                .executes(ctx -> suggest(ctx, Optional.empty()))
                                .then(Commands.argument("modid", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                BuiltInRegistries.ITEM.keySet().stream().map(ResourceLocation::getNamespace).distinct(), builder))
                                        .executes(ctx -> suggest(ctx, Optional.of(StringArgumentType.getString(ctx, "modid"))))))
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
        AdminRecoveryCommands.addTo(admin);
        AdminBankCommands.addTo(admin);
        ContractCommands.addTo(admin);
        return admin;
    }

    private static Item item(CommandContext<CommandSourceStack> ctx) {
        return ItemArgument.getItem(ctx, "item").getItem();
    }

    private static ResourceLocation id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    /** Whether the command was run by a player who can't see this item; the console sees everything. */
    private static boolean hiddenFrom(CommandSourceStack source, Item item) {
        return source.getPlayer() != null && MarketVisibility.hidden(source.getPlayer(), item);
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
            String unit = unit(ctx.getSource());
            ctx.getSource().sendSuccess(() -> Component.literal("%s %d × %s for %d %s (first unit %d); you %s %d %s. Market stock: %s"
                    .formatted(verb, result.quantity(), id(item), result.total(), unit, result.unitPrice(), direction,
                            result.total(), unit, stock)), false);
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
        int shown = 0;
        for (CatalogEntry entry : MarketCatalog.INSTANCE.entries()) {
            if (hiddenFrom(ctx.getSource(), entry.item())) {
                continue;
            }
            String stock = describeStock(ctx.getSource().getServer(), entry.item());
            ctx.getSource().sendSuccess(() -> Component.literal("%s: %s".formatted(entry.itemId(), stock)), false);
            shown++;
        }
        return shown;
    }

    private static int showStock(CommandContext<CommandSourceStack> ctx) {
        Item item = item(ctx);
        if (hiddenFrom(ctx.getSource(), item)) {
            ctx.getSource().sendSuccess(() -> Component.literal("%s: 0 (not listed)".formatted(id(item))), false);
            return 1;
        }
        String stock = describeStock(ctx.getSource().getServer(), item);
        String listed = MarketCatalog.INSTANCE.get(item).isPresent() ? "" : " (not listed)";
        ctx.getSource().sendSuccess(() -> Component.literal("%s: %s%s".formatted(id(item), stock, listed)), false);
        return 1;
    }

    private static int showPrice(CommandContext<CommandSourceStack> ctx) {
        Item item = item(ctx);
        Optional<MarketService.Quote> quote = MarketService.quote(ctx.getSource().getServer(), item);
        if (quote.isEmpty() || hiddenFrom(ctx.getSource(), item)) {
            ctx.getSource().sendFailure(Component.literal(id(item) + " is not listed"));
            return 0;
        }
        MarketService.Quote q = quote.get();
        String unit = unit(ctx.getSource());
        String sell = q.entry().canSell() ? q.sellPrice() + " " + unit : "disabled";
        String buy = q.entry().canBuy() ? q.buyPrice() + " " + unit : "disabled";
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
        long before = MarketStockData.get(ctx.getSource().getServer()).getStock(id(item));
        MarketStockData.get(ctx.getSource().getServer()).setStock(id(item), qty);
        MarketSync.broadcastRow(ctx.getSource().getServer(), item);
        AuditLog.record(ctx.getSource(), "setstock", id(item).toString(), "ok", new JsonPrimitive(before), new JsonPrimitive(qty));
        String listed = MarketCatalog.INSTANCE.get(item).isPresent() ? "" : " (warning: not listed in the catalog)";
        ctx.getSource().sendSuccess(() -> Component.literal("Set %s stock to %d%s".formatted(id(item), qty, listed)), true);
        return 1;
    }

    private static int resetPrice(CommandContext<CommandSourceStack> ctx) {
        Item item = item(ctx);
        var server = ctx.getSource().getServer();
        MarketStockData.Factor before = MarketStockData.get(server).factorsView().get(id(item));
        boolean moved = MarketStockData.get(server).resetFactor(id(item));
        MarketService.sampleHistory(server);
        MarketSync.broadcastRow(server, item);
        AuditLog.record(ctx.getSource(), "resetprice", id(item).toString(), moved ? "ok" : "already at base",
                before == null ? null : new JsonPrimitive(before.value()), new JsonPrimitive(1.0));
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
        AuditLog.record(ctx.getSource(), "resetprice", "all", "ok: %d item(s) reset".formatted(count), null, null);
        ctx.getSource().sendSuccess(() -> Component.literal("Reset %d item(s) to their base price".formatted(count)), true);
        return count;
    }

    /** Puts every item in a tag back to its base price, or with {@code preview} says what that would do. */
    private static int resetTagPrices(CommandContext<CommandSourceStack> ctx, boolean preview) {
        ResourceLocation tag = ResourceLocationArgument.getId(ctx, "tag");
        Set<ResourceLocation> members = CatalogOverlay.registryTag(tag).stream().map(MarketCommand::id).collect(Collectors.toSet());
        if (members.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No items are in the tag #%s (check the spelling)".formatted(tag)));
            return 0;
        }
        if (preview) {
            return previewReset(ctx, "#" + tag, members::contains);
        }
        var server = ctx.getSource().getServer();
        MarketStockData market = MarketStockData.get(server);
        int count = 0;
        for (ResourceLocation id : members) {
            if (market.resetFactor(id)) {
                count++;
            }
        }
        MarketService.sampleHistory(server);
        MarketSync.broadcastSnapshot(server);
        int reset = count;
        AuditLog.record(ctx.getSource(), "resetprice", "#" + tag, "ok: %d item(s) reset".formatted(reset), null, null);
        ctx.getSource().sendSuccess(() -> Component.literal("Reset %d item(s) in #%s to their base price".formatted(reset, tag)), true);
        return count;
    }

    /** Lists the items a price reset would move back to base, furthest first. Changes nothing. */
    private static int previewReset(CommandContext<CommandSourceStack> ctx, String what, java.util.function.Predicate<ResourceLocation> include) {
        var server = ctx.getSource().getServer();
        MarketStockData market = MarketStockData.get(server);
        long now = MarketStockData.now(server);
        Map<ResourceLocation, Double> moved = new java.util.HashMap<>();
        for (ResourceLocation id : market.factorsView().keySet()) {
            if (include.test(id)) {
                double factor = BuiltInRegistries.ITEM.getOptional(id).flatMap(MarketCatalog.INSTANCE::get)
                        .map(entry -> MarketService.currentFactor(entry, PriceModel.inputs(entry), market, now))
                        .orElse(market.factorsView().get(id).value());
                if (factor != 1.0) {
                    moved.put(id, factor);
                }
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add("Preview of resetting %s (nothing has been changed): %d item(s) would go back to base price"
                .formatted(what, moved.size()));
        moved.entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<ResourceLocation, Double> e) -> Math.abs(e.getValue() - 1)).reversed())
                .limit(TOP_ITEMS_SHOWN)
                .forEach(e -> lines.add("  %s: now %d%% of base".formatted(e.getKey(), Math.round(e.getValue() * 100))));
        lines.forEach(line -> ctx.getSource().sendSuccess(() -> Component.literal(line), false));
        return moved.size();
    }

    private static final int PRUNE_SHOWN = 10;

    /**
     * Deletes saved data (stock, prices, history, item money counts, admin edits) for items that no longer exist in
     * the game, typically because their mod was removed. Without {@code confirm} it only lists them.
     */
    private static int prune(CommandContext<CommandSourceStack> ctx, boolean confirm) {
        var server = ctx.getSource().getServer();
        MarketStockData market = MarketStockData.get(server);
        MarketOverrides overrides = MarketOverrides.get(server);
        SortedSet<ResourceLocation> gone = MarketDiagnostics.unregisteredItems(market, overrides.snapshot());
        if (gone.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("Nothing to prune: every item with saved market data exists"), false);
            return 0;
        }
        if (!confirm) {
            List<String> lines = new ArrayList<>();
            lines.add("%d item(s) with saved market data no longer exist in the game:".formatted(gone.size()));
            gone.stream().limit(PRUNE_SHOWN).forEach(id -> lines.add("  " + id));
            if (gone.size() > PRUNE_SHOWN) {
                lines.add("  ...and %d more".formatted(gone.size() - PRUNE_SHOWN));
            }
            lines.add("/market admin prune confirm deletes their stock, prices, price history, money counts and admin "
                    + "edits. The overall money counts are kept. A backup of stock, prices and edits is taken first");
            lines.forEach(line -> ctx.getSource().sendSuccess(() -> Component.literal(line), false));
            return gone.size();
        }
        String backup;
        try {
            backup = MarketBackup.create(server, "prune");
        } catch (IOException e) {
            ctx.getSource().sendFailure(Component.literal("Couldn't back up first, so nothing was deleted: " + e.getMessage()));
            return 0;
        }
        for (ResourceLocation id : gone) {
            market.forget(id);
            overrides.revertItem(id);
        }
        com.google.gson.JsonArray removed = new com.google.gson.JsonArray();
        gone.forEach(id -> removed.add(id.toString()));
        AuditLog.record(ctx.getSource(), "prune", "%d item(s)".formatted(gone.size()), "ok, backed up first as " + backup, removed, null);
        ctx.getSource().sendSuccess(() -> Component.literal("Deleted saved market data for %d item(s) that no longer exist, e.g. %s. "
                .formatted(gone.size(), gone.first()) + "The state before is saved as \"%s\"".formatted(backup)), true);
        return gone.size();
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
        String unit = unit(source);
        List.of(title + ":",
                "  Paid to sellers: %,d %s for %,d items (new money)".formatted(totals.paidOut(), unit, totals.unitsSold()),
                "  Paid by buyers: %,d %s for %,d items (removed from the economy)".formatted(totals.collected(), unit, totals.unitsBought()),
                "  Net: %s%,d %s %s".formatted(net >= 0 ? "+" : "", net, unit, net >= 0 ? "added to the economy" : "removed from the economy"))
                .forEach(line -> source.sendSuccess(() -> Component.literal(line), false));
    }

    /** The world's money unit: spurs with Numismatics' bank, bits with the mod's own. */
    static String unit(CommandSourceStack source) {
        return WorldBank.kind(source.getServer()).unit();
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
            CatalogExport.Result result = CatalogExport.export(configRoot().resolve("exports"), modId, MarketCatalog.INSTANCE.entries(),
                    MarketCatalog.INSTANCE::sources);
            String folder = "config/" + DynamicEconomy.MOD_ID + "/exports/" + result.folder().getFileName();
            AuditLog.record(ctx.getSource(), "export", modId.orElse("all mods"), "ok: %d items to %s".formatted(result.total(), folder), null, null);
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

    /**
     * Prices unlisted items from their recipes, using the live catalog as the known prices, and writes the result as
     * a CSV that can be reviewed and imported. Changes nothing in the market.
     */
    private static int suggest(CommandContext<CommandSourceStack> ctx, Optional<String> modId) {
        if (modId.isPresent() && BuiltInRegistries.ITEM.keySet().stream().noneMatch(id -> id.getNamespace().equals(modId.get()))) {
            ctx.getSource().sendFailure(Component.literal("No items are registered by a mod called \"%s\"".formatted(modId.get())));
            return 0;
        }
        MinecraftServer server = ctx.getSource().getServer();
        Map<Item, CatalogEntry> catalog = new java.util.LinkedHashMap<>();
        MarketCatalog.INSTANCE.entries().forEach(entry -> catalog.put(entry.item(), entry));
        PriceSuggestion.Result result = PriceSuggestion.suggest(server.getRecipeManager().getRecipes(), server.registryAccess(), catalog);
        try {
            CatalogExport.SuggestionResult written = CatalogExport.exportSuggestions(configRoot().resolve("exports"), modId,
                    catalog.values(), MarketCatalog.INSTANCE::sources, result);
            // Listed prices that recipes can beat are written alongside, since the recipes are already loaded.
            List<PriceSuggestion.Path> paths = PriceSuggestion.paths(server.getRecipeManager().getRecipes(), server.registryAccess());
            List<PriceSuggestion.CraftProfit> profits = PriceSuggestion.craftProfits(paths, catalog);
            Map<Item, CatalogEntry> market = withSuggestions(catalog, result);
            List<PriceSuggestion.ChainProfit> chains = PriceSuggestion.chainProfits(paths, market);
            java.nio.file.Files.write(written.folder().resolve("chain_profits.txt"), chainLines(chains, catalog));
            java.nio.file.Files.write(written.folder().resolve("chain_details.txt"), chainDetails(chains, result, catalog, market));
            List<String> profitLines = new ArrayList<>(List.of(
                    "Listed items whose ingredients can be bought for less than the item sells for (at normal prices)."));
            Map<PriceSuggestion.ProfitKind, String> headings = Map.of(
                    PriceSuggestion.ProfitKind.PRICE_GAP,
                    "Price gaps: raise the ingredients' prices, lower the item's, or set can_sell false on it.",
                    PriceSuggestion.ProfitKind.MAY_HIDE_INPUTS,
                    "Check by hand: these recipe types may take fluids, extra items or ingredient amounts the check can't see.",
                    PriceSuggestion.ProfitKind.MULTIPLIES_INPUT,
                    "Recipes that make more of their own ingredient: no price stops these; sell impact limits what they earn.");
            for (PriceSuggestion.ProfitKind kind : PriceSuggestion.ProfitKind.values()) {
                List<PriceSuggestion.CraftProfit> ofKind = profits.stream().filter(p -> p.kind() == kind).toList();
                if (ofKind.isEmpty()) {
                    continue;
                }
                profitLines.add("");
                profitLines.add(headings.get(kind));
                ofKind.forEach(p -> profitLines.add("%s via %s: ingredients cost %s, result sells for %s".formatted(
                        BuiltInRegistries.ITEM.getKey(p.output()), p.recipe(), p.buyCost().setScale(2, java.math.RoundingMode.HALF_UP),
                        p.sellValue().setScale(2, java.math.RoundingMode.HALF_UP))));
            }
            java.nio.file.Files.write(written.folder().resolve("craft_profits.txt"), profitLines);
            List<String> skippedLines = new ArrayList<>(List.of("Recipes suggest couldn't use, by recipe type and reason."));
            result.unusable().entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> skippedLines.add(e.getValue() + " " + e.getKey()));
            java.nio.file.Files.write(written.folder().resolve("skipped_recipes.txt"), skippedLines);
            String file = "config/" + DynamicEconomy.MOD_ID + "/exports/" + written.folder().getFileName() + "/suggested.csv";
            AuditLog.record(ctx.getSource(), "suggest", modId.orElse("all mods"), "ok: %d suggested to %s".formatted(written.suggested(), file),
                    null, null);
            List<String> lines = new ArrayList<>();
            lines.add("Wrote " + file + ". Nothing on the market changed.");
            lines.add("  %d already listed, %d priced from recipes (%d of them worth under 1 spur, left blank; %d from recipe types worth checking)"
                    .formatted(written.listed(), written.suggested(), written.underOneSpur(), written.toReview()));
            lines.add("  %d made only from unpriced items, %d with no usable recipe (these need hand-set prices)"
                    .formatted(written.unpricedInputs(), written.noRecipe()));
            if (written.leftOut() > 0) {
                lines.add("  %d decorative variants (%s) left out of the market".formatted(written.leftOut(),
                        String.join(", ", new java.util.TreeSet<>(PriceSuggestion.LEFT_OUT_MODS))));
            }
            if (result.premium() < PriceSuggestion.PREMIUM) {
                lines.add("  Processing premium lowered to %s to stay at or below the smallest markup in the catalog"
                        .formatted(java.math.BigDecimal.valueOf(result.premium()).stripTrailingZeros().toPlainString()));
            }
            if (!result.unusable().isEmpty()) {
                lines.add("  Recipes skipped: " + result.unusable().entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(5)
                        .map(e -> e.getValue() + " " + e.getKey()).collect(java.util.stream.Collectors.joining(", "))
                        + (result.unusable().size() > 5 ? ", ... (all in skipped_recipes.txt)" : ""));
            }
            if (!result.settled()) {
                lines.add("  Warning: some prices kept falling through a recipe loop; check the log");
            }
            Map<PriceSuggestion.ProfitKind, Long> byKind = profits.stream()
                    .collect(java.util.stream.Collectors.groupingBy(PriceSuggestion.CraftProfit::kind, java.util.stream.Collectors.counting()));
            lines.add(profits.isEmpty() ? "  No listed item can be crafted from bought ingredients at a profit"
                    : ("  %d recipe(s) turn bought ingredients into a profit (%d price gaps, %d to check by hand, %d that multiply"
                    + " their own ingredient): see craft_profits.txt in the same folder").formatted(profits.size(),
                    byKind.getOrDefault(PriceSuggestion.ProfitKind.PRICE_GAP, 0L),
                    byKind.getOrDefault(PriceSuggestion.ProfitKind.MAY_HIDE_INPUTS, 0L),
                    byKind.getOrDefault(PriceSuggestion.ProfitKind.MULTIPLIES_INPUT, 0L)));
            long listedChains = chains.stream().filter(c -> catalog.containsKey(c.item())).count();
            lines.add(chains.isEmpty() ? "  No item can be bought and processed through several recipes at a profit"
                    : "  %d item(s) can be bought and processed into more than they cost (%d listed, %d suggested): see chain_profits.txt"
                    .formatted(chains.size(), listedChains, chains.size() - listedChains));
            lines.forEach(line -> ctx.getSource().sendSuccess(() -> Component.literal(line), true));
            return written.suggested();
        } catch (IOException e) {
            DynamicEconomy.LOGGER.error("Market price suggestion failed", e);
            ctx.getSource().sendFailure(Component.literal("Suggest failed: " + e.getMessage()));
            return 0;
        }
    }

    /** The catalog plus every priced suggestion, as the market would list them if the suggestions were imported. */
    private static Map<Item, CatalogEntry> withSuggestions(Map<Item, CatalogEntry> catalog, PriceSuggestion.Result result) {
        Map<Item, CatalogEntry> market = new java.util.HashMap<>(catalog);
        result.suggestions().values().forEach(s -> s.basePrice()
                .flatMap(price -> PriceSuggestion.asEntry(s.item(), price, s.itemClass()))
                .ifPresent(entry -> market.put(s.item(), entry)));
        return market;
    }

    private static final int CHAIN_STEPS_SHOWN = 3;

    private static List<String> chainLines(List<PriceSuggestion.ChainProfit> chains, Map<Item, CatalogEntry> catalog) {
        List<String> lines = new ArrayList<>(List.of(
                "Items that can be bought and processed through one or more recipes into more than they cost, with the",
                "suggested prices imported. Usually the first item is priced too low for what it processes into: raise it,",
                "or stop the market selling it (can_buy false). A chain marked 'check' uses a recipe type that may hide inputs."));
        for (boolean listed : new boolean[]{true, false}) {
            List<PriceSuggestion.ChainProfit> group = chains.stream().filter(c -> catalog.containsKey(c.item()) == listed).toList();
            if (group.isEmpty()) {
                continue;
            }
            lines.add("");
            lines.add(listed ? "Listed items (fix these first; suggested items below often follow from them):" : "Suggested items:");
            for (PriceSuggestion.ChainProfit c : group) {
                // Long chains are shortened to their first steps and the item sold at the end.
                StringBuilder chain = new StringBuilder();
                List<PriceSuggestion.Path> steps = c.steps();
                for (int i = 0; i < steps.size(); i++) {
                    if (i == CHAIN_STEPS_SHOWN && steps.size() > CHAIN_STEPS_SHOWN + 1) {
                        chain.append(" -> ... (").append(steps.size() - CHAIN_STEPS_SHOWN - 1).append(" more)");
                        i = steps.size() - 1;
                    }
                    chain.append(" -> ").append(BuiltInRegistries.ITEM.getKey(steps.get(i).output()))
                            .append(" (").append(steps.get(i).recipe()).append(')');
                }
                lines.add("%s%s costs %s, processes into %s:%s".formatted(c.complete() ? "" : "[check] ",
                        BuiltInRegistries.ITEM.getKey(c.item()), c.buyCost().setScale(2, java.math.RoundingMode.HALF_UP),
                        c.value().setScale(2, java.math.RoundingMode.HALF_UP), chain));
            }
        }
        return lines;
    }

    private static final int DETAILS_SHOWN = 25;

    /**
     * Every step, with its amounts and each output's price, of the most-raised suggestions, the most profitable chains
     * and every complete chain from a listed item: the step that makes more than it takes is the one to fix.
     */
    private static List<String> chainDetails(List<PriceSuggestion.ChainProfit> chains, PriceSuggestion.Result result,
                                             Map<Item, CatalogEntry> catalog, Map<Item, CatalogEntry> market) {
        List<String> lines = new ArrayList<>(List.of("Full recipe chains behind the largest raises and chain profits, step by step."));
        lines.add("");
        lines.add("Suggestions raised furthest above their recipe cost:");
        result.suggestions().values().stream().filter(PriceSuggestion.Suggestion::raised)
                .sorted(java.util.Comparator.comparing((PriceSuggestion.Suggestion s) -> java.math.BigDecimal.valueOf(s.basePrice().orElse(0))
                        .divide(s.cost().max(new java.math.BigDecimal("0.01")), java.math.MathContext.DECIMAL64)).reversed())
                .limit(DETAILS_SHOWN).forEach(s -> {
                    lines.add("%s: recipe cost %s, raised to %d".formatted(BuiltInRegistries.ITEM.getKey(s.item()),
                            s.cost().setScale(2, java.math.RoundingMode.HALF_UP), s.basePrice().orElse(0)));
                    s.raisedVia().forEach(step -> lines.add(stepLine(step, market)));
                });
        for (boolean complete : new boolean[]{true, false}) {
            lines.add("");
            lines.add(complete ? "Most profitable complete chains:" : "Most profitable chains marked 'check':");
            chains.stream().filter(c -> c.complete() == complete).limit(DETAILS_SHOWN).forEach(c -> {
                lines.add("%s: costs %s, processes into %s".formatted(BuiltInRegistries.ITEM.getKey(c.item()),
                        c.buyCost().setScale(2, java.math.RoundingMode.HALF_UP), c.value().setScale(2, java.math.RoundingMode.HALF_UP)));
                c.steps().forEach(step -> lines.add(stepLine(step, market)));
            });
        }
        lines.add("");
        lines.add("Every complete chain from a listed item:");
        chains.stream().filter(c -> c.complete() && catalog.containsKey(c.item())).forEach(c -> {
            lines.add("%s: costs %s, processes into %s".formatted(BuiltInRegistries.ITEM.getKey(c.item()),
                    c.buyCost().setScale(2, java.math.RoundingMode.HALF_UP), c.value().setScale(2, java.math.RoundingMode.HALF_UP)));
            c.steps().forEach(step -> lines.add(stepLine(step, market)));
        });
        return lines;
    }

    /** {@code  recipe [type]: 9 x a|b + 1 x c -> 1 x d (price 5)}, showing at most three options per slot. */
    private static String stepLine(PriceSuggestion.Path step, Map<Item, CatalogEntry> market) {
        String inputs = step.slots().stream().map(slot -> {
            String options = slot.options().stream().limit(3).map(i -> BuiltInRegistries.ITEM.getKey(i).toString())
                    .collect(java.util.stream.Collectors.joining("|")) + (slot.options().size() > 3 ? "|..." : "");
            return amount(slot.amount()) + " x " + options;
        }).collect(java.util.stream.Collectors.joining(" + "));
        CatalogEntry out = market.get(step.output());
        return "    %s [%s]: %s -> %s x %s%s%s".formatted(step.recipe(), step.type(), inputs, amount(step.count()),
                BuiltInRegistries.ITEM.getKey(step.output()), out == null ? "" : " (price " + out.basePrice() + ")",
                step.complete() ? "" : " (check)");
    }

    private static String amount(double amount) {
        return java.math.BigDecimal.valueOf(amount).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
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
            AuditLog.record(source, "import", typed, "failed: " + e.getMessage(), null, null);
            source.sendFailure(Component.literal("Writing the catalog files failed: " + e.getMessage()));
            return 0;
        }
        com.google.gson.JsonArray files = new com.google.gson.JsonArray();
        written.files().forEach(file -> files.add(file.getFileName().toString()));
        AuditLog.record(source, "import", typed, "ok: %d entries, %d rejected rows, %d file(s) backed up as .json.bak"
                .formatted(report.toWrite(), report.rejected().size(), written.backups().size()), null, files);
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
        AuditLog.record(ctx.getSource(), "reload", "catalog", "ok: %d entries".formatted(count), null, null);
        ctx.getSource().sendSuccess(() -> Component.literal("Reloaded market catalog: %d entries".formatted(count)), true);
        return count;
    }
}
