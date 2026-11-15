package com.starwake.ship.block;

import com.starwake.ship.panel.GreebleShape;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Greeble (M9 pass 3): surface detail (vents, pipes, conduits, seams, antennas, railings, ladders, catwalks, ribbed panels,
 * hazard trim, cargo racks, tanks, heatsinks). One block with a {@link GreebleShape} and an orientation; the Hull Tool
 * cycles the shape and turns it. Placed like a Hull Panel: mounted on the clicked face, front toward you.
 */
public class GreebleBlock extends Block {
	public static final EnumProperty<GreebleShape> SHAPE = EnumProperty.create("shape", GreebleShape.class);
	public static final IntegerProperty ORIENT = HullPanelBlock.ORIENT;

	public GreebleBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(SHAPE, GreebleShape.VENT).setValue(ORIENT, PanelGeometry.orient(Direction.UP, Direction.NORTH)));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(SHAPE, ORIENT);
	}

	@Override
	public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(ORIENT, PanelGeometry.placement(context));
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
		return state.setValue(ORIENT, PanelGeometry.rotated(state.getValue(ORIENT), rotation));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return rotate(state, mirror.getRotation(PanelGeometry.front(state.getValue(ORIENT))));
	}
}
