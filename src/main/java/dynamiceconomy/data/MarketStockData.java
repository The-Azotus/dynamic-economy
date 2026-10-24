package dynamiceconomy.data;

import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.service.PriceModel;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The market's shared state, saved with the overworld: stock per item, and each item's supply/demand factor with
 * the overworld game tick it was last written. Kept apart from the catalog so editing prices never touches it.
 * It also counts the money and units that have gone through the market (see {@link Totals}).
 * Items dropped from the catalog keep their state in case they are listed again.
 * Only {@code MarketService} and admin commands should change it.
 */
public final class MarketStockData extends SavedData {
    public static final String ID = "dynamic_economy_market";
    private static final int FORMAT_VERSION = 4;
    private static final SavedData.Factory<MarketStockData> FACTORY =
            new SavedData.Factory<>(MarketStockData::new, MarketStockData::fromTag, null);
    /** Most points kept per item. At one point per recovery interval that is about an hour at the default. */
    public static final int HISTORY_CAP = 64;

    /** A factor as last written. Absent from the map means 1.0. */
    public record Factor(double value, long lastTick) {
    }

    private final Map<ResourceLocation, Long> stock = new HashMap<>();
    private final Map<ResourceLocation, Factor> factors = new HashMap<>();
    private final Map<ResourceLocation, History> history = new HashMap<>();
    private Totals totals = Totals.ZERO;
    private final Map<ResourceLocation, Totals> itemTotals = new HashMap<>();

    /**
     * Recent factor values for one item, oldest first, at most {@link #HISTORY_CAP}. Each point is the factor from
     * that game tick until the next point; the last one holds until now.
     */
    public record History(long[] ticks, float[] factors) {
        public int size() {
            return ticks.length;
        }
    }

    /**
     * Lifetime money and units through the market, in spurs. The market keeps no balance of its own: what it pays
     * sellers is new money, and what buyers pay it leaves the economy. Counts saturate rather than overflow.
     *
     * @param paidOut   paid to players for items they sold (money created)
     * @param collected paid by players for items they bought (money destroyed)
     */
    public record Totals(long paidOut, long collected, long unitsSold, long unitsBought) {
        public static final Totals ZERO = new Totals(0, 0, 0, 0);

        Totals plus(boolean buy, long qty, long total) {
            return buy
                    ? new Totals(paidOut, add(collected, total), unitsSold, add(unitsBought, qty))
                    : new Totals(add(paidOut, total), collected, add(unitsSold, qty), unitsBought);
        }

        /** Money the market has added to the economy overall; negative when it has removed more than it created. */
        public long netCreated() {
            return paidOut - collected;
        }

        private static long add(long a, long b) {
            long sum = a + b;
            return sum < a ? Long.MAX_VALUE : sum;
        }

        long[] toArray() {
            return new long[] {paidOut, collected, unitsSold, unitsBought};
        }

        static Totals fromArray(long[] values) {
            return values.length == 4 ? new Totals(Math.max(0, values[0]), Math.max(0, values[1]), Math.max(0, values[2]),
                    Math.max(0, values[3])) : ZERO;
        }
    }

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

    /**
     * Drops everything saved about an item: stock, price factor, history and its own money counts. The overall money
     * counts keep what it traded. For items that no longer exist in the game; returns whether anything was saved.
     */
    public boolean forget(ResourceLocation item) {
        boolean had = stock.remove(item) != null;
        had |= factors.remove(item) != null;
        had |= history.remove(item) != null;
        had |= itemTotals.remove(item) != null;
        if (had) {
            setDirty();
        }
        return had;
    }

    /** Replaces all stock with {@code restored}, e.g. from a backup. Amounts must not be negative. */
    public void replaceStock(Map<ResourceLocation, Long> restored) {
        restored.forEach((id, amount) -> {
            if (amount < 0) {
                throw new IllegalArgumentException("Stock cannot be negative: " + id + " = " + amount);
            }
        });
        stock.clear();
        stock.putAll(restored);
        setDirty();
    }

    /** Replaces every price factor with {@code restored}, e.g. from a backup. Factors of exactly 1.0 aren't stored. */
    public void replaceFactors(Map<ResourceLocation, Factor> restored) {
        factors.clear();
        restored.forEach((id, factor) -> {
            if (factor.value() != 1.0) {
                factors.put(id, factor);
            }
        });
        setDirty();
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

    /**
     * Adds a history point, keeping at most one per recovery interval: a newer value in the same interval replaces the
     * last point, and an unchanged value adds nothing. Items that never left 1.0 keep no history at all.
     */
    public void recordHistory(ResourceLocation item, double factor, long now, int intervalTicks) {
        History old = history.get(item);
        float value = (float) factor;
        if (old == null) {
            if (factor == 1.0) {
                return;
            }
            history.put(item, new History(new long[] {now}, new float[] {value}));
            setDirty();
            return;
        }
        int last = old.size() - 1;
        if (old.factors()[last] == value) {
            return;
        }
        long[] ticks;
        float[] values;
        if (Math.floorDiv(old.ticks()[last], intervalTicks) == Math.floorDiv(now, intervalTicks)) {
            ticks = old.ticks().clone();
            values = old.factors().clone();
            ticks[last] = now;
            values[last] = value;
        } else {
            int keep = Math.min(old.size(), HISTORY_CAP - 1);
            ticks = new long[keep + 1];
            values = new float[keep + 1];
            System.arraycopy(old.ticks(), old.size() - keep, ticks, 0, keep);
            System.arraycopy(old.factors(), old.size() - keep, values, 0, keep);
            ticks[keep] = now;
            values[keep] = value;
        }
        history.put(item, new History(ticks, values));
        setDirty();
    }

    /** An item's history, or an empty one. */
    public History history(ResourceLocation item) {
        return history.getOrDefault(item, new History(new long[0], new float[0]));
    }

    /** Every item's history. A changed history is always a new {@link History} object, so identity shows change. */
    public Map<ResourceLocation, History> historyView() {
        return Collections.unmodifiableMap(history);
    }

    /** Counts a completed trade. Called after the trade can no longer fail; it can't fail itself. */
    public void recordTrade(ResourceLocation item, boolean buy, int qty, long total) {
        totals = totals.plus(buy, qty, total);
        itemTotals.put(item, itemTotals.getOrDefault(item, Totals.ZERO).plus(buy, qty, total));
        setDirty();
    }

    public Totals totals() {
        return totals;
    }

    public Totals totals(ResourceLocation item) {
        return itemTotals.getOrDefault(item, Totals.ZERO);
    }

    public Map<ResourceLocation, Totals> itemTotalsView() {
        return Collections.unmodifiableMap(itemTotals);
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

    /** Reads saved data. Public so tests can check that a save reads back unchanged. */
    public static MarketStockData fromTag(CompoundTag tag, HolderLookup.Provider registries) {
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
        // Absent before Phase 5 saves.
        CompoundTag historyTag = tag.getCompound("history");
        for (String key : historyTag.getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(key);
            CompoundTag entry = historyTag.getCompound(key);
            long[] ticks = entry.getLongArray("t");
            int[] bits = entry.getIntArray("s");
            if (id != null && ticks.length == bits.length && ticks.length > 0) {
                int keep = Math.min(ticks.length, HISTORY_CAP);
                float[] values = new float[keep];
                for (int i = 0; i < keep; i++) {
                    values[i] = Float.intBitsToFloat(bits[ticks.length - keep + i]);
                }
                data.history.put(id, new History(Arrays.copyOfRange(ticks, ticks.length - keep, ticks.length), values));
            }
        }
        // Absent before format 4: counting starts from zero.
        CompoundTag totalsTag = tag.getCompound("totals");
        data.totals = Totals.fromArray(totalsTag.getLongArray("all"));
        CompoundTag itemTotalsTag = totalsTag.getCompound("items");
        for (String key : itemTotalsTag.getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(key);
            Totals itemTotal = Totals.fromArray(itemTotalsTag.getLongArray(key));
            if (id != null && !itemTotal.equals(Totals.ZERO)) {
                data.itemTotals.put(id, itemTotal);
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
        CompoundTag historyTag = new CompoundTag();
        history.forEach((id, points) -> {
            CompoundTag entry = new CompoundTag();
            entry.putLongArray("t", points.ticks());
            int[] bits = new int[points.size()];
            for (int i = 0; i < bits.length; i++) {
                bits[i] = Float.floatToIntBits(points.factors()[i]);
            }
            entry.putIntArray("s", bits);
            historyTag.put(id.toString(), entry);
        });
        tag.put("history", historyTag);
        CompoundTag totalsTag = new CompoundTag();
        totalsTag.putLongArray("all", totals.toArray());
        CompoundTag itemTotalsTag = new CompoundTag();
        itemTotals.forEach((id, itemTotal) -> itemTotalsTag.putLongArray(id.toString(), itemTotal.toArray()));
        totalsTag.put("items", itemTotalsTag);
        tag.put("totals", totalsTag);
        return tag;
    }
}
