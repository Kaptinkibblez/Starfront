package com.starwake.ship.block;

import com.starwake.ship.panel.PanelGeometry;
import com.starwake.ship.panel.PanelShape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Hull Panel (M9 pass 3): one block, many shapes. A panel keeps its alloy like {@link HullPlatingBlock} (mass, heat
 * limit and armour follow it; mass also scales with how much of the cube the shape fills) and takes any of the
 * {@link PanelShape}s in any of the 24 orientations. Placing it points the shape's up out of the clicked face and its
 * front toward you; the Hull Tool cycles the shape and turns it.
 */
public class HullPanelBlock extends HullPlatingBlock {
	public static final EnumProperty<PanelShape> SHAPE = EnumProperty.create("shape", PanelShape.class);
	public static final IntegerProperty ORIENT = IntegerProperty.create("orient", 0, PanelGeometry.ORIENTATIONS - 1);

	public HullPanelBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(SHAPE, PanelShape.SLOPE).setValue(ORIENT, PanelGeometry.orient(Direction.UP, Direction.NORTH)));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(SHAPE, ORIENT);
	}

	@Override
	public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
		Direction up = context.getClickedFace();
		Direction front = null;
		for (Direction d : context.getNearestLookingDirections()) {
			if (d.getAxis() != up.getAxis()) {
				front = d.getOpposite();
				break;
			}
		}
		if (front == null) front = up.getAxis() == Direction.Axis.Y ? Direction.NORTH : Direction.UP;
		return defaultBlockState().setValue(ORIENT, PanelGeometry.orient(up, front));
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return PanelGeometry.outline(state.getValue(SHAPE), state.getValue(ORIENT));
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return PanelGeometry.shape(state.getValue(SHAPE), state.getValue(ORIENT));
	}

	@Override
	protected VoxelShape getOcclusionShape(BlockState state) {
		return PanelGeometry.shape(state.getValue(SHAPE), state.getValue(ORIENT));
	}

	@Override
	protected boolean useShapeForLightOcclusion(BlockState state) {
		return true;
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		int orient = state.getValue(ORIENT);
		Direction up = rotation.rotate(PanelGeometry.up(orient)), front = rotation.rotate(PanelGeometry.front(orient));
		return state.setValue(ORIENT, PanelGeometry.orient(up, front));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		// A true mirror would need the mirrored shape; turning keeps symmetric shapes right and asymmetric ones close.
		return rotate(state, mirror.getRotation(PanelGeometry.front(state.getValue(ORIENT))));
	}

	/** Share of a full block's material a panel of this state holds. */
	public static double volume(BlockState state) {
		return PanelGeometry.volume(state.getValue(SHAPE));
	}
}
