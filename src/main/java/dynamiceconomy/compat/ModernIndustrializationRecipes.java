package dynamiceconomy.compat;

import dynamiceconomy.DynamicEconomy;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What the generic recipe methods don't say about Modern Industrialization's machine recipes: how many of each input
 * they take (a packer takes 9 ingots, but its ingredient list shows one), which inputs are catalysts that are never
 * used up (probability 0), and how many of each output a run makes on average. Without the amounts, pack and unpack
 * recipes form loops that drive suggested prices towards zero.
 * <p>
 * Modern Industrialization is optional and not a build dependency, so its recipes are read by reflection, using the
 * names in its 2.5 release: {@code MachineRecipe}'s public {@code itemInputs}, {@code itemOutputs} and
 * {@code fluidInputs} lists, whose entries have {@code ingredient()} or {@code getStack()}, {@code amount()} and
 * {@code probability()}. If a later version renames them, its recipes fall back to the generic reading, with one
 * warning in the log.
 */
public final class ModernIndustrializationRecipes {
    private static final String MACHINE_RECIPE = "aztech.modern_industrialization.machines.recipe.MachineRecipe";
    private static volatile boolean warned;

    private ModernIndustrializationRecipes() {
    }

    /** One input: the items that fit it and how many a run uses on average (amount × probability). */
    public record Input(List<Item> options, double amount) {
    }

    /**
     * @param inputs   inputs a run uses up; catalysts (probability 0) are left out
     * @param item     the main output
     * @param expected how many of {@code item} one run makes on average
     * @param fluids   whether the recipe uses up fluids, which aren't counted in its cost
     */
    public record Read(List<Input> inputs, Item item, double expected, boolean fluids) {
    }

    /** The recipe's inputs and main output with their amounts; empty for recipes that aren't Modern Industrialization's. */
    public static Optional<Read> read(Recipe<?> recipe) {
        Class<?> type = machineRecipeClass(recipe.getClass());
        if (type == null) {
            return Optional.empty();
        }
        try {
            List<Input> inputs = new ArrayList<>();
            for (Object input : list(type, "itemInputs", recipe)) {
                double amount = number(input, "amount") * number(input, "probability");
                Ingredient ingredient = (Ingredient) input.getClass().getMethod("ingredient").invoke(input);
                List<Item> options = new ArrayList<>();
                for (ItemStack stack : ingredient.getItems()) {
                    if (!stack.isEmpty() && !options.contains(stack.getItem())) {
                        options.add(stack.getItem());
                    }
                }
                if (amount > 0 && !options.isEmpty()) {
                    inputs.add(new Input(List.copyOf(options), amount));
                }
            }
            Item item = null;
            double expected = 0;
            for (Object output : list(type, "itemOutputs", recipe)) {
                ItemStack stack = (ItemStack) output.getClass().getMethod("getStack").invoke(output);
                if (item == null) {
                    item = stack.getItem();
                }
                if (stack.getItem() == item) {
                    expected += number(output, "amount") * number(output, "probability");
                }
            }
            boolean fluids = false;
            for (Object fluid : list(type, "fluidInputs", recipe)) {
                fluids |= number(fluid, "probability") > 0;
            }
            return Optional.of(new Read(List.copyOf(inputs), item, expected, fluids));
        } catch (ReflectiveOperationException | ClassCastException e) {
            if (!warned) {
                warned = true;
                DynamicEconomy.LOGGER.warn("Couldn't read Modern Industrialization's recipe amounts ({}); its recipes are priced from "
                        + "the generic recipe data, which leaves amounts out", e.toString());
            }
            return Optional.empty();
        }
    }

    private static Class<?> machineRecipeClass(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (c.getName().equals(MACHINE_RECIPE)) {
                return c;
            }
        }
        return null;
    }

    private static List<?> list(Class<?> type, String field, Object recipe) throws ReflectiveOperationException {
        Field f = type.getField(field);
        Object value = f.get(recipe);
        return value == null ? List.of() : (List<?>) value;
    }

    private static double number(Object record, String accessor) throws ReflectiveOperationException {
        return ((Number) record.getClass().getMethod(accessor).invoke(record)).doubleValue();
    }
}
