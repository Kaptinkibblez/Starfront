package com.starwake.ship.block;

import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/**
 * A thruster. FACING is the direction the nozzle (exhaust) points; it pushes the ship the
 * opposite way. Placed so the nozzle points out of the face you clicked: clicking the
 * underside of the hull gives a downward nozzle, which means lift.
 *
 * Thrust and fuel burn come from ship_stats JSON, not this class, so tiers are just new blocks
 * plus a JSON entry.
 */
public class ThrusterBlock extends DirectionalBlock implements net.minecraft.world.level.block.EntityBlock {
	/** M8e-2: remembers the part's material and quality (see {@link com.starwake.fab.MaterialBlockEntity}). */
	@Override
	public net.minecraft.world.level.block.entity.@org.jspecify.annotations.Nullable BlockEntity newBlockEntity(net.minecraft.core.BlockPos pos, BlockState state) {
		return new com.starwake.fab.MaterialBlockEntity(pos, state);
	}

	public ThrusterBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.DOWN));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getClickedFace());
	}

	/** The direction this thruster pushes the ship. */
	public static Direction pushDirection(BlockState state) {
		return state.getValue(FACING).getOpposite();
	}
}
