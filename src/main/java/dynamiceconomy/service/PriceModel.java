package dynamiceconomy.service;

import dynamiceconomy.MarketConfig;
import dynamiceconomy.catalog.CatalogEntry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Supply-and-demand pricing, in spurs. Each item has a factor {@code s}, 1.0 at rest.
 * <ul>
 *   <li>The market pays {@code base × s × (1 − sell_fee)} per unit and charges {@code base × s × (1 + markup)}.
 *       Unlimited-stock items charge {@code base × max(s × (1 + markup), 1 + min_buy_markup)}.</li>
 *   <li>Within an order each unit moves {@code s}: a sale multiplies it by {@code 1 − sell_impact} (not below
 *       {@code floor}), a purchase by {@code 1 + buy_impact} (not above {@code ceiling}). Purchases of
 *       unlimited items never move it.</li>
 *   <li>Each unit is valued at the {@code s} it trades at; the exact values are summed and the order total rounded
 *       once, down for payouts and up for charges. Splitting an order therefore never earns more or costs less.</li>
 *   <li>Between trades {@code s} recovers toward 1.0 in steps at server-wide interval boundaries.</li>
 * </ul>
 * Everything here except {@link #inputs} is pure, and the client runs the same code to show order totals, so the
 * total it quotes matches the server's exactly. Decimal arithmetic avoids binary rounding surprises (10 × 1.1 = 11).
 */
public final class PriceModel {
    private PriceModel() {
    }

    /** Everything needed to price an item, with config defaults already applied. */
    public record Inputs(int basePrice, double markup, double sellFee, double minBuyMarkup, boolean unlimited,
                         boolean dynamic, double sellImpact, double buyImpact, double floor, double ceiling,
                         double recovery) {
    }

    /** An order's rounded total and where it leaves the factor. {@code overflow} means the total exceeds a long. */
    public record Order(long total, double factorAfter, boolean overflow) {
    }

    /** Server side: an entry's inputs, filling anything it doesn't override from the server config. */
    public static Inputs inputs(CatalogEntry entry) {
        CatalogEntry.Overrides o = entry.overrides();
        return new Inputs(entry.basePrice(), entry.markup(), entry.sellFee(), entry.minBuyMarkup().orElse(entry.markup()),
                entry.unlimitedStock(), entry.dynamic(),
                o.sellImpact().orElse(MarketConfig.sellImpact()), o.buyImpact().orElse(MarketConfig.buyImpact()),
                o.floor().orElse(MarketConfig.floor()), o.ceiling().orElse(MarketConfig.ceiling()),
                o.recovery().orElse(MarketConfig.recovery()));
    }

    /**
     * The factor to trade at, given the stored one. Non-dynamic items always trade at 1.0, and unlimited items never
     * above it (only sales move them, and those push down).
     */
    public static double effectiveFactor(Inputs in, double stored) {
        if (!in.dynamic()) {
            return 1.0;
        }
        return in.unlimited() ? Math.min(1.0, stored) : stored;
    }

    /** The player sells {@code qty} to the market. */
    public static Order sell(Inputs in, double factor, int qty) {
        BigDecimal perUnit = BigDecimal.valueOf(in.basePrice()).multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(in.sellFee())));
        BigDecimal sum = BigDecimal.ZERO;
        double s = factor;
        for (int i = 0; i < qty; i++) {
            sum = sum.add(perUnit.multiply(BigDecimal.valueOf(s)));
            s = afterSale(in, s);
        }
        return order(sum.setScale(0, RoundingMode.FLOOR), s);
    }

    /** The player buys {@code qty} from the market. */
    public static Order buy(Inputs in, double factor, int qty) {
        BigDecimal base = BigDecimal.valueOf(in.basePrice());
        BigDecimal markup = BigDecimal.ONE.add(BigDecimal.valueOf(in.markup()));
        if (in.unlimited()) {
            // Purchases don't move s, so every unit costs the same; the buy price never falls below its minimum.
            BigDecimal unit = base.multiply(BigDecimal.valueOf(factor).multiply(markup)
                    .max(BigDecimal.ONE.add(BigDecimal.valueOf(in.minBuyMarkup()))));
            return order(unit.multiply(BigDecimal.valueOf(qty)).setScale(0, RoundingMode.CEILING), factor);
        }
        BigDecimal perUnit = base.multiply(markup);
        BigDecimal sum = BigDecimal.ZERO;
        double s = factor;
        for (int i = 0; i < qty; i++) {
            sum = sum.add(perUnit.multiply(BigDecimal.valueOf(s)));
            s = afterPurchase(in, s);
        }
        return order(sum.setScale(0, RoundingMode.CEILING), s);
    }

    /** What the market pays for one unit right now. */
    public static long unitSellPrice(Inputs in, double factor) {
        return sell(in, factor, 1).total();
    }

    /** What the market charges for one unit right now. */
    public static long unitBuyPrice(Inputs in, double factor) {
        return buy(in, factor, 1).total();
    }

    /** A sale never raises s, even if the floor was raised above the current value by a config change. */
    static double afterSale(Inputs in, double s) {
        if (!in.dynamic()) {
            return s;
        }
        return Math.min(s, Math.max(in.floor(), s * (1 - in.sellImpact())));
    }

    /** A purchase never lowers s; purchases of unlimited items don't move it at all. */
    static double afterPurchase(Inputs in, double s) {
        if (!in.dynamic() || in.unlimited()) {
            return s;
        }
        return Math.max(s, Math.min(in.ceiling(), s * (1 + in.buyImpact())));
    }

    /**
     * The factor after recovering from {@code lastTick} to {@code now}: {@code 1 + (s − 1) × (1 − recovery)^steps},
     * where steps counts the multiples of {@code intervalTicks} crossed. Boundaries are absolute, so frequent trades
     * don't reset the clock and every item steps at the same moment.
     */
    public static double recovered(double s, long lastTick, long now, double recovery, int intervalTicks) {
        long steps = Math.floorDiv(now, intervalTicks) - Math.floorDiv(lastTick, intervalTicks);
        if (steps <= 0 || s == 1.0) {
            return s;
        }
        double result = 1 + (s - 1) * Math.pow(1 - recovery, steps);
        return Math.abs(result - 1) < 1e-9 ? 1.0 : result;
    }

    /**
     * Why buying and immediately reselling this item could make a profit, or empty if it can't. Pairing the k-th
     * unit sold back with the matching unit bought, the sale pays at most
     * {@code (1 + b) × ((1 + b)(1 − a))^k × (1 − fee) / (1 + markup)} of the purchase, so both factors must stay
     * at or below 1. Unlimited and non-dynamic items always pay out at or below base and charge at or above it.
     */
    public static Optional<String> arbitrageRisk(Inputs in) {
        if (!in.dynamic() || in.unlimited()) {
            return Optional.empty();
        }
        double b = in.buyImpact();
        if ((1 + b) * (1 - in.sellImpact()) > 1) {
            return Optional.of("buy_impact " + b + " outweighs sell_impact " + in.sellImpact());
        }
        if ((1 + b) * (1 - in.sellFee()) > 1 + in.markup()) {
            return Optional.of("buy_impact " + b + " exceeds markup " + in.markup() + " plus sell_fee");
        }
        return Optional.empty();
    }

    private static Order order(BigDecimal total, double factorAfter) {
        if (total.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            return new Order(Long.MAX_VALUE, factorAfter, true);
        }
        return new Order(total.longValueExact(), factorAfter, false);
    }
}
