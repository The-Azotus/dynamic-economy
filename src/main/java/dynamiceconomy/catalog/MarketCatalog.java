package dynamiceconomy.catalog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.coin.Coins;
import dynamiceconomy.data.MarketOverrides;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/**
 * Item listings loaded from {@code data/<namespace>/market/*.json}. Each file holds an {@code entries} array and may
 * hold a {@code defaults} object whose fields apply to every entry in that file unless the entry sets them itself.
 * <p>
 * A bad entry is logged and skipped without discarding the rest of its file; entries with {@code base_price} ≤ 0
 * (unfilled templates) are skipped with one summary warning. If several files list the same item, they're applied in
 * priority order (datapack order; within one pack, by file ID), and each changes only the fields it sets, unless the
 * entry has {@code "replace": true}, which starts the entry over. The loader also records where each field of each
 * entry came from, and keeps its problems for {@code /market admin diagnose}.
 * <p>
 * The same pass reads the item classes ({@link ItemClasses}) from {@code data/<namespace>/market_classes/*.json}, and
 * uses them before decoding any entry. Both kinds of file are also read from {@link #configFolder()}, which applies to
 * every world and sits just above the built-in packs, below every other pack (see {@link #configOrder}).
 */
public final class MarketCatalog extends SimplePreparableReloadListener<MarketCatalog.Prepared> {
    public static final MarketCatalog INSTANCE = new MarketCatalog();
    private static final FileToIdConverter FILES = FileToIdConverter.json("market");
    private static final FileToIdConverter CLASS_FILES = FileToIdConverter.json("market_classes");

    /** One market file as read from a pack. Higher {@code packOrder} means higher priority. */
    public record SourceFile(ResourceLocation id, int packOrder, String packId, JsonElement json) {
    }

    /** The files read off the main thread, and the ones that couldn't be read. Class files are keyed by class ID. */
    public record Prepared(List<SourceFile> files, Map<ResourceLocation, JsonElement> classFiles, List<Problem> unreadable) {
    }

    /** Something the loader noticed. {@code skipped} means an entry or file was left out; otherwise it's a warning. */
    public record Problem(boolean skipped, String message) {
    }

    /**
     * What a set of files loads to.
     *
     * @param sources  where each entry's fields came from (see {@link FieldSource})
     * @param unpriced entries skipped because their {@code base_price} is 0 or below (unfilled templates)
     */
    public record Loaded(Map<Item, CatalogEntry> entries, Map<Item, Map<String, List<FieldSource>>> sources,
                         List<Problem> problems, int unpriced) {
    }

    /** What the catalog files say, before admin edits. */
    private volatile Loaded files = new Loaded(Map.of(), Map.of(), List.of(), 0);
    /** The live catalog: file entries with admin edits applied. */
    private volatile Map<Item, CatalogEntry> entries = Map.of();
    private volatile Map<Item, Map<String, List<FieldSource>>> sources = Map.of();
    private volatile MarketOverrides.Snapshot overrides = MarketOverrides.Snapshot.EMPTY;
    private volatile Map<ResourceLocation, String> problems = Map.of();

    private MarketCatalog() {
    }

    public Optional<CatalogEntry> get(Item item) {
        return Optional.ofNullable(entries.get(item));
    }

    public Collection<CatalogEntry> entries() {
        return entries.values();
    }

    /** The item's entry as the catalog files have it, ignoring admin edits. */
    public Optional<CatalogEntry> fileEntry(Item item) {
        return Optional.ofNullable(files.entries().get(item));
    }

    /** The catalog files as last loaded, before admin edits, with their sources and problems. */
    public Loaded files() {
        return files;
    }

    /** Where each field of a listed item's live entry came from; empty if it isn't listed. */
    public Map<String, List<FieldSource>> sources(Item item) {
        return sources.getOrDefault(item, FieldSource.NONE);
    }

    /** Admin edits that couldn't be applied at the last rebuild, by item. */
    public Map<ResourceLocation, String> problems() {
        return problems;
    }

    /**
     * Uses a new set of admin edits and rebuilds the live catalog. Call on the server thread; tags must be loaded.
     * Pass {@link MarketOverrides.Snapshot#EMPTY} when no world is running.
     */
    public Map<ResourceLocation, String> setOverrides(MarketOverrides.Snapshot snapshot) {
        overrides = snapshot;
        return rebuild();
    }

    /** Re-applies the current admin edits to the file entries, e.g. after tags changed. */
    public Map<ResourceLocation, String> rebuild() {
        return rebuild(true);
    }

    private Map<ResourceLocation, String> rebuild(boolean report) {
        CatalogOverlay.Result result = CatalogOverlay.apply(files.entries(), files.sources(), overrides, CatalogOverlay::registryTag);
        entries = result.entries();
        sources = result.sources();
        problems = result.problems();
        if (report) {
            result.problems().values().forEach(problem -> DynamicEconomy.LOGGER.warn("Market admin edit not applied: {}", problem));
        }
        return result.problems();
    }

    /** Re-reads only the market files from the server's current resource manager. */
    public int reloadNow(ResourceManager resourceManager) {
        apply(prepare(resourceManager, InactiveProfiler.INSTANCE), resourceManager, InactiveProfiler.INSTANCE);
        return entries.size();
    }

    /**
     * Swaps the live catalog and returns the previous one so a test can put it back. GameTests only: they run on
     * the server thread within a single tick, so no trade can see the swapped catalog.
     */
    public Map<Item, CatalogEntry> replaceForTesting(Map<Item, CatalogEntry> replacement) {
        Map<Item, CatalogEntry> previous = entries;
        entries = Collections.unmodifiableMap(new LinkedHashMap<>(replacement));
        return previous;
    }

    @Override
    protected Prepared prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        // Pack positions are doubled so the config folder can sit between two packs (see configOrder).
        Map<String, Integer> packOrder = new HashMap<>();
        List<String> packIds = resourceManager.listPacks().map(PackResources::packId).toList();
        for (int i = 0; i < packIds.size(); i++) {
            packOrder.put(packIds.get(i), 2 * i);
        }
        int configOrder = configOrder(packIds);
        List<SourceFile> found = new ArrayList<>();
        List<Problem> unreadable = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Resource> file : FILES.listMatchingResources(resourceManager).entrySet()) {
            ResourceLocation id = FILES.fileToId(file.getKey());
            Resource resource = file.getValue();
            try (Reader reader = resource.openAsReader()) {
                found.add(new SourceFile(id, packOrder.getOrDefault(resource.sourcePackId(), -1), resource.sourcePackId(),
                        JsonParser.parseReader(reader)));
            } catch (Exception e) {
                DynamicEconomy.LOGGER.error("Market file {} in pack {} could not be read; skipped", id, resource.sourcePackId(), e);
                unreadable.add(new Problem(true, "Market file %s in pack %s could not be read: %s".formatted(id, resource.sourcePackId(),
                        e.getMessage())));
            }
        }
        // Only the highest-priority pack's copy of each class file is listed, so a pack replaces a whole class.
        Map<ResourceLocation, JsonElement> classFiles = new LinkedHashMap<>();
        Map<ResourceLocation, Integer> classOrder = new HashMap<>();
        for (Map.Entry<ResourceLocation, Resource> file : CLASS_FILES.listMatchingResources(resourceManager).entrySet()) {
            ResourceLocation id = CLASS_FILES.fileToId(file.getKey());
            try (Reader reader = file.getValue().openAsReader()) {
                classFiles.put(id, JsonParser.parseReader(reader));
                classOrder.put(id, packOrder.getOrDefault(file.getValue().sourcePackId(), -1));
            } catch (Exception e) {
                DynamicEconomy.LOGGER.error("Market class {} could not be read; skipped", id, e);
                unreadable.add(new Problem(true, "Market class %s could not be read: %s".formatted(id, e.getMessage())));
            }
        }

        ConfigFolder config = readConfigFolder(configFolder(), configOrder);
        found.addAll(config.files());
        unreadable.addAll(config.problems());
        // A config class replaces a class from a pack below the folder, but not one from a world datapack.
        config.classFiles().forEach((id, json) -> {
            if (!classOrder.containsKey(id) || classOrder.get(id) < configOrder) {
                classFiles.put(id, json);
            }
        });
        return new Prepared(found, classFiles, unreadable);
    }

    /**
     * Packs that are always there and hold built-in data: the config folder never goes below them. Each mod's own
     * data is also a pack, {@code mod/<modid>} (listed after {@code mod_data}); see {@link #isBuiltIn}.
     */
    private static final Set<String> BUILT_IN_PACKS = Set.of("vanilla", "mod_data", "mod_resources",
            DynamicEconomy.DEFAULT_PRICES_PACK_ID);

    /**
     * Whether a pack holds built-in data: one of {@link #BUILT_IN_PACKS}, or a mod's own data ({@code mod/<modid>},
     * where the built-in classes are). A mod's optional packs ({@code mod/<modid>:<name>}, such as the example
     * catalog) aren't built in.
     */
    private static boolean isBuiltIn(String packId) {
        return BUILT_IN_PACKS.contains(packId) || (packId.startsWith("mod/") && packId.indexOf(':') < 0);
    }

    /**
     * Where the config folder goes among packs listed lowest priority first, with pack {@code i} at {@code 2 * i}: just
     * above the highest built-in pack ({@link #isBuiltIn}: the built-in prices and the mods' own data, where the
     * built-in classes are). Every other pack is above it, whichever mod adds it (world datapacks, the example
     * catalog, global-pack mods), so its place never depends on which other packs a world has.
     */
    public static int configOrder(List<String> packIds) {
        int highest = -1;
        for (int i = 0; i < packIds.size(); i++) {
            if (isBuiltIn(packIds.get(i))) {
                highest = i;
            }
        }
        return 2 * highest + 1;
    }

    /** {@code config/dynamic_economy/catalog/}: catalog files that apply to every world. */
    public static Path configFolder() {
        return FMLPaths.CONFIGDIR.get().resolve(DynamicEconomy.MOD_ID).resolve("catalog");
    }

    /** What the config folder holds, as {@link #readConfigFolder} found it. */
    public record ConfigFolder(List<SourceFile> files, Map<ResourceLocation, JsonElement> classFiles, List<Problem> problems) {
    }

    /**
     * Reads {@code market/} and {@code market_classes/} under {@code root}, creating both if missing so admins can find
     * them. Market files get the ID {@code config:<path>} and priority {@code order}; class files get
     * {@code dynamic_economy:<path>}, so a file named after a built-in class replaces it.
     */
    public static ConfigFolder readConfigFolder(Path root, int order) {
        List<SourceFile> files = new ArrayList<>();
        Map<ResourceLocation, JsonElement> classFiles = new LinkedHashMap<>();
        List<Problem> problems = new ArrayList<>();
        readFolder(root.resolve("market"), CONFIG_NAMESPACE, problems,
                (id, json) -> files.add(new SourceFile(id, order, CONFIG_PACK_ID, json)));
        readFolder(root.resolve("market_classes"), DynamicEconomy.MOD_ID, problems, classFiles::put);
        return new ConfigFolder(files, classFiles, problems);
    }

    /** The pack name config-folder files are reported under. */
    public static final String CONFIG_PACK_ID = "config/" + DynamicEconomy.MOD_ID + "/catalog";
    /** The namespace of the config folder's market files: {@code market/prices.json} loads as {@code config:prices}. */
    public static final String CONFIG_NAMESPACE = "config";

    private static void readFolder(Path folder, String namespace, List<Problem> problems,
                                   BiConsumer<ResourceLocation, JsonElement> found) {
        List<Path> paths;
        try {
            Files.createDirectories(folder);
            try (Stream<Path> walk = Files.walk(folder)) {
                paths = walk.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                        .sorted().toList();
            }
        } catch (IOException e) {
            DynamicEconomy.LOGGER.error("The catalog folder {} could not be read; skipped", folder, e);
            problems.add(new Problem(true, "The catalog folder %s could not be read: %s".formatted(folder, e.getMessage())));
            return;
        }
        for (Path path : paths) {
            String relative = folder.relativize(path).toString().replace('\\', '/');
            String name = relative.substring(0, relative.length() - ".json".length());
            ResourceLocation id = ResourceLocation.tryBuild(namespace, name);
            if (id == null) {
                skipped(problems, "Catalog file %s in %s: file names may use only lowercase letters, digits, _ - . and /; skipped"
                        .formatted(relative, CONFIG_PACK_ID));
                continue;
            }
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                found.accept(id, JsonParser.parseReader(reader));
            } catch (Exception e) {
                DynamicEconomy.LOGGER.error("Catalog file {} in {} could not be read; skipped", relative, CONFIG_PACK_ID, e);
                problems.add(new Problem(true, "Catalog file %s in %s could not be read: %s".formatted(relative, CONFIG_PACK_ID,
                        e.getMessage())));
            }
        }
    }

    @Override
    protected void apply(Prepared prepared, ResourceManager resourceManager, ProfilerFiller profiler) {
        ItemClasses.Loaded classes = ItemClasses.parse(prepared.classFiles());
        ItemClasses.use(classes);
        Loaded loaded = load(prepared.files());
        List<Problem> allProblems = new ArrayList<>(prepared.unreadable());
        allProblems.addAll(classes.problems());
        allProblems.addAll(loaded.problems());
        files = new Loaded(loaded.entries(), loaded.sources(), List.copyOf(allProblems), loaded.unpriced());
        // Tags are bound only after every reload listener has run, so tag rules here may still see the old tags.
        // Build quietly; DynamicEconomy.catalogReloaded rebuilds and reports once tags are current.
        rebuild(false);
        DynamicEconomy.LOGGER.info("Loaded {} market catalog entries from {} files, and {} item classes", entries.size(),
                prepared.files().size(), classes.classes().size());
    }

    /** Parses files that all come from one pack, keyed by file ID. Pure: logs problems but changes no state. */
    public static Map<Item, CatalogEntry> parse(Map<ResourceLocation, JsonElement> files) {
        List<SourceFile> list = new ArrayList<>();
        files.forEach((id, json) -> list.add(new SourceFile(id, 0, "test", json)));
        return parse(list);
    }

    /** Turns market files into listings, lowest priority first so higher packs override. Pure apart from logging. */
    public static Map<Item, CatalogEntry> parse(List<SourceFile> files) {
        return load(files).entries();
    }

    /** As {@link #parse(List)}, also returning where each field came from and what went wrong. */
    public static Loaded load(List<SourceFile> files) {
        List<SourceFile> ordered = new ArrayList<>(files);
        ordered.sort(Comparator.comparingInt(SourceFile::packOrder).thenComparing(SourceFile::id));
        Map<Item, CatalogEntry> loaded = new LinkedHashMap<>();
        Map<Item, Map<String, List<FieldSource>>> sources = new LinkedHashMap<>();
        Map<Item, SourceFile> origin = new HashMap<>();
        List<Problem> problems = new ArrayList<>();
        int unpriced = 0;
        for (SourceFile file : ordered) {
            unpriced += loadFile(file, loaded, sources, origin, problems);
        }
        if (unpriced > 0) {
            DynamicEconomy.LOGGER.warn("{} unpriced market entries (base_price 0 or below) skipped", unpriced);
        }
        return new Loaded(Collections.unmodifiableMap(loaded), Collections.unmodifiableMap(sources), List.copyOf(problems), unpriced);
    }

    /**
     * Decodes one entry, already merged with its file's defaults, using exactly the loader's rules. The importer uses
     * this too, so a CSV row is accepted only if the same entry would load from a catalog file. If the entry names a
     * class, the class fills every field the entry doesn't set.
     */
    public static DataResult<CatalogEntry> decode(JsonObject entry) {
        if (!entry.has("class")) {
            return decodeWithoutClass(entry, Optional.empty());
        }
        DataResult<ItemClasses.ItemClass> itemClass = ItemClasses.lookup(entry.get("class"));
        if (itemClass.error().isPresent()) {
            return DataResult.error(itemClass.error().get().messageSupplier());
        }
        ItemClasses.ItemClass found = itemClass.result().orElseThrow();
        return decodeWithoutClass(CatalogJson.merge(found.values(), entry), Optional.of(found.id()));
    }

    /** Decodes an entry whose class values, if any, are already merged in. */
    static DataResult<CatalogEntry> decodeWithoutClass(JsonObject entry, Optional<ResourceLocation> itemClass) {
        DataResult<CatalogEntry.Raw> raw = CatalogEntry.Raw.CODEC.parse(JsonOps.INSTANCE, entry);
        if (raw.error().isPresent()) {
            return DataResult.error(raw.error().get().messageSupplier());
        }
        CatalogEntry.Raw value = raw.result().orElseThrow();
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(value.item());
        if (item.isEmpty() || item.get() == Items.AIR) {
            return DataResult.error(() -> "unknown item " + value.item());
        }
        if (Coins.isCoin(item.get())) {
            return DataResult.error(() -> value.item() + " is a coin, and coins can never be listed: selling one would create money");
        }
        DataResult<CatalogEntry.ContractTerms> contract = CatalogEntry.ContractTerms.CODEC.codec().parse(JsonOps.INSTANCE, entry)
                .flatMap(CatalogEntry.ContractTerms::validate);
        if (contract.error().isPresent()) {
            return DataResult.error(contract.error().get().messageSupplier());
        }
        DataResult<CatalogEntry> resolved = value.resolve(item.get(), itemClass)
                .map(decoded -> decoded.withContract(contract.result().orElseThrow()));
        // Strict: never accept a partial result, e.g. an out-of-range optional field replaced by its default.
        return resolved.error().isPresent() ? DataResult.error(resolved.error().get().messageSupplier()) : resolved;
    }

    /** Whether an entry's base_price is present but not positive: an unfilled template entry. */
    public static boolean isUnpriced(JsonObject entry) {
        JsonElement price = entry.get("base_price");
        return price != null && price.isJsonPrimitive() && price.getAsJsonPrimitive().isNumber()
                && price.getAsDouble() <= 0;
    }

    /** Loads one file's entries into {@code into}; returns how many were skipped as unpriced. */
    private static int loadFile(SourceFile file, Map<Item, CatalogEntry> into, Map<Item, Map<String, List<FieldSource>>> sources,
                                Map<Item, SourceFile> origin, List<Problem> problems) {
        ResourceLocation fileId = file.id();
        if (!(file.json() instanceof JsonObject obj) || !obj.has("entries") || !obj.get("entries").isJsonArray()) {
            skipped(problems, "Market file %s must be an object with an \"entries\" array; skipped".formatted(fileId));
            return 0;
        }
        if (obj.has("required_mods")) {
            List<String> required = new ArrayList<>();
            if (obj.get("required_mods") instanceof com.google.gson.JsonArray array) {
                for (JsonElement mod : array) {
                    if (mod.isJsonPrimitive() && mod.getAsJsonPrimitive().isString()) {
                        required.add(mod.getAsString());
                    }
                }
            }
            if (!(obj.get("required_mods") instanceof com.google.gson.JsonArray array) || required.size() != array.size()) {
                skipped(problems, "Market file %s: \"required_mods\" must be a list of mod IDs; file skipped".formatted(fileId));
                return 0;
            }
            // A price file for a mod that isn't installed is expected, not a mistake: skip it quietly.
            Optional<String> missing = required.stream().filter(mod -> !ModList.get().isLoaded(mod)).findFirst();
            if (missing.isPresent()) {
                DynamicEconomy.LOGGER.debug("Market file {} skipped: mod {} isn't installed", fileId, missing.get());
                return 0;
            }
        }
        JsonObject defaults = new JsonObject();
        if (obj.has("defaults")) {
            if (!obj.get("defaults").isJsonObject()) {
                // Skipping is safer than guessing: the entries would otherwise trade at unintended prices.
                skipped(problems, "Market file %s: \"defaults\" must be an object; file skipped".formatted(fileId));
                return 0;
            }
            defaults = obj.getAsJsonObject("defaults").deepCopy();
            if (defaults.remove("item") != null) {
                warning(problems, "Market file %s: \"item\" is not allowed in defaults; ignored".formatted(fileId));
            }
        }

        int unpriced = 0;
        int index = 0;
        for (JsonElement element : obj.getAsJsonArray("entries")) {
            int i = index++;
            if (!element.isJsonObject()) {
                skipped(problems, "Market file %s entry %d: must be an object; skipped".formatted(fileId, i));
                continue;
            }
            JsonObject own = element.getAsJsonObject();
            JsonObject merged = defaults.deepCopy();
            own.entrySet().forEach(field -> merged.add(field.getKey(), field.getValue()));
            String where = merged.has("item") ? " (" + merged.get("item") + ")" : "";
            JsonElement replaceField = merged.remove(REPLACE);
            if (replaceField != null && !(replaceField.isJsonPrimitive() && replaceField.getAsJsonPrimitive().isBoolean())) {
                skipped(problems, "Market file %s entry %d%s: \"replace\" must be true or false; skipped".formatted(fileId, i, where));
                continue;
            }
            boolean replace = replaceField != null && replaceField.getAsBoolean();
            if (isUnpriced(merged)) {
                unpriced++;
                continue;
            }

            // An entry for an item a lower file already lists changes only the fields it sets, unless it replaces.
            Optional<Item> target = itemOf(merged);
            Map<String, List<FieldSource>> earlier = replace || target.isEmpty() || !into.containsKey(target.get())
                    ? FieldSource.NONE : sources.get(target.get());
            JsonObject combined = earlier.isEmpty() ? merged
                    : CatalogJson.merge(CatalogJson.explicit(into.get(target.get()).itemId(), earlier), merged);
            DataResult<CatalogEntry> decoded = decode(combined);
            if (decoded.error().isPresent()) {
                String message = decoded.error().get().message();
                String kept = target.isPresent() && into.containsKey(target.get())
                        ? " (the entry from " + origin.get(target.get()).id() + " stays)" + conflictHint(message, merged, combined, earlier)
                        : "";
                skipped(problems, "Market file %s entry %d%s: %s; skipped%s".formatted(fileId, i, where, message, kept));
                continue;
            }
            CatalogEntry entry = decoded.result().orElseThrow();
            Map<String, List<FieldSource>> fields = new LinkedHashMap<>(earlier);
            for (Map.Entry<String, JsonElement> field : merged.entrySet()) {
                if (!field.getKey().equals("item")) {
                    String layer = own.has(field.getKey()) ? "file " + fileId : "file " + fileId + " defaults";
                    List<FieldSource> layers = new ArrayList<>(fields.getOrDefault(field.getKey(), List.of()));
                    layers.add(new FieldSource(layer, field.getValue()));
                    fields.put(field.getKey(), List.copyOf(layers));
                }
            }
            sources.put(entry.item(), Collections.unmodifiableMap(fields));
            CatalogEntry previous = into.put(entry.item(), entry);
            SourceFile previousFile = origin.put(entry.item(), file);
            if (previous != null && previousFile != null) {
                String how = replace ? "replaces" : "changes";
                if (previousFile.packOrder() == file.packOrder()) {
                    warning(problems, "Market file %s %s %s, listed earlier in %s of the same pack"
                            .formatted(fileId, how, entry.itemId(), previousFile.id()));
                } else {
                    DynamicEconomy.LOGGER.debug("Market file {} (pack {}) {} {} from {} (pack {})",
                            fileId, file.packId(), how, entry.itemId(), previousFile.id(), previousFile.packId());
                }
            }
        }
        return unpriced;
    }

    /**
     * The entry field that makes an entry replace a lower file's entry for the same item, instead of changing only the
     * fields it sets. Allowed in a file's {@code defaults}, where it applies to every entry.
     */
    public static final String REPLACE = "replace";

    /** The rules that span two fields, as (field named in the error, the field it's checked against). */
    private static final List<List<String>> PAIRED_FIELDS = List.of(List.of("min_buy_markup", "markup"),
            List.of("contract_min_size", "contract_max_size"));

    /**
     * For a change that failed only because of a value it didn't set: which value that is and how to get past it.
     * Empty when the change fails on its own too (a bad value of its own), since then the hint wouldn't help.
     */
    private static String conflictHint(String error, JsonObject change, JsonObject combined, Map<String, List<FieldSource>> earlier) {
        if (earlier.isEmpty()) {
            return "";
        }
        // base_price is the one required field a change may leave to the lower entry, and no paired rule uses it.
        JsonObject alone = change.deepCopy();
        if (!alone.has("base_price") && combined.has("base_price")) {
            alone.add("base_price", combined.get("base_price").deepCopy());
        }
        if (decode(alone).error().isPresent()) {
            return "";
        }
        for (List<String> pair : PAIRED_FIELDS) {
            if (!error.contains(pair.get(0))) {
                continue;
            }
            // The value to name is the one a lower file set and this change didn't. Failing that, it came from a class
            // (this change's, if it sets one) or a built-in default.
            Optional<String> lowerField = pair.stream()
                    .filter(field -> !change.has(field) && earlier.containsKey(field) && !earlier.get(field).isEmpty()).findFirst();
            String other = lowerField.orElse(change.has(pair.get(0)) ? pair.get(1) : pair.get(0));
            String from = lowerField.map(field -> earlier.get(field).get(earlier.get(field).size() - 1))
                    .map(layer -> layer.value() + " from " + layer.layer())
                    .orElse("from the item's class or the default");
            return ". It conflicts with %s %s: set %s in this entry (or its file's defaults) too, or add \"replace\": true"
                    .formatted(other, from, other);
        }
        return "";
    }

    /** The registered item an entry's {@code item} field names, if it names one. */
    private static Optional<Item> itemOf(JsonObject entry) {
        if (!(entry.get("item") instanceof JsonElement id) || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) {
            return Optional.empty();
        }
        ResourceLocation parsed = ResourceLocation.tryParse(id.getAsString());
        return parsed == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(parsed).filter(item -> item != Items.AIR);
    }

    private static void skipped(List<Problem> problems, String message) {
        DynamicEconomy.LOGGER.warn(message);
        problems.add(new Problem(true, message));
    }

    private static void warning(List<Problem> problems, String message) {
        DynamicEconomy.LOGGER.warn(message);
        problems.add(new Problem(false, message));
    }
}
