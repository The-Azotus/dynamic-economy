package dynamiceconomy.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Each player's most recent trades, at most {@link #CAP} each, saved with the world so the market screen can show
 * them. Written only by the server after a trade has completed; holds nothing beyond what the trade itself was.
 */
public final class TradeHistory extends SavedData {
    public static final String ID = "dynamic_economy_trades";
    public static final int CAP = 50;
    private static final SavedData.Factory<TradeHistory> FACTORY =
            new SavedData.Factory<>(TradeHistory::new, TradeHistory::fromTag, null);

    /**
     * One completed trade.
     *
     * @param time  when it happened, in milliseconds since 1970 (server clock)
     * @param total bits paid or received
     */
    public record Trade(long time, ResourceLocation item, boolean buy, int quantity, long total) {
    }

    private final Map<UUID, Deque<Trade>> trades = new HashMap<>();

    public static TradeHistory get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    /** Adds a completed trade, dropping the player's oldest beyond the cap. Can't fail. */
    public void record(UUID player, Trade trade) {
        Deque<Trade> list = trades.computeIfAbsent(player, id -> new ArrayDeque<>());
        list.addFirst(trade);
        while (list.size() > CAP) {
            list.removeLast();
        }
        setDirty();
    }

    /** The player's trades, newest first. */
    public List<Trade> recent(UUID player) {
        return List.copyOf(trades.getOrDefault(player, new ArrayDeque<>()));
    }

    public static TradeHistory fromTag(CompoundTag tag, HolderLookup.Provider registries) {
        TradeHistory data = new TradeHistory();
        CompoundTag players = tag.getCompound("players");
        for (String key : players.getAllKeys()) {
            UUID player;
            try {
                player = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            ListTag list = players.getList(key, Tag.TAG_COMPOUND);
            Deque<Trade> loaded = new ArrayDeque<>();
            for (int i = 0; i < list.size() && loaded.size() < CAP; i++) {
                CompoundTag entry = list.getCompound(i);
                ResourceLocation item = ResourceLocation.tryParse(entry.getString("item"));
                if (item != null) {
                    loaded.addLast(new Trade(entry.getLong("time"), item, entry.getBoolean("buy"), entry.getInt("qty"),
                            entry.getLong("total")));
                }
            }
            if (!loaded.isEmpty()) {
                data.trades.put(player, loaded);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag players = new CompoundTag();
        trades.forEach((player, list) -> {
            ListTag entries = new ListTag();
            for (Trade trade : list) {
                CompoundTag entry = new CompoundTag();
                entry.putLong("time", trade.time());
                entry.putString("item", trade.item().toString());
                entry.putBoolean("buy", trade.buy());
                entry.putInt("qty", trade.quantity());
                entry.putLong("total", trade.total());
                entries.add(entry);
            }
            players.put(player.toString(), entries);
        });
        tag.putInt("version", 1);
        tag.put("players", players);
        return tag;
    }

    /** Every player's trades, for tests. */
    public Map<UUID, List<Trade>> view() {
        Map<UUID, List<Trade>> copy = new HashMap<>();
        trades.forEach((player, list) -> copy.put(player, new ArrayList<>(list)));
        return copy;
    }
}
