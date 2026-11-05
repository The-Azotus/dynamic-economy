package dynamiceconomy.api.event;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;

import java.util.UUID;

/**
 * Something happened to a contract held by a player. Listen for one of the nested events, or for this class to hear
 * them all. Each is posted once the action is complete, money included.
 * <p>
 * Money paid to the holder goes to the account the deposit came from (their team's while it exists). If the bank
 * can't be reached when a contract expires, the money is owed and paid later; the event is posted either way.
 * <p>
 * Posted by Dynamic-Economy; add-ons only listen for them. The class isn't abstract only so that a listener can be
 * registered for it (NeoForge refuses abstract event classes); it is only ever posted as one of the nested events.
 */
public class ContractEvent extends Event {
    private final MinecraftServer server;
    private final int contractId;
    private final UUID holder;
    private final ResourceLocation item;
    private final int units;
    private final int delivered;

    protected ContractEvent(MinecraftServer server, int contractId, UUID holder, ResourceLocation item, int units, int delivered) {
        this.server = server;
        this.contractId = contractId;
        this.holder = holder;
        this.item = item;
        this.units = units;
        this.delivered = delivered;
    }

    /** The server the contract is on. */
    public MinecraftServer getServer() {
        return server;
    }

    /** The contract's ID on the board, as {@code /market contracts} shows it. */
    public int getContractId() {
        return contractId;
    }

    /** The player who holds (or held) the contract. They may be offline when it expires. */
    public UUID getHolder() {
        return holder;
    }

    /**
     * The ID of the item the contract asks for. An ID rather than an {@code Item}, since a contract can outlive the
     * mod that adds its item.
     */
    public ResourceLocation getItem() {
        return item;
    }

    /** How many units the contract asks for in all. */
    public int getUnits() {
        return units;
    }

    /** How many units have been delivered in all, counting this action. */
    public int getDelivered() {
        return delivered;
    }

    /** A player claimed an open contract and paid its deposit. */
    public static final class Claimed extends ContractEvent {
        private final long deposit;

        /** Posted by Dynamic-Economy. */
        public Claimed(MinecraftServer server, int contractId, UUID holder, ResourceLocation item, int units, int delivered,
                       long deposit) {
            super(server, contractId, holder, item, units, delivered);
            this.deposit = deposit;
        }

        /** The deposit taken, in bits. */
        public long getDeposit() {
            return deposit;
        }
    }

    /**
     * The holder delivered units. Posted for every delivery, the last one included; when the last one completes the
     * contract, {@link Completed} follows it.
     */
    public static final class Delivered extends ContractEvent {
        private final int quantity;

        /** Posted by Dynamic-Economy. */
        public Delivered(MinecraftServer server, int contractId, UUID holder, ResourceLocation item, int units, int delivered,
                         int quantity) {
            super(server, contractId, holder, item, units, delivered);
            this.quantity = quantity;
        }

        /** How many units this delivery brought. */
        public int getQuantity() {
            return quantity;
        }
    }

    /** The last unit was delivered: the reward was paid and the deposit returned. Posted after {@link Delivered}. */
    public static final class Completed extends ContractEvent {
        private final long reward;
        private final long deposit;

        /** Posted by Dynamic-Economy. */
        public Completed(MinecraftServer server, int contractId, UUID holder, ResourceLocation item, int units, int delivered,
                         long reward, long deposit) {
            super(server, contractId, holder, item, units, delivered);
            this.reward = reward;
            this.deposit = deposit;
        }

        /** The reward paid, in bits, not counting the deposit. */
        public long getReward() {
            return reward;
        }

        /** The deposit returned, in bits. */
        public long getDeposit() {
            return deposit;
        }
    }

    /** The holder gave the contract up: the units delivered so far were paid for, and the deposit is lost. */
    public static final class Abandoned extends ContractEvent {
        private final long paid;
        private final long depositLost;

        /** Posted by Dynamic-Economy. */
        public Abandoned(MinecraftServer server, int contractId, UUID holder, ResourceLocation item, int units, int delivered,
                         long paid, long depositLost) {
            super(server, contractId, holder, item, units, delivered);
            this.paid = paid;
            this.depositLost = depositLost;
        }

        /** What the units delivered so far paid, in bits; 0 if none were delivered. */
        public long getPaid() {
            return paid;
        }

        /** The deposit lost, in bits. */
        public long getDepositLost() {
            return depositLost;
        }
    }

    /**
     * A held contract ended unfinished because the board was replaced (its time ran out, or an admin replaced it) or
     * contracts were turned off. The units delivered so far were paid for. The deposit is lost when the board was
     * replaced, and returned when contracts were turned off, since that wasn't the holder's doing.
     * <p>
     * Every contract a board replacement ends gets one, posted together once the new board is up.
     */
    public static final class Expired extends ContractEvent {
        private final long paid;
        private final long depositLost;
        private final boolean depositReturned;

        /** Posted by Dynamic-Economy. */
        public Expired(MinecraftServer server, int contractId, UUID holder, ResourceLocation item, int units, int delivered,
                       long paid, long depositLost, boolean depositReturned) {
            super(server, contractId, holder, item, units, delivered);
            this.paid = paid;
            this.depositLost = depositLost;
            this.depositReturned = depositReturned;
        }

        /** What the units delivered paid, in bits; 0 if none were delivered. Not counting a returned deposit. */
        public long getPaid() {
            return paid;
        }

        /** The deposit lost, in bits; 0 when it was returned. */
        public long getDepositLost() {
            return depositLost;
        }

        /** Whether the deposit was returned, which happens when contracts were turned off. */
        public boolean isDepositReturned() {
            return depositReturned;
        }
    }
}
