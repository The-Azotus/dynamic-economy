package dynamiceconomy.api.event;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.Event;

/**
 * A player sold items to the market or bought items from it. Posted after every market trade, whether it came from
 * the market screen, a command or the API, once the items, stock and money have all moved.
 * <p>
 * Posted by Dynamic-Economy; add-ons only listen for it.
 */
public final class MarketTradeEvent extends Event {
    private final MinecraftServer server;
    private final ServerPlayer player;
    private final Item item;
    private final boolean buy;
    private final int quantity;
    private final long unitPrice;
    private final long total;

    /** Posted by Dynamic-Economy. The server is the player's. */
    public MarketTradeEvent(ServerPlayer player, Item item, boolean buy, int quantity, long unitPrice, long total) {
        this.server = player.server;
        this.player = player;
        this.item = item;
        this.buy = buy;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.total = total;
    }

    /** The server the trade happened on. */
    public MinecraftServer getServer() {
        return server;
    }

    /** The player who traded. */
    public ServerPlayer getPlayer() {
        return player;
    }

    /** The item traded. Only fresh copies trade with the market, so the item says all there is to say. */
    public Item getItem() {
        return item;
    }

    /** {@code true} if the player bought from the market, {@code false} if they sold to it. */
    public boolean isBuy() {
        return buy;
    }

    /** How many units were traded. Always at least 1. */
    public int getQuantity() {
        return quantity;
    }

    /**
     * What the first unit traded at, in bits. Later units in the same trade can differ, since each unit moves the
     * price; {@link #getTotal()} is what actually changed hands.
     */
    public long getUnitPrice() {
        return unitPrice;
    }

    /** The whole trade's price in bits: what the player was paid when selling, or charged when buying. */
    public long getTotal() {
        return total;
    }
}
