package dynamiceconomy.client;

import dynamiceconomy.MarketConfig;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.network.MarketPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.math.RoundingMode;

/**
 * Adds "Value: X spurs" (bits with the mod's own bank) to the tooltip of every item the market buys, wherever it
 * appears, with the whole stack's value on a stack. It shows at 0 stock too, since the value doesn't depend on stock.
 * Items the market doesn't buy (not listed, selling disabled, or a modified copy such as an enchanted one) get no line.
 */
public final class SellPriceTooltip {
    private static final int GOLD = 0xF2C94C;

    private SellPriceTooltip() {
    }

    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !ClientConfig.showSellPrice() || Minecraft.getInstance().screen instanceof MarketScreen) {
            return; // The market screen shows its own prices.
        }
        MarketPayloads.Row row = ClientMarketState.rows().get(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        if (row == null || !row.canSell() || !CatalogEntry.plain(stack, stack.getItem(), MarketConfig.ignoredComponents())) {
            return;
        }
        Component each = PriceFormat.money(PriceFormat.unitPrice(row.exactSellPrice(), RoundingMode.FLOOR));
        Component line = stack.getCount() > 1
                ? Component.translatable("tooltip.dynamic_economy.sell_price_stack", each,
                PriceFormat.amount(row.sellTotal(stack.getCount())), stack.getCount())
                : Component.translatable("tooltip.dynamic_economy.sell_price", each);
        event.getToolTip().add(line.copy().withColor(GOLD));
    }
}
