package dynamiceconomy.catalog;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dynamiceconomy.MarketConfig;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One listed item. Prices are in bits (= spurs).
 *
 * @param basePrice      what the market pays per unit before the supply/demand factor and fee
 * @param markup         extra fraction charged when the market sells, e.g. 0.5 means buyers pay 150%
 * @param startStock     stock given to the item the first time it appears in the market's saved data; ignored for
 *                       unlimited items
 * @param unlimitedStock the market always has this item to sell and never stocks what players sell it
 * @param sellFee        fraction kept back from what the market pays (a money sink)
 * @param dynamic        whether the supply/demand factor moves for this item; if false it stays at 1.0
 * @param minBuyMarkup   unlimited items only: the lowest markup the buy price can fall to; empty means
 *                       {@code markup}
 * @param overrides      per-item replacements for the server config's pricing defaults
 * @param itemClass      the item class whose values filled the fields no catalog layer set, if any
 * @param contract       per-item replacements for the server config's contract defaults
 */
public record CatalogEntry(Item item, ResourceLocation itemId, int basePrice, double markup, long startStock,
                           boolean canSell, boolean canBuy, boolean unlimitedStock, double sellFee, boolean dynamic,
                           Optional<Double> minBuyMarkup, Overrides overrides, Optional<ResourceLocation> itemClass,
                           ContractTerms contract) {

    public static final double DEFAULT_MARKUP = 0.5;

    /** An entry using the server config's contract settings. */
    public CatalogEntry(Item item, ResourceLocation itemId, int basePrice, double markup, long startStock,
                        boolean canSell, boolean canBuy, boolean unlimitedStock, double sellFee, boolean dynamic,
                        Optional<Double> minBuyMarkup, Overrides overrides, Optional<ResourceLocation> itemClass) {
        this(item, itemId, basePrice, markup, startStock, canSell, canBuy, unlimitedStock, sellFee, dynamic, minBuyMarkup,
                overrides, itemClass, ContractTerms.NONE);
    }

    /**
     * How the item appears on the contract board; empty means "use the server config". Read with its own codec,
     * beside {@link Raw}, since one record codec holds at most 16 fields.
     *
     * @param weight  how likely the item is to be picked, relative to others; 0 keeps it off the board
     * @param minSize fewest units one contract asks for
     * @param maxSize most units one contract asks for
     * @param premium the reward per unit is {@code base × (1 + premium)}, capped below the lowest buy price
     */
    public record ContractTerms(Optional<Integer> weight, Optional<Integer> minSize, Optional<Integer> maxSize,
                                Optional<Double> premium) {
        public static final ContractTerms NONE = new ContractTerms(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        static final MapCodec<ContractTerms> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.intRange(0, 1000).optionalFieldOf("contract_weight").forGetter(ContractTerms::weight),
                Codec.intRange(1, 100_000).optionalFieldOf("contract_min_size").forGetter(ContractTerms::minSize),
                Codec.intRange(1, 100_000).optionalFieldOf("contract_max_size").forGetter(ContractTerms::maxSize),
                Codec.doubleRange(0, 100).optionalFieldOf("contract_premium").forGetter(ContractTerms::premium)
        ).apply(i, ContractTerms::new));

        public int effectiveWeight() {
            return weight.orElse(MarketConfig.contractWeight());
        }

        public int effectiveMinSize() {
            return minSize.orElse(MarketConfig.contractMinSize());
        }

        /** Never below {@link #effectiveMinSize()}, so a size can always be picked. */
        public int effectiveMaxSize() {
            return Math.max(effectiveMinSize(), maxSize.orElse(MarketConfig.contractMaxSize()));
        }

        public double effectivePremium() {
            return premium.orElse(MarketConfig.contractPremium());
        }

        /** Checks rules that span fields. Each field's range is checked by the codec. */
        DataResult<ContractTerms> validate() {
            if (minSize.isPresent() && maxSize.isPresent() && minSize.get() > maxSize.get()) {
                return DataResult.error(() -> "contract_min_size (" + minSize.get() + ") must not be above contract_max_size ("
                        + maxSize.get() + ")");
            }
            return DataResult.success(this);
        }
    }

    /** Pricing settings an entry may override; empty means "use the server config". */
    public record Overrides(Optional<Double> sellImpact, Optional<Double> buyImpact, Optional<Double> recovery,
                            Optional<Double> floor, Optional<Double> ceiling) {
        public static final Overrides NONE =
                new Overrides(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** A copy with a different unlimited flag. */
    public CatalogEntry withUnlimitedStock(boolean unlimited) {
        return new CatalogEntry(item, itemId, basePrice, markup, startStock, canSell, canBuy, unlimited, sellFee,
                dynamic, minBuyMarkup, overrides, itemClass, contract);
    }

    /** A copy with different supply/demand settings. */
    public CatalogEntry withPricing(double sellFee, boolean dynamic, Optional<Double> minBuyMarkup, Overrides overrides) {
        return new CatalogEntry(item, itemId, basePrice, markup, startStock, canSell, canBuy, unlimitedStock, sellFee,
                dynamic, minBuyMarkup, overrides, itemClass, contract);
    }

    /** A copy with different contract settings. */
    public CatalogEntry withContract(ContractTerms terms) {
        return new CatalogEntry(item, itemId, basePrice, markup, startStock, canSell, canBuy, unlimitedStock, sellFee,
                dynamic, minBuyMarkup, overrides, itemClass, terms);
    }

    /** The JSON shape, before the item ID is resolved against the registry. */
    record Raw(ResourceLocation item, int basePrice, double markup, long startStock, boolean canSell, boolean canBuy,
               boolean unlimitedStock, double sellFee, boolean dynamic, Optional<Double> minBuyMarkup,
               Optional<Double> sellImpact, Optional<Double> buyImpact, Optional<Double> recovery,
               Optional<Double> floor, Optional<Double> ceiling) {
        static final Codec<Raw> CODEC = RecordCodecBuilder.create(i -> i.group(
                ResourceLocation.CODEC.fieldOf("item").forGetter(Raw::item),
                Codec.intRange(1, Integer.MAX_VALUE).fieldOf("base_price").forGetter(Raw::basePrice),
                Codec.doubleRange(0, 1000).optionalFieldOf("markup", DEFAULT_MARKUP).forGetter(Raw::markup),
                Codec.LONG.optionalFieldOf("start_stock", 0L).forGetter(Raw::startStock),
                Codec.BOOL.optionalFieldOf("can_sell", true).forGetter(Raw::canSell),
                Codec.BOOL.optionalFieldOf("can_buy", true).forGetter(Raw::canBuy),
                Codec.BOOL.optionalFieldOf("unlimited_stock", false).forGetter(Raw::unlimitedStock),
                Codec.doubleRange(0, 0.99).optionalFieldOf("sell_fee", 0.0).forGetter(Raw::sellFee),
                Codec.BOOL.optionalFieldOf("dynamic", true).forGetter(Raw::dynamic),
                Codec.DOUBLE.optionalFieldOf("min_buy_markup").forGetter(Raw::minBuyMarkup),
                Codec.doubleRange(0, 0.5).optionalFieldOf("sell_impact").forGetter(Raw::sellImpact),
                Codec.doubleRange(0, 0.5).optionalFieldOf("buy_impact").forGetter(Raw::buyImpact),
                Codec.doubleRange(0, 1).optionalFieldOf("recovery").forGetter(Raw::recovery),
                Codec.doubleRange(0.001, 1).optionalFieldOf("floor").forGetter(Raw::floor),
                Codec.doubleRange(1, 1000).optionalFieldOf("ceiling").forGetter(Raw::ceiling)
        ).apply(i, Raw::new));

        /** Checks rules that span fields, then builds the entry. */
        DataResult<CatalogEntry> resolve(Item resolved, Optional<ResourceLocation> itemClass) {
            // Checked only when set explicitly: the default (= markup) is fine even when markup is 0.
            if (minBuyMarkup.isPresent() && (minBuyMarkup.get() <= 0 || minBuyMarkup.get() > markup)) {
                return DataResult.error(() -> "min_buy_markup must be > 0 and <= markup (" + markup + "), got "
                        + minBuyMarkup.get());
            }
            return DataResult.success(new CatalogEntry(resolved, item, basePrice, markup, Math.max(0, startStock),
                    canSell, canBuy, unlimitedStock, sellFee, dynamic, minBuyMarkup,
                    new Overrides(sellImpact, buyImpact, recovery, floor, ceiling), itemClass));
        }
    }

    /**
     * A stack can be sold only if it carries the item's default components, apart from those the server config
     * ignores ({@link MarketConfig#ignoredComponents}).
     */
    public boolean matches(ItemStack stack) {
        return matches(stack, MarketConfig.ignoredComponents());
    }

    public boolean matches(ItemStack stack, Set<ResourceLocation> ignored) {
        return plain(stack, item, ignored);
    }

    /** Whether {@code stack} is {@code item} with no changed components other than {@code ignored} ones. */
    public static boolean plain(ItemStack stack, Item item, Set<ResourceLocation> ignored) {
        if (!stack.is(item)) {
            return false;
        }
        for (Map.Entry<DataComponentType<?>, Optional<?>> changed : stack.getComponentsPatch().entrySet()) {
            if (!ignored.contains(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(changed.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** Exactly as the market hands it out: bought items only ever join stacks like this. */
    public boolean isFresh(ItemStack stack) {
        return ItemStack.isSameItemSameComponents(stack, new ItemStack(item));
    }
}
