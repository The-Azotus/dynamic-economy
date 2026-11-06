package dynamiceconomy.api;

/**
 * How a payment or charge made through the API ended.
 *
 * @param result what happened; only {@link MarketResult#OK} moved money
 * @param amount the amount asked for, in bits
 * @param owed   true when a payment couldn't reach the bank and is owed instead: it is paid as soon as the bank is
 *               back, and counts on the leaderboard meanwhile. Always false for a charge.
 */
public record PaymentResult(MarketResult result, long amount, boolean owed) {
    /** Whether the money moved (or, for a payment, is owed). */
    public boolean ok() {
        return result == MarketResult.OK;
    }

    static PaymentResult refused(MarketResult result, long amount) {
        return new PaymentResult(result, amount, false);
    }
}
