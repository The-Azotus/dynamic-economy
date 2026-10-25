package dynamiceconomy.catalog;

import com.google.gson.JsonElement;

import java.util.List;
import java.util.Map;

/**
 * One layer's value for one catalog field, e.g. {@code base_price = 16} from {@code file dynamic_economy:ores}.
 * An item's sources map each field it sets to its layers, lowest first; the last one is the value in effect. A field
 * with no sources takes its built-in default (or the server config, for the supply-and-demand settings).
 */
public record FieldSource(String layer, JsonElement value) {
    /** No field set anywhere: every value is a default. */
    public static final Map<String, List<FieldSource>> NONE = Map.of();
}
