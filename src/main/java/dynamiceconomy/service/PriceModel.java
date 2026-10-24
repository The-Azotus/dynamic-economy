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
            BigDecimal unit = perUnit.multiply(BigDecimal.valueOf(s));
            double next = afterSale(in, s);
            if (next == s) {
                // s has stopped moving (floor, or a fixed price): the remaining units all trade at this value.
                sum = sum.add(unit.multiply(BigDecimal.valueOf(qty - i)));
                break;
            }
            sum = sum.add(unit);
            s = next;
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
            BigDecimal unit = perUnit.multiply(BigDecimal.valueOf(s));
            double next = afterPurchase(in, s);
            if (next == s) {
                // s has stopped moving (ceiling, or a fixed price): the remaining units all trade at this value.
                sum = sum.add(unit.multiply(BigDecimal.valueOf(qty - i)));
                break;
            }
            sum = sum.add(unit);
            s = next;
        }
        return order(sum.setScale(0, RoundingMode.CEILING), s);
    }

    /** What the market pays for the next unit, before rounding. For display: orders are priced by {@link #sell}. */
    public static BigDecimal exactUnitSellPrice(Inputs in, double factor) {
        return BigDecimal.valueOf(in.basePrice()).multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(in.sellFee())))
                .multiply(BigDecimal.valueOf(factor));
    }

    /** What the market charges for the next unit, before rounding. For display: orders are priced by {@link #buy}. */
    public static BigDecimal exactUnitBuyPrice(Inputs in, double factor) {
        BigDecimal base = BigDecimal.valueOf(in.basePrice());
        BigDecimal markup = BigDecimal.ONE.add(BigDecimal.valueOf(in.markup()));
        if (in.unlimited()) {
            return base.multiply(BigDecimal.valueOf(factor).multiply(markup).max(BigDecimal.ONE.add(BigDecimal.valueOf(in.minBuyMarkup()))));
        }
        return base.multiply(markup).multiply(BigDecimal.valueOf(factor));
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
    public static double afterSale(Inputs in, double s) {
        if (!in.dynamic()) {
            return s;
        }
        return Math.min(s, Math.max(in.floor(), s * (1 - in.sellImpact())));
    }

    /** A purchase never lowers s; purchases of unlimited items don't move it at all. */
    public static double afterPurchase(Inputs in, double s) {
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
     * Why a round trip on this item could make a profit, or empty if it can't. Unlimited and non-dynamic items always
     * pay out at or below base and charge at or above it, so only finite dynamic items are checked, in both orders.
     * <ul>
     *   <li>Buying then reselling: pairing the k-th unit sold back with the matching unit bought, the sale pays at
     *       most {@code (1 + b) × ((1 + b)(1 − a))^k × (1 − fee) / (1 + markup)} of the purchase, so both factors must
     *       stay at or below 1.</li>
     *   <li>Selling then buying back: see {@link #worstBuyBackRatio}. Buying back must cost at least what the sale
     *       paid, so that ratio times {@code (1 + markup)} must be at least {@code 1 − fee}.</li>
     * </ul>
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
        if (worstBuyBackRatio(in) * (1 + in.markup()) < 1 - in.sellFee()) {
            return Optional.of("selling then buying back can profit: buy_impact " + b + " is too far below sell_impact "
                    + in.sellImpact() + " for markup " + in.markup());
        }
        return Optional.empty();
    }

    /**
     * A lower bound on what one unit bought back costs, before markup, relative to a unit sold just before it.
     * Pair the k-th unit sold with the (q−1−k)-th unit bought back, d = q − k units apart. With r = 1 − sell_impact
     * and g = 1 + buy_impact, the purchase is worth at least {@code max(r^d, floor/ceiling) × g^(d−1)} of the sale
     * (clamping only ever raises the buy-back price, and a pair where either side is clamped is worth at least 1).
     * Over all d that is smallest at d = n or n + 1, where n is the most sales that can stay above the floor.
     */
    static double worstBuyBackRatio(Inputs in) {
        double r = 1 - in.sellImpact();
        double g = 1 + in.buyImpact();
        double span = in.floor() / in.ceiling();
        if (r >= 1 || span >= 1) {
            return 1.0; // Sales never move s, or s has nowhere to move.
        }
        long n = (long) Math.floor(Math.log(span) / Math.log(r));
        if (n < 1) {
            return span;
        }
        return Math.min(r * Math.pow(r * g, n - 1), span * Math.pow(g, n));
    }

    private static Order order(BigDecimal total, double factorAfter) {
        if (total.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            return new Order(Long.MAX_VALUE, factorAfter, true);
        }
        return new Order(total.longValueExact(), factorAfter, false);
    }
}
