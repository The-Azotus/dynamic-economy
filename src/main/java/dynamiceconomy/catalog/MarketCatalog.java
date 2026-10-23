package dynamiceconomy.catalog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dynamiceconomy.DynamicEconomy;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Item listings loaded from {@code data/<namespace>/market/*.json}. Each file holds an {@code entries} array and may
 * hold a {@code defaults} object whose fields apply to every entry in that file unless the entry sets them itself.
 * A bad entry is logged and skipped without discarding the rest of its file. If two files list the same item,
 * the file whose ID sorts last wins.
 */
public final class MarketCatalog extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new GsonBuilder().create();
    public static final MarketCatalog INSTANCE = new MarketCatalog();

    private volatile Map<Item, CatalogEntry> entries = Map.of();

    private MarketCatalog() {
        super(GSON, "market");
    }

    public Optional<CatalogEntry> get(Item item) {
        return Optional.ofNullable(entries.get(item));
    }

    public Collection<CatalogEntry> entries() {
        return entries.values();
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
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager, ProfilerFiller profiler) {
        entries = parse(files);
        DynamicEconomy.LOGGER.info("Loaded {} market catalog entries from {} files", entries.size(), files.size());
    }

    /** Turns market files (keyed by file ID) into listings. Pure: logs problems but changes no state. */
    public static Map<Item, CatalogEntry> parse(Map<ResourceLocation, JsonElement> files) {
        Map<Item, CatalogEntry> loaded = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> file : new TreeMap<>(files).entrySet()) {
            loadFile(file.getKey(), file.getValue(), loaded);
        }
        return Collections.unmodifiableMap(loaded);
    }

    /**
     * The value only if decoding had no error at all. {@code resultOrPartial} would hand back a partial result, e.g.
     * an entry with an out-of-range optional field silently replaced by its default.
     */
    @Nullable
    private static <T> T strict(DataResult<T> result, Consumer<String> onError) {
        if (result.error().isPresent()) {
            onError.accept(result.error().get().message());
            return null;
        }
        return result.result().orElse(null);
    }

    private static void loadFile(ResourceLocation fileId, JsonElement json, Map<Item, CatalogEntry> into) {
        if (!(json instanceof JsonObject obj) || !obj.has("entries") || !obj.get("entries").isJsonArray()) {
            DynamicEconomy.LOGGER.error("Market file {} must be an object with an \"entries\" array; skipped", fileId);
            return;
        }
        JsonObject defaults = new JsonObject();
        if (obj.has("defaults")) {
            if (!obj.get("defaults").isJsonObject()) {
                // Skipping is safer than guessing: the entries would otherwise trade at unintended prices.
                DynamicEconomy.LOGGER.error("Market file {}: \"defaults\" must be an object; file skipped", fileId);
                return;
            }
            defaults = obj.getAsJsonObject("defaults").deepCopy();
            if (defaults.remove("item") != null) {
                DynamicEconomy.LOGGER.warn("Market file {}: \"item\" is not allowed in defaults; ignored", fileId);
            }
        }

        int index = 0;
        for (JsonElement element : obj.getAsJsonArray("entries")) {
            int i = index++;
            if (!element.isJsonObject()) {
                DynamicEconomy.LOGGER.error("Market file {} entry {}: must be an object; skipped", fileId, i);
                continue;
            }
            JsonObject merged = defaults.deepCopy();
            element.getAsJsonObject().entrySet().forEach(field -> merged.add(field.getKey(), field.getValue()));

            CatalogEntry.Raw raw = strict(CatalogEntry.Raw.CODEC.parse(JsonOps.INSTANCE, merged),
                    err -> DynamicEconomy.LOGGER.error("Market file {} entry {}: {}; skipped", fileId, i, err));
            if (raw == null) {
                continue;
            }
            Optional<Item> item = BuiltInRegistries.ITEM.getOptional(raw.item());
            if (item.isEmpty() || item.get() == Items.AIR) {
                DynamicEconomy.LOGGER.warn("Market file {} entry {}: unknown item {}; skipped", fileId, i, raw.item());
                continue;
            }
            CatalogEntry entry = strict(raw.resolve(item.get()),
                    err -> DynamicEconomy.LOGGER.warn("Market file {} entry {} ({}): {}; skipped", fileId, i, raw.item(), err));
            if (entry == null) {
                continue;
            }
            CatalogEntry previous = into.put(item.get(), entry);
            if (previous != null) {
                DynamicEconomy.LOGGER.warn("Market file {} overrides an earlier listing of {}", fileId, raw.item());
            }
        }
    }
}
