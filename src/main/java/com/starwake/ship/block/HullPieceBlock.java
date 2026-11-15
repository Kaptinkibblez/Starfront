package com.starwake.ship.block;

import com.starwake.ship.panel.HullPartShapes;
import com.starwake.ship.panel.PanelGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * The simpler hull pieces (M9 pass 4), all keeping their alloy: Hull Plate and Half Plate (a 4- or 8-sixteenths plate on
 * the clicked face), Hull Pillar (a round column along the axis you place it on) and Hull Frame (a truss that grows arms
 * toward neighbouring frames and other hull blocks; alone it's an open cube).
 */
public abstract class HullPieceBlock extends HullPlatingBlock implements ShapedBlock {
	protected HullPieceBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return PanelGeometry.outline(shapeOf(state), orientOf(state));
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return PanelGeometry.shape(shapeOf(state), orientOf(state));
	}

	@Override
	protected VoxelShape getOcclusionShape(BlockState state) {
		return PanelGeometry.shape(shapeOf(state), orientOf(state));
	}

	@Override
	protected boolean useShapeForLightOcclusion(BlockState state) {
		return true;
	}

	/** A plate on the clicked face. */
	public static class Plate extends HullPieceBlock {
		private final HullPartShapes.Plate shape;

		public Plate(int sixteenths, Properties properties) {
			super(properties);
			this.shape = new HullPartShapes.Plate(sixteenths);
			registerDefaultState(defaultBlockState().setValue(ORIENT, PanelGeometry.orient(Direction.UP, Direction.NORTH)));
		}

		@Override
		protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(ORIENT);
		}

		@Override
		public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
			return defaultBlockState().setValue(ORIENT, PanelGeometry.placement(context));
		}

		@Override
		public PanelGeometry.Shape shapeOf(BlockState state) {
			return shape;
		}

		@Override
		public int orientOf(BlockState state) {
			return state.getValue(ORIENT);
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

	/** A round column. */
	public static class Pillar extends HullPieceBlock {
		public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.AXIS;
		private static final HullPartShapes.Pillar SHAPE = new HullPartShapes.Pillar();

		public Pillar(Properties properties) {
			super(properties);
			registerDefaultState(defaultBlockState().setValue(AXIS, Direction.Axis.Y));
		}

		@Override
		protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(AXIS);
		}

		@Override
		public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
			return defaultBlockState().setValue(AXIS, context.getClickedFace().getAxis());
		}

		@Override
		public PanelGeometry.Shape shapeOf(BlockState state) {
			return SHAPE;
		}

		@Override
		public int orientOf(BlockState state) {
			return switch (state.getValue(AXIS)) {
				case X -> PanelGeometry.orient(Direction.EAST, Direction.UP);
				case Y -> PanelGeometry.orient(Direction.UP, Direction.NORTH);
				case Z -> PanelGeometry.orient(Direction.SOUTH, Direction.UP);
			};
		}

		@Override
		public boolean seals(BlockState state) {
			return false;
		}

		@Override
		protected BlockState rotate(BlockState state, Rotation rotation) {
			Direction.Axis axis = state.getValue(AXIS);
			if (axis == Direction.Axis.Y || rotation == Rotation.NONE || rotation == Rotation.CLOCKWISE_180) return state;
			return state.setValue(AXIS, axis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X);
		}
	}

	/** A truss that connects to neighbouring hull blocks. */
	public static class Frame extends HullPieceBlock {
		public Frame(Properties properties) {
			super(properties);
			BlockState s = defaultBlockState();
			for (BooleanProperty p : PipeBlock.PROPERTY_BY_DIRECTION.values()) s = s.setValue(p, false);
			registerDefaultState(s);
		}

		@Override
		protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(PipeBlock.NORTH, PipeBlock.EAST, PipeBlock.SOUTH, PipeBlock.WEST, PipeBlock.UP, PipeBlock.DOWN);
		}

		private BlockState connect(BlockState state, BlockGetter level, BlockPos pos) {
			for (Direction d : Direction.values()) {
				BlockState n = level.getBlockState(pos.relative(d));
				boolean link = n.is(this) || n.getBlock() instanceof HullPlatingBlock || n.isFaceSturdy(level, pos.relative(d), d.getOpposite());
				state = state.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(d), link);
			}
			return state;
		}

		@Override
		public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
			return connect(defaultBlockState(), context.getLevel(), context.getClickedPos());
		}

		@Override
		protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
				BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
			return connect(state, level, pos);
		}

		@Override
		public PanelGeometry.Shape shapeOf(BlockState state) {
			int mask = 0;
			for (Direction d : Direction.values()) if (state.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(d))) mask |= 1 << d.ordinal();
			return new HullPartShapes.Frame(mask);
		}

		@Override
		public int orientOf(BlockState state) {
			return PanelGeometry.orient(Direction.UP, Direction.NORTH); // the identity: arms are built in block space
		}

		@Override
		public boolean seals(BlockState state) {
			return false;
		}

		@Override
		protected BlockState rotate(BlockState state, Rotation rotation) {
			BlockState out = state;
			for (Direction d : Direction.Plane.HORIZONTAL) {
				out = out.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(rotation.rotate(d)), state.getValue(PipeBlock.PROPERTY_BY_DIRECTION.get(d)));
			}
			return out;
		}
	}
}
