/**
 * The add-on API: the only part of Dynamic-Economy other mods should call. Start at {@link dynamiceconomy.api.DynamicEconomyApi}.
 * <p>
 * It lets an add-on read market prices, trade for a player, read, take and pay money, hide items from players, and
 * listen for what happened (the {@code dynamiceconomy.api.event} package). Every call keeps the market's own rules:
 * every check happens before anything changes, money moves only through the world's bank, and coins are never
 * listed. Amounts are in bits (1 bit = 1 Numismatics spur, so they mean the same under either bank).
 * <p>
 * <b>Threads.</b> Every method that reads or changes the market or the bank must run on the server thread, and throws
 * {@link java.lang.IllegalStateException} otherwise. Neither bank is synchronized. From a packet handler, use
 * {@code IPayloadContext.enqueueWork}; from another thread, {@code server.execute}. The methods that say they may be
 * called from any thread are the exception.
 * <p>
 * <b>Stability.</b> The API is experimental until 1.0: it may still change in a 0.x minor release, and every change is
 * listed in the add-on guide's changelog ({@code docs/api.md}). Every other package is internal and may change in any
 * release.
 */
@ApiStatus.Experimental
package dynamiceconomy.api;

import org.jetbrains.annotations.ApiStatus;
