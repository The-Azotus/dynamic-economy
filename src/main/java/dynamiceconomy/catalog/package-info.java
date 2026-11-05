/**
 * The market catalog: loading price files and item classes, merging them with admin edits into the live catalog,
 * and recording where each field of each entry came from.
 * <p>
 * Internal: not part of the add-on API (see {@code dynamiceconomy.api}); it may change in any release.
 */
@ApiStatus.Internal
package dynamiceconomy.catalog;

import org.jetbrains.annotations.ApiStatus;
