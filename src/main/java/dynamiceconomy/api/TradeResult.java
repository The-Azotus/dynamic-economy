package dynamiceconomy.api;

import dynamiceconomy.service.MarketService;

/**
 * How a market trade made through the API ended.
 *
 * @param result    what happened; only {@link MarketResult#OK} changed anything
 * @param quantity  the units traded: the quantity asked for on success, otherwise 0
 * @param unitPrice what the first unit traded at (or would have), in bits; 0 if the trade didn't get as far as pricing,
 *                  and on {@link MarketResult#BANK_UNAVAILABLE}
 * @param total     what the whole order paid or cost (or would have, on {@link MarketResult#PRICE_CHANGED},
 *                  {@link MarketResult#NO_FUNDS}, {@link MarketResult#NO_STOCK}, {@link MarketResult#NO_SPACE} and
 *                  {@link MarketResult#NOT_ENOUGH_ITEMS}), in bits; otherwise 0
 */
public record TradeResult(MarketResult result, int quantity, long unitPrice, long total) {
    /** Whether the trade happened. */
    public boolean ok() {
        return result == MarketResult.OK;
    }

    static TradeResult from(MarketService.TradeResult result) {
        return new TradeResult(MarketResult.from(result.result()), result.quantity(), result.unitPrice(), result.total());
    }
}
