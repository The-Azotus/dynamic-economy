package dynamiceconomy.data;

import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.service.PriceModel;
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
 * The market's shared state, saved with the overworld: stock per item, and each item's supply/demand factor with
 * the overworld game tick it was last written. Kept apart from the catalog so editing prices never touches it.
 * Items dropped from the catalog keep their state in case they are listed again.
 * Only {@code MarketService} and admin commands should change it.
 */
public final class MarketStockData extends SavedData {
    public static final String ID = "dynamic_economy_market";
    private static final int FORMAT_VERSION = 2;
    private static final SavedData.Factory<MarketStockData> FACTORY =
            new SavedData.Factory<>(MarketStockData::new, MarketStockData::load, null);

    /** A factor as last written. Absent from the map means 1.0. */
    public record Factor(double value, long lastTick) {
    }

    private final Map<ResourceLocation, Long> stock = new HashMap<>();
    private final Map<ResourceLocation, Factor> factors = new HashMap<>();

    public static MarketStockData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    /** Overworld game time: the clock factors recover by. It doesn't advance while the server is stopped. */
    public static long now(MinecraftServer server) {
        return server.overworld().getGameTime();
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

    /** The item's stored factor with recovery applied up to {@code now}. Read only: nothing is written. */
    public double factor(ResourceLocation item, long now, double recovery, int intervalTicks) {
        Factor stored = factors.get(item);
        return stored == null ? 1.0 : PriceModel.recovered(stored.value(), stored.lastTick(), now, recovery, intervalTicks);
    }

    /** Stores a factor as of {@code now}. 1.0 is stored as "no entry". */
    public void setFactor(ResourceLocation item, double value, long now) {
        if (value == 1.0) {
            factors.remove(item);
        } else {
            factors.put(item, new Factor(value, now));
        }
        setDirty();
    }

    /** Puts an item back at 1.0. Returns whether it had moved. */
    public boolean resetFactor(ResourceLocation item) {
        boolean had = factors.remove(item) != null;
        if (had) {
            setDirty();
        }
        return had;
    }

    /** Puts every item back at 1.0. Returns how many had moved. */
    public int resetAllFactors() {
        int count = factors.size();
        if (count > 0) {
            factors.clear();
            setDirty();
        }
        return count;
    }

    public Map<ResourceLocation, Factor> factorsView() {
        return Collections.unmodifiableMap(factors);
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
        // Absent in version 1 saves: every factor starts at 1.0.
        CompoundTag factorTag = tag.getCompound("factors");
        for (String key : factorTag.getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(key);
            CompoundTag entry = factorTag.getCompound(key);
            double value = entry.getDouble("s");
            if (id != null && value > 0 && Double.isFinite(value)) {
                data.factors.put(id, new Factor(value, entry.getLong("t")));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag stockTag = new CompoundTag();
        stock.forEach((id, amount) -> stockTag.putLong(id.toString(), amount));
        CompoundTag factorTag = new CompoundTag();
        factors.forEach((id, factor) -> {
            CompoundTag entry = new CompoundTag();
            entry.putDouble("s", factor.value());
            entry.putLong("t", factor.lastTick());
            factorTag.put(id.toString(), entry);
        });
        tag.putInt("version", FORMAT_VERSION);
        tag.put("stock", stockTag);
        tag.put("factors", factorTag);
        return tag;
    }
}
