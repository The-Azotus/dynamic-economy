package dynamiceconomy.catalog;

import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import dynamiceconomy.data.MarketOverrides;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Builds the live catalog from the catalog files plus admin edits. For each item: start from its file entry (if
 * any), apply every tag rule containing it in rule order, then its own edits. A tag rule without a {@code base_price}
 * only changes items that are already listed; it never lists an unpriced item. Per-item edits always win, even over a
 * tag rule that removed the item. Every result is checked with the loader's own rules. Pure apart from reading the
 * item registry: tag membership is passed in.
 */
public final class CatalogOverlay {
    private CatalogOverlay() {
    }

    /**
     * @param entries  the live catalog
     * @param problems one message per edit that couldn't be applied, keyed by the item it concerns
     */
    public record Result(Map<Item, CatalogEntry> entries, Map<ResourceLocation, String> problems) {
    }

    public static Result apply(Map<Item, CatalogEntry> files, MarketOverrides.Snapshot overrides,
                               Function<ResourceLocation, Collection<Item>> tagMembers) {
        Map<ResourceLocation, Set<Item>> members = new LinkedHashMap<>();
        Set<Item> candidates = new LinkedHashSet<>(files.keySet());
        for (MarketOverrides.TagRule rule : overrides.tags()) {
            Set<Item> inTag = new LinkedHashSet<>(tagMembers.apply(rule.tag()));
            members.put(rule.tag(), inTag);
            candidates.addAll(inTag);
        }
        for (ResourceLocation id : overrides.items().keySet()) {
            BuiltInRegistries.ITEM.getOptional(id).ifPresent(candidates::add);
        }

        Map<Item, CatalogEntry> result = new LinkedHashMap<>();
        Map<ResourceLocation, String> problems = new LinkedHashMap<>();
        for (Item item : candidates) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            CatalogEntry fileEntry = files.get(item);
            JsonObject fileJson = fileEntry == null ? null : CatalogJson.toJson(fileEntry);
            JsonObject json = fileJson;
            boolean removed = false;
            boolean edited = false;

            for (MarketOverrides.TagRule rule : overrides.tags()) {
                if (!members.get(rule.tag()).contains(item)) {
                    continue;
                }
                if (rule.patch().has(MarketOverrides.REMOVED)) {
                    removed = true;
                    continue;
                }
                JsonObject base = removed ? fileJson : json;
                if (base == null && !rule.patch().has("base_price")) {
                    continue; // The rule doesn't price items, so it can't list one that isn't listed.
                }
                json = CatalogJson.merge(base == null ? named(id) : base, rule.patch());
                removed = false;
                edited = true;
            }

            JsonObject own = overrides.items().get(id);
            if (own != null) {
                if (own.has(MarketOverrides.REMOVED)) {
                    removed = true;
                } else {
                    // Per-item edits beat a tag rule's removal: start again from the file entry in that case.
                    JsonObject base = removed ? fileJson : json;
                    if (base == null && !own.has("base_price")) {
                        problems.put(id, id + " has edits but no base_price, and isn't in the catalog files, so it isn't listed");
                        continue;
                    }
                    json = CatalogJson.merge(base == null ? named(id) : base, own);
                    removed = false;
                    edited = true;
                }
            }

            if (removed || json == null) {
                continue;
            }
            if (!edited) {
                result.put(item, fileEntry);
                continue;
            }
            DataResult<CatalogEntry> decoded = MarketCatalog.decode(json);
            if (decoded.error().isPresent()) {
                problems.put(id, id + ": " + decoded.error().get().message());
                // Fall back to the file entry rather than delisting an item because an edit was bad.
                if (fileEntry != null) {
                    result.put(item, fileEntry);
                }
                continue;
            }
            result.put(item, decoded.result().orElseThrow());
        }
        return new Result(Collections.unmodifiableMap(result), Collections.unmodifiableMap(problems));
    }

    /** Tag members from the live item registry; empty for an unknown tag. */
    public static Collection<Item> registryTag(ResourceLocation tag) {
        List<Item> items = new ArrayList<>();
        BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, tag))
                .ifPresent(set -> set.forEach(holder -> items.add(holder.value())));
        return items;
    }

    private static JsonObject named(ResourceLocation id) {
        JsonObject json = new JsonObject();
        json.addProperty("item", id.toString());
        return json;
    }
}
