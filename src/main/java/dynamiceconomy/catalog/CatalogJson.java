package dynamiceconomy.catalog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

/** Catalog entries as catalog-file JSON, so edits can be laid over an entry and re-checked with the loader's rules. */
public final class CatalogJson {
    private CatalogJson() {
    }

    /** Every field of the entry, written out explicitly; optional fields only when set. */
    public static JsonObject toJson(CatalogEntry entry) {
        JsonObject json = new JsonObject();
        json.addProperty("item", entry.itemId().toString());
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
