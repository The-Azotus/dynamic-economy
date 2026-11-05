package dynamiceconomy.api.event;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;

/**
 * The market catalog was rebuilt: at server start, after {@code /reload} or {@code /market admin reload}, after an
 * import or a restore of a backup's edits, and after every in-game admin edit. The new prices are live and have been sent to every
 * player. Listen for it to re-read anything you cached from the catalog, such as the list of listed items. Quotes
 * also change with trades and over time, without this event: ask for one when it's needed.
 * <p>
 * One admin action can post it more than once (an edit the catalog rules refuse is applied, then undone), so treat it
 * as "the catalog may have changed", not as a count of changes.
 * <p>
 * Posted by Dynamic-Economy; add-ons only listen for it.
 */
public final class CatalogReloadedEvent extends Event {
    private final MinecraftServer server;

    /** Posted by Dynamic-Economy. */
    public CatalogReloadedEvent(MinecraftServer server) {
        this.server = server;
    }

    /** The server whose catalog was rebuilt. */
    public MinecraftServer getServer() {
        return server;
    }
}
