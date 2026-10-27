package dynamiceconomy.service;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.compat.CreateRecipes;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Suggests prices for unlisted items from the recipes that make them, starting from the items the catalog already
 * prices ("seeds"). An item's cost is its cheapest recipe: for each ingredient slot the cheapest priced item that fits,
 * minus any priced item the slot gives back (a bucket from a milk bucket), plus fuel for furnace recipes, divided by
 * the output count and raised by a processing premium. Costs only ever fall; a recipe loop that makes something out of
 * less than itself would fall forever, so passes are capped and such items reported.
 * <p>
 * The premium is kept at or below every markup in the catalog, and suggested prices are rounded down, so buying a
 * recipe's ingredients at normal prices, crafting and selling the result never profits. Items from decorative-variant
 * mods ({@link #LEFT_OUT_MODS}) are left out of the market, so they are never priced. Anything the recipes can't tell (fluids, chance
 * ingredient counts some mods keep outside {@link Ingredient}) is missing from the cost; such recipes are marked for
 * review. Create's machine recipes are read more closely: their output counts are what a run makes on average, and
 * recipes taking fluids are marked. Pure apart from reading the item registry: recipes and seeds are passed in.
 */
public final class PriceSuggestion {
    /**
     * Fraction added for each processing step, at most: {@link #premium} lowers it to the catalog's smallest markup when
     * an admin sets one below it. Below the smallest built-in markup (0.4), with room for rounding.
     */
    public static final double PREMIUM = 0.1;
    /** Decorative variants of blocks (thousands each), left out of the market entirely: never suggested or exported. */
    public static final Set<String> LEFT_OUT_MODS = Set.of("chipped", "rechiseled");
    /** Recipes are applied at most this many times over; enough for any real chain, and a stop for loops. */
    private static final int MAX_PASSES = 64;
    /** One coal smelts 8 items. */
    private static final int ITEMS_PER_COAL = 8;
    private static final MathContext MATH = MathContext.DECIMAL64;

    private static final Set<String> RARE_CLASSES = Set.of("rare_resource", "rare_processed", "rare_loot", "unique");
    /** Recipe types whose ingredients and outputs are fully described by the generic recipe methods. */
    private static final Set<String> COMPLETE_TYPES = Set.of("minecraft:crafting", "minecraft:smelting", "minecraft:blasting",
            "minecraft:smoking", "minecraft:campfire_cooking", "minecraft:stonecutting", "minecraft:smithing",
            "create:pressing", "create:cutting", "create:milling", "create:crushing", "create:mechanical_crafting",
            "create:deploying", "create:item_application", "create:splashing", "create:haunting", "create:sandpaper_polishing",
            "create:mixing", "create:compacting", "create:filling", "create:emptying");

    private PriceSuggestion() {
    }

    /**
     * One recipe as the suggester sees it: each slot is the items that fit it, with how many it takes. {@code count}
     * is how many outputs a run makes on average; {@code complete} is false when the recipe has inputs the suggester
     * can't price (fluids) or comes from a recipe type it doesn't fully understand.
     */
    public record Path(ResourceLocation recipe, String type, Item output, double count, List<Slot> slots, boolean burnsFuel,
                       boolean complete) {
        public Path(ResourceLocation recipe, String type, Item output, double count, List<Slot> slots, boolean burnsFuel) {
            this(recipe, type, output, count, slots, burnsFuel, COMPLETE_TYPES.contains(type));
        }
    }

    public record Slot(List<Item> options, int amount) {
    }

    /**
     * A suggested price for an item the catalog doesn't list.
     *
     * @param cost        the exact derived value in spurs, before rounding
     * @param basePrice   {@code cost} rounded down; empty if it's under 1 spur
     * @param itemClass   {@code rare_processed} if any input is rare, else {@code common_processed}
     * @param depth       recipe steps from the nearest priced item
     * @param reverse     items this one is made from that can also be made back from it (storage blocks, nuggets)
     */
    public record Suggestion(Item item, BigDecimal cost, Optional<Integer> basePrice, String itemClass, ResourceLocation recipe,
                             String recipeType, int depth, boolean completeType, Set<Item> reverse) {
    }

    /**
     * @param suggestions     by item, for every unlisted item some recipe could price
     * @param noRecipe        unlisted items no usable recipe makes: candidates for new seeds
     * @param unpricedInputs  unlisted items whose recipes all need something unpriced
     * @param unusable        recipes skipped, by reason
     * @param looping         items whose price was still falling when the passes ran out: a recipe loop makes them out
     *                        of less than themselves, so their suggestion is too low and needs a hand-set price
     * @param premium         the processing premium used (see {@link #premium})
     */
    public record Result(Map<Item, Suggestion> suggestions, Set<Item> noRecipe, Set<Item> unpricedInputs,
                         Map<String, Integer> unusable, Set<Item> looping, double premium) {
        public boolean settled() {
            return looping.isEmpty();
        }
    }

    /** Suggests prices from the game's recipes, for every item that isn't already in {@code catalog}. */
    public static Result suggest(Collection<RecipeHolder<?>> recipes, HolderLookup.Provider registries, Map<Item, CatalogEntry> catalog) {
        Map<String, Integer> unusable = new TreeMap<>();
        List<Path> paths = new ArrayList<>();
        for (RecipeHolder<?> holder : recipes) {
            toPath(holder, registries, unusable).ifPresent(paths::add);
        }
        return suggest(paths, catalog, unusable);
    }

    /** As {@link #suggest(Collection, HolderLookup.Provider, Map)}, from recipes already read. */
    public static Result suggest(List<Path> allPaths, Map<Item, CatalogEntry> catalog, Map<String, Integer> unusable) {
        List<Path> paths = allPaths.stream().filter(path -> !leftOut(path.output())).toList();
        Map<Item, BigDecimal> price = new HashMap<>();
        Map<Item, String> itemClass = new HashMap<>();
        catalog.forEach((item, entry) -> {
            price.put(item, BigDecimal.valueOf(entry.basePrice()));
            itemClass.put(item, entry.itemClass().map(ResourceLocation::getPath).orElse(""));
        });
        double premiumUsed = premium(catalog.values());
        BigDecimal premium = BigDecimal.ONE.add(BigDecimal.valueOf(premiumUsed));
        Map<Item, Path> chosen = new HashMap<>();
        Map<Item, Integer> depth = new HashMap<>();

        Set<Item> changed = new HashSet<>();
        for (int pass = 0; pass < MAX_PASSES && (pass == 0 || !changed.isEmpty()); pass++) {
            changed.clear();
            for (Path path : paths) {
                if (catalog.containsKey(path.output())) {
                    continue; // Listed items keep their catalog price.
                }
                BigDecimal sum = BigDecimal.ZERO;
                int deepest = 0;
                boolean rare = false;
                boolean priced = true;
                for (Slot slot : path.slots()) {
                    Item cheapest = null;
                    for (Item option : slot.options()) {
                        BigDecimal p = price.get(option);
                        if (p != null && (cheapest == null || p.compareTo(price.get(cheapest)) < 0)) {
                            cheapest = option;
                        }
                    }
                    if (cheapest == null) {
                        priced = false;
                        break;
                    }
                    BigDecimal slotCost = price.get(cheapest);
                    Item remainder = remainder(cheapest);
                    if (remainder != null && price.containsKey(remainder)) {
                        slotCost = slotCost.subtract(price.get(remainder)).max(BigDecimal.ZERO);
                    }
                    sum = sum.add(slotCost.multiply(BigDecimal.valueOf(slot.amount())));
                    deepest = Math.max(deepest, depth.getOrDefault(cheapest, 0));
                    rare |= RARE_CLASSES.contains(itemClass.getOrDefault(cheapest, ""));
                }
                if (!priced) {
                    continue;
                }
                if (path.burnsFuel() && price.containsKey(Items.COAL)) {
                    sum = sum.add(price.get(Items.COAL).divide(BigDecimal.valueOf(ITEMS_PER_COAL), MATH));
                }
                BigDecimal cost = sum.multiply(premium).divide(BigDecimal.valueOf(path.count()), MATH);
                BigDecimal current = price.get(path.output());
                if (current == null || cost.compareTo(current) < 0) {
                    price.put(path.output(), cost);
                    chosen.put(path.output(), path);
                    depth.put(path.output(), deepest + 1);
                    itemClass.put(path.output(), rare ? "rare_processed" : "common_processed");
                    changed.add(path.output());
                }
            }
        }
        if (!changed.isEmpty()) {
            DynamicEconomy.LOGGER.warn("Price suggestions still changing after {} passes; a recipe loop makes something out of less", MAX_PASSES);
        }

        // Which items can be turned into each other, so the admin can avoid listing both forms.
        Map<Item, Set<Item>> makes = new HashMap<>();
        for (Path path : paths) {
            for (Slot slot : path.slots()) {
                for (Item option : slot.options()) {
                    makes.computeIfAbsent(option, k -> new HashSet<>()).add(path.output());
                }
            }
        }

        Map<Item, Suggestion> suggestions = new LinkedHashMap<>();
        for (Map.Entry<Item, Path> entry : chosen.entrySet()) {
            Item item = entry.getKey();
            Path path = entry.getValue();
            BigDecimal cost = price.get(item);
            BigDecimal rounded = cost.setScale(0, RoundingMode.FLOOR);
            Optional<Integer> base = rounded.signum() > 0 && rounded.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0
                    ? Optional.of(rounded.intValueExact()) : Optional.empty();
            Set<Item> reverse = new HashSet<>();
            for (Slot slot : path.slots()) {
                for (Item option : slot.options()) {
                    if (makes.getOrDefault(item, Set.of()).contains(option)) {
                        reverse.add(option);
                    }
                }
            }
            suggestions.put(item, new Suggestion(item, cost, base, itemClass.get(item), path.recipe(), path.type(), depth.get(item),
                    path.complete(), reverse));
        }

        Set<Item> made = new HashSet<>();
        paths.forEach(path -> made.add(path.output()));
        Set<Item> noRecipe = new HashSet<>();
        Set<Item> unpricedInputs = new HashSet<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR || catalog.containsKey(item) || suggestions.containsKey(item) || leftOut(item)) {
                continue;
            }
            (made.contains(item) ? unpricedInputs : noRecipe).add(item);
        }
        return new Result(suggestions, noRecipe, unpricedInputs, unusable, Set.copyOf(changed), premiumUsed);
    }

    /**
     * The processing premium for a catalog: {@link #PREMIUM}, or the smallest markup (or lower buy-price limit) any
     * entry has if that is less, so a suggested item never sells for more than its ingredients cost to buy.
     */
    public static double premium(Collection<CatalogEntry> catalog) {
        double premium = PREMIUM;
        for (CatalogEntry entry : catalog) {
            premium = Math.min(premium, Math.min(entry.markup(), entry.minBuyMarkup().orElse(entry.markup())));
        }
        return Math.max(0, premium);
    }

    /** Whether the item comes from a mod left out of the market ({@link #LEFT_OUT_MODS}). */
    public static boolean leftOut(Item item) {
        return leftOut(BuiltInRegistries.ITEM.getKey(item));
    }

    public static boolean leftOut(ResourceLocation itemId) {
        return LEFT_OUT_MODS.contains(itemId.getNamespace());
    }

    /** A recipe whose output sells for more than its ingredients cost to buy, at normal prices (factor 1.0). */
    public record CraftProfit(Item output, ResourceLocation recipe, BigDecimal sellValue, BigDecimal buyCost, ProfitKind kind) {
    }

    /** What a {@link CraftProfit} most likely means, which decides how to deal with it. */
    public enum ProfitKind {
        /** The prices are wrong: raise the ingredients', lower the output's, or stop the market buying it. */
        PRICE_GAP,
        /** The recipe type may take fluids, extra items or ingredient amounts the check can't see: check it by hand. */
        MAY_HIDE_INPUTS,
        /** The output is one of its own ingredients (1 cobblestone makes 2): no price stops it; sell impact limits it. */
        MULTIPLIES_INPUT
    }

    /**
     * Recipes that turn market purchases into a profit: every ingredient can be bought, the output can be sold, and
     * one craft's output sells for more than its ingredients cost (less anything the recipe gives back, valued at what
     * the market pays for it). Fuel is ignored, so the check errs towards reporting. Pure apart from the registry.
     */
    public static List<CraftProfit> craftProfits(Collection<RecipeHolder<?>> recipes, HolderLookup.Provider registries,
                                                 Map<Item, CatalogEntry> catalog) {
        List<Path> paths = new ArrayList<>();
        for (RecipeHolder<?> holder : recipes) {
            toPath(holder, registries, new HashMap<>()).ifPresent(paths::add);
        }
        return craftProfits(paths, catalog);
    }

    public static List<CraftProfit> craftProfits(List<Path> paths, Map<Item, CatalogEntry> catalog) {
        List<CraftProfit> profits = new ArrayList<>();
        for (Path path : paths) {
            CatalogEntry output = catalog.get(path.output());
            if (output == null || !output.canSell()) {
                continue;
            }
            BigDecimal cost = BigDecimal.ZERO;
            boolean buyable = true;
            boolean multiplies = false;
            for (Slot slot : path.slots()) {
                multiplies |= slot.options().contains(path.output());
                BigDecimal cheapest = null;
                Item cheapestItem = null;
                for (Item option : slot.options()) {
                    CatalogEntry entry = catalog.get(option);
                    if (entry != null && entry.canBuy()) {
                        BigDecimal buy = PriceModel.exactUnitBuyPrice(PriceModel.inputs(entry), 1.0);
                        if (cheapest == null || buy.compareTo(cheapest) < 0) {
                            cheapest = buy;
                            cheapestItem = option;
                        }
                    }
                }
                if (cheapest == null) {
                    buyable = false;
                    break;
                }
                Item remainder = remainder(cheapestItem);
                CatalogEntry back = remainder == null ? null : catalog.get(remainder);
                if (back != null && back.canSell()) {
                    cheapest = cheapest.subtract(PriceModel.exactUnitSellPrice(PriceModel.inputs(back), 1.0));
                }
                cost = cost.add(cheapest.multiply(BigDecimal.valueOf(slot.amount())));
            }
            if (!buyable) {
                continue;
            }
            BigDecimal sell = PriceModel.exactUnitSellPrice(PriceModel.inputs(output), 1.0).multiply(BigDecimal.valueOf(path.count()));
            if (sell.compareTo(cost) > 0) {
                ProfitKind kind = multiplies ? ProfitKind.MULTIPLIES_INPUT : path.complete() ? ProfitKind.PRICE_GAP : ProfitKind.MAY_HIDE_INPUTS;
                profits.add(new CraftProfit(path.output(), path.recipe(), sell, cost, kind));
            }
        }
        return profits;
    }

    private static Item remainder(Item item) {
        ItemStack left = item.getCraftingRemainingItem(new ItemStack(item));
        return left.isEmpty() ? null : left.getItem();
    }

    /** The recipe in the suggester's terms, or empty (counted in {@code unusable}) if it can't price anything. */
    private static Optional<Path> toPath(RecipeHolder<?> holder, HolderLookup.Provider registries, Map<String, Integer> unusable) {
        Recipe<?> recipe = holder.value();
        String type = String.valueOf(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()));
        try {
            // Not isSpecial(): mods such as Create mark their machine recipes special to keep them out of the recipe book.
            if (recipe instanceof CustomRecipe) {
                unusable.merge("special recipes (map cloning, dyeing, ...)", 1, Integer::sum);
                return Optional.empty();
            }
            ItemStack out = recipe.getResultItem(registries);
            Optional<CreateRecipes.Output> create = CreateRecipes.read(recipe);
            if (create.isPresent() && create.get().expected() <= 0) {
                unusable.merge(type + ": no item output listed", 1, Integer::sum);
                return Optional.empty();
            }
            if (out.isEmpty() && create.isEmpty()) {
                unusable.merge(type + ": no item output listed", 1, Integer::sum);
                return Optional.empty();
            }
            List<Slot> slots = new ArrayList<>();
            if (recipe instanceof SmithingTransformRecipe smithing) {
                // Smithing recipes don't list their ingredients; find the items each part accepts.
                slots.add(new Slot(fitting(smithing::isTemplateIngredient), 1));
                slots.add(new Slot(fitting(smithing::isBaseIngredient), 1));
                slots.add(new Slot(fitting(smithing::isAdditionIngredient), 1));
            } else {
                for (Ingredient ingredient : recipe.getIngredients()) {
                    if (ingredient.isEmpty()) {
                        continue;
                    }
                    ItemStack[] stacks = ingredient.getItems();
                    List<Item> options = new ArrayList<>();
                    int amount = 1;
                    for (ItemStack stack : stacks) {
                        if (!stack.isEmpty() && !options.contains(stack.getItem())) {
                            options.add(stack.getItem());
                            amount = Math.max(1, stack.getCount());
                        }
                    }
                    if (!options.isEmpty()) {
                        slots.add(new Slot(List.copyOf(options), amount));
                    }
                }
            }
            if (slots.isEmpty()) {
                // A recipe with no listed inputs would make its output free.
                unusable.merge(type + ": no item inputs listed", 1, Integer::sum);
                return Optional.empty();
            }
            // Furnaces burn fuel; campfires don't.
            boolean fuel = recipe instanceof AbstractCookingRecipe && !type.equals("minecraft:campfire_cooking");
            if (create.isPresent()) {
                CreateRecipes.Output o = create.get();
                return Optional.of(new Path(holder.id(), type, o.item(), o.expected(), slots, fuel,
                        COMPLETE_TYPES.contains(type) && !o.fluids()));
            }
            return Optional.of(new Path(holder.id(), type, out.getItem(), out.getCount(), slots, fuel));
        } catch (RuntimeException e) {
            unusable.merge(type + ": couldn't be read", 1, Integer::sum);
            return Optional.empty();
        }
    }

    /** Every item a smithing slot accepts. Scanning the registry is fine for the few dozen smithing recipes a pack has. */
    private static List<Item> fitting(java.util.function.Predicate<ItemStack> accepts) {
        List<Item> items = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item != Items.AIR && accepts.test(new ItemStack(item))) {
                items.add(item);
            }
        }
        return items;
    }
}
