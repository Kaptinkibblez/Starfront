package com.starwake.trade.block;

import com.starwake.trade.Terminals;
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
 * A hub terminal (M7): the Commodity Terminal (dynamic market), the Outfitter (gear at set
 * prices) or the Contract Board. The screen faces FACING. Using it opens the matching screen for
 * the hub it stands in ({@link Terminals}); a terminal outside any hub has no market link.
 */
public class TerminalBlock extends HorizontalDirectionalBlock {
	public enum Kind { COMMODITY, OUTFITTER, CONTRACTS }

	private final Kind kind;

	public TerminalBlock(Kind kind, Properties properties) {
		super(properties);
		this.kind = kind;
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	public Kind kind() {
		return kind;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (player instanceof ServerPlayer serverPlayer) Terminals.open(serverPlayer, pos, kind);
		return InteractionResult.SUCCESS;
	}
}
