package dynamiceconomy.block;

import com.mojang.serialization.MapCodec;
import dynamiceconomy.menu.MarketMenu;
import dynamiceconomy.network.MarketSync;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A block that opens the market when used. It works in every access mode; in block-only mode it's the only way for
 * players without operator permission to reach the market. The screen closes when the player walks away or the
 * terminal is broken.
 */
public final class MarketTerminalBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<MarketTerminalBlock> CODEC = simpleCodec(MarketTerminalBlock::new);

    public MarketTerminalBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** The screen faces whoever placed it. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            MarketMenu.openAtTerminal(serverPlayer, pos);
            MarketSync.sendFullState(serverPlayer);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
