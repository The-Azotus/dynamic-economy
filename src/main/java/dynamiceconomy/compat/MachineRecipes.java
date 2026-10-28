package dynamiceconomy.compat;

import dynamiceconomy.DynamicEconomy;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.neoforge.common.crafting.SizedIngredient;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the generic recipe methods don't say about some mods' machine recipes: their inputs, how many of each they take,
 * and how many outputs a run makes. Without this, Immersive Engineering, Mekanism and Oritech recipes price nothing
 * (their inputs or outputs live outside {@code getIngredients()} and {@code getResultItem()}), and Modern
 * Industrialization's pack and unpack recipes form loops that drive suggested prices towards zero.
 * <p>
 * None of these mods is a build dependency, so their recipes are read by reflection, using the names in the versions
 * the pack ships (Modern Industrialization 2.5, Immersive Engineering 12.4, Mekanism 10.7, Oritech 1.2). If a later
 * version renames something, that mod's recipes fall back to the generic reading, with one warning in the log. Energy
 * and machine fuel aren't priced; secondary and chance outputs other than the main item are left out, which errs
 * towards a higher price for the main output.
 */
public final class MachineRecipes {
    private static final String MI_RECIPE = "aztech.modern_industrialization.machines.recipe.MachineRecipe";
    private static final String IE_BASE = "blusunrize.immersiveengineering.api.crafting.IESerializableRecipe";
    private static final String IE_MULTIBLOCK = "blusunrize.immersiveengineering.api.crafting.MultiblockRecipe";
    private static final String MEKANISM_RECIPE = "mekanism.api.recipes.MekanismRecipe";
    private static final String MEKANISM_ITEM_INGREDIENT = "mekanism.api.recipes.ingredients.ItemStackIngredient";
    private static final Set<String> MEKANISM_OTHER_INGREDIENTS = Set.of("mekanism.api.recipes.ingredients.ChemicalStackIngredient",
            "mekanism.api.recipes.ingredients.FluidStackIngredient");
    private static final String ORITECH_RECIPE = "rearth.oritech.init.recipes.OritechRecipe";
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private MachineRecipes() {
    }

    /** One input: the items that fit it and how many a run uses on average. */
    public record Input(List<Item> options, double amount) {
    }

    /**
     * @param inputs   inputs a run uses up; catalysts that are never used up are left out
     * @param item     the main output, or null if the recipe makes no item
     * @param expected how many of {@code item} one run makes on average
     * @param fluids   whether the recipe uses up fluids or chemicals, which aren't counted in its cost
     */
    public record Read(List<Input> inputs, Item item, double expected, boolean fluids) {
    }

    /** The recipe's inputs and main output with their amounts; empty for recipes none of the readers knows. */
    public static Optional<Read> read(Recipe<?> recipe) {
        Class<?> type = recipe.getClass();
        Class<?> found;
        String mod = null;
        try {
            if ((found = superclass(type, MI_RECIPE)) != null) {
                mod = "Modern Industrialization";
                return Optional.of(modernIndustrialization(found, recipe));
            }
            if (superclass(type, IE_BASE) != null) {
                mod = "Immersive Engineering";
                return Optional.of(immersiveEngineering(type, recipe));
            }
            if (superclass(type, MEKANISM_RECIPE) != null) {
                mod = "Mekanism";
                return Optional.ofNullable(mekanism(type, recipe));
            }
            if (superclass(type, ORITECH_RECIPE) != null) {
                mod = "Oritech";
                return Optional.of(oritech(recipe));
            }
            return Optional.empty();
        } catch (ReflectiveOperationException | ClassCastException e) {
            if (WARNED.add(mod)) {
                DynamicEconomy.LOGGER.warn("Couldn't read {}'s recipe amounts ({}); its recipes are priced from the generic recipe "
                        + "data, which leaves amounts out", mod, e.toString());
            }
            return Optional.empty();
        }
    }

    /**
     * {@code MachineRecipe}'s public {@code itemInputs}, {@code itemOutputs} and {@code fluidInputs} lists, whose entries
     * have {@code ingredient()} or {@code getStack()}, {@code amount()} and {@code probability()}. Probability 0 marks
     * a catalyst that is never used up.
     */
    private static Read modernIndustrialization(Class<?> type, Object recipe) throws ReflectiveOperationException {
        List<Input> inputs = new ArrayList<>();
        for (Object input : list(type.getField("itemInputs").get(recipe))) {
            double amount = number(input, "amount") * number(input, "probability");
            Ingredient ingredient = (Ingredient) call(input, "ingredient");
            if (amount > 0) {
                add(inputs, ingredient, amount);
            }
        }
        List<ItemStack> outputs = new ArrayList<>();
        List<Double> chances = new ArrayList<>();
        for (Object output : list(type.getField("itemOutputs").get(recipe))) {
            outputs.add((ItemStack) call(output, "getStack"));
            chances.add(number(output, "amount") * number(output, "probability"));
        }
        boolean fluids = false;
        for (Object fluid : list(type.getField("fluidInputs").get(recipe))) {
            fluids |= number(fluid, "probability") > 0;
        }
        Item item = outputs.isEmpty() ? null : outputs.get(0).getItem();
        double expected = 0;
        for (int i = 0; i < outputs.size(); i++) {
            if (outputs.get(i).getItem() == item) {
                expected += chances.get(i);
            }
        }
        return new Read(List.copyOf(inputs), item, expected, fluids);
    }

    /**
     * Multiblock recipes list their inputs ({@code IngredientWithSize}: {@code getBaseIngredient()}, {@code getCount()})
     * and outputs through {@code getItemInputs()}, {@code getItemOutputs()} and {@code getFluidInputs()}. The single-block
     * ones (alloy kiln, coke oven, blast furnace) keep them in public fields named {@code input}, {@code input0},
     * {@code input1} and {@code output} ({@code TagOutput}: {@code get()}). The metal press's mold is a catalyst and
     * isn't among the inputs.
     */
    private static Read immersiveEngineering(Class<?> type, Object recipe) throws ReflectiveOperationException {
        List<Input> inputs = new ArrayList<>();
        List<ItemStack> outputs = new ArrayList<>();
        boolean fluids = false;
        if (superclass(type, IE_MULTIBLOCK) != null) {
            for (Object input : list(call(recipe, "getItemInputs"))) {
                add(inputs, (Ingredient) call(input, "getBaseIngredient"), number(input, "getCount"));
            }
            for (Object output : list(call(recipe, "getItemOutputs"))) {
                outputs.add((ItemStack) output);
            }
            fluids = !list(call(recipe, "getFluidInputs")).isEmpty();
        } else {
            for (String name : List.of("input", "input0", "input1")) {
                Object input = field(type, name, recipe);
                if (input != null && !(input instanceof Ingredient)) {
                    add(inputs, (Ingredient) call(input, "getBaseIngredient"), number(input, "getCount"));
                }
            }
            Object output = field(type, "output", recipe);
            if (output != null && !(output instanceof Collection<?>)) {
                outputs.add((ItemStack) call(output, "get"));
            }
        }
        return fromStacks(inputs, outputs, fluids);
    }

    /**
     * Every Mekanism item input is an {@code ItemStackIngredient} returned by a no-argument getter ({@code getInput},
     * {@code getMainInput}, {@code getExtraInput}, {@code getItemInput}); its {@code ingredient()} is a
     * {@link SizedIngredient}. Outputs are {@code getOutputDefinition()} or {@code getMainOutputDefinition()}. A getter
     * returning a chemical or fluid ingredient means the recipe uses one up. Recipes whose outputs aren't items (gases,
     * energy) give null.
     */
    private static Read mekanism(Class<?> type, Object recipe) throws ReflectiveOperationException {
        List<Input> inputs = new ArrayList<>();
        boolean fluids = false;
        Method outputs = null;
        for (Method method : type.getMethods()) {
            if (method.getParameterCount() != 0 || method.isBridge()) {
                continue;
            }
            String returns = method.getReturnType().getName();
            if (returns.equals(MEKANISM_ITEM_INGREDIENT)) {
                SizedIngredient sized = (SizedIngredient) call(method.invoke(recipe), "ingredient");
                add(inputs, sized.ingredient(), sized.count());
            } else if (MEKANISM_OTHER_INGREDIENTS.contains(returns)) {
                fluids = true;
            } else if (method.getName().equals("getMainOutputDefinition")
                    || method.getName().equals("getOutputDefinition") && outputs == null) {
                outputs = method;
            }
        }
        if (outputs == null) {
            return null;
        }
        List<ItemStack> stacks = new ArrayList<>();
        for (Object output : list(outputs.invoke(recipe))) {
            if (!(output instanceof ItemStack stack)) {
                return null;
            }
            stacks.add(stack);
        }
        return fromStacks(inputs, stacks, fluids);
    }

    /** {@code getInputs()} (one ingredient per item used), {@code getResults()} and {@code getFluidInput()}. */
    private static Read oritech(Object recipe) throws ReflectiveOperationException {
        List<Input> inputs = new ArrayList<>();
        for (Object input : list(call(recipe, "getInputs"))) {
            add(inputs, (Ingredient) input, 1);
        }
        List<ItemStack> outputs = new ArrayList<>();
        for (Object output : list(call(recipe, "getResults"))) {
            outputs.add((ItemStack) output);
        }
        Object fluid = call(recipe, "getFluidInput");
        boolean fluids = fluid != null && !(Boolean) call(fluid, "isEmpty") && number(fluid, "amount") > 0;
        return fromStacks(inputs, outputs, fluids);
    }

    /** The first non-empty stack is the main output; the counts of every stack of that item add up. */
    private static Read fromStacks(List<Input> inputs, List<ItemStack> outputs, boolean fluids) {
        Item item = null;
        double expected = 0;
        for (ItemStack stack : outputs) {
            if (stack.isEmpty()) {
                continue;
            }
            if (item == null) {
                item = stack.getItem();
            }
            if (stack.getItem() == item) {
                expected += stack.getCount();
            }
        }
        return new Read(List.copyOf(inputs), item, expected, fluids);
    }

    /** Adds an input, merging it into an earlier one that accepts the same items. */
    private static void add(List<Input> inputs, Ingredient ingredient, double amount) {
        List<Item> options = new ArrayList<>();
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty() && !options.contains(stack.getItem())) {
                options.add(stack.getItem());
            }
        }
        if (amount <= 0 || options.isEmpty()) {
            return;
        }
        for (int i = 0; i < inputs.size(); i++) {
            if (inputs.get(i).options().equals(options)) {
                inputs.set(i, new Input(inputs.get(i).options(), inputs.get(i).amount() + amount));
                return;
            }
        }
        inputs.add(new Input(List.copyOf(options), amount));
    }

    private static Class<?> superclass(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    /** A public field by name anywhere in the class chain, or null if there is none. */
    private static Object field(Class<?> type, String name, Object target) throws IllegalAccessException {
        try {
            Field f = type.getField(name);
            return f.get(target);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static Object call(Object target, String method) throws ReflectiveOperationException {
        return target.getClass().getMethod(method).invoke(target);
    }

    private static List<?> list(Object value) {
        return value == null ? List.of() : (List<?>) value;
    }

    private static double number(Object target, String accessor) throws ReflectiveOperationException {
        return ((Number) call(target, accessor)).doubleValue();
    }
}
