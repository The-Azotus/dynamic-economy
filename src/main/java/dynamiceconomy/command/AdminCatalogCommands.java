package dynamiceconomy.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.MarketConfig;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.catalog.CatalogJson;
import dynamiceconomy.catalog.CatalogOverlay;
import dynamiceconomy.catalog.FieldSource;
import dynamiceconomy.catalog.ItemClasses;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.data.AuditLog;
import dynamiceconomy.data.MarketOverrides;
import dynamiceconomy.data.MarketStockData;
import dynamiceconomy.network.MarketSync;
import dynamiceconomy.service.ContractPricing;
import dynamiceconomy.service.MarketDiagnostics;
import dynamiceconomy.service.MarketService;
import dynamiceconomy.service.PriceModel;
import dynamiceconomy.service.PriceSuggestion;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * In-game catalog editing: {@code /market admin setprice|set|unset|remove|revert|clearstock|info} for one item, and
 * {@code /market admin tag <tag> ...} for live tag rules. Edits are saved with the world and sit on top of the
 * catalog files (see {@link MarketOverrides}). Every edit is checked by rebuilding the catalog with it; if the result
 * breaks a catalog rule, the edit is undone and the reason shown.
 */
final class AdminCatalogCommands {
    /**
     * A catalog field that can be set by command, with its argument type, how to read it as JSON and, optionally,
     * what to suggest.
     */
    private record Field(String name, ArgumentType<?> type, BiFunction<CommandContext<CommandSourceStack>, String, JsonElement> read,
                         SuggestionProvider<CommandSourceStack> suggestions) {
        Field(String name, ArgumentType<?> type, BiFunction<CommandContext<CommandSourceStack>, String, JsonElement> read) {
            this(name, type, read, null);
        }
    }

    private static final List<Field> FIELDS = List.of(
            new Field("class", ResourceLocationArgument.id(), (c, a) -> new JsonPrimitive(typed(c, a)),
                    (c, builder) -> SharedSuggestionProvider.suggest(ItemClasses.all().keySet().stream().map(ItemClasses::shortName), builder)),
            new Field("base_price", IntegerArgumentType.integer(1), (c, a) -> new JsonPrimitive(IntegerArgumentType.getInteger(c, a))),
            decimal("markup", 0, 1000),
            decimal("sell_fee", 0, 0.99),
            decimal("min_buy_markup", 0, 1000),
            new Field("start_stock", LongArgumentType.longArg(0), (c, a) -> new JsonPrimitive(LongArgumentType.getLong(c, a))),
            bool("can_sell"),
            bool("can_buy"),
            bool("unlimited_stock"),
            bool("dynamic"),
            decimal("sell_impact", 0, 0.5),
            decimal("buy_impact", 0, 0.5),
            decimal("recovery", 0, 1),
            decimal("floor", 0.001, 1),
            decimal("ceiling", 1, 1000),
            whole("contract_weight", 0, 1000),
            whole("contract_min_size", 1, 100_000),
            whole("contract_max_size", 1, 100_000),
            decimal("contract_premium", 0, 100));

    private static final int PROBLEMS_SHOWN = 5;

    private AdminCatalogCommands() {
    }

    /**
     * An argument exactly as typed. A class name without a namespace means this mod's class, but the ID argument would
     * read {@code rare_resource} as {@code minecraft:rare_resource}.
     */
    private static String typed(CommandContext<CommandSourceStack> ctx, String argument) {
        return ctx.getNodes().stream().filter(node -> node.getNode().getName().equals(argument)).findFirst()
                .map(node -> node.getRange().get(ctx.getInput())).orElseThrow();
    }

    private static Field decimal(String name, double min, double max) {
        return new Field(name, DoubleArgumentType.doubleArg(min, max), (c, a) -> new JsonPrimitive(DoubleArgumentType.getDouble(c, a)));
    }

    private static Field whole(String name, int min, int max) {
        return new Field(name, IntegerArgumentType.integer(min, max), (c, a) -> new JsonPrimitive(IntegerArgumentType.getInteger(c, a)));
    }

    private static Field bool(String name) {
        return new Field(name, BoolArgumentType.bool(), (c, a) -> new JsonPrimitive(BoolArgumentType.getBool(c, a)));
    }

    /** Adds the editing commands to the {@code /market admin} node. */
    static void addTo(LiteralArgumentBuilder<CommandSourceStack> admin, CommandBuildContext context) {
        admin.then(Commands.literal("setprice")
                .then(Commands.argument("item", ItemArgument.item(context))
                        .then(Commands.argument("base_price", IntegerArgumentType.integer(1))
                                .executes(ctx -> editItem(ctx, "base_price", new JsonPrimitive(IntegerArgumentType.getInteger(ctx, "base_price")))))));
        admin.then(fieldCommands(Commands.literal("set"), Commands.argument("item", ItemArgument.item(context)),
                (ctx, field, value, preview) -> editItem(ctx, field, value), false));
        admin.then(Commands.literal("unset")
                .then(fieldNames(Commands.argument("item", ItemArgument.item(context)), AdminCatalogCommands::unsetItem)));
        admin.then(Commands.literal("remove")
                .then(Commands.argument("item", ItemArgument.item(context)).executes(AdminCatalogCommands::removeItem)));
        admin.then(Commands.literal("revert")
                .then(Commands.argument("item", ItemArgument.item(context)).executes(AdminCatalogCommands::revertItem)));
        admin.then(Commands.literal("clearstock")
                .then(Commands.argument("item", ItemArgument.item(context)).executes(AdminCatalogCommands::clearStock)));
        admin.then(Commands.literal("info")
                .then(Commands.argument("item", ItemArgument.item(context)).executes(AdminCatalogCommands::info)));

        var tag = Commands.argument("tag", ResourceLocationArgument.id())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggestResource(
                        BuiltInRegistries.ITEM.getTagNames().map(TagKey::location), builder));
        // Tag rules can change many items at once, so each change can be previewed first by adding "preview".
        tag.then(Commands.literal("setprice")
                .then(Commands.argument("base_price", IntegerArgumentType.integer(1))
                        .executes(ctx -> editTag(ctx, "base_price", new JsonPrimitive(IntegerArgumentType.getInteger(ctx, "base_price")), false))
                        .then(Commands.literal("preview")
                                .executes(ctx -> editTag(ctx, "base_price", new JsonPrimitive(IntegerArgumentType.getInteger(ctx, "base_price")), true)))));
        tag.then(fieldCommands(Commands.literal("set"), null, AdminCatalogCommands::editTag, true));
        tag.then(fieldNames(Commands.literal("unset"), AdminCatalogCommands::unsetTag));
        tag.then(Commands.literal("remove").executes(ctx -> removeTag(ctx, false))
                .then(Commands.literal("preview").executes(ctx -> removeTag(ctx, true))));
        tag.then(Commands.literal("revert").executes(ctx -> revertTag(ctx, false))
                .then(Commands.literal("preview").executes(ctx -> revertTag(ctx, true))));
        tag.then(Commands.literal("info").executes(AdminCatalogCommands::tagInfo));
        admin.then(Commands.literal("tag").then(tag));
        admin.then(Commands.literal("tags").executes(AdminCatalogCommands::listTags));
        admin.then(Commands.literal("classes").executes(AdminCatalogCommands::listClasses));
        admin.then(Commands.literal("diagnose").executes(ctx -> diagnose(ctx, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> diagnose(ctx, IntegerArgumentType.getInteger(ctx, "page")))));
    }

    @FunctionalInterface
    private interface FieldEdit {
        int run(CommandContext<CommandSourceStack> ctx, String field, JsonElement value, boolean preview)
                throws com.mojang.brigadier.exceptions.CommandSyntaxException;
    }

    @FunctionalInterface
    private interface FieldAction {
        int run(CommandContext<CommandSourceStack> ctx, String field) throws com.mojang.brigadier.exceptions.CommandSyntaxException;
    }

    /** {@code <literal> [target] <field> <value> [preview]} for every settable field. */
    private static <T extends ArgumentBuilder<CommandSourceStack, T>> LiteralArgumentBuilder<CommandSourceStack> fieldCommands(
            LiteralArgumentBuilder<CommandSourceStack> literal, ArgumentBuilder<CommandSourceStack, ?> target, FieldEdit edit,
            boolean withPreview) {
        ArgumentBuilder<CommandSourceStack, ?> parent = target == null ? literal : target;
        for (Field field : FIELDS) {
            var value = Commands.argument("value", field.type())
                    .executes(ctx -> edit.run(ctx, field.name(), field.read().apply(ctx, "value"), false));
            if (field.suggestions() != null) {
                value.suggests(field.suggestions());
            }
            if (withPreview) {
                value.then(Commands.literal("preview")
                        .executes(ctx -> edit.run(ctx, field.name(), field.read().apply(ctx, "value"), true)));
            }
            parent.then(Commands.literal(field.name()).then(value));
        }
        if (target != null) {
            literal.then(target);
        }
        return literal;
    }

    /** {@code <parent> <field>} for every settable field, e.g. for unset. */
    private static <B extends ArgumentBuilder<CommandSourceStack, ?>> B fieldNames(B parent, FieldAction action) {
        for (Field field : FIELDS) {
            parent.then(Commands.literal(field.name()).executes(ctx -> action.run(ctx, field.name())));
        }
        return parent;
    }

    // ---- One item ---------------------------------------------------------------------------------------------

    private static Item item(CommandContext<CommandSourceStack> ctx) {
        return ItemArgument.getItem(ctx, "item").getItem();
    }

    private static ResourceLocation id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    private static int editItem(CommandContext<CommandSourceStack> ctx, String field, JsonElement value) {
        ResourceLocation item = id(item(ctx));
        return apply(ctx, Set.of(item), data -> data.setItemField(item, field, value),
                "Set %s for %s to %s".formatted(field, item, value), item, "set " + field, null);
    }

    private static int unsetItem(CommandContext<CommandSourceStack> ctx, String field) {
        ResourceLocation item = id(item(ctx));
        JsonObject patch = MarketOverrides.get(ctx.getSource().getServer()).itemPatch(item);
        if (patch == null || !patch.has(field)) {
            ctx.getSource().sendFailure(Component.literal("%s has no admin edit for %s".formatted(item, field)));
            return 0;
        }
        return apply(ctx, Set.of(item), data -> data.unsetItemField(item, field),
                "Removed the %s edit for %s; the catalog files decide it again".formatted(field, item), item, "unset " + field, null);
    }

    private static int removeItem(CommandContext<CommandSourceStack> ctx) {
        ResourceLocation item = id(item(ctx));
        return apply(ctx, Set.of(item), data -> data.removeItem(item),
                "Took %s off the market. Its stock is kept; /market admin revert %s lists it again".formatted(item, item), item,
                "remove", null);
    }

    private static int revertItem(CommandContext<CommandSourceStack> ctx) {
        ResourceLocation item = id(item(ctx));
        if (MarketOverrides.get(ctx.getSource().getServer()).itemPatch(item) == null) {
            ctx.getSource().sendFailure(Component.literal(item + " has no admin edits"));
            return 0;
        }
        return apply(ctx, Set.of(item), data -> data.revertItem(item),
                "Dropped every admin edit for %s; it's back to what the catalog files and tag rules say".formatted(item), item,
                "revert", null);
    }

    private static int clearStock(CommandContext<CommandSourceStack> ctx) {
        Item item = item(ctx);
        MinecraftServer server = ctx.getSource().getServer();
        if (MarketCatalog.INSTANCE.get(item).map(CatalogEntry::unlimitedStock).orElse(false)) {
            ctx.getSource().sendFailure(Component.literal(id(item) + " has unlimited stock, so there's nothing to clear"));
            return 0;
        }
        long before = MarketStockData.get(server).getStock(id(item));
        MarketStockData.get(server).setStock(id(item), 0);
        MarketSync.broadcastRow(server, item);
        AuditLog.record(ctx.getSource(), "clearstock", id(item).toString(), "ok", new JsonPrimitive(before), new JsonPrimitive(0));
        ctx.getSource().sendSuccess(() -> Component.literal("Cleared %s stock (was %d). It stays listed".formatted(id(item), before)), true);
        return 1;
    }

    /** Catalog fields in the order {@code info} lists them. */
    private static final List<String> INFO_FIELDS = List.of("class", "base_price", "markup", "sell_fee", "can_sell", "can_buy",
            "unlimited_stock", "start_stock", "dynamic", "min_buy_markup", "sell_impact", "buy_impact", "recovery", "floor", "ceiling",
            "contract_weight", "contract_min_size", "contract_max_size", "contract_premium");

    /**
     * For a listed item, every field's value and the layer it comes from, with the lower layers it overrides. For an
     * unlisted one, what each layer says, so the admin can see why it isn't listed.
     */
    private static int info(CommandContext<CommandSourceStack> ctx) {
        Item item = item(ctx);
        ResourceLocation id = id(item);
        MinecraftServer server = ctx.getSource().getServer();
        MarketOverrides.Snapshot overrides = MarketOverrides.get(server).snapshot();
        List<String> lines = new ArrayList<>();
        Optional<CatalogEntry> listed = MarketCatalog.INSTANCE.get(item);
        if (listed.isPresent()) {
            lines.add(id + " is listed. Each field: value [where it comes from]");
            JsonObject values = CatalogJson.toJson(listed.get());
            PriceModel.Inputs inputs = PriceModel.inputs(listed.get());
            Map<String, List<FieldSource>> sources = MarketCatalog.INSTANCE.sources(item);
            Optional<ItemClasses.ItemClass> itemClass = listed.get().itemClass().flatMap(ItemClasses::get);
            INFO_FIELDS.forEach(field -> lines.add("  " + describeField(field, values, inputs, sources.get(field), itemClass)));
            MarketStockData market = MarketStockData.get(server);
            double factor = MarketService.currentFactor(listed.get(), inputs, market, MarketStockData.now(server));
            lines.add("  Contracts: " + ContractPricing.whyNotOffered(listed.get(), inputs, factor, MarketConfig.depositFraction())
                    .map(reason -> "never offered: " + reason)
                    .orElse("can be offered, paying up to " + plain(ContractPricing.unitReward(listed.get()).doubleValue())
                            + " per unit before the cap"));
        } else {
            lines.add(id + " is not listed");
            lines.add("  Catalog files: " + MarketCatalog.INSTANCE.fileEntry(item).map(AdminCatalogCommands::describe).orElse("not listed"));
            String rules = overrides.tags().stream()
                    .filter(rule -> CatalogOverlay.registryTag(rule.tag()).contains(item))
                    .map(rule -> "#" + rule.tag() + " " + rule.patch())
                    .collect(Collectors.joining(", "));
            lines.add("  Tag rules: " + (rules.isEmpty() ? "none" : rules));
            JsonObject own = overrides.items().get(id);
            lines.add("  Admin edits: " + (own == null ? "none" : own.toString()));
        }
        String problem = MarketCatalog.INSTANCE.problems().get(id);
        if (problem != null) {
            lines.add("  Not applied: " + problem);
        }
        lines.forEach(line -> ctx.getSource().sendSuccess(() -> Component.literal(line), false));
        return 1;
    }

    /**
     * {@code markup = 0.75 [item edit; overrides 0.5 from file a:ores defaults]}. A field no layer sets shows the value
     * from the item's class, its built-in default, the server config value, or (for min_buy_markup) the markup it
     * follows.
     */
    static String describeField(String field, JsonObject values, PriceModel.Inputs inputs, List<FieldSource> layers,
                                Optional<ItemClasses.ItemClass> itemClass) {
        String value = field.equals("class") && values.has(field)
                ? ItemClasses.parseId(values.get(field).getAsString()).map(ItemClasses::shortName).orElse("?")
                : values.has(field) ? show(values.get(field)) : switch (field) {
            case "class" -> "none";
            case "sell_impact" -> plain(inputs.sellImpact());
            case "buy_impact" -> plain(inputs.buyImpact());
            case "recovery" -> plain(inputs.recovery());
            case "floor" -> plain(inputs.floor());
            case "ceiling" -> plain(inputs.ceiling());
            case "min_buy_markup" -> plain(inputs.minBuyMarkup());
            case "contract_weight" -> String.valueOf(MarketConfig.contractWeight());
            case "contract_min_size" -> String.valueOf(MarketConfig.contractMinSize());
            case "contract_max_size" -> String.valueOf(MarketConfig.contractMaxSize());
            case "contract_premium" -> plain(MarketConfig.contractPremium());
            default -> "?";
        };
        if (layers == null || layers.isEmpty()) {
            if (itemClass.isPresent() && itemClass.get().sets(field)) {
                return "%s = %s [class %s]".formatted(field, value, ItemClasses.shortName(itemClass.get().id()));
            }
            String from = switch (field) {
                case "sell_impact", "buy_impact", "recovery", "floor", "ceiling", "contract_weight", "contract_min_size",
                     "contract_max_size", "contract_premium" -> "server config";
                case "min_buy_markup" -> "same as markup";
                default -> "default";
            };
            return "%s = %s [%s]".formatted(field, value, from);
        }
        StringBuilder line = new StringBuilder("%s = %s [%s".formatted(field, value, layers.get(layers.size() - 1).layer()));
        for (int i = layers.size() - 2; i >= 0; i--) {
            line.append(i == layers.size() - 2 ? "; overrides " : ", then ")
                    .append(show(layers.get(i).value())).append(" from ").append(layers.get(i).layer());
        }
        return line.append(']').toString();
    }

    /** Numbers in plain form (0.0005, not 5.0E-4; 16, not 16.0); anything else as JSON. */
    private static String show(JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            return new java.math.BigDecimal(value.getAsString()).stripTrailingZeros().toPlainString();
        }
        return value.toString();
    }

    private static String plain(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    // ---- Diagnosis --------------------------------------------------------------------------------------------

    private static final int FINDINGS_PER_PAGE = 10;

    private static int diagnose(CommandContext<CommandSourceStack> ctx, int page) {
        MinecraftServer server = ctx.getSource().getServer();
        List<MarketDiagnostics.Finding> findings = new ArrayList<>(MarketDiagnostics.run(MarketCatalog.INSTANCE.files(),
                MarketCatalog.INSTANCE.entries(), MarketCatalog.INSTANCE::sources, MarketCatalog.INSTANCE.problems(),
                MarketOverrides.get(server).snapshot(), MarketStockData.get(server), MarketStockData.now(server),
                CatalogOverlay::registryTag));
        // Needs the recipes, which only the running server has, so it's checked here rather than in MarketDiagnostics.
        Map<Item, CatalogEntry> catalog = new LinkedHashMap<>();
        MarketCatalog.INSTANCE.entries().forEach(entry -> catalog.put(entry.item(), entry));
        List<PriceSuggestion.Path> paths = PriceSuggestion.paths(server.getRecipeManager().getRecipes(), server.registryAccess());
        for (PriceSuggestion.CraftProfit profit : PriceSuggestion.craftProfits(paths, catalog)) {
            String advice = switch (profit.kind()) {
                case PRICE_GAP -> "Raise the ingredients' prices, lower this one, or stop the market buying it";
                case MAY_HIDE_INPUTS -> "This recipe type may take fluids or amounts the check can't see; check it by hand";
                case MULTIPLIES_INPUT -> "The recipe makes more of its own ingredient, so no price stops it; sell impact limits what it earns";
            };
            findings.add(new MarketDiagnostics.Finding(profit.kind() == PriceSuggestion.ProfitKind.PRICE_GAP
                    ? MarketDiagnostics.Severity.WARNING : MarketDiagnostics.Severity.NOTE,
                    "%s: buying the ingredients for %s costs %s, and the result sells for %s. %s"
                    .formatted(id(profit.output()), profit.recipe(), plain(profit.buyCost().doubleValue()), plain(profit.sellValue().doubleValue()),
                            advice)));
        }
        for (PriceSuggestion.CraftProfit profit : PriceSuggestion.craftProfits(paths, catalog, AdminCatalogCommands::contractUnitValue)) {
            findings.add(new MarketDiagnostics.Finding(profit.kind() == PriceSuggestion.ProfitKind.PRICE_GAP
                    ? MarketDiagnostics.Severity.WARNING : MarketDiagnostics.Severity.NOTE,
                    "%s: buying the ingredients for %s costs %s, and a contract for the result pays %s. Lower its contract_premium, or set its contract_weight to 0"
                    .formatted(id(profit.output()), profit.recipe(), plain(profit.buyCost().doubleValue()), plain(profit.sellValue().doubleValue()))));
        }
        if (findings.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("Market check: nothing to report"), false);
            return 0;
        }
        Map<MarketDiagnostics.Severity, Long> counts = findings.stream()
                .collect(Collectors.groupingBy(MarketDiagnostics.Finding::severity, Collectors.counting()));
        int pages = (findings.size() + FINDINGS_PER_PAGE - 1) / FINDINGS_PER_PAGE;
        int shown = Math.min(page, pages);
        ctx.getSource().sendSuccess(() -> Component.literal("Market check: %d error(s), %d warning(s), %d note(s); page %d of %d"
                .formatted(counts.getOrDefault(MarketDiagnostics.Severity.ERROR, 0L),
                        counts.getOrDefault(MarketDiagnostics.Severity.WARNING, 0L),
                        counts.getOrDefault(MarketDiagnostics.Severity.NOTE, 0L), shown, pages)), false);
        findings.stream().skip((long) (shown - 1) * FINDINGS_PER_PAGE).limit(FINDINGS_PER_PAGE).forEach(finding -> {
            ChatFormatting colour = switch (finding.severity()) {
                case ERROR -> ChatFormatting.RED;
                case WARNING -> ChatFormatting.YELLOW;
                case NOTE -> ChatFormatting.GRAY;
            };
            ctx.getSource().sendSuccess(() -> Component.literal("[" + finding.severity().name().toLowerCase(java.util.Locale.ROOT)
                    + "] " + finding.message()).withStyle(colour), false);
        });
        if (shown < pages) {
            ctx.getSource().sendSuccess(() -> Component.literal("/market admin diagnose " + (shown + 1) + " for more"), false);
        }
        return findings.size();
    }

    /** What a contract for the item pays per unit at normal prices, or empty if it's never offered. */
    private static Optional<java.math.BigDecimal> contractUnitValue(CatalogEntry entry) {
        if (entry.contract().effectiveWeight() == 0) {
            return Optional.empty();
        }
        int units = entry.contract().effectiveMinSize();
        return ContractPricing.offer(entry, PriceModel.inputs(entry), 1.0, units, MarketConfig.depositFraction())
                .map(offer -> java.math.BigDecimal.valueOf(offer.reward()).divide(java.math.BigDecimal.valueOf(units), 4,
                        java.math.RoundingMode.FLOOR));
    }

    // ---- Tag rules --------------------------------------------------------------------------------------------

    private static ResourceLocation tag(CommandContext<CommandSourceStack> ctx) {
        return ResourceLocationArgument.getId(ctx, "tag");
    }

    private static Set<ResourceLocation> members(ResourceLocation tag) {
        return CatalogOverlay.registryTag(tag).stream().map(AdminCatalogCommands::id).collect(Collectors.toSet());
    }

    private static boolean checkTagExists(CommandContext<CommandSourceStack> ctx, ResourceLocation tag) {
        if (members(tag).isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No items are in the tag #%s (check the spelling)".formatted(tag)));
            return false;
        }
        return true;
    }

    private static int editTag(CommandContext<CommandSourceStack> ctx, String field, JsonElement value, boolean preview) {
        ResourceLocation tag = tag(ctx);
        if (!checkTagExists(ctx, tag)) {
            return 0;
        }
        Consumer<MarketOverrides> edit = data -> data.setTagField(tag, field, value);
        if (preview) {
            return preview(ctx, tag, edit, "setting %s = %s on #%s".formatted(field, value, tag));
        }
        return apply(ctx, members(tag), edit,
                "Tag rule #%s: %s = %s. It applies to every item in the tag, now and as the tag changes; items with their own edits keep them"
                        .formatted(tag, field, value), null, "tag set " + field, tag);
    }

    private static int unsetTag(CommandContext<CommandSourceStack> ctx, String field) {
        ResourceLocation tag = tag(ctx);
        return apply(ctx, members(tag), data -> data.unsetTagField(tag, field),
                "Removed %s from the tag rule #%s".formatted(field, tag), null, "tag unset " + field, tag);
    }

    private static int removeTag(CommandContext<CommandSourceStack> ctx, boolean preview) {
        ResourceLocation tag = tag(ctx);
        if (!checkTagExists(ctx, tag)) {
            return 0;
        }
        Consumer<MarketOverrides> edit = data -> data.removeTag(tag);
        if (preview) {
            return preview(ctx, tag, edit, "taking #%s off the market".formatted(tag));
        }
        return apply(ctx, members(tag), edit,
                "Took every item in #%s off the market (stock kept); items with their own edits stay listed".formatted(tag), null,
                "tag remove", tag);
    }

    private static int revertTag(CommandContext<CommandSourceStack> ctx, boolean preview) {
        ResourceLocation tag = tag(ctx);
        boolean exists = MarketOverrides.get(ctx.getSource().getServer()).snapshot().tags().stream().anyMatch(r -> r.tag().equals(tag));
        if (!exists) {
            ctx.getSource().sendFailure(Component.literal("There's no tag rule for #" + tag));
            return 0;
        }
        Consumer<MarketOverrides> edit = data -> data.revertTag(tag);
        if (preview) {
            return preview(ctx, tag, edit, "deleting the tag rule #" + tag);
        }
        return apply(ctx, members(tag), edit, "Deleted the tag rule #" + tag, null, "tag revert", tag);
    }

    private static final int PREVIEW_EXAMPLES = 5;

    /**
     * Shows what a tag-rule change would do to the tag's items, without changing anything: the edit is made to a
     * copy of the admin edits and the catalog is built from that copy on the side.
     */
    private static int preview(CommandContext<CommandSourceStack> ctx, ResourceLocation tag, Consumer<MarketOverrides> edit,
                               String what) {
        MarketOverrides copy = new MarketOverrides();
        copy.restore(MarketOverrides.get(ctx.getSource().getServer()).snapshot());
        edit.accept(copy);
        MarketCatalog.Loaded files = MarketCatalog.INSTANCE.files();
        CatalogOverlay.Result after = CatalogOverlay.apply(files.entries(), files.sources(), copy.snapshot(), CatalogOverlay::registryTag);

        // Items already on the market matter most, so removals and changes are shown before new listings.
        List<String> taken = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        int same = 0;
        List<Item> items = new ArrayList<>(CatalogOverlay.registryTag(tag));
        items.sort(java.util.Comparator.comparing(item -> id(item).toString()));
        for (Item item : items) {
            CatalogEntry now = MarketCatalog.INSTANCE.get(item).orElse(null);
            CatalogEntry next = after.entries().get(item);
            if (now == null && next != null) {
                added.add(id(item) + ": listed at " + next.basePrice() + " " + MarketCommand.unit(ctx.getSource()));
            } else if (now != null && next == null) {
                taken.add(id(item) + ": taken off the market");
            } else if (now != null && !now.equals(next)) {
                changed.add(id(item) + ": " + differences(now, next));
            } else {
                same++;
            }
        }
        List<String> examples = new ArrayList<>(taken);
        examples.addAll(changed);
        examples.addAll(added);
        List<String> refused = after.problems().entrySet().stream()
                .filter(problem -> BuiltInRegistries.ITEM.getOptional(problem.getKey()).map(items::contains).orElse(false))
                .map(Map.Entry::getValue).toList();

        List<String> lines = new ArrayList<>();
        lines.add("Preview of " + what + " (nothing has been changed):");
        lines.add("  Of the tag's %d items: %d newly listed, %d taken off, %d changed, %d unaffected"
                .formatted(items.size(), added.size(), taken.size(), changed.size(), same));
        examples.stream().limit(PREVIEW_EXAMPLES).forEach(example -> lines.add("  " + example));
        if (examples.size() > PREVIEW_EXAMPLES) {
            lines.add("  ...and %d more".formatted(examples.size() - PREVIEW_EXAMPLES));
        }
        if (!refused.isEmpty()) {
            lines.add("  The change would be refused, since it breaks the catalog rules for %d item(s), e.g. %s"
                    .formatted(refused.size(), refused.get(0)));
        }
        lines.forEach(line -> ctx.getSource().sendSuccess(() -> Component.literal(line), false));
        return 1;
    }

    /** {@code base_price 2 → 4, markup 0.5 → 1.0}: the fields that differ between two entries for the same item. */
    private static String differences(CatalogEntry before, CatalogEntry after) {
        JsonObject a = CatalogJson.toJson(before);
        JsonObject b = CatalogJson.toJson(after);
        List<String> changes = new ArrayList<>();
        for (String field : INFO_FIELDS) {
            JsonElement from = a.get(field);
            JsonElement to = b.get(field);
            if (!java.util.Objects.equals(from, to)) {
                changes.add(field + " " + (from == null ? "default" : from) + " → " + (to == null ? "default" : to));
            }
        }
        return String.join(", ", changes);
    }

    private static int tagInfo(CommandContext<CommandSourceStack> ctx) {
        ResourceLocation tag = tag(ctx);
        Collection<Item> items = CatalogOverlay.registryTag(tag);
        long listed = items.stream().filter(item -> MarketCatalog.INSTANCE.get(item).isPresent()).count();
        String rule = MarketOverrides.get(ctx.getSource().getServer()).snapshot().tags().stream()
                .filter(r -> r.tag().equals(tag)).map(r -> r.patch().toString()).findFirst().orElse("none");
        ctx.getSource().sendSuccess(() -> Component.literal("#%s: %d items, %d listed. Rule: %s".formatted(tag, items.size(), listed, rule)), false);
        return items.size();
    }

    private static int listTags(CommandContext<CommandSourceStack> ctx) {
        List<MarketOverrides.TagRule> rules = MarketOverrides.get(ctx.getSource().getServer()).snapshot().tags();
        if (rules.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No tag rules. Add one with /market admin tag <tag> setprice <price>"), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Tag rules, applied in this order (later ones win where they overlap):"), false);
        for (MarketOverrides.TagRule rule : rules) {
            int count = CatalogOverlay.registryTag(rule.tag()).size();
            ctx.getSource().sendSuccess(() -> Component.literal("  #%s (%d items): %s".formatted(rule.tag(), count, rule.patch())), false);
        }
        return rules.size();
    }

    // ---- Item classes -----------------------------------------------------------------------------------------

    private static int listClasses(CommandContext<CommandSourceStack> ctx) {
        Map<ResourceLocation, ItemClasses.ItemClass> classes = ItemClasses.all();
        if (classes.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No item classes are loaded"), false);
            return 0;
        }
        Map<ResourceLocation, Long> members = MarketCatalog.INSTANCE.entries().stream()
                .filter(entry -> entry.itemClass().isPresent())
                .collect(Collectors.groupingBy(entry -> entry.itemClass().get(), Collectors.counting()));
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Item classes. Each fills the fields an item's catalog entry, tag rules and edits leave unset:"), false);
        for (ItemClasses.ItemClass itemClass : classes.values()) {
            long count = members.getOrDefault(itemClass.id(), 0L);
            String about = itemClass.description().isEmpty() ? "" : " - " + itemClass.description();
            ctx.getSource().sendSuccess(() -> Component.literal("  %s (%d listed)%s".formatted(ItemClasses.shortName(itemClass.id()),
                    count, about)), false);
            String file = classFile(itemClass);
            ResourceLocation id = itemClass.id();
            String saveAs = id.getNamespace().equals(DynamicEconomy.MOD_ID)
                    ? MarketCatalog.CONFIG_PACK_ID + "/market_classes/" + id.getPath() + ".json"
                    : "data/" + id.getNamespace() + "/market_classes/" + id.getPath() + ".json in a datapack";
            ctx.getSource().sendSuccess(() -> Component.literal("    " + itemClass.values()).withStyle(style -> style
                    .withColor(ChatFormatting.GRAY)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, file))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(
                            "Click to copy as a class file. Saved as " + saveAs + ", it replaces this class")))), false);
        }
        return classes.size();
    }

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** The class as a file that loads back as the same class: its description, then its values. */
    private static String classFile(ItemClasses.ItemClass itemClass) {
        JsonObject file = new JsonObject();
        if (!itemClass.description().isEmpty()) {
            file.addProperty("description", itemClass.description());
        }
        itemClass.values().entrySet().forEach(field -> file.add(field.getKey(), field.getValue()));
        return PRETTY.toJson(file);
    }

    // ---- Applying an edit -------------------------------------------------------------------------------------

    /**
     * Makes an edit, rebuilds the catalog and, if any of the items it concerns ends up breaking a catalog rule, undoes
     * it and reports why. Never touches stock or price factors. Either way it goes in the audit log, with the edits of
     * the item ({@code single}) or tag rule ({@code tag}) before and after.
     */
    private static int apply(CommandContext<CommandSourceStack> ctx, Set<ResourceLocation> concerns, Consumer<MarketOverrides> edit,
                             String success, ResourceLocation single, String action, ResourceLocation tag) {
        MinecraftServer server = ctx.getSource().getServer();
        MarketOverrides data = MarketOverrides.get(server);
        MarketOverrides.Snapshot before = data.snapshot();
        String target = single != null ? single.toString() : "#" + tag;
        java.util.function.Supplier<JsonElement> state = () -> single != null ? data.itemPatch(single) : data.tagRulePatch(tag);
        JsonElement stateBefore = state.get();
        edit.accept(data);
        Map<ResourceLocation, String> problems = new LinkedHashMap<>(DynamicEconomy.catalogReloaded(server));
        problems.keySet().retainAll(concerns);
        if (!problems.isEmpty()) {
            data.restore(before);
            DynamicEconomy.catalogReloaded(server);
            AuditLog.record(ctx.getSource(), action, target, "refused: " + problems.values().iterator().next(), stateBefore, null);
            ctx.getSource().sendFailure(Component.literal("Not changed: the edit would break the catalog rules for %d item(s):"
                    .formatted(problems.size())));
            problems.values().stream().limit(PROBLEMS_SHOWN).forEach(p -> ctx.getSource().sendFailure(Component.literal("  " + p)));
            return 0;
        }
        AuditLog.record(ctx.getSource(), action, target, "ok", stateBefore, state.get());
        ctx.getSource().sendSuccess(() -> Component.literal(success), true);
        if (single != null) {
            BuiltInRegistries.ITEM.getOptional(single).flatMap(MarketCatalog.INSTANCE::get).ifPresent(entry ->
                    ctx.getSource().sendSuccess(() -> Component.literal("  Now: " + describe(entry)), false));
        } else {
            long listed = concerns.stream().map(BuiltInRegistries.ITEM::getOptional)
                    .filter(item -> item.flatMap(MarketCatalog.INSTANCE::get).isPresent()).count();
            ctx.getSource().sendSuccess(() -> Component.literal("  %d of the tag's %d items are now listed".formatted(listed, concerns.size())), false);
        }
        return 1;
    }

    private static String describe(CatalogEntry entry) {
        JsonObject json = CatalogJson.toJson(entry);
        json.remove("item");
        return json.toString();
    }
}
