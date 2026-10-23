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
 */
public final class MarketCatalog extends SimplePreparableReloadListener<List<MarketCatalog.SourceFile>> {
    public static final MarketCatalog INSTANCE = new MarketCatalog();
    private static final FileToIdConverter FILES = FileToIdConverter.json("market");

    /** One market file as read from a pack. Higher {@code packOrder} means higher priority. */
    public record SourceFile(ResourceLocation id, int packOrder, String packId, JsonElement json) {
    }

    /** What the catalog files say, before admin edits. */
    private volatile Map<Item, CatalogEntry> fileEntries = Map.of();
    /** The live catalog: file entries with admin edits applied. */
    private volatile Map<Item, CatalogEntry> entries = Map.of();
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
        return Optional.ofNullable(fileEntries.get(item));
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
        CatalogOverlay.Result result = CatalogOverlay.apply(fileEntries, overrides, CatalogOverlay::registryTag);
        entries = result.entries();
        problems = result.problems();
        result.problems().values().forEach(problem -> DynamicEconomy.LOGGER.warn("Market admin edit not applied: {}", problem));
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
    protected List<SourceFile> prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        Map<String, Integer> packOrder = new HashMap<>();
        List<PackResources> packs = resourceManager.listPacks().toList();
        for (int i = 0; i < packs.size(); i++) {
            packOrder.put(packs.get(i).packId(), i);
        }
        List<SourceFile> files = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Resource> found : FILES.listMatchingResources(resourceManager).entrySet()) {
            ResourceLocation id = FILES.fileToId(found.getKey());
            Resource resource = found.getValue();
            try (Reader reader = resource.openAsReader()) {
                files.add(new SourceFile(id, packOrder.getOrDefault(resource.sourcePackId(), -1), resource.sourcePackId(),
                        JsonParser.parseReader(reader)));
            } catch (Exception e) {
                DynamicEconomy.LOGGER.error("Market file {} in pack {} could not be read; skipped", id, resource.sourcePackId(), e);
            }
        }
        return files;
    }

    @Override
    protected void apply(List<SourceFile> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        fileEntries = parse(files);
        rebuild();
        DynamicEconomy.LOGGER.info("Loaded {} market catalog entries from {} files", entries.size(), files.size());
    }

    /** Parses files that all come from one pack, keyed by file ID. Pure: logs problems but changes no state. */
    public static Map<Item, CatalogEntry> parse(Map<ResourceLocation, JsonElement> files) {
        List<SourceFile> list = new ArrayList<>();
        files.forEach((id, json) -> list.add(new SourceFile(id, 0, "test", json)));
        return parse(list);
    }

    /** Turns market files into listings, lowest priority first so higher packs override. Pure apart from logging. */
    public static Map<Item, CatalogEntry> parse(List<SourceFile> files) {
        List<SourceFile> ordered = new ArrayList<>(files);
        ordered.sort(Comparator.comparingInt(SourceFile::packOrder).thenComparing(SourceFile::id));
        Map<Item, CatalogEntry> loaded = new LinkedHashMap<>();
        Map<Item, SourceFile> origin = new HashMap<>();
        int unpriced = 0;
        for (SourceFile file : ordered) {
            unpriced += loadFile(file, loaded, origin);
        }
        if (unpriced > 0) {
            DynamicEconomy.LOGGER.warn("{} unpriced market entries (base_price 0 or below) skipped", unpriced);
        }
        return Collections.unmodifiableMap(loaded);
    }

    /**
     * Decodes one entry, already merged with its file's defaults, using exactly the loader's rules. The importer uses
     * this too, so a CSV row is accepted only if the same entry would load from a catalog file.
     */
    public static DataResult<CatalogEntry> decode(JsonObject entry) {
        DataResult<CatalogEntry.Raw> raw = CatalogEntry.Raw.CODEC.parse(JsonOps.INSTANCE, entry);
        if (raw.error().isPresent()) {
            return DataResult.error(raw.error().get().messageSupplier());
        }
        CatalogEntry.Raw value = raw.result().orElseThrow();
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(value.item());
        if (item.isEmpty() || item.get() == Items.AIR) {
            return DataResult.error(() -> "unknown item " + value.item());
        }
        DataResult<CatalogEntry> resolved = value.resolve(item.get());
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
    private static int loadFile(SourceFile file, Map<Item, CatalogEntry> into, Map<Item, SourceFile> origin) {
        ResourceLocation fileId = file.id();
        if (!(file.json() instanceof JsonObject obj) || !obj.has("entries") || !obj.get("entries").isJsonArray()) {
            DynamicEconomy.LOGGER.error("Market file {} must be an object with an \"entries\" array; skipped", fileId);
            return 0;
        }
        JsonObject defaults = new JsonObject();
        if (obj.has("defaults")) {
            if (!obj.get("defaults").isJsonObject()) {
                // Skipping is safer than guessing: the entries would otherwise trade at unintended prices.
                DynamicEconomy.LOGGER.error("Market file {}: \"defaults\" must be an object; file skipped", fileId);
                return 0;
            }
            defaults = obj.getAsJsonObject("defaults").deepCopy();
            if (defaults.remove("item") != null) {
                DynamicEconomy.LOGGER.warn("Market file {}: \"item\" is not allowed in defaults; ignored", fileId);
            }
        }

        int unpriced = 0;
        int index = 0;
        for (JsonElement element : obj.getAsJsonArray("entries")) {
            int i = index++;
            if (!element.isJsonObject()) {
                DynamicEconomy.LOGGER.error("Market file {} entry {}: must be an object; skipped", fileId, i);
                continue;
            }
            JsonObject merged = defaults.deepCopy();
            element.getAsJsonObject().entrySet().forEach(field -> merged.add(field.getKey(), field.getValue()));
            if (isUnpriced(merged)) {
                unpriced++;
                continue;
            }

            DataResult<CatalogEntry> decoded = decode(merged);
            if (decoded.error().isPresent()) {
                DynamicEconomy.LOGGER.warn("Market file {} entry {}: {}; skipped", fileId, i, decoded.error().get().message());
                continue;
            }
            CatalogEntry entry = decoded.result().orElseThrow();
            CatalogEntry previous = into.put(entry.item(), entry);
            SourceFile previousFile = origin.put(entry.item(), file);
            if (previous != null && previousFile != null) {
                if (previousFile.packOrder() == file.packOrder()) {
                    DynamicEconomy.LOGGER.warn("Market file {} overrides {} listed earlier in {} of the same pack",
                            fileId, entry.itemId(), previousFile.id());
                } else {
                    DynamicEconomy.LOGGER.debug("Market file {} (pack {}) overrides {} from {} (pack {})",
                            fileId, file.packId(), entry.itemId(), previousFile.id(), previousFile.packId());
                }
            }
        }
        return unpriced;
    }
}
