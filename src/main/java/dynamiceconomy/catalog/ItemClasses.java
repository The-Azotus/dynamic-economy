package dynamiceconomy.catalog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import dynamiceconomy.DynamicEconomy;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Item classes: named sets of catalog defaults, loaded from {@code data/<namespace>/market_classes/<name>.json}, so
 * groups of items share pricing behaviour (rare items volatile and slow to recover, common ones stable). An entry
 * names its class with {@code "class": "rare_resource"}; the class supplies only the fields no layer of the catalog
 * sets, so precedence is server config < class < file defaults < entry < tag rules < admin edits. A name without a
 * namespace means {@code dynamic_economy:}. As with any datapack file, a higher-priority pack replaces a whole class.
 */
public final class ItemClasses {
    /** The catalog fields a class may set. Not {@code item} or {@code base_price}: those are per item. */
    public static final Set<String> FIELDS = Set.of("markup", "sell_fee", "can_sell", "can_buy", "unlimited_stock",
            "start_stock", "dynamic", "min_buy_markup", "sell_impact", "buy_impact", "recovery", "floor", "ceiling",
            "contract_weight", "contract_min_size", "contract_max_size", "contract_premium");
    private static final String DESCRIPTION = "description";

    /**
     * @param values      the catalog fields the class sets
     * @param description optional text for admins
     */
    public record ItemClass(ResourceLocation id, JsonObject values, String description) {
        /** A copy of the values, so callers can't change the class. */
        @Override
        public JsonObject values() {
            return values.deepCopy();
        }

        public boolean sets(String field) {
            return values.has(field);
        }
    }

    /** Classes as loaded, sorted by ID, with what went wrong. */
    public record Loaded(Map<ResourceLocation, ItemClass> classes, List<MarketCatalog.Problem> problems) {
        public static final Loaded EMPTY = new Loaded(Map.of(), List.of());
    }

    private static volatile Loaded current = Loaded.EMPTY;

    private ItemClasses() {
    }

    public static Map<ResourceLocation, ItemClass> all() {
        return current.classes();
    }

    public static Optional<ItemClass> get(ResourceLocation id) {
        return Optional.ofNullable(current.classes().get(id));
    }

    static Loaded loaded() {
        return current;
    }

    /** Makes these the classes entries are decoded against. Call before loading the catalog files. */
    static void use(Loaded classes) {
        current = classes;
    }

    /** {@code rare_resource} → {@code dynamic_economy:rare_resource}; empty if it isn't a valid ID. */
    public static Optional<ResourceLocation> parseId(String text) {
        String full = text.indexOf(':') < 0 ? DynamicEconomy.MOD_ID + ":" + text : text;
        return Optional.ofNullable(ResourceLocation.tryParse(full));
    }

    /** The name to show or write for a class: {@code rare_resource} for this mod's own, the full ID otherwise. */
    public static String shortName(ResourceLocation id) {
        return id.getNamespace().equals(DynamicEconomy.MOD_ID) ? id.getPath() : id.toString();
    }

    /** The class a catalog entry's {@code class} field names, or why it can't be used. */
    static DataResult<ItemClass> lookup(JsonElement name) {
        if (!name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()) {
            return DataResult.error(() -> "class must be a name like \"rare_resource\", got " + name);
        }
        String text = name.getAsString();
        Optional<ResourceLocation> id = parseId(text);
        if (id.isEmpty()) {
            return DataResult.error(() -> "\"" + text + "\" is not a valid class name");
        }
        ItemClass found = current.classes().get(id.get());
        return found != null ? DataResult.success(found) : DataResult.error(() -> "unknown class " + id.get());
    }

    /**
     * Turns class files into classes, checking each one's values with the catalog's own rules. A bad class is left
     * out (entries naming it are then skipped as naming an unknown class); unknown fields are ignored with a warning.
     * Pure apart from logging.
     */
    public static Loaded parse(Map<ResourceLocation, JsonElement> files) {
        Map<ResourceLocation, ItemClass> classes = new TreeMap<>();
        List<MarketCatalog.Problem> problems = new java.util.ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> file : files.entrySet()) {
            ResourceLocation id = file.getKey();
            if (!(file.getValue() instanceof JsonObject obj)) {
                problem(problems, true, "Market class %s must be an object; skipped".formatted(id));
                continue;
            }
            JsonObject values = new JsonObject();
            String description = "";
            for (Map.Entry<String, JsonElement> field : obj.entrySet()) {
                if (field.getKey().equals(DESCRIPTION)) {
                    description = field.getValue().isJsonPrimitive() ? field.getValue().getAsString() : field.getValue().toString();
                } else if (FIELDS.contains(field.getKey())) {
                    values.add(field.getKey(), field.getValue().deepCopy());
                } else {
                    problem(problems, false, "Market class %s: unknown field \"%s\" ignored%s".formatted(id, field.getKey(),
                            field.getKey().equals("base_price") || field.getKey().equals("item") || field.getKey().equals("class")
                                    ? " (it's set per item, not per class)" : ""));
                }
            }
            // Check the values the way an entry using only this class would be checked.
            JsonObject probe = values.deepCopy();
            probe.addProperty("item", "minecraft:stone");
            probe.addProperty("base_price", 1);
            DataResult<CatalogEntry> check = MarketCatalog.decodeWithoutClass(probe, Optional.empty());
            if (check.error().isPresent()) {
                problem(problems, true, "Market class %s: %s; skipped".formatted(id, check.error().get().message()));
                continue;
            }
            classes.put(id, new ItemClass(id, values, description));
        }
        return new Loaded(Collections.unmodifiableMap(new LinkedHashMap<>(classes)), List.copyOf(problems));
    }

    private static void problem(List<MarketCatalog.Problem> problems, boolean skipped, String message) {
        DynamicEconomy.LOGGER.warn(message);
        problems.add(new MarketCatalog.Problem(skipped, message));
    }
}
