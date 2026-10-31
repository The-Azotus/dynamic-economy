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
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A block that opens the market when used. It works in every access mode; in block-only mode it's the only way for
 * players without operator permission to reach the market. The screen closes when the player walks away or the
 * terminal is broken. It looks like a table with coins on it.
 */
public final class MarketTerminalBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<MarketTerminalBlock> CODEC = simpleCodec(MarketTerminalBlock::new);
    /** Table top and four legs. The table is symmetric, so one shape serves every facing; the coins aren't solid. */
    private static final VoxelShape SHAPE = Shapes.or(
            box(0, 12, 0, 16, 14, 16),
            box(1, 0, 1, 3, 12, 3), box(13, 0, 1, 15, 12, 3),
            box(1, 0, 13, 3, 12, 15), box(13, 0, 13, 15, 12, 15));

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

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    /** The coins face whoever placed it. */
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
