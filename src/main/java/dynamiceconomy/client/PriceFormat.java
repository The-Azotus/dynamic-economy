package dynamiceconomy.client;

import dynamiceconomy.bank.BankKind;
import net.minecraft.network.chat.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** How the market screen and item tooltips write numbers. Locale-neutral, so every player sees the same digits. */
final class PriceFormat {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    private PriceFormat() {
    }

    /** An amount of money with the world's unit: "1,234 spurs" with Numismatics' bank, "1,234 bits" with ours. */
    static Component money(String amount) {
        return Component.translatable(ClientMarketState.bank() == BankKind.OWN
                ? "money.dynamic_economy.bits" : "money.dynamic_economy.spurs", amount);
    }

    /** A whole amount with thousands separators: "1,234". */
    static String amount(long amount) {
        return String.format(Locale.ROOT, "%,d", amount);
    }

    /** Full digits up to 99,999; abbreviated above that so the price columns keep their width. */
    static String compact(long amount) {
        if (amount < 100_000) {
            return amount(amount);
        }
        return amount < 1_000_000
                ? String.format(Locale.ROOT, "%.0fk", amount / 1_000.0)
                : String.format(Locale.ROOT, "%.2fM", amount / 1_000_000.0);
    }

    static String count(long count) {
        if (count >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", count / 1_000_000.0);
        }
        if (count >= 10_000) {
            return String.format(Locale.ROOT, "%.1fk", count / 1_000.0);
        }
        return String.valueOf(count);
    }

    /**
     * A price for one unit: up to two decimals below 100, so a cheap item's moving price doesn't show as 0, and
     * whole numbers above. Pass {@code FLOOR} for what the market pays and {@code CEILING} for what it charges, so the
     * shown price is never better than the real one. Orders are still totalled exactly and rounded once.
     */
    static String unitPrice(BigDecimal exact, RoundingMode direction) {
        if (exact.compareTo(HUNDRED) < 0) {
            return plain(exact.setScale(2, direction));
        }
        return compact(exact.setScale(0, direction).longValueExact());
    }

    /** An average or other decimal amount: two decimals below 1,000, whole numbers with separators above. */
    static String decimal(BigDecimal value) {
        if (value.compareTo(THOUSAND) < 0) {
            return plain(value.setScale(2, RoundingMode.HALF_UP));
        }
        return amount(value.setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    private static String plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }
}
