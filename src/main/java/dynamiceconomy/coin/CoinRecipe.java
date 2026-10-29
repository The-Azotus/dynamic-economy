package dynamiceconomy.coin;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;

/**
 * A shapeless recipe that only works while the world uses the mod's own bank: changing 8 coins for the next one up,
 * or one back into 8. Written exactly like {@code minecraft:crafting_shapeless}, with this recipe's type instead.
 * <p>
 * Recipes load before the world's saved data, so whether coins are in use can't be decided when the recipe loads;
 * it is checked each time the recipe is matched instead.
 */
public final class CoinRecipe extends ShapelessRecipe {
    CoinRecipe(ShapelessRecipe base) {
        super(base.getGroup(), base.category(), base.getResultItem(RegistryAccess.EMPTY), base.getIngredients());
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return Coins.inUse(level) && super.matches(input, level);
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return Coins.COIN_RECIPE.get();
    }

    public static final class Serializer implements RecipeSerializer<CoinRecipe> {
        private static final MapCodec<CoinRecipe> CODEC = RecipeSerializer.SHAPELESS_RECIPE.codec()
                .xmap(CoinRecipe::new, recipe -> recipe);
        private static final StreamCodec<RegistryFriendlyByteBuf, CoinRecipe> STREAM_CODEC =
                RecipeSerializer.SHAPELESS_RECIPE.streamCodec().map(CoinRecipe::new, recipe -> recipe);

        @Override
        public MapCodec<CoinRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, CoinRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
