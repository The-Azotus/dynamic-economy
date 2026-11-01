package dynamiceconomy.io;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The spreadsheet layout shared by export and import. Catalog columns are named exactly like the catalog's JSON
 * fields, so a row converts straight into a catalog entry. The override and contract columns at the end go beyond the
 * brief's list; without them an export → import round trip would drop per-item settings.
 */
final class CatalogColumns {
    static final String ITEM_ID = "item_id";
    static final String MOD_ID = "mod_id";
    static final String DISPLAY_NAME = "display_name";
    static final String MAX_STACK_SIZE = "max_stack_size";
    static final String IN_CATALOG = "in_catalog";
    static final String BASE_PRICE = "base_price";

    enum Kind { BOOLEAN, WHOLE_NUMBER, DECIMAL, TEXT }

    /**
     * Optional catalog fields, in column order. A blank cell means "not set": the item's class, or else the catalog
     * default, decides it.
     */
    static final Map<String, Kind> OPTIONAL_FIELDS = new LinkedHashMap<>();

    static {
        OPTIONAL_FIELDS.put("class", Kind.TEXT);
        OPTIONAL_FIELDS.put("unlimited_stock", Kind.BOOLEAN);
        OPTIONAL_FIELDS.put("markup", Kind.DECIMAL);
        OPTIONAL_FIELDS.put("min_buy_markup", Kind.DECIMAL);
        OPTIONAL_FIELDS.put("sell_fee", Kind.DECIMAL);
        OPTIONAL_FIELDS.put("can_sell", Kind.BOOLEAN);
        OPTIONAL_FIELDS.put("can_buy", Kind.BOOLEAN);
        OPTIONAL_FIELDS.put("start_stock", Kind.WHOLE_NUMBER);
        OPTIONAL_FIELDS.put("dynamic", Kind.BOOLEAN);
        OPTIONAL_FIELDS.put("sell_impact", Kind.DECIMAL);
        OPTIONAL_FIELDS.put("buy_impact", Kind.DECIMAL);
        OPTIONAL_FIELDS.put("recovery", Kind.DECIMAL);
        OPTIONAL_FIELDS.put("floor", Kind.DECIMAL);
        OPTIONAL_FIELDS.put("ceiling", Kind.DECIMAL);
        OPTIONAL_FIELDS.put("contract_weight", Kind.WHOLE_NUMBER);
        OPTIONAL_FIELDS.put("contract_min_size", Kind.WHOLE_NUMBER);
        OPTIONAL_FIELDS.put("contract_max_size", Kind.WHOLE_NUMBER);
        OPTIONAL_FIELDS.put("contract_premium", Kind.DECIMAL);
    }

    static final List<String> HEADER;

    static {
        List<String> header = new java.util.ArrayList<>(List.of(ITEM_ID, MOD_ID, DISPLAY_NAME, MAX_STACK_SIZE, IN_CATALOG, BASE_PRICE));
        header.addAll(OPTIONAL_FIELDS.keySet());
        HEADER = List.copyOf(header);
    }

    private CatalogColumns() {
    }

    /** The shortest decimal that reads back as the same double: 0.5, not 0.50000000000000000. */
    static String decimal(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
