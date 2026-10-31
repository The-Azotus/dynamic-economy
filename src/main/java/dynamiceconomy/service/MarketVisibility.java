package dynamiceconomy.service;

import dynamiceconomy.compat.StagesBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiPredicate;

/**
 * Which listed items a player may see and trade. Prices and stock are the same for everyone; only visibility differs.
 * A hidden item is left out of everything sent to that player, and trading it is rejected as {@code NOT_LISTED} so the
 * reply doesn't reveal that it exists. Server thread only.
 */
public final class MarketVisibility {
    /** Set by GameTests to lock items without ProgressiveStages; null otherwise. */
    @Nullable
    private static BiPredicate<ServerPlayer, Item> testLocks;

    private MarketVisibility() {
    }

    public static boolean hidden(ServerPlayer player, Item item) {
        BiPredicate<ServerPlayer, Item> locks = testLocks;
        return locks != null ? locks.test(player, item) : StagesBridge.isLocked(player, item);
    }

    /** Whether anything can be hidden from anyone. When not, every player is sent the same catalog. */
    public static boolean active() {
        return testLocks != null || StagesBridge.isAvailable();
    }

    /** GameTests only: hide items by this rule instead of ProgressiveStages' locks. Null goes back to normal. */
    public static void setTestLocks(@Nullable BiPredicate<ServerPlayer, Item> locks) {
        testLocks = locks;
    }
}
