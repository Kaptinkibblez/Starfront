package com.starwake.ship.block;

import com.starwake.ship.panel.FormShape;
import com.starwake.ship.panel.PanelGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Hull Form (M9 pass 4): a deformable hull block. Its four top corners (relative to {@code facing}, the side it rises
 * toward) each sit at 0..8 eighths of a block; the Hull Tool raises or lowers a corner, an edge or the whole top, and
 * moves the matching corners of neighbouring forms with it so the surface stays seamless. Smooth curves come from
 * gentle steps across many blocks, sharp ridges and creases from steep ones. Keeps its alloy like Hull Plating (mass
 * scales with how full it is).
 */
public class HullFormBlock extends HullPlatingBlock {
	public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
	public static final IntegerProperty[] CORNER = {
			IntegerProperty.create("h0", 0, FormShape.STEPS), IntegerProperty.create("h1", 0, FormShape.STEPS),
			IntegerProperty.create("h2", 0, FormShape.STEPS), IntegerProperty.create("h3", 0, FormShape.STEPS)};

	public HullFormBlock(Properties properties) {
		super(properties);
		BlockState state = defaultBlockState().setValue(FACING, Direction.UP);
		for (IntegerProperty p : CORNER) state = state.setValue(p, FormShape.STEPS);
		registerDefaultState(state);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, CORNER[0], CORNER[1], CORNER[2], CORNER[3]);
	}

	public static FormShape shape(BlockState state) {
		return new FormShape(state.getValue(CORNER[0]), state.getValue(CORNER[1]), state.getValue(CORNER[2]), state.getValue(CORNER[3]));
	}

	/** Orientation index for the geometry: up = facing, a fixed front. */
	public static int orient(BlockState state) {
		Direction up = state.getValue(FACING);
		return PanelGeometry.orient(up, up.getAxis() == Direction.Axis.Y ? Direction.NORTH : Direction.UP);
	}

	public static double volume(BlockState state) {
		return PanelGeometry.volume(shape(state));
	}

	@Override
	public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getClickedFace());
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return PanelGeometry.outline(shape(state), orient(state));
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return PanelGeometry.shape(shape(state), orient(state));
	}

	@Override
	protected VoxelShape getOcclusionShape(BlockState state) {
		return PanelGeometry.shape(shape(state), orient(state));
	}

	@Override
	protected boolean useShapeForLightOcclusion(BlockState state) {
		return true;
	}

	/**
	 * World column of corner {@code corner}: the model corner's position mapped to block space, as {x, z}-style cell
	 * offsets along the two axes across {@code facing} (each 0 or 1).
	 */
	public static int[] cornerOffset(BlockState state, int corner) {
		double mx = corner == 1 || corner == 2 ? 1 : 0, mz = corner >= 2 ? 1 : 0;
		var p = PanelGeometry.transform(orient(state), mx, 0.5, mz);
		return new int[] {Math.round(p.x), Math.round(p.y), Math.round(p.z)};
	}

	/** The corner index whose column passes through block-space offsets (ox, oy, oz) (the facing axis is ignored). */
	public static int cornerAt(BlockState state, int ox, int oy, int oz) {
		Direction.Axis axis = state.getValue(FACING).getAxis();
		for (int c = 0; c < 4; c++) {
			int[] o = cornerOffset(state, c);
			boolean match = (axis == Direction.Axis.X || o[0] == ox) && (axis == Direction.Axis.Y || o[1] == oy)
					&& (axis == Direction.Axis.Z || o[2] == oz);
			if (match) return c;
		}
		return -1;
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		// Turning a form keeps each corner's world column: map every corner through the rotation.
		BlockState out = state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
		for (int c = 0; c < 4; c++) {
			int[] o = cornerOffset(state, c);
			int[] r = rotateOffset(o, rotation);
			int target = cornerAt(out, r[0], r[1], r[2]);
			if (target >= 0) out = out.setValue(CORNER[target], state.getValue(CORNER[c]));
		}
		return out;
	}

	private static int[] rotateOffset(int[] o, Rotation rotation) {
		int x = o[0] * 2 - 1, z = o[2] * 2 - 1; // to -1/+1 about the block centre
		int rx = x, rz = z;
		switch (rotation) {
			case CLOCKWISE_90 -> { rx = -z; rz = x; }
			case CLOCKWISE_180 -> { rx = -x; rz = -z; }
			case COUNTERCLOCKWISE_90 -> { rx = z; rz = -x; }
			default -> { }
		}
		return new int[] {(rx + 1) / 2, o[1], (rz + 1) / 2};
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}
}
