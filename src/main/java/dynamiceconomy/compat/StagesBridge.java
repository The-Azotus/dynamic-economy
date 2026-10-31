package dynamiceconomy.compat;

import com.enviouse.progressivestages.common.api.StageActorChangeEvent;
import com.enviouse.progressivestages.common.api.StageChangeEvent;
import com.enviouse.progressivestages.common.api.StagesBulkChangedEvent;
import com.enviouse.progressivestages.common.lock.LockRegistry;
import dynamiceconomy.DynamicEconomy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * The only class allowed to import ProgressiveStages, an optional dependency. Without the mod nothing is locked and
 * nothing here touches its classes: they're only referenced from {@link Present}, which loads only when the mod does.
 * <p>
 * "Locked" is ProgressiveStages' own item lock: the player is missing a stage the item requires. It deliberately
 * ignores creative bypass, per-category enforcement switches and structure locks, which say whether an item can be
 * <em>used</em> right now, not whether the player has reached it.
 * <p>
 * Checked against the 3.1.1 jar. The lock check lives outside ProgressiveStages' {@code api} package, so it is probed
 * by reflection once; if it doesn't match, or fails later, the market logs an error and hides nothing.
 * Server thread only.
 */
public final class StagesBridge {
    public static final String MOD_ID = "progressivestages";
    private static final boolean AVAILABLE = ModList.get().isLoaded(MOD_ID) && Present.probe();
    /** Set when a lock check throws; from then on nothing is hidden, rather than failing every trade. */
    private static boolean failed;

    private StagesBridge() {
    }

    /** Whether ProgressiveStages is loaded and its lock API matches, so items can be locked at all. */
    public static boolean isAvailable() {
        return AVAILABLE && !failed;
    }

    /** Whether {@code item} is locked for {@code player}: they lack a stage it needs. Always false without the mod. */
    public static boolean isLocked(ServerPlayer player, Item item) {
        if (!isAvailable()) {
            return false;
        }
        try {
            return Present.isLocked(player, item);
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            DynamicEconomy.LOGGER.error("ProgressiveStages lock check failed; the market will no longer hide locked items", e);
            return false;
        }
    }

    /**
     * Calls {@code onChange} whenever a player's stages may have changed: with that player, or with null when it may
     * be anyone (a team's stages changed). Does nothing without the mod.
     */
    public static void listen(Consumer<@Nullable ServerPlayer> onChange) {
        if (AVAILABLE) {
            Present.listen(onChange);
        }
    }

    /** Everything that touches ProgressiveStages classes. */
    private static final class Present {
        static boolean probe() {
            try {
                requireReturn(LockRegistry.class.getMethod("getInstance").getReturnType(), LockRegistry.class);
                requireReturn(LockRegistry.class.getMethod("isItemBlockedFor", ServerPlayer.class, Item.class).getReturnType(),
                        boolean.class);
                DynamicEconomy.LOGGER.info("ProgressiveStages found: items locked for a player are hidden from them in the market");
                return true;
            } catch (ReflectiveOperationException | LinkageError e) {
                DynamicEconomy.LOGGER.error("ProgressiveStages is installed but its item lock API does not match what this mod "
                        + "was built against (3.1.1); the market will not hide locked items", e);
                return false;
            }
        }

        static boolean isLocked(ServerPlayer player, Item item) {
            LockRegistry locks = LockRegistry.getInstance();
            return locks != null && locks.isItemBlockedFor(player, item);
        }

        static void listen(Consumer<@Nullable ServerPlayer> onChange) {
            NeoForge.EVENT_BUS.addListener((StageChangeEvent event) -> onChange.accept(event.getPlayer()));
            NeoForge.EVENT_BUS.addListener((StagesBulkChangedEvent event) -> onChange.accept(event.getPlayer()));
            // An actor can be a team; its members aren't named, so treat it as anyone.
            NeoForge.EVENT_BUS.addListener((StageActorChangeEvent event) -> onChange.accept(null));
        }

        private static void requireReturn(Class<?> actual, Class<?> expected) throws NoSuchMethodException {
            if (actual != expected) {
                throw new NoSuchMethodException("expected " + expected.getName() + " but found " + actual.getName());
            }
        }
    }
}
