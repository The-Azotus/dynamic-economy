package dynamiceconomy.coin;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;
import java.util.Locale;

/** One of the mod's coins. Its tooltip says what it's worth, and what the whole stack is worth. */
public final class CoinItem extends Item {
    private final Coin coin;

    public CoinItem(Coin coin, Properties properties) {
        super(properties.rarity(coin.rarity()));
        this.coin = coin;
    }

    public Coin coin() {
        return coin;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.dynamic_economy.coin_value", bits(coin.value())).withStyle(ChatFormatting.GRAY));
        if (stack.getCount() > 1) {
            tooltip.add(Component.translatable("tooltip.dynamic_economy.coin_stack_value",
                    bits((long) coin.value() * stack.getCount())).withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private static String bits(long bits) {
        return String.format(Locale.ROOT, "%,d", bits);
    }
}
