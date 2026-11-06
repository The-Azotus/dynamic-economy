package dynamiceconomy.api;

import dynamiceconomy.service.MarketService;
import net.minecraft.world.item.Item;

import java.util.OptionalLong;

/**
 * An item's market terms at one moment. Prices are in bits, for the next single unit: a larger order moves the price
 * as it goes, so its total isn't simply the unit price times the quantity. Same for every player.
 *
 * @param item      the item
 * @param basePrice the item's base price from the catalog
 * @param sellPrice what the market pays a player for the next unit
 * @param buyPrice  what the market charges a player for the next unit
 * @param stock     how many the market holds to sell; empty when its stock is unlimited
 * @param canSell   whether players may sell it to the market
 * @param canBuy    whether players may buy it from the market
 * @param factor    the supply and demand factor right now: 1.0 is the base price, lower after players sell, higher
 *                  after they buy
 */
public record MarketQuote(Item item, long basePrice, long sellPrice, long buyPrice, OptionalLong stock, boolean canSell,
                          boolean canBuy, double factor) {
    static MarketQuote from(Item item, MarketService.Quote quote) {
        return new MarketQuote(item, quote.entry().basePrice(), quote.sellPrice(), quote.buyPrice(), quote.stock(),
                quote.entry().canSell(), quote.entry().canBuy(), quote.factor());
    }
}
