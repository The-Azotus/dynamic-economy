package dynamiceconomy.service;

import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.data.MarketStockData;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Unit prices in spurs. The market pays {@code floor(base × s)} and charges {@code ceil(base × s × (1 + markup))},
 * where {@code s} is the item's supply/demand factor.
 * <p>
 * Callers always pass the catalog entry and the market state, so a real factor can later be computed from that state
 * without changing any caller. Arithmetic is decimal, not binary floating point, so 10 × 1.1 charges 11, not 12.
 */
public final class PriceModel {
    private PriceModel() {
    }

    public record UnitPrices(long sell, long buy) {
    }

    public static UnitPrices unitPrices(CatalogEntry entry, MarketStockData market) {
        BigDecimal scaled = BigDecimal.valueOf(entry.basePrice()).multiply(BigDecimal.valueOf(supplyFactor(entry, market)));
        BigDecimal withMarkup = scaled.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(entry.markup())));
        return new UnitPrices(
                scaled.setScale(0, RoundingMode.FLOOR).longValueExact(),
                withMarkup.setScale(0, RoundingMode.CEILING).longValueExact());
    }

    /** Phase 1: prices are fixed. */
    static double supplyFactor(CatalogEntry entry, MarketStockData market) {
        return 1.0;
    }
}
