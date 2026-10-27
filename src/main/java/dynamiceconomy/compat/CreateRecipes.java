package dynamiceconomy.compat;

import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Recipe;

import java.util.Optional;

/**
 * What the generic recipe methods don't say about Create's machine recipes: their outputs come with a chance (crushing
 * diorite gives quartz only sometimes), and some take fluids. Create is always present, since Numismatics needs it.
 */
public final class CreateRecipes {
    private CreateRecipes() {
    }

    /**
     * @param expected how many of {@code item} one run makes on average, over every output of that item
     * @param fluids   whether the recipe also takes fluids, which aren't counted in its cost
     */
    public record Output(Item item, double expected, boolean fluids) {
    }

    /** The main output of a Create processing recipe and how many of it to expect; empty for other recipes. */
    public static Optional<Output> read(Recipe<?> recipe) {
        if (!(recipe instanceof ProcessingRecipe<?, ?> processing) || processing.getRollableResults().isEmpty()) {
            return Optional.empty();
        }
        Item item = processing.getRollableResults().get(0).getStack().getItem();
        double expected = 0;
        for (ProcessingOutput output : processing.getRollableResults()) {
            if (output.getStack().getItem() == item) {
                expected += output.getStack().getCount() * (double) output.getChance();
            }
        }
        return Optional.of(new Output(item, expected, !processing.getFluidIngredients().isEmpty()));
    }
}
