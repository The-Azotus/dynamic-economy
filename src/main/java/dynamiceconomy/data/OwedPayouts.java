package dynamiceconomy.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Money owed to players while the bank couldn't be reached, kept in the saved data that owes it (the contract board,
 * the exchange) so a restart never loses it. {@code PayoutService} pays it as soon as the bank is back. Server thread
 * only.
 */
public final class OwedPayouts {
    /** Money owed to a player, or to the team account it goes to while that team exists. */
    public record Payout(UUID player, Optional<UUID> team, long amount) {
    }

    private final SavedData owner;
    private final List<Payout> owed = new ArrayList<>();

    OwedPayouts(SavedData owner) {
        this.owner = owner;
    }

    public void owe(Payout payout) {
        owed.add(payout);
        owner.setDirty();
    }

    public void paid(Payout payout) {
        owed.remove(payout);
        owner.setDirty();
    }

    /** A copy, safe to iterate while paying. */
    public List<Payout> list() {
        return List.copyOf(owed);
    }

    public boolean isEmpty() {
        return owed.isEmpty();
    }

    public int size() {
        return owed.size();
    }

    /** What each player's own account is owed. Team accounts aren't included, as they aren't ranked. */
    public Map<UUID, Long> forPlayers() {
        Map<UUID, Long> held = new LinkedHashMap<>();
        for (Payout payout : owed) {
            if (payout.team().isEmpty()) {
                held.merge(payout.player(), payout.amount(), Long::sum);
            }
        }
        return held;
    }

    void read(ListTag list) {
        for (Tag element : list) {
            CompoundTag o = (CompoundTag) element;
            if (o.hasUUID("player")) {
                owed.add(new Payout(o.getUUID("player"), o.hasUUID("team") ? Optional.of(o.getUUID("team")) : Optional.empty(),
                        o.getLong("amount")));
            }
        }
    }

    ListTag write() {
        ListTag list = new ListTag();
        for (Payout payout : owed) {
            CompoundTag o = new CompoundTag();
            o.putUUID("player", payout.player());
            payout.team().ifPresent(id -> o.putUUID("team", id));
            o.putLong("amount", payout.amount());
            list.add(o);
        }
        return list;
    }
}
