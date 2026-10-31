package dynamiceconomy.coin;

import dynamiceconomy.menu.MarketMenu;
import dynamiceconomy.network.MarketSync;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Opens the market screen's Bank tab from anywhere, to deposit and withdraw coins. It can't trade. */
public final class BankCardItem extends Item {
    public BankCardItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            MarketMenu.openBank(serverPlayer);
            MarketSync.sendFullState(serverPlayer);
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }
}
