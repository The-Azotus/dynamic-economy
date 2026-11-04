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
import java.util.function.Predicate;

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
    /** The server tick before which a payment that failed isn't tried again. Not saved: a restart tries at once. */
    private long retryAt;

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

    /** Whether payments may be tried at server tick {@code tick}. */
    public boolean retryDue(long tick) {
        return tick >= retryAt;
    }

    /** Waits until server tick {@code tick} before trying again, after a payment failed. */
    public void retryAt(long tick) {
        retryAt = tick;
    }

    public boolean isEmpty() {
        return owed.isEmpty();
    }

    public int size() {
        return owed.size();
    }

    /**
     * What each player's own account is owed. Money for a team account isn't included, as team accounts aren't
     * ranked; {@code paysTeam} says whether a payout's team still takes it (see {@code PayoutService.paysTeam}).
     */
    public Map<UUID, Long> forPlayers(Predicate<Optional<UUID>> paysTeam) {
        Map<UUID, Long> held = new LinkedHashMap<>();
        for (Payout payout : owed) {
            if (!paysTeam.test(payout.team())) {
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
