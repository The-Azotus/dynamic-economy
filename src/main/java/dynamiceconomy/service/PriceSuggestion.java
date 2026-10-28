package dynamiceconomy.service;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.compat.CreateRecipes;
import dynamiceconomy.compat.MachineRecipes;
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
 * less than itself would fall forever, so passes are capped and such items reported. Recipes whose type may hide inputs
 * (see {@link Path#complete}) only price items no complete recipe can, so a recipe that leaves its main ingredient out
 * can't undercut one that lists it.
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
    /** A made item is rare if at least this share of its cost is rare material (or a whole unit of the cheapest rare item). */
    private static final BigDecimal RARE_SHARE = new BigDecimal("0.5");
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

    /** {@code amount} is how many a run uses on average; it is fractional when a mod uses an input up only sometimes. */
    public record Slot(List<Item> options, double amount) {
    }

    /**
     * A suggested price for an item the catalog doesn't list.
     *
     * @param cost        the exact derived value in spurs, before rounding
     * @param basePrice   {@code cost} rounded down; empty if it's under 1 spur
     * @param itemClass   {@code rare_processed} if its rare inputs are worth at least half its cost or one unit of the
     *                    cheapest rare item in the catalog, else {@code common_processed}
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
        double premiumUsed = premium(catalog.values());
        Propagation state = new Propagation(catalog, BigDecimal.ONE.add(BigDecimal.valueOf(premiumUsed)));
        // Complete recipes first; then the rest, only for items those couldn't price.
        Set<Item> looping = new HashSet<>(state.run(paths.stream().filter(Path::complete).toList()));
        state.frozen.addAll(state.price.keySet());
        looping.addAll(state.run(paths));
        Map<Item, BigDecimal> price = state.price;
        Map<Item, Path> chosen = state.chosen;

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
            suggestions.put(item, new Suggestion(item, cost, base, state.itemClass.get(item), path.recipe(), path.type(), state.depth.get(item),
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
        return new Result(suggestions, noRecipe, unpricedInputs, unusable, Set.copyOf(looping), premiumUsed);
    }

    /**
     * Prices spreading from the seeds through recipes. {@code rare} is how much of an item's cost is rare material:
     * a rare seed's whole price, and for a made item its inputs' rare parts, so a trace of rare material deep in a long
     * chain doesn't make a cheap item rare. Items in {@code frozen} keep the price they have.
     * <p>
     * An item is never repriced from ingredients that were themselves priced from it ({@code madeFrom}), so a pair of
     * recipes that turns one plank into a door and the door into two planks can't drive both prices towards zero: the
     * chosen recipes always form a chain back to the seeds.
     */
    private static final class Propagation {
        final Map<Item, BigDecimal> price = new HashMap<>();
        final Map<Item, BigDecimal> rare = new HashMap<>();
        final Map<Item, String> itemClass = new HashMap<>();
        final Map<Item, Path> chosen = new HashMap<>();
        final Map<Item, Integer> depth = new HashMap<>();
        /** The ingredients each item's price came from. */
        final Map<Item, List<Item>> madeFrom = new HashMap<>();
        final Set<Item> frozen = new HashSet<>();
        final BigDecimal premium;
        /** The cheapest rare item in the catalog; a made item holding this much rare material is rare. */
        final BigDecimal oneRareUnit;

        Propagation(Map<Item, CatalogEntry> catalog, BigDecimal premium) {
            this.premium = premium;
            BigDecimal cheapestRare = null;
            for (Map.Entry<Item, CatalogEntry> entry : catalog.entrySet()) {
                BigDecimal p = BigDecimal.valueOf(entry.getValue().basePrice());
                String c = entry.getValue().itemClass().map(ResourceLocation::getPath).orElse("");
                price.put(entry.getKey(), p);
                itemClass.put(entry.getKey(), c);
                boolean isRare = RARE_CLASSES.contains(c);
                rare.put(entry.getKey(), isRare ? p : BigDecimal.ZERO);
                if (isRare && (cheapestRare == null || p.compareTo(cheapestRare) < 0)) {
                    cheapestRare = p;
                }
                frozen.add(entry.getKey()); // Listed items keep their catalog price.
            }
            oneRareUnit = cheapestRare;
        }

        /** Applies {@code paths} until nothing gets cheaper; returns the items still changing when the passes ran out. */
        Set<Item> run(List<Path> paths) {
            Set<Item> changed = new HashSet<>();
            for (int pass = 0; pass < MAX_PASSES && (pass == 0 || !changed.isEmpty()); pass++) {
                changed.clear();
                for (Path path : paths) {
                    if (frozen.contains(path.output())) {
                        continue;
                    }
                    BigDecimal sum = BigDecimal.ZERO;
                    BigDecimal rareSum = BigDecimal.ZERO;
                    int deepest = 0;
                    boolean priced = true;
                    List<Item> used = new ArrayList<>();
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
                        used.add(cheapest);
                        BigDecimal slotCost = price.get(cheapest);
                        BigDecimal slotRare = rare.get(cheapest);
                        Item remainder = remainder(cheapest);
                        if (remainder != null && price.containsKey(remainder)) {
                            slotCost = slotCost.subtract(price.get(remainder)).max(BigDecimal.ZERO);
                            slotRare = slotRare.subtract(rare.get(remainder)).max(BigDecimal.ZERO).min(slotCost);
                        }
                        BigDecimal amount = BigDecimal.valueOf(slot.amount());
                        sum = sum.add(slotCost.multiply(amount));
                        rareSum = rareSum.add(slotRare.multiply(amount));
                        deepest = Math.max(deepest, depth.getOrDefault(cheapest, 0));
                    }
                    if (!priced) {
                        continue;
                    }
                    if (path.burnsFuel() && price.containsKey(Items.COAL)) {
                        sum = sum.add(price.get(Items.COAL).divide(BigDecimal.valueOf(ITEMS_PER_COAL), MATH));
                    }
                    BigDecimal count = BigDecimal.valueOf(path.count());
                    BigDecimal cost = sum.multiply(premium).divide(count, MATH);
                    BigDecimal current = price.get(path.output());
                    if (current == null || cost.compareTo(current) < 0 && !reaches(used, path.output())) {
                        BigDecimal rareCost = rareSum.multiply(premium).divide(count, MATH);
                        boolean isRare = rareCost.signum() > 0 && (rareCost.compareTo(cost.multiply(RARE_SHARE)) >= 0
                                || oneRareUnit != null && rareCost.compareTo(oneRareUnit) >= 0);
                        price.put(path.output(), cost);
                        rare.put(path.output(), rareCost);
                        chosen.put(path.output(), path);
                        madeFrom.put(path.output(), used);
                        depth.put(path.output(), deepest + 1);
                        itemClass.put(path.output(), isRare ? "rare_processed" : "common_processed");
                        changed.add(path.output());
                    }
                }
            }
            if (!changed.isEmpty()) {
                DynamicEconomy.LOGGER.warn("Price suggestions still changing after {} passes; a recipe loop makes something out of less", MAX_PASSES);
            }
            return Set.copyOf(changed);
        }

        /** Whether {@code target} is among {@code items} or the ingredients any of them was priced from, however deep. */
        boolean reaches(List<Item> items, Item target) {
            Set<Item> seen = new HashSet<>();
            java.util.ArrayDeque<Item> open = new java.util.ArrayDeque<>(items);
            while (!open.isEmpty()) {
                Item item = open.pop();
                if (item == target) {
                    return true;
                }
                if (seen.add(item)) {
                    open.addAll(madeFrom.getOrDefault(item, List.of()));
                }
            }
            return false;
        }
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
        return craftProfits(paths(recipes, registries), catalog);
    }

    /** Every recipe the suggester can read, in its terms. */
    public static List<Path> paths(Collection<RecipeHolder<?>> recipes, HolderLookup.Provider registries) {
        List<Path> paths = new ArrayList<>();
        for (RecipeHolder<?> holder : recipes) {
            toPath(holder, registries, new HashMap<>()).ifPresent(paths::add);
        }
        return paths;
    }

    /**
     * An item the market sells for less than it can be turned into: buying it, running {@code steps} and selling the
     * last step's output (buying any other ingredients on the way) earns {@code value} per unit bought.
     *
     * @param complete false if a step is a recipe type that may hide inputs, so the profit may not be real
     */
    public record ChainProfit(Item item, BigDecimal buyCost, BigDecimal value, List<Path> steps, boolean complete) {
    }

    /**
     * Profits that take several recipes to reach, which {@link #craftProfits} can't see because it checks one recipe
     * between listed items. For every item it finds the most one unit can be turned into: its own sell price, or for
     * each recipe it goes into, what the output is worth less the other ingredients' buy prices, per unit used. A seed
     * priced below what it processes into (a cheap stone that crushes into ore) shows up here, with the chain.
     * Recipes that make more of their own ingredient are left out (they're generators, reported by {@link #craftProfits}),
     * as is any step that would lead back to the item, so values settle. Fuel and anything a recipe gives back are
     * ignored. {@code market} is everything the market would list, with suggested items included.
     */
    public static List<ChainProfit> chainProfits(List<Path> allPaths, Map<Item, CatalogEntry> market) {
        List<Path> paths = allPaths.stream()
                .filter(path -> path.slots().stream().noneMatch(slot -> slot.options().contains(path.output()))).toList();
        Map<Item, BigDecimal> buy = new HashMap<>();
        Map<Item, BigDecimal> value = new HashMap<>();
        market.forEach((item, entry) -> {
            PriceModel.Inputs in = PriceModel.inputs(entry);
            if (entry.canBuy()) {
                buy.put(item, PriceModel.exactUnitBuyPrice(in, 1.0));
            }
            if (entry.canSell()) {
                value.put(item, PriceModel.exactUnitSellPrice(in, 1.0));
            }
        });
        Map<Item, Path> next = new HashMap<>();
        boolean changed = true;
        for (int pass = 0; pass < MAX_PASSES && changed; pass++) {
            changed = false;
            for (Path path : paths) {
                BigDecimal out = value.get(path.output());
                if (out == null) {
                    continue;
                }
                // The cheapest way to buy each slot, for when another slot is the item being valued.
                List<Slot> slots = path.slots();
                BigDecimal[] slotBuy = new BigDecimal[slots.size()];
                int unbuyable = 0;
                BigDecimal allOthers = BigDecimal.ZERO;
                for (int i = 0; i < slots.size(); i++) {
                    for (Item option : slots.get(i).options()) {
                        BigDecimal b = buy.get(option);
                        if (b != null && (slotBuy[i] == null || b.compareTo(slotBuy[i]) < 0)) {
                            slotBuy[i] = b;
                        }
                    }
                    if (slotBuy[i] == null) {
                        unbuyable++;
                    } else {
                        allOthers = allOthers.add(slotBuy[i].multiply(BigDecimal.valueOf(slots.get(i).amount())));
                    }
                }
                BigDecimal total = out.multiply(BigDecimal.valueOf(path.count()));
                for (int k = 0; k < slots.size(); k++) {
                    // Every other slot has to be bought, so at most this one can be unbuyable.
                    if (unbuyable > 1 || unbuyable == 1 && slotBuy[k] != null) {
                        continue;
                    }
                    BigDecimal others = slotBuy[k] == null ? allOthers
                            : allOthers.subtract(slotBuy[k].multiply(BigDecimal.valueOf(slots.get(k).amount())));
                    BigDecimal perUnit = total.subtract(others).divide(BigDecimal.valueOf(slots.get(k).amount()), MATH);
                    for (Item option : slots.get(k).options()) {
                        BigDecimal current = value.get(option);
                        if ((current == null || perUnit.compareTo(current) > 0) && !leadsTo(next, path.output(), option)) {
                            value.put(option, perUnit);
                            next.put(option, path);
                            changed = true;
                        }
                    }
                }
            }
        }

        List<ChainProfit> profits = new ArrayList<>();
        for (Map.Entry<Item, BigDecimal> entry : buy.entrySet()) {
            Item item = entry.getKey();
            BigDecimal worth = value.get(item);
            if (worth == null || !next.containsKey(item) || worth.compareTo(entry.getValue()) <= 0) {
                continue;
            }
            List<Path> steps = new ArrayList<>();
            boolean complete = true;
            for (Item at = item; next.containsKey(at) && steps.size() < MAX_PASSES; at = next.get(at).output()) {
                steps.add(next.get(at));
                complete &= next.get(at).complete();
            }
            profits.add(new ChainProfit(item, entry.getValue(), worth, steps, complete));
        }
        profits.sort(java.util.Comparator.comparing((ChainProfit p) -> !p.complete())
                .thenComparing(p -> p.value().divide(p.buyCost().max(new BigDecimal("0.01")), MATH), java.util.Comparator.reverseOrder()));
        return profits;
    }

    /** Whether following {@code next} from {@code from} reaches {@code target}. */
    private static boolean leadsTo(Map<Item, Path> next, Item from, Item target) {
        Set<Item> seen = new HashSet<>();
        for (Item at = from; at != null && seen.add(at); at = next.containsKey(at) ? next.get(at).output() : null) {
            if (at == target) {
                return true;
            }
        }
        return false;
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
            Optional<MachineRecipes.Read> machine = MachineRecipes.read(recipe);
            if (machine.isPresent()) {
                return machinePath(holder, type, machine.get(), unusable);
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

    /**
     * A machine recipe read by {@link MachineRecipes}, with the inputs and amounts its generic data leaves out. Machines
     * run on energy, which isn't priced, so a recipe counts as complete unless it uses up fluids or chemicals.
     */
    private static Optional<Path> machinePath(RecipeHolder<?> holder, String type, MachineRecipes.Read read,
                                              Map<String, Integer> unusable) {
        if (read.item() == null || read.expected() <= 0) {
            unusable.merge(type + ": no item output listed", 1, Integer::sum);
            return Optional.empty();
        }
        if (read.inputs().isEmpty()) {
            unusable.merge(type + ": no item inputs listed", 1, Integer::sum);
            return Optional.empty();
        }
        List<Slot> slots = read.inputs().stream().map(input -> new Slot(input.options(), input.amount())).toList();
        return Optional.of(new Path(holder.id(), type, read.item(), read.expected(), slots, false, !read.fluids()));
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
