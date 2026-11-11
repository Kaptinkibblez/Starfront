package com.starwake.ship.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A wing panel (3i): a thin plate that makes lift and drag in an atmosphere. AXIS is the plate's
 * normal: Y = a horizontal wing (lifts the ship when it flies forward), X or Z = a vertical fin
 * (no lift, but it resists sideslip, so fins at the tail keep the nose pointing into the wind).
 *
 * Placement: click the side of the hull for a horizontal wing; click the top or bottom for a
 * fin lined up with the way you're facing. Aerodynamics live in ShipDynamics.
 */
public class WingBlock extends RotatedPillarBlock {
	private static final VoxelShape HORIZONTAL = Block.box(0, 6, 0, 16, 10, 16);
	private static final VoxelShape FIN_X = Block.box(6, 0, 0, 10, 16, 16);
	private static final VoxelShape FIN_Z = Block.box(0, 0, 6, 16, 16, 10);

	public WingBlock(Properties properties) {
		super(properties);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		Direction face = context.getClickedFace();
		if (face.getAxis().isHorizontal()) return defaultBlockState().setValue(AXIS, Direction.Axis.Y);
		// A fin: plate along the player's facing, so its normal is sideways.
		Direction facing = context.getHorizontalDirection();
		return defaultBlockState().setValue(AXIS, facing.getClockWise().getAxis());
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return switch (state.getValue(AXIS)) {
			case Y -> HORIZONTAL;
			case X -> FIN_X;
			case Z -> FIN_Z;
		};
	}

	/** The plate's normal as a unit step. */
	public static Direction.Axis normal(BlockState state) {
		return state.getValue(AXIS);
	}
}
