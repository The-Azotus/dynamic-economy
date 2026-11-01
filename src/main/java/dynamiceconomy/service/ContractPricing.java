package dynamiceconomy.service;

import dynamiceconomy.catalog.CatalogEntry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Contract money, in bits. A contract offers {@code base × (1 + contract_premium)} per unit. What delivering {@code d}
 * units pays is that times {@code d}, rounded down once, but always at least one bit less than buying those {@code d}
 * units from the market could ever cost. Buying from the market to fill a contract therefore never profits, however
 * cheaply or how long ago the items were bought. An item is offered only if a contract would still pay more than
 * selling the same units at the item's normal price, so the cap leaves most finite-stock items off the board: with
 * the built-in classes their price can fall to a third of base.
 * <p>
 * Pure: everything it needs is passed in.
 */
public final class ContractPricing {
    private ContractPricing() {
    }

    /** One contract's terms, as offered on a board. */
    public record Offer(int units, BigDecimal unitReward, long reward, long deposit) {
    }

    /** The reward per unit before the cap. */
    public static BigDecimal unitReward(CatalogEntry entry) {
        return BigDecimal.valueOf(entry.basePrice())
                .multiply(BigDecimal.ONE.add(BigDecimal.valueOf(entry.contract().effectivePremium())));
    }

    /**
     * The least one unit could ever cost to buy from the market, unrounded; empty if the market doesn't sell it.
     * Unlimited items never charge less than {@code min_buy_markup} over base. A finite dynamic item's price never
     * falls below its floor, or below its factor now if a raised floor hasn't caught up with it yet.
     *
     * @param factor the item's factor now
     */
    public static Optional<BigDecimal> lowestUnitBuyPrice(CatalogEntry entry, PriceModel.Inputs in, double factor) {
        if (!entry.canBuy()) {
            return Optional.empty();
        }
        BigDecimal base = BigDecimal.valueOf(in.basePrice());
        if (in.unlimited()) {
            return Optional.of(base.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(in.minBuyMarkup()))));
        }
        double lowest = in.dynamic() ? Math.min(in.floor(), factor) : 1.0;
        return Optional.of(base.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(in.markup()))).multiply(BigDecimal.valueOf(lowest)));
    }

    /**
     * What delivering {@code delivered} units pays: {@code delivered × unitReward} rounded down, but below
     * {@code delivered × lowestBuy} rounded up, which is the least buying them could cost. Capped at
     * {@link Long#MAX_VALUE}; callers reject anything above an int.
     */
    public static long payout(BigDecimal unitReward, Optional<BigDecimal> lowestBuy, int delivered) {
        if (delivered <= 0) {
            return 0;
        }
        BigDecimal units = BigDecimal.valueOf(delivered);
        BigDecimal reward = units.multiply(unitReward).setScale(0, RoundingMode.FLOOR);
        if (lowestBuy.isPresent()) {
            reward = reward.min(units.multiply(lowestBuy.get()).setScale(0, RoundingMode.CEILING).subtract(BigDecimal.ONE));
        }
        if (reward.signum() <= 0) {
            return 0;
        }
        return reward.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0 ? Long.MAX_VALUE : reward.longValueExact();
    }

    /**
     * A contract for {@code units} of the item, or empty if one isn't worth offering: it would pay no more than
     * selling those units at the item's normal price, or the reward plus deposit wouldn't fit in one bank transaction.
     *
     * @param factor the item's factor now
     */
    public static Optional<Offer> offer(CatalogEntry entry, PriceModel.Inputs in, double factor, int units, double depositFraction) {
        if (!entry.canSell() || units <= 0) {
            return Optional.empty();
        }
        BigDecimal unitReward = unitReward(entry);
        long reward = payout(unitReward, lowestUnitBuyPrice(entry, in, factor), units);
        PriceModel.Order selling = PriceModel.sell(in, 1.0, units);
        if (reward <= 0 || (!selling.overflow() && reward <= selling.total())) {
            return Optional.empty();
        }
        long deposit = deposit(reward, depositFraction);
        if (reward > Integer.MAX_VALUE || deposit > Integer.MAX_VALUE - reward) {
            return Optional.empty();
        }
        return Optional.of(new Offer(units, unitReward, reward, deposit));
    }

    /** {@code reward × fraction}, rounded up. */
    public static long deposit(long reward, double fraction) {
        return BigDecimal.valueOf(reward).multiply(BigDecimal.valueOf(fraction)).setScale(0, RoundingMode.CEILING).longValueExact();
    }

    /** Why the item never appears on the board, or empty if it can. For admins. */
    public static Optional<String> whyNotOffered(CatalogEntry entry, PriceModel.Inputs in, double factor, double depositFraction) {
        if (entry.contract().effectiveWeight() == 0) {
            return Optional.of("its contract_weight is 0");
        }
        if (!entry.canSell()) {
            return Optional.of("the market doesn't buy it (can_sell false), so contracts would reopen what that closed");
        }
        int size = entry.contract().effectiveMinSize();
        if (offer(entry, in, factor, size, depositFraction).isPresent()) {
            return Optional.empty();
        }
        Optional<BigDecimal> lowest = lowestUnitBuyPrice(entry, in, factor);
        String cap = lowest.map(price -> "the market could sell it for as little as " + plain(price) + " each, ").orElse("");
        return Optional.of(cap + "so a contract for " + size + " couldn't pay more than selling them ("
                + PriceModel.sell(in, 1.0, size).total() + ") or is too large for one payment");
    }

    private static String plain(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
