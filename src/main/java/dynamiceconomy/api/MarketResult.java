package dynamiceconomy.api;

import dynamiceconomy.service.MarketService;

/**
 * How an API trade or payment ended. Only {@link #OK} changed anything; every other result left the market, the bank
 * and the player's inventory exactly as they were.
 */
public enum MarketResult {
    /** Done. */
    OK,
    /** The item isn't listed, or it is hidden from this player (the two answer alike so neither reveals the item). */
    NOT_LISTED,
    /** The market doesn't buy this item from players. */
    SELL_DISABLED,
    /** The market doesn't sell this item to players. */
    BUY_DISABLED,
    /** The player's main inventory holds fewer plain copies of the item than asked to sell. */
    NOT_ENOUGH_ITEMS,
    /** The market holds fewer of the item than asked to buy. */
    NO_STOCK,
    /** The account can't cover the amount. */
    NO_FUNDS,
    /** The items bought wouldn't fit in the player's main inventory. */
    NO_SPACE,
    /** The price differs from the one the player was shown. The API trades at the current price, so it never sees this. */
    PRICE_CHANGED,
    /**
     * Too much at once: a quantity above the server's per-request limit, or a total or amount above
     * {@link Integer#MAX_VALUE} bits, which the banks can't take in one go.
     */
    TOO_LARGE,
    /** A quantity of zero or less. */
    INVALID_QUANTITY,
    /** The world's bank can't be reached: Numismatics' bank with Numismatics missing, or its API changed. */
    BANK_UNAVAILABLE,
    /** An amount of zero or less. */
    INVALID_AMOUNT;

    /** The same result by name, so the internal results can change without changing this enum. */
    static MarketResult from(MarketService.Result result) {
        try {
            return valueOf(result.name());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("No API result for the market result " + result, e);
        }
    }
}
