package dynamiceconomy.compat;

import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import com.simibubi.create.content.processing.sequenced.SequencedRecipe;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.List;
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

    /**
     * A sequenced assembly, which lists none of its inputs through the generic methods: its starting item, plus what
     * each step adds (every step's ingredients but the half-made item it works on) once per loop. Its main output comes
     * at the recipe's success chance; the scrap made otherwise isn't subtracted, which errs towards a higher price.
     * Empty for other recipes.
     */
    public static Optional<MachineRecipes.Read> sequencedAssembly(Recipe<?> recipe) {
        if (!(recipe instanceof SequencedAssemblyRecipe assembly)) {
            return Optional.empty();
        }
        Item transitional = assembly.getTransitionalItem().getItem();
        List<MachineRecipes.Input> inputs = new ArrayList<>();
        MachineRecipes.add(inputs, assembly.getIngredient(), 1);
        boolean fluids = false;
        for (SequencedRecipe<?> step : assembly.getSequence()) {
            ProcessingRecipe<?, ?> processing = step.getRecipe();
            for (Ingredient ingredient : processing.getIngredients()) {
                if (!ingredient.test(new ItemStack(transitional))) {
                    MachineRecipes.add(inputs, ingredient, assembly.getLoops());
                }
            }
            fluids |= !processing.getFluidIngredients().isEmpty();
        }
        ItemStack result = assembly.getResultItem(null);
        return Optional.of(new MachineRecipes.Read(List.copyOf(inputs), result.isEmpty() ? null : result.getItem(),
                result.getCount() * (double) assembly.getOutputChance(), fluids));
    }
}
