package dynamiceconomy.menu;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.MarketAccess;
import dynamiceconomy.block.MarketBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The market screen's server-side half. It has no slots: all market data travels in our own packets. Its job is to
 * mark which players have the market open, so trade packets from anyone else are ignored and updates go only to
 * viewers. Opened from a terminal it stays valid only while the player is near that terminal; opened by command it
 * stays valid only while the access mode allows that player to use commands.
 */
public final class MarketMenu extends AbstractContainerMenu {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, DynamicEconomy.MOD_ID);
    public static final DeferredHolder<MenuType<?>, MenuType<MarketMenu>> TYPE =
            MENUS.register("market", () -> new MenuType<>(MarketMenu::new, FeatureFlags.DEFAULT_FLAGS));

    /** The terminal this was opened from, or {@link ContainerLevelAccess#NULL} if opened by command (or on the client). */
    private final ContainerLevelAccess terminal;

    public MarketMenu(int containerId, Inventory inventory) {
        this(containerId, ContainerLevelAccess.NULL);
    }

    private MarketMenu(int containerId, ContainerLevelAccess terminal) {
        super(TYPE.get(), containerId);
        this.terminal = terminal;
    }

    /** Opens the market by command. */
    public static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new MarketMenu(id, ContainerLevelAccess.NULL),
                Component.translatable("screen.dynamic_economy.market")));
    }

    /** Opens the market from the terminal at {@code pos}. */
    public static void openAtTerminal(ServerPlayer player, BlockPos pos) {
        ContainerLevelAccess access = ContainerLevelAccess.create(player.level(), pos);
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new MarketMenu(id, access),
                Component.translatable("screen.dynamic_economy.market")));
    }

    /** A server-side menu for the terminal at {@code pos}, without opening a screen. For tests. */
    public static MarketMenu forTerminal(ServerPlayer player, BlockPos pos) {
        return new MarketMenu(0, ContainerLevelAccess.create(player.level(), pos));
    }

    /** A server-side menu as {@code /market} opens it, without opening a screen. For tests. */
    public static MarketMenu forCommand() {
        return new MarketMenu(0, ContainerLevelAccess.NULL);
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

    @Override
    public boolean stillValid(Player player) {
        if (terminal != ContainerLevelAccess.NULL) {
            // Vanilla's container rule: the block must still be there and the player within 8 blocks of it.
            return stillValid(terminal, player, MarketBlocks.MARKET_TERMINAL.get());
        }
        return MarketAccess.commandsAllowed(player);
    }
}
