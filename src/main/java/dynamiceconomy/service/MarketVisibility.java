package dynamiceconomy.service;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.compat.StagesBridge;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * Which listed items a player may see and trade. Prices and stock are the same for everyone; only visibility differs.
 * A hidden item is left out of everything sent to that player, and trading it is rejected as {@code NOT_LISTED} so the
 * reply doesn't reveal that it exists. An item is hidden when ProgressiveStages locks it (or the GameTests' locks do)
 * or when any add-on rule says so.
 * <p>
 * {@link #hidden} and {@link #active} run on the server thread. Add-on rules may be added and removed from any thread,
 * including while mods load.
 */
public final class MarketVisibility {
    /** Set by GameTests to lock items without ProgressiveStages; null otherwise. */
    @Nullable
    private static BiPredicate<ServerPlayer, Item> testLocks;
    /** Add-on rules by ID, each returning true for an item it hides from a player. */
    private static final Map<ResourceLocation, BiPredicate<ServerPlayer, Item>> rules = new ConcurrentHashMap<>();

    private MarketVisibility() {
    }

    public static boolean hidden(ServerPlayer player, Item item) {
        BiPredicate<ServerPlayer, Item> locks = testLocks;
        if (locks != null ? locks.test(player, item) : StagesBridge.isLocked(player, item)) {
            return true;
        }
        if (rules.isEmpty()) {
            return false;
        }
        for (Map.Entry<ResourceLocation, BiPredicate<ServerPlayer, Item>> rule : rules.entrySet()) {
            try {
                if (rule.getValue().test(player, item)) {
                    return true;
                }
            } catch (RuntimeException | LinkageError e) {
                // Removed only if it's still the rule that threw, so the error is logged once per rule.
                if (rules.remove(rule.getKey(), rule.getValue())) {
                    DynamicEconomy.LOGGER.error("Market visibility rule {} threw an exception and was removed; "
                            + "the items it hid are shown again", rule.getKey(), e);
                }
            }
        }
        return false;
    }

    /** Whether anything can be hidden from anyone. When not, every player is sent the same catalog. */
    public static boolean active() {
        return testLocks != null || StagesBridge.isAvailable() || !rules.isEmpty();
    }

    /** Adds an add-on rule, replacing any rule with the same ID. Any thread. The caller resends catalogs. */
    public static void addRule(ResourceLocation id, BiPredicate<ServerPlayer, Item> hides) {
        rules.put(Objects.requireNonNull(id, "id"), Objects.requireNonNull(hides, "hides"));
    }

    /** Removes an add-on rule; returns whether there was one. Any thread. The caller resends catalogs. */
    public static boolean removeRule(ResourceLocation id) {
        return rules.remove(Objects.requireNonNull(id, "id")) != null;
    }

    /** GameTests only: hide items by this rule instead of ProgressiveStages' locks. Null goes back to normal. */
    public static void setTestLocks(@Nullable BiPredicate<ServerPlayer, Item> locks) {
        testLocks = locks;
    }
}
