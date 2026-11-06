/**
 * Bridges to optional mods (Numismatics, Create, FTB Teams, ProgressiveStages) and readers for other mods' machine
 * recipes. Each bridge is the only class allowed to touch its mod.
 * <p>
 * Internal: not part of the add-on API (see {@code dynamiceconomy.api}); it may change in any release.
 */
@ApiStatus.Internal
package dynamiceconomy.compat;

import org.jetbrains.annotations.ApiStatus;
