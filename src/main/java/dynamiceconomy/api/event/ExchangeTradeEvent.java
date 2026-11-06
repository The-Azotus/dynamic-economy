package dynamiceconomy.api.event;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;

import java.util.UUID;

/**
 * Two players traded on the player exchange. Posted once for every fill of an order (an order that fills against
 * three others posts three) and once for every purchase from a listing, after the money and items have moved.
 * <p>
 * The seller has been paid {@code value − tax} (or is owed it, if the bank couldn't be reached); the tax is destroyed.
 * The bought units are in the buyer's collection box. Either player may be offline.
 * <p>
 * Posted by Dynamic-Economy; add-ons only listen for it.
 */
public final class ExchangeTradeEvent extends Event {
    private final MinecraftServer server;
    private final UUID buyer;
    private final UUID seller;
    private final ItemStack item;
    private final int quantity;
    private final long unitPrice;
    private final long value;
    private final long tax;
    private final boolean fromListing;

    /** Posted by Dynamic-Economy. {@code item} is copied, with a count of 1. */
    public ExchangeTradeEvent(MinecraftServer server, UUID buyer, UUID seller, ItemStack item, int quantity, long unitPrice,
                              long value, long tax, boolean fromListing) {
        this.server = server;
        this.buyer = buyer;
        this.seller = seller;
        this.item = item.copyWithCount(1);
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.value = value;
        this.tax = tax;
        this.fromListing = fromListing;
    }

    /** The server the trade happened on. */
    public MinecraftServer getServer() {
        return server;
    }

    /** The player who bought. */
    public UUID getBuyer() {
        return buyer;
    }

    /** The player who sold. */
    public UUID getSeller() {
        return seller;
    }

    /**
     * One unit of what was traded, as a new copy each call. A fresh copy of the item for an order fill; for a listing,
     * the listed item with all its components.
     */
    public ItemStack getItem() {
        return item.copy();
    }

    /** How many units changed hands. */
    public int getQuantity() {
        return quantity;
    }

    /** The price per unit in bits: the waiting order's price for an order fill, the listing's price for a listing. */
    public long getUnitPrice() {
        return unitPrice;
    }

    /** What the buyer paid in bits: {@code unitPrice × quantity}. */
    public long getValue() {
        return value;
    }

    /** The exchange tax taken from the seller's takings in bits, which is destroyed. */
    public long getTax() {
        return tax;
    }

    /** {@code true} for a purchase from a listing, {@code false} for two orders on the book filling each other. */
    public boolean isFromListing() {
        return fromListing;
    }
}
