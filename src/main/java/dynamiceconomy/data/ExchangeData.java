package dynamiceconomy.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The player exchange, saved with the world: open orders, each player's collection box, and anything owed or still
 * to be told. Orders, boxes and owed money are one saved file, so a restart never splits a trade between them. Only
 * {@code ExchangeService} changes it. Server thread only.
 * <p>
 * What an order holds: a buy order holds {@code price × remaining} in money, taken when it was posted; a sell order
 * holds its remaining items, taken from the seller's inventory when it was posted.
 */
public final class ExchangeData extends SavedData {
    public static final String ID = "dynamic_economy_exchange";
    private static final SavedData.Factory<ExchangeData> FACTORY = new SavedData.Factory<>(ExchangeData::new, ExchangeData::fromTag, null);
    /** Best price first, then oldest first: the order in which a side of the book fills. */
    private static final Comparator<Order> ASKS = Comparator.comparingLong(Order::price).thenComparingLong(Order::id);
    private static final Comparator<Order> BIDS = Comparator.comparingLong(Order::price).reversed().thenComparingLong(Order::id);

    /**
     * One open order.
     *
     * @param team      the team account a buy order's money came from, which money goes back to while that team exists;
     *                  for a sell order, the account its proceeds go to
     * @param price     per unit, in bits
     * @param quantity  units ordered; {@code filled} of them have traded
     * @param postedDay the contracts' day count when it was posted, for expiry
     */
    public record Order(long id, UUID owner, Optional<UUID> team, ResourceLocation item, boolean buy, long price, int quantity,
                        int filled, long postedDay) {
        public int remaining() {
            return quantity - filled;
        }

        /** The money a buy order still holds; 0 for a sell order. */
        public long heldMoney() {
            return buy ? price * remaining() : 0;
        }

        public Order withFilled(int total) {
            return new Order(id, owner, team, item, buy, price, quantity, total, postedDay);
        }
    }

    public enum NoticeKind {
        /** A sell order sold units: {@code amount} is what was paid in, after tax. */
        SOLD,
        /** A buy order bought units, now in the collection box: {@code amount} is what they cost. */
        BOUGHT,
        /** An order expired or was closed: a buy order's money ({@code amount}) or a sell order's items went back. */
        RETURNED
    }

    /** Something that happened to a player's order, kept until they next log in. Same-kind notices for an item add up. */
    public record Notice(UUID player, ResourceLocation item, NoticeKind kind, long quantity, long amount) {
    }

    /** Money owed to a player (or the team account it goes to) while the bank couldn't be reached. */
    public record Payout(UUID player, Optional<UUID> team, long amount) {
    }

    private final List<Order> orders = new ArrayList<>();
    /** Items waiting for each player, by item, in the order they arrived. */
    private final Map<UUID, Map<ResourceLocation, Long>> boxes = new LinkedHashMap<>();
    private final List<Notice> notices = new ArrayList<>();
    private final List<Payout> owed = new ArrayList<>();
    private long nextId = 1;
    private long taxCollected;
    private long volume;

    public static ExchangeData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    // ---- Orders ----------------------------------------------------------------------------------------------

    public List<Order> orders() {
        return Collections.unmodifiableList(orders);
    }

    public Optional<Order> find(long id) {
        return orders.stream().filter(o -> o.id() == id).findFirst();
    }

    /** Takes the next order ID. IDs never repeat, so they also give time priority. */
    public long takeId() {
        setDirty();
        return nextId++;
    }

    public void add(Order order) {
        orders.add(order);
        setDirty();
    }

    /** Replaces the order with the same ID, or removes it once nothing remains. */
    public void update(Order order) {
        for (int i = 0; i < orders.size(); i++) {
            if (orders.get(i).id() == order.id()) {
                if (order.remaining() <= 0) {
                    orders.remove(i);
                } else {
                    orders.set(i, order);
                }
                setDirty();
                return;
            }
        }
        throw new IllegalArgumentException("No exchange order " + order.id());
    }

    public void remove(long id) {
        if (orders.removeIf(o -> o.id() == id)) {
            setDirty();
        }
    }

    /** One side of an item's book in fill order: asks cheapest first, bids dearest first; oldest first at a price. */
    public List<Order> side(ResourceLocation item, boolean buy) {
        return orders.stream().filter(o -> o.item().equals(item) && o.buy() == buy).sorted(buy ? BIDS : ASKS).toList();
    }

    public int openOrders(UUID player) {
        return (int) orders.stream().filter(o -> o.owner().equals(player)).count();
    }

    // ---- Collection boxes ------------------------------------------------------------------------------------

    /** What waits in the player's box, by item. */
    public Map<ResourceLocation, Long> box(UUID player) {
        return Collections.unmodifiableMap(boxes.getOrDefault(player, Map.of()));
    }

    public Map<UUID, Map<ResourceLocation, Long>> boxes() {
        return Collections.unmodifiableMap(boxes);
    }

    public void addToBox(UUID player, ResourceLocation item, long count) {
        if (count <= 0) {
            return;
        }
        boxes.computeIfAbsent(player, p -> new LinkedHashMap<>()).merge(item, count, Long::sum);
        setDirty();
    }

    /** Takes {@code count} of an item out of the player's box; the caller has checked there are that many. */
    public void takeFromBox(UUID player, ResourceLocation item, long count) {
        Map<ResourceLocation, Long> box = boxes.get(player);
        long have = box == null ? 0 : box.getOrDefault(item, 0L);
        if (count > have) {
            throw new IllegalArgumentException("Box holds " + have + " of " + item + ", not " + count);
        }
        if (count == have) {
            box.remove(item);
            if (box.isEmpty()) {
                boxes.remove(player);
            }
        } else {
            box.put(item, have - count);
        }
        setDirty();
    }

    // ---- Notices and owed money ------------------------------------------------------------------------------

    public void addNotice(Notice notice) {
        for (int i = 0; i < notices.size(); i++) {
            Notice n = notices.get(i);
            if (n.player().equals(notice.player()) && n.item().equals(notice.item()) && n.kind() == notice.kind()) {
                notices.set(i, new Notice(n.player(), n.item(), n.kind(), n.quantity() + notice.quantity(), n.amount() + notice.amount()));
                setDirty();
                return;
            }
        }
        notices.add(notice);
        setDirty();
    }

    /** Removes and returns the player's notices. */
    public List<Notice> takeNotices(UUID player) {
        List<Notice> theirs = notices.stream().filter(n -> n.player().equals(player)).toList();
        if (!theirs.isEmpty()) {
            notices.removeAll(theirs);
            setDirty();
        }
        return theirs;
    }

    public void owe(Payout payout) {
        owed.add(payout);
        setDirty();
    }

    public List<Payout> owed() {
        return List.copyOf(owed);
    }

    public void paidOwed(Payout payout) {
        owed.remove(payout);
        setDirty();
    }

    /**
     * The money the exchange holds for each player's own account: what their open buy orders hold plus what they're
     * owed. Money for team accounts isn't included, as team accounts aren't ranked.
     */
    public Map<UUID, Long> heldForPlayers() {
        Map<UUID, Long> held = new LinkedHashMap<>();
        for (Order order : orders) {
            if (order.team().isEmpty() && order.heldMoney() > 0) {
                held.merge(order.owner(), order.heldMoney(), Long::sum);
            }
        }
        for (Payout payout : owed) {
            if (payout.team().isEmpty()) {
                held.merge(payout.player(), payout.amount(), Long::sum);
            }
        }
        return held;
    }

    // ---- Money counts ----------------------------------------------------------------------------------------

    /** Adds one trade to the counts: its value, and the tax taken from it (money destroyed). */
    public void count(long value, long tax) {
        volume += value;
        taxCollected += tax;
        setDirty();
    }

    public long taxCollected() {
        return taxCollected;
    }

    public long volume() {
        return volume;
    }

    // ---- Saving ----------------------------------------------------------------------------------------------

    public static ExchangeData fromTag(CompoundTag tag, HolderLookup.Provider registries) {
        ExchangeData data = new ExchangeData();
        data.nextId = Math.max(1, tag.getLong("next_id"));
        data.taxCollected = tag.getLong("tax_collected");
        data.volume = tag.getLong("volume");
        for (Tag element : tag.getList("orders", Tag.TAG_COMPOUND)) {
            CompoundTag o = (CompoundTag) element;
            ResourceLocation item = ResourceLocation.tryParse(o.getString("item"));
            if (item != null && o.hasUUID("owner")) {
                data.orders.add(new Order(o.getLong("id"), o.getUUID("owner"), o.hasUUID("team") ? Optional.of(o.getUUID("team")) : Optional.empty(),
                        item, o.getBoolean("buy"), o.getLong("price"), o.getInt("quantity"), o.getInt("filled"), o.getLong("posted_day")));
            }
        }
        for (Tag element : tag.getList("boxes", Tag.TAG_COMPOUND)) {
            CompoundTag b = (CompoundTag) element;
            ResourceLocation item = ResourceLocation.tryParse(b.getString("item"));
            if (item != null && b.hasUUID("player")) {
                data.boxes.computeIfAbsent(b.getUUID("player"), p -> new LinkedHashMap<>()).merge(item, b.getLong("count"), Long::sum);
            }
        }
        for (Tag element : tag.getList("notices", Tag.TAG_COMPOUND)) {
            CompoundTag n = (CompoundTag) element;
            ResourceLocation item = ResourceLocation.tryParse(n.getString("item"));
            NoticeKind kind;
            try {
                kind = NoticeKind.valueOf(n.getString("kind").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (item != null && n.hasUUID("player")) {
                data.notices.add(new Notice(n.getUUID("player"), item, kind, n.getLong("quantity"), n.getLong("amount")));
            }
        }
        for (Tag element : tag.getList("owed", Tag.TAG_COMPOUND)) {
            CompoundTag o = (CompoundTag) element;
            if (o.hasUUID("player")) {
                data.owed.add(new Payout(o.getUUID("player"), o.hasUUID("team") ? Optional.of(o.getUUID("team")) : Optional.empty(),
                        o.getLong("amount")));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", 1);
        tag.putLong("next_id", nextId);
        tag.putLong("tax_collected", taxCollected);
        tag.putLong("volume", volume);
        ListTag orderList = new ListTag();
        for (Order order : orders) {
            CompoundTag o = new CompoundTag();
            o.putLong("id", order.id());
            o.putUUID("owner", order.owner());
            order.team().ifPresent(id -> o.putUUID("team", id));
            o.putString("item", order.item().toString());
            o.putBoolean("buy", order.buy());
            o.putLong("price", order.price());
            o.putInt("quantity", order.quantity());
            o.putInt("filled", order.filled());
            o.putLong("posted_day", order.postedDay());
            orderList.add(o);
        }
        tag.put("orders", orderList);
        ListTag boxList = new ListTag();
        boxes.forEach((player, box) -> box.forEach((item, count) -> {
            CompoundTag b = new CompoundTag();
            b.putUUID("player", player);
            b.putString("item", item.toString());
            b.putLong("count", count);
            boxList.add(b);
        }));
        tag.put("boxes", boxList);
        ListTag noticeList = new ListTag();
        for (Notice notice : notices) {
            CompoundTag n = new CompoundTag();
            n.putUUID("player", notice.player());
            n.putString("item", notice.item().toString());
            n.putString("kind", notice.kind().name().toLowerCase(Locale.ROOT));
            n.putLong("quantity", notice.quantity());
            n.putLong("amount", notice.amount());
            noticeList.add(n);
        }
        tag.put("notices", noticeList);
        ListTag owedList = new ListTag();
        for (Payout payout : owed) {
            CompoundTag o = new CompoundTag();
            o.putUUID("player", payout.player());
            payout.team().ifPresent(id -> o.putUUID("team", id));
            o.putLong("amount", payout.amount());
            owedList.add(o);
        }
        tag.put("owed", owedList);
        return tag;
    }
}
