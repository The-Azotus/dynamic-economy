package dynamiceconomy.command;

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
import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.catalog.CatalogJson;
import dynamiceconomy.catalog.CatalogOverlay;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.data.MarketOverrides;
import dynamiceconomy.data.MarketStockData;
import dynamiceconomy.network.MarketSync;
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
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    /** A catalog field that can be set by command, with its argument type and how to read it as JSON. */
    private record Field(String name, ArgumentType<?> type, BiFunction<CommandContext<CommandSourceStack>, String, JsonElement> read) {
    }

    private static final List<Field> FIELDS = List.of(
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
            decimal("ceiling", 1, 1000));

    private static final int PROBLEMS_SHOWN = 5;

    private AdminCatalogCommands() {
    }

    private static Field decimal(String name, double min, double max) {
        return new Field(name, DoubleArgumentType.doubleArg(min, max), (c, a) -> new JsonPrimitive(DoubleArgumentType.getDouble(c, a)));
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
                (ctx, field, value) -> editItem(ctx, field, value)));
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
        tag.then(Commands.literal("setprice")
                .then(Commands.argument("base_price", IntegerArgumentType.integer(1))
                        .executes(ctx -> editTag(ctx, "base_price", new JsonPrimitive(IntegerArgumentType.getInteger(ctx, "base_price"))))));
        tag.then(fieldCommands(Commands.literal("set"), null, AdminCatalogCommands::editTag));
        tag.then(fieldNames(Commands.literal("unset"), AdminCatalogCommands::unsetTag));
        tag.then(Commands.literal("remove").executes(AdminCatalogCommands::removeTag));
        tag.then(Commands.literal("revert").executes(AdminCatalogCommands::revertTag));
        tag.then(Commands.literal("info").executes(AdminCatalogCommands::tagInfo));
        admin.then(Commands.literal("tag").then(tag));
        admin.then(Commands.literal("tags").executes(AdminCatalogCommands::listTags));
    }

    @FunctionalInterface
    private interface FieldEdit {
        int run(CommandContext<CommandSourceStack> ctx, String field, JsonElement value) throws com.mojang.brigadier.exceptions.CommandSyntaxException;
    }

    @FunctionalInterface
    private interface FieldAction {
        int run(CommandContext<CommandSourceStack> ctx, String field) throws com.mojang.brigadier.exceptions.CommandSyntaxException;
    }

    /** {@code <literal> [target] <field> <value>} for every settable field. */
    private static <T extends ArgumentBuilder<CommandSourceStack, T>> LiteralArgumentBuilder<CommandSourceStack> fieldCommands(
            LiteralArgumentBuilder<CommandSourceStack> literal, ArgumentBuilder<CommandSourceStack, ?> target, FieldEdit edit) {
        ArgumentBuilder<CommandSourceStack, ?> parent = target == null ? literal : target;
        for (Field field : FIELDS) {
            parent.then(Commands.literal(field.name())
                    .then(Commands.argument("value", field.type())
                            .executes(ctx -> edit.run(ctx, field.name(), field.read().apply(ctx, "value")))));
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
                "Set %s for %s to %s".formatted(field, item, value), item);
    }

    private static int unsetItem(CommandContext<CommandSourceStack> ctx, String field) {
        ResourceLocation item = id(item(ctx));
        JsonObject patch = MarketOverrides.get(ctx.getSource().getServer()).itemPatch(item);
        if (patch == null || !patch.has(field)) {
            ctx.getSource().sendFailure(Component.literal("%s has no admin edit for %s".formatted(item, field)));
            return 0;
        }
        return apply(ctx, Set.of(item), data -> data.unsetItemField(item, field),
                "Removed the %s edit for %s; the catalog files decide it again".formatted(field, item), item);
    }

    private static int removeItem(CommandContext<CommandSourceStack> ctx) {
        ResourceLocation item = id(item(ctx));
        return apply(ctx, Set.of(item), data -> data.removeItem(item),
                "Took %s off the market. Its stock is kept; /market admin revert %s lists it again".formatted(item, item), item);
    }

    private static int revertItem(CommandContext<CommandSourceStack> ctx) {
        ResourceLocation item = id(item(ctx));
        if (MarketOverrides.get(ctx.getSource().getServer()).itemPatch(item) == null) {
            ctx.getSource().sendFailure(Component.literal(item + " has no admin edits"));
            return 0;
        }
        return apply(ctx, Set.of(item), data -> data.revertItem(item),
                "Dropped every admin edit for %s; it's back to what the catalog files and tag rules say".formatted(item), item);
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
        ctx.getSource().sendSuccess(() -> Component.literal("Cleared %s stock (was %d). It stays listed".formatted(id(item), before)), true);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        Item item = item(ctx);
        ResourceLocation id = id(item);
        MinecraftServer server = ctx.getSource().getServer();
        MarketOverrides.Snapshot overrides = MarketOverrides.get(server).snapshot();
        List<String> lines = new ArrayList<>();
        lines.add(MarketCatalog.INSTANCE.get(item).map(e -> id + " is listed: " + describe(e)).orElse(id + " is not listed"));
        lines.add("  Catalog files: " + MarketCatalog.INSTANCE.fileEntry(item).map(AdminCatalogCommands::describe).orElse("not listed"));
        String rules = overrides.tags().stream()
                .filter(rule -> CatalogOverlay.registryTag(rule.tag()).contains(item))
                .map(rule -> "#" + rule.tag() + " " + rule.patch())
                .collect(Collectors.joining(", "));
        lines.add("  Tag rules: " + (rules.isEmpty() ? "none" : rules));
        JsonObject own = overrides.items().get(id);
        lines.add("  Admin edits: " + (own == null ? "none" : own.toString()));
        String problem = MarketCatalog.INSTANCE.problems().get(id);
        if (problem != null) {
            lines.add("  Not applied: " + problem);
        }
        lines.forEach(line -> ctx.getSource().sendSuccess(() -> Component.literal(line), false));
        return 1;
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

    private static int editTag(CommandContext<CommandSourceStack> ctx, String field, JsonElement value) {
        ResourceLocation tag = tag(ctx);
        if (!checkTagExists(ctx, tag)) {
            return 0;
        }
        return apply(ctx, members(tag), data -> data.setTagField(tag, field, value),
                "Tag rule #%s: %s = %s. It applies to every item in the tag, now and as the tag changes; items with their own edits keep them"
                        .formatted(tag, field, value), null);
    }

    private static int unsetTag(CommandContext<CommandSourceStack> ctx, String field) {
        ResourceLocation tag = tag(ctx);
        return apply(ctx, members(tag), data -> data.unsetTagField(tag, field),
                "Removed %s from the tag rule #%s".formatted(field, tag), null);
    }

    private static int removeTag(CommandContext<CommandSourceStack> ctx) {
        ResourceLocation tag = tag(ctx);
        if (!checkTagExists(ctx, tag)) {
            return 0;
        }
        return apply(ctx, members(tag), data -> data.removeTag(tag),
                "Took every item in #%s off the market (stock kept); items with their own edits stay listed".formatted(tag), null);
    }

    private static int revertTag(CommandContext<CommandSourceStack> ctx) {
        ResourceLocation tag = tag(ctx);
        boolean exists = MarketOverrides.get(ctx.getSource().getServer()).snapshot().tags().stream().anyMatch(r -> r.tag().equals(tag));
        if (!exists) {
            ctx.getSource().sendFailure(Component.literal("There's no tag rule for #" + tag));
            return 0;
        }
        return apply(ctx, members(tag), data -> data.revertTag(tag), "Deleted the tag rule #" + tag, null);
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

    // ---- Applying an edit -------------------------------------------------------------------------------------

    /**
     * Makes an edit, rebuilds the catalog and, if any of the items it concerns ends up breaking a catalog rule, undoes
     * it and reports why. Never touches stock or price factors.
     */
    private static int apply(CommandContext<CommandSourceStack> ctx, Set<ResourceLocation> concerns, Consumer<MarketOverrides> edit,
                             String success, ResourceLocation single) {
        MinecraftServer server = ctx.getSource().getServer();
        MarketOverrides data = MarketOverrides.get(server);
        MarketOverrides.Snapshot before = data.snapshot();
        edit.accept(data);
        Map<ResourceLocation, String> problems = new LinkedHashMap<>(DynamicEconomy.catalogReloaded(server));
        problems.keySet().retainAll(concerns);
        if (!problems.isEmpty()) {
            data.restore(before);
            DynamicEconomy.catalogReloaded(server);
            ctx.getSource().sendFailure(Component.literal("Not changed: the edit would break the catalog rules for %d item(s):"
                    .formatted(problems.size())));
            problems.values().stream().limit(PROBLEMS_SHOWN).forEach(p -> ctx.getSource().sendFailure(Component.literal("  " + p)));
            return 0;
        }
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
