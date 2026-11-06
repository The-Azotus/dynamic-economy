/**
 * Events Dynamic-Economy posts on {@link net.neoforged.neoforge.common.NeoForge#EVENT_BUS} so add-ons can react to
 * what happens in the market.
 * <ul>
 *   <li>Every event is posted <em>after</em> its change is complete: money has moved, items have moved and the saved
 *       data is updated. When one action changes several things (a contract board being replaced, an exchange order
 *       filling against several others), its events are posted together after the last change, never in the
 *       middle.</li>
 *   <li>No event is cancellable. To stop a trade before it happens, hide the item with a visibility rule, or decide
 *       in your own code whether to call the API.</li>
 *   <li>Events are posted only for actions that succeeded, and always on the server thread.</li>
 *   <li>A listener that throws is logged and ignored, so it can't undo or interrupt market work. Listeners after it
 *       on the same event may then not run.</li>
 * </ul>
 * Listen as with any NeoForge event, for example
 * {@code NeoForge.EVENT_BUS.addListener((MarketTradeEvent event) -> ...)}.
 */
@ApiStatus.Experimental
package dynamiceconomy.api.event;

import org.jetbrains.annotations.ApiStatus;
