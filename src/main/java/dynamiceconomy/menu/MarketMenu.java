package dynamiceconomy.menu;

import dynamiceconomy.DynamicEconomy;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The market screen's server-side half. It has no slots: all market data travels in our own packets. Its job is to
 * mark which players have the market open, so trade packets from anyone else are ignored and updates go only to
 * viewers.
 */
public final class MarketMenu extends AbstractContainerMenu {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, DynamicEconomy.MOD_ID);
    public static final DeferredHolder<MenuType<?>, MenuType<MarketMenu>> TYPE =
            MENUS.register("market", () -> new MenuType<>(MarketMenu::new, FeatureFlags.DEFAULT_FLAGS));

    public MarketMenu(int containerId, Inventory inventory) {
        super(TYPE.get(), containerId);
    }

    public static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new MarketMenu(id, inventory),
                Component.translatable("screen.dynamic_economy.market")));
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        DynamicEconomy.LOGGER.debug("Market closed for {} ({} side)", player.getGameProfile().getName(),
                player.level().isClientSide ? "client" : "server");
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    /** Opened by command, so there is no block to stay near. A terminal block would add a distance check here. */
    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
