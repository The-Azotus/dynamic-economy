package dynamiceconomy.catalog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;

/** Catalog entries as catalog-file JSON, so edits can be laid over an entry and re-checked with the loader's rules. */
public final class CatalogJson {
    private CatalogJson() {
    }

    /**
     * Every field of the entry with the value in effect, written out explicitly; optional fields only when set. This
     * loses which values came from the entry's class or a built-in default: to lay edits over an entry, start from
     * {@link #explicit} instead, so a changed class still fills the fields nothing sets.
     */
    public static JsonObject toJson(CatalogEntry entry) {
        JsonObject json = new JsonObject();
        json.addProperty("item", entry.itemId().toString());
        entry.itemClass().ifPresent(c -> json.addProperty("class", c.toString()));
        json.addProperty("base_price", entry.basePrice());
        json.addProperty("markup", entry.markup());
        json.addProperty("start_stock", entry.startStock());
        json.addProperty("can_sell", entry.canSell());
        json.addProperty("can_buy", entry.canBuy());
        json.addProperty("unlimited_stock", entry.unlimitedStock());
        json.addProperty("sell_fee", entry.sellFee());
        json.addProperty("dynamic", entry.dynamic());
        entry.minBuyMarkup().ifPresent(v -> json.addProperty("min_buy_markup", v));
        CatalogEntry.Overrides o = entry.overrides();
        o.sellImpact().ifPresent(v -> json.addProperty("sell_impact", v));
        o.buyImpact().ifPresent(v -> json.addProperty("buy_impact", v));
        o.recovery().ifPresent(v -> json.addProperty("recovery", v));
        o.floor().ifPresent(v -> json.addProperty("floor", v));
        o.ceiling().ifPresent(v -> json.addProperty("ceiling", v));
        CatalogEntry.ContractTerms c = entry.contract();
        c.weight().ifPresent(v -> json.addProperty("contract_weight", v));
        c.minSize().ifPresent(v -> json.addProperty("contract_min_size", v));
        c.maxSize().ifPresent(v -> json.addProperty("contract_max_size", v));
        c.premium().ifPresent(v -> json.addProperty("contract_premium", v));
        return json;
    }

    /** Only the fields some catalog layer set, each with its newest value: the entry as the layers wrote it. */
    public static JsonObject explicit(ResourceLocation item, Map<String, List<FieldSource>> sources) {
        JsonObject json = new JsonObject();
        json.addProperty("item", item.toString());
        sources.forEach((field, layers) -> {
            if (!layers.isEmpty()) {
                json.add(field, layers.get(layers.size() - 1).value().deepCopy());
            }
        });
        return json;
    }

    /** A copy of {@code base} with every field of {@code patch} laid over it. */
    public static JsonObject merge(JsonObject base, JsonObject patch) {
        JsonObject merged = base.deepCopy();
        for (Map.Entry<String, JsonElement> field : patch.entrySet()) {
            merged.add(field.getKey(), field.getValue().deepCopy());
        }
        return merged;
    }
}
