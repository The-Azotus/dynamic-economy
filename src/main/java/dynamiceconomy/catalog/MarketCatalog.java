package dynamiceconomy.catalog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dynamiceconomy.DynamicEconomy;
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

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Item listings loaded from {@code data/<namespace>/market/*.json}. Each file holds an {@code entries} array and may
 * hold a {@code defaults} object whose fields apply to every entry in that file unless the entry sets them itself.
 * <p>
 * A bad entry is logged and skipped without discarding the rest of its file; entries with {@code base_price} ≤ 0
 * (unfilled templates) are skipped with one summary warning. If several files list the same item, the file from the
 * higher-priority datapack wins, as with any datapack override; within one pack, the file whose ID sorts last wins.
 * The loader also records where each field of each entry came from, and keeps its problems for
 * {@code /market admin diagnose}.
 * <p>
 * The same pass reads the item classes ({@link ItemClasses}) from {@code data/<namespace>/market_classes/*.json}, and
 * uses them before decoding any entry.
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
        Map<String, Integer> packOrder = new HashMap<>();
        List<PackResources> packs = resourceManager.listPacks().toList();
        for (int i = 0; i < packs.size(); i++) {
            packOrder.put(packs.get(i).packId(), i);
        }
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
        for (Map.Entry<ResourceLocation, Resource> file : CLASS_FILES.listMatchingResources(resourceManager).entrySet()) {
            ResourceLocation id = CLASS_FILES.fileToId(file.getKey());
            try (Reader reader = file.getValue().openAsReader()) {
                classFiles.put(id, JsonParser.parseReader(reader));
            } catch (Exception e) {
                DynamicEconomy.LOGGER.error("Market class {} could not be read; skipped", id, e);
                unreadable.add(new Problem(true, "Market class %s could not be read: %s".formatted(id, e.getMessage())));
            }
        }
        return new Prepared(found, classFiles, unreadable);
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
        DataResult<CatalogEntry> resolved = value.resolve(item.get(), itemClass);
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
            if (isUnpriced(merged)) {
                unpriced++;
                continue;
            }

            DataResult<CatalogEntry> decoded = decode(merged);
            if (decoded.error().isPresent()) {
                String where = merged.has("item") ? " (" + merged.get("item") + ")" : "";
                skipped(problems, "Market file %s entry %d%s: %s; skipped".formatted(fileId, i, where, decoded.error().get().message()));
                continue;
            }
            CatalogEntry entry = decoded.result().orElseThrow();
            Map<String, List<FieldSource>> fields = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> field : merged.entrySet()) {
                if (!field.getKey().equals("item")) {
                    String layer = own.has(field.getKey()) ? "file " + fileId : "file " + fileId + " defaults";
                    fields.put(field.getKey(), List.of(new FieldSource(layer, field.getValue())));
                }
            }
            // A higher-priority file replaces the whole entry, so its sources replace the earlier file's too.
            sources.put(entry.item(), Collections.unmodifiableMap(fields));
            CatalogEntry previous = into.put(entry.item(), entry);
            SourceFile previousFile = origin.put(entry.item(), file);
            if (previous != null && previousFile != null) {
                if (previousFile.packOrder() == file.packOrder()) {
                    warning(problems, "Market file %s overrides %s listed earlier in %s of the same pack"
                            .formatted(fileId, entry.itemId(), previousFile.id()));
                } else {
                    DynamicEconomy.LOGGER.debug("Market file {} (pack {}) overrides {} from {} (pack {})",
                            fileId, file.packId(), entry.itemId(), previousFile.id(), previousFile.packId());
                }
            }
        }
        return unpriced;
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
