package dynamiceconomy.catalog;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * One listed item. Prices are in spurs.
 *
 * @param basePrice  what the market pays per unit before any supply/demand factor
 * @param markup     extra fraction charged when the market sells, e.g. 0.5 means buyers pay 150%
 * @param startStock stock given to the item the first time it appears in the market's saved data
 */
public record CatalogEntry(Item item, ResourceLocation itemId, int basePrice, double markup, long startStock,
                           boolean canSell, boolean canBuy) {

    public static final double DEFAULT_MARKUP = 0.5;

    /** The JSON shape, before the item ID is resolved against the registry. */
    record Raw(ResourceLocation item, int basePrice, double markup, long startStock, boolean canSell, boolean canBuy) {
        static final Codec<Raw> CODEC = RecordCodecBuilder.create(i -> i.group(
                ResourceLocation.CODEC.fieldOf("item").forGetter(Raw::item),
                Codec.intRange(0, Integer.MAX_VALUE).fieldOf("base_price").forGetter(Raw::basePrice),
                Codec.doubleRange(0, 1000).optionalFieldOf("markup", DEFAULT_MARKUP).forGetter(Raw::markup),
                Codec.LONG.optionalFieldOf("start_stock", 0L).forGetter(Raw::startStock),
                Codec.BOOL.optionalFieldOf("can_sell", true).forGetter(Raw::canSell),
                Codec.BOOL.optionalFieldOf("can_buy", true).forGetter(Raw::canBuy)
        ).apply(i, Raw::new));

        CatalogEntry resolve(Item resolved) {
            return new CatalogEntry(resolved, item, basePrice, markup, Math.max(0, startStock), canSell, canBuy);
        }
    }

    /** A stack is tradeable only if it carries exactly the item's default components. */
    public boolean matches(ItemStack stack) {
        return ItemStack.isSameItemSameComponents(stack, new ItemStack(item));
    }
}
