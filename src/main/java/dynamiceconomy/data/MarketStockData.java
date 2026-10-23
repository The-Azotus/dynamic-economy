package dynamiceconomy.data;

import dynamiceconomy.catalog.CatalogEntry;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The market's shared stock, saved with the overworld. Kept apart from the catalog so editing prices never touches
 * stock. Items dropped from the catalog keep their stock in case they are listed again.
 * Only {@code MarketService} and admin commands should change it.
 */
public final class MarketStockData extends SavedData {
    public static final String ID = "dynamic_economy_market";
    private static final int FORMAT_VERSION = 1;
    private static final SavedData.Factory<MarketStockData> FACTORY =
            new SavedData.Factory<>(MarketStockData::new, MarketStockData::load, null);

    private final Map<ResourceLocation, Long> stock = new HashMap<>();

    public static MarketStockData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public long getStock(ResourceLocation item) {
        return stock.getOrDefault(item, 0L);
    }

    public void setStock(ResourceLocation item, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Stock cannot be negative: " + item + " = " + amount);
        }
        stock.put(item, amount);
        setDirty();
    }

    public Map<ResourceLocation, Long> view() {
        return Collections.unmodifiableMap(stock);
    }

    /**
     * Gives each finite entry its start_stock, but only if the item has never been in the market before. Unlimited
     * entries are skipped so their stored value, if any, is still there when the catalog makes them finite again.
     */
    public void seed(Iterable<CatalogEntry> entries) {
        for (CatalogEntry entry : entries) {
            if (!entry.unlimitedStock() && !stock.containsKey(entry.itemId())) {
                setStock(entry.itemId(), entry.startStock());
            }
        }
    }

    private static MarketStockData load(CompoundTag tag, HolderLookup.Provider registries) {
        MarketStockData data = new MarketStockData();
        CompoundTag stockTag = tag.getCompound("stock");
        for (String key : stockTag.getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(key);
            if (id != null && stockTag.contains(key, Tag.TAG_LONG)) {
                data.stock.put(id, Math.max(0, stockTag.getLong(key)));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag stockTag = new CompoundTag();
        stock.forEach((id, amount) -> stockTag.putLong(id.toString(), amount));
        tag.putInt("version", FORMAT_VERSION);
        tag.put("stock", stockTag);
        return tag;
    }
}
