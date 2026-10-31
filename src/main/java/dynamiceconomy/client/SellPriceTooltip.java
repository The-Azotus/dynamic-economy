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
import java.util.Locale;

/**
 * Adds "Market pays X spurs" (bits with the mod's own bank) to the tooltip of every item the market buys, wherever
 * it appears. Items the market doesn't buy (not listed, selling disabled, or a modified copy such as an enchanted one)
 * get no line.
 */
public final class SellPriceTooltip {
    private static final int GOLD = 0xF2C94C;
    private static final int GREY = 0xA0A0A8;

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
                PriceFormat.money(PriceFormat.spurs(row.sellTotal(stack.getCount()))), stack.getCount())
                : Component.translatable("tooltip.dynamic_economy.sell_price", each);
        event.getToolTip().add(line.copy().withColor(GOLD));
        if (Math.abs(row.factor() - 1) > 0.005) {
            event.getToolTip().add(Component.translatable("tooltip.dynamic_economy.sell_price_trend",
                    String.format(Locale.ROOT, "%.0f", row.factor() * 100)).withColor(GREY));
        }
    }
}
