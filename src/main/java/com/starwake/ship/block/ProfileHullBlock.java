package com.starwake.ship.block;

import com.starwake.ship.panel.PanelGeometry;
import com.starwake.ship.panel.ProfileShape;
import com.starwake.ship.panel.ProfileShape.Conn;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Auto-connecting hull pieces (M9 pass 4): Hull Slope / Curve / Cove at 1:1, 1:2 and 1:4, and the Hull Wedge. Like
 * vanilla stairs, a piece works out its own shape from its neighbours of the same type and up direction:
 * <ul>
 *   <li>a piece whose back neighbour runs across it becomes an outer corner, one whose front neighbour does an inner
 *   corner (the second run's side decides {@code mirror});</li>
 *   <li>1:2 and 1:4 pieces count the matching pieces below them in the run and take that section of the profile, so a
 *   long run is one smooth ramp;</li>
 *   <li>a wedge ends in a point where its ridge stops.</li>
 * </ul>
 * Placing (M10): stairs-style, upright with the low side toward you (upside down off a block's underside or the upper
 * half of a side face); sneak to mount it on the clicked face instead. Keeps its alloy (mass x how full it is).
 *
 * M10: 1:1 slopes, curves and coves connect with each other (a corner keeps each run's own profile, {@code second});
 * half-height slopes ({@link Half}) rise over the lower or upper half of their block; the Hull Tool can force a
 * piece's corner type, which {@code locked} keeps from auto-connecting.
 */
public abstract class ProfileHullBlock extends HullPlatingBlock implements ShapedBlock {
	public static final EnumProperty<Conn> CONN = EnumProperty.create("conn", Conn.class);
	public static final BooleanProperty MIRROR = BooleanProperty.create("mirror");
	/** M10: the corner type was set by hand (Hull Tool); auto-connect leaves it alone. */
	public static final BooleanProperty LOCKED = BooleanProperty.create("locked");
	/** M10: a 1:1 corner's second run, when it's another profile. */
	public static final EnumProperty<Second> SECOND = EnumProperty.create("second", Second.class);

	/** The other run's profile at a mixed corner. */
	public enum Second implements net.minecraft.util.StringRepresentable {
		SAME, SLOPE, CURVE, COVE;

		@Override
		public String getSerializedName() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}

		static Second of(ProfileShape.Profile p) {
			return switch (p) {
				case SLOPE -> SLOPE;
				case CURVE -> CURVE;
				case COVE -> COVE;
				case RIDGE -> SAME;
			};
		}
	}
	public static final IntegerProperty PART_A2 = IntegerProperty.create("part", 0, 1), PART_A4 = IntegerProperty.create("part", 0, 3);
	public static final IntegerProperty PART_B2 = IntegerProperty.create("part2", 0, 1), PART_B4 = IntegerProperty.create("part2", 0, 3);
	/** Longest run counted when finding a piece's section (the pattern repeats every N). */
	private static final int MAX_RUN = 64;

	private final ProfileShape.Profile profile;

	protected ProfileHullBlock(ProfileShape.Profile profile, Properties properties) {
		super(properties);
		this.profile = profile;
		registerDefaultState(defaultBlockState().setValue(ORIENT, PanelGeometry.orient(Direction.UP, Direction.NORTH))
				.setValue(CONN, Conn.STRAIGHT).setValue(MIRROR, false).setValue(LOCKED, false));
	}

	/** Run length in blocks (1, 2 or 4). */
	public abstract int run();

	protected @Nullable IntegerProperty partA() {
		return null;
	}

	protected @Nullable IntegerProperty partB() {
		return null;
	}

	public int partA(BlockState state) {
		IntegerProperty p = partA();
		return p == null ? 0 : state.getValue(p);
	}

	public int partB(BlockState state) {
		IntegerProperty p = partB();
		return p == null ? 0 : state.getValue(p);
	}

	public ProfileShape.Profile profile() {
		return profile;
	}

	/** The rise band (lo, hi) of this block's profile: the whole block, or half for half-height slopes. */
	protected double lo(BlockState state) {
		return 0;
	}

	protected double hi(BlockState state) {
		return 1;
	}

	@Override
	public PanelGeometry.Shape shapeOf(BlockState state) {
		ProfileShape.Profile second = profile;
		if (state.hasProperty(SECOND)) {
			second = switch (state.getValue(SECOND)) {
				case SAME -> profile;
				case SLOPE -> ProfileShape.Profile.SLOPE;
				case CURVE -> ProfileShape.Profile.CURVE;
				case COVE -> ProfileShape.Profile.COVE;
			};
		}
		return new ProfileShape(profile, run(), state.getValue(CONN), partA(state), partB(state), state.getValue(MIRROR), second,
				lo(state), hi(state));
	}

	/** Whether a neighbour counts as part of the same surface for corners (M10: any 1:1 slope/curve/cove for 1:1 pieces). */
	protected boolean joins(BlockState other) {
		if (other.is(this)) return true;
		return run() == 1 && profile != ProfileShape.Profile.RIDGE && other.hasProperty(SECOND)
				&& other.getBlock() instanceof ProfileHullBlock p && p.run() == 1 && p.profile != ProfileShape.Profile.RIDGE;
	}

	@Override
	public int orientOf(BlockState state) {
		return state.getValue(ORIENT);
	}

	@Override
	public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
		return connect(defaultBlockState().setValue(ORIENT, PanelGeometry.placementUpright(context)), context.getLevel(), context.getClickedPos());
	}

	@Override
	protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
			BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
		return connect(state, level, pos);
	}

	private static Direction up(BlockState s) {
		return PanelGeometry.up(s.getValue(ORIENT));
	}

	/** The direction a piece rises toward (its high side). */
	private static Direction back(BlockState s) {
		return PanelGeometry.front(s.getValue(ORIENT)).getOpposite();
	}

	/** Works out corner, mirror and run sections from the neighbours (the stairs rule, generalised to any up). */
	public BlockState connect(BlockState state, BlockGetter level, BlockPos pos) {
		if (state.getValue(LOCKED)) return state;
		Direction up = up(state), back = back(state), front = back.getOpposite();
		ProfileShape.Profile other = profile;
		Conn conn = Conn.STRAIGHT;
		Direction second = null;
		int pb = 0;
		if (profile == ProfileShape.Profile.RIDGE) {
			BlockState ahead = level.getBlockState(pos.relative(front));
			conn = ahead.is(this) && ahead.getValue(ORIENT).equals(state.getValue(ORIENT)) ? Conn.STRAIGHT : Conn.END;
		} else {
			BlockState behind = level.getBlockState(pos.relative(back));
			if (joins(behind) && up(behind) == up) {
				Direction b2 = back(behind);
				if (b2.getAxis() != back.getAxis() && canTake(level, pos, b2.getOpposite(), up, back)) {
					conn = Conn.OUTER;
					second = b2;
					pb = partA(behind);
					other = ((ProfileHullBlock) behind.getBlock()).profile;
				}
			}
			if (conn == Conn.STRAIGHT) {
				BlockState ahead = level.getBlockState(pos.relative(front));
				if (joins(ahead) && up(ahead) == up) {
					Direction b3 = back(ahead);
					if (b3.getAxis() != back.getAxis() && canTake(level, pos, b3, up, back)) {
						conn = Conn.INNER;
						second = b3;
						pb = partA(ahead);
						other = ((ProfileHullBlock) ahead.getBlock()).profile;
					}
				}
			}
		}
		// Section of the run: how many matching pieces lie below this one, modulo the run length.
		int below = 0;
		BlockPos.MutableBlockPos p = pos.mutable();
		for (int i = 0; i < MAX_RUN; i++) {
			p.move(front);
			BlockState s = level.getBlockState(p);
			if (!s.is(this) || !s.getValue(ORIENT).equals(state.getValue(ORIENT))) break;
			below++;
		}
		boolean mirror = false;
		if (second != null) {
			var x = PanelGeometry.transform(state.getValue(ORIENT), 1, 0.5, 0.5);
			Direction modelX = Direction.getApproximateNearest(x.x - 0.5f, x.y - 0.5f, x.z - 0.5f);
			mirror = modelX != second;
		}
		BlockState out = state.setValue(CONN, conn).setValue(MIRROR, mirror);
		if (out.hasProperty(SECOND)) out = out.setValue(SECOND, other == profile ? Second.SAME : Second.of(other));
		if (partA() != null) out = out.setValue(partA(), below % run());
		if (partB() != null) out = out.setValue(partB(), pb % run());
		return out;
	}

	/** Stairs' rule: a corner only forms if the cell beside it isn't already a straight continuation. */
	private boolean canTake(BlockGetter level, BlockPos pos, Direction dir, Direction up, Direction back) {
		BlockState s = level.getBlockState(pos.relative(dir));
		return !(joins(s) && up(s) == up && back(s) == back);
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

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(ORIENT, PanelGeometry.rotated(state.getValue(ORIENT), rotation));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return rotate(state, mirror.getRotation(PanelGeometry.front(state.getValue(ORIENT))));
	}

	/** One-block run (1:1 slope, curve, cove; wedge). */
	public static class One extends ProfileHullBlock {
		public One(ProfileShape.Profile profile, Properties properties) {
			super(profile, properties);
		}

		@Override
		public int run() {
			return 1;
		}

		@Override
		protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(ORIENT, CONN, MIRROR, LOCKED, SECOND);
		}
	}

	/** M10: a half-height 1:1 slope: rises over the lower (bottom) or upper (top, on a half-block base) half of its block. */
	public static class Half extends ProfileHullBlock {
		public static final BooleanProperty TOP = BooleanProperty.create("top");

		public Half(Properties properties) {
			super(ProfileShape.Profile.SLOPE, properties);
			registerDefaultState(defaultBlockState().setValue(TOP, false));
		}

		@Override
		public int run() {
			return 1;
		}

		@Override
		protected double lo(BlockState state) {
			return state.getValue(TOP) ? 0.5 : 0;
		}

		@Override
		protected double hi(BlockState state) {
			return state.getValue(TOP) ? 1 : 0.5;
		}

		@Override
		protected boolean joins(BlockState other) {
			return other.is(this);
		}

		@Override
		protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(ORIENT, CONN, MIRROR, LOCKED, TOP);
		}
	}

	/** Two-block run (1:2). */
	public static class Two extends ProfileHullBlock {
		public Two(ProfileShape.Profile profile, Properties properties) {
			super(profile, properties);
		}

		@Override
		public int run() {
			return 2;
		}

		@Override
		protected IntegerProperty partA() {
			return PART_A2;
		}

		@Override
		protected IntegerProperty partB() {
			return PART_B2;
		}

		@Override
		protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(ORIENT, CONN, MIRROR, LOCKED, PART_A2, PART_B2);
		}
	}

	/** Four-block run (1:4). */
	public static class Four extends ProfileHullBlock {
		public Four(ProfileShape.Profile profile, Properties properties) {
			super(profile, properties);
		}

		@Override
		public int run() {
			return 4;
		}

		@Override
		protected IntegerProperty partA() {
			return PART_A4;
		}

		@Override
		protected IntegerProperty partB() {
			return PART_B4;
		}

		@Override
		protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(ORIENT, CONN, MIRROR, LOCKED, PART_A4, PART_B4);
		}
	}
}
