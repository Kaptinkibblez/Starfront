package com.starwake.machine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

/**
 * A power cable (M8b): connects to other cables and to machines on any side. What it can carry
 * (kW) comes from its {@link MachineSpec} {@code rating}; a network carries its weakest cable's
 * rating. Copper is the starter cable, aluminium the high-voltage line.
 */
public class CableBlock extends PipeBlock {
	public CableBlock(float size, Properties properties) {
		super(size, properties);
		BlockState state = stateDefinition.any();
		for (var p : PROPERTY_BY_DIRECTION.values()) state = state.setValue(p, false);
		registerDefaultState(state);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(NORTH, EAST, SOUTH, WEST, UP, DOWN);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		BlockState state = defaultBlockState();
		for (Direction d : Direction.values()) {
			state = state.setValue(PROPERTY_BY_DIRECTION.get(d), connectsTo(context.getLevel(), context.getClickedPos().relative(d), d));
		}
		return state;
	}

	@Override
	protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
			BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
		return state.setValue(PROPERTY_BY_DIRECTION.get(direction), connectsTo(level, neighbourPos, direction));
	}

	static boolean connectsTo(BlockGetter level, BlockPos pos, Direction from) {
		if (level.getBlockState(pos).getBlock() instanceof CableBlock) return true;
		return level.getBlockEntity(pos) instanceof EnergyNode node && node.connectsEnergy(from.getOpposite());
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		if (!oldState.is(state.getBlock())) PowerNetworks.invalidate(level, pos);
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		PowerNetworks.invalidate(level, pos);
	}
}
