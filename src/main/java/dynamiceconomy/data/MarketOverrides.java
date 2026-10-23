package dynamiceconomy.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalog edits made in game with admin commands, saved with the world. They sit on top of the catalog files:
 * files, then tag rules (in the order they were first created), then per-item edits, so pricing one item by hand
 * always beats a tag rule. Each edit is a partial catalog entry in JSON, plus {@code "removed": true} to take items
 * off the market. Editing never touches stock or price factors.
 */
public final class MarketOverrides extends SavedData {
    public static final String ID = "dynamic_economy_overrides";
    public static final String REMOVED = "removed";
    private static final int FORMAT_VERSION = 1;
    private static final SavedData.Factory<MarketOverrides> FACTORY =
            new SavedData.Factory<>(MarketOverrides::new, MarketOverrides::fromTag, null);

    /** A live tag rule: applies to every item in the tag each time the catalog is built. */
    public record TagRule(ResourceLocation tag, JsonObject patch) {
    }

    /** An immutable copy of every edit, for building the catalog and for undoing a rejected command. */
    public record Snapshot(Map<ResourceLocation, JsonObject> items, List<TagRule> tags) {
        public static final Snapshot EMPTY = new Snapshot(Map.of(), List.of());
    }

    private final Map<ResourceLocation, JsonObject> items = new LinkedHashMap<>();
    private final List<TagRule> tags = new ArrayList<>();

    public static MarketOverrides get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public Snapshot snapshot() {
        Map<ResourceLocation, JsonObject> itemCopy = new LinkedHashMap<>();
        items.forEach((id, patch) -> itemCopy.put(id, patch.deepCopy()));
        List<TagRule> tagCopy = new ArrayList<>();
        tags.forEach(rule -> tagCopy.add(new TagRule(rule.tag(), rule.patch().deepCopy())));
        return new Snapshot(Collections.unmodifiableMap(itemCopy), Collections.unmodifiableList(tagCopy));
    }

    /** Puts every edit back as it was in {@code snapshot}, e.g. after a command turned out to be invalid. */
    public void restore(Snapshot snapshot) {
        items.clear();
        snapshot.items().forEach((id, patch) -> items.put(id, patch.deepCopy()));
        tags.clear();
        snapshot.tags().forEach(rule -> tags.add(new TagRule(rule.tag(), rule.patch().deepCopy())));
        setDirty();
    }

    // ---- Per-item edits ---------------------------------------------------------------------------------------

    /** Sets one field for an item. Setting anything also lists the item again if it was removed. */
    public void setItemField(ResourceLocation item, String field, JsonElement value) {
        JsonObject patch = items.computeIfAbsent(item, id -> new JsonObject());
        patch.remove(REMOVED);
        patch.add(field, value);
        setDirty();
    }

    /** Drops one field from an item's edits, so the catalog files (or a tag rule) decide it again. */
    public boolean unsetItemField(ResourceLocation item, String field) {
        JsonObject patch = items.get(item);
        if (patch == null || patch.remove(field) == null) {
            return false;
        }
        if (patch.size() == 0) {
            items.remove(item);
        }
        setDirty();
        return true;
    }

    /** Takes an item off the market. Its other edits are kept, so {@code revert} or listing it again restores them. */
    public void removeItem(ResourceLocation item) {
        items.computeIfAbsent(item, id -> new JsonObject()).addProperty(REMOVED, true);
        setDirty();
    }

    /** Drops every edit for an item. Returns whether there were any. */
    public boolean revertItem(ResourceLocation item) {
        boolean had = items.remove(item) != null;
        if (had) {
            setDirty();
        }
        return had;
    }

    public JsonObject itemPatch(ResourceLocation item) {
        JsonObject patch = items.get(item);
        return patch == null ? null : patch.deepCopy();
    }

    // ---- Tag rules --------------------------------------------------------------------------------------------

    /** Sets one field on a tag rule, creating the rule at the end of the order if it's new. */
    public void setTagField(ResourceLocation tag, String field, JsonElement value) {
        JsonObject patch = tagPatch(tag, true);
        patch.remove(REMOVED);
        patch.add(field, value);
        setDirty();
    }

    public boolean unsetTagField(ResourceLocation tag, String field) {
        JsonObject patch = tagPatch(tag, false);
        if (patch == null || patch.remove(field) == null) {
            return false;
        }
        if (patch.size() == 0) {
            tags.removeIf(rule -> rule.tag().equals(tag));
        }
        setDirty();
        return true;
    }

    /** Takes every item in the tag off the market, except items with their own edits. */
    public void removeTag(ResourceLocation tag) {
        tagPatch(tag, true).addProperty(REMOVED, true);
        setDirty();
    }

    public boolean revertTag(ResourceLocation tag) {
        boolean had = tags.removeIf(rule -> rule.tag().equals(tag));
        if (had) {
            setDirty();
        }
        return had;
    }

    private JsonObject tagPatch(ResourceLocation tag, boolean create) {
        for (TagRule rule : tags) {
            if (rule.tag().equals(tag)) {
                return rule.patch();
            }
        }
        if (!create) {
            return null;
        }
        TagRule rule = new TagRule(tag, new JsonObject());
        tags.add(rule);
        return rule.patch();
    }

    // ---- Saving -----------------------------------------------------------------------------------------------

    public static MarketOverrides fromTag(CompoundTag tag, HolderLookup.Provider registries) {
        MarketOverrides data = new MarketOverrides();
        CompoundTag itemTag = tag.getCompound("items");
        for (String key : itemTag.getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(key);
            JsonObject patch = parse(itemTag.getString(key));
            if (id != null && patch != null) {
                data.items.put(id, patch);
            }
        }
        ListTag tagList = tag.getList("tags", Tag.TAG_COMPOUND);
        for (int i = 0; i < tagList.size(); i++) {
            CompoundTag rule = tagList.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(rule.getString("tag"));
            JsonObject patch = parse(rule.getString("patch"));
            if (id != null && patch != null) {
                data.tags.add(new TagRule(id, patch));
            }
        }
        return data;
    }

    private static JsonObject parse(String json) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag itemTag = new CompoundTag();
        items.forEach((id, patch) -> itemTag.putString(id.toString(), patch.toString()));
        ListTag tagList = new ListTag();
        for (TagRule rule : tags) {
            CompoundTag entry = new CompoundTag();
            entry.putString("tag", rule.tag().toString());
            entry.putString("patch", rule.patch().toString());
            tagList.add(entry);
        }
        tag.putInt("version", FORMAT_VERSION);
        tag.put("items", itemTag);
        tag.put("tags", tagList);
        return tag;
    }
}
