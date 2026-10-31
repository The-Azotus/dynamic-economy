package dynamiceconomy.menu;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.MarketAccess;
import dynamiceconomy.block.MarketBlocks;
import dynamiceconomy.coin.Coins;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;

/**
 * The market screen's server-side half. It has no slots: all market data travels in our own packets. Its job is to
 * mark which players have the market open, so trade packets from anyone else are ignored and updates go only to
 * viewers. Opened from a terminal it stays valid only while the player is near that terminal; opened by command it
 * stays valid only while the access mode allows that player to use commands.
 * <p>
 * A Bank Card opens it bank-only: just the Bank tab, valid while the player still carries a card, and no trades.
 */
public final class MarketMenu extends AbstractContainerMenu {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, DynamicEconomy.MOD_ID);
    public static final DeferredHolder<MenuType<?>, MenuType<MarketMenu>> TYPE =
            MENUS.register("market", () -> IMenuTypeExtension.create(MarketMenu::fromNetwork));

    /** The terminal this was opened from, or {@link ContainerLevelAccess#NULL} if opened by command (or on the client). */
    private final ContainerLevelAccess terminal;
    private final boolean bankOnly;

    private MarketMenu(int containerId, ContainerLevelAccess terminal, boolean bankOnly) {
        super(TYPE.get(), containerId);
        this.terminal = terminal;
        this.bankOnly = bankOnly;
    }

    /** The client's half, told by the server whether it is bank-only. */
    private static MarketMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf data) {
        return new MarketMenu(containerId, ContainerLevelAccess.NULL, data.readBoolean());
    }

    /** Opens the market by command. */
    public static void open(ServerPlayer player) {
        open(player, new MarketMenu(0, ContainerLevelAccess.NULL, false));
    }

    /** Opens the market from the terminal at {@code pos}. */
    public static void openAtTerminal(ServerPlayer player, BlockPos pos) {
        open(player, forTerminal(player, pos));
    }

    /** Opens just the Bank tab, from a Bank Card. */
    public static void openBank(ServerPlayer player) {
        open(player, forBankCard());
    }

    private static void open(ServerPlayer player, MarketMenu template) {
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new MarketMenu(id, template.terminal, template.bankOnly),
                Component.translatable("screen.dynamic_economy.market")), buf -> buf.writeBoolean(template.bankOnly));
    }

    /** A server-side menu for the terminal at {@code pos}, without opening a screen. For tests. */
    public static MarketMenu forTerminal(ServerPlayer player, BlockPos pos) {
        return new MarketMenu(0, ContainerLevelAccess.create(player.level(), pos), false);
    }

    /** A server-side menu as {@code /market} opens it, without opening a screen. For tests. */
    public static MarketMenu forCommand() {
        return new MarketMenu(0, ContainerLevelAccess.NULL, false);
    }

    /** A server-side menu as a Bank Card opens it, without opening a screen. For tests. */
    public static MarketMenu forBankCard() {
        return new MarketMenu(0, ContainerLevelAccess.NULL, true);
    }

    /** Whether only the Bank tab is open: no trading. */
    public boolean bankOnly() {
        return bankOnly;
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
        if (bankOnly) {
            return carriesBankCard(player);
        }
        if (terminal != ContainerLevelAccess.NULL) {
            // Vanilla's container rule: the block must still be there and the player within 8 blocks of it.
            return stillValid(terminal, player, MarketBlocks.MARKET_TERMINAL.get());
        }
        return MarketAccess.commandsAllowed(player);
    }

    /** Anywhere in the inventory: main, armor or offhand. */
    private static boolean carriesBankCard(Player player) {
        Inventory inventory = player.getInventory();
        for (List<ItemStack> compartment : List.of(inventory.items, inventory.armor, inventory.offhand)) {
            for (ItemStack stack : compartment) {
                if (stack.is(Coins.BANK_CARD.get())) {
                    return true;
                }
            }
        }
        return false;
    }
}
