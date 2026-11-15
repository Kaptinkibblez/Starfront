package com.starwake.ship.block;

import com.starwake.ship.panel.JointShape;
import com.starwake.ship.panel.PanelGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Hinge and Rotor (M9 pass 3): on an assembled ship, everything built onto the joint's facing side turns as one
 * sub-assembly about the facing axis (through the centre of that face). A Hinge has two set angles, one while the landing
 * gear is down and one while it's up (e.g. engine pods pointing down for VTOL, back for cruise); empty-hand use steps the
 * gear-up angle by 15 degrees, sneak-use the gear-down angle. A Rotor turns continuously; use steps its speed. Thrusters
 * on a sub-assembly push along their turned direction. Build the part in its gear-down pose: that's the pose collision
 * uses.
 */
public class JointBlock extends Block implements ShapedBlock {
	public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
	/** Hinge angle with gear down / up, in 15-degree steps. */
	public static final IntegerProperty DOWN_ANGLE = IntegerProperty.create("down_angle", 0, 23);
	public static final IntegerProperty UP_ANGLE = IntegerProperty.create("up_angle", 0, 23);
	/** Rotor speed step (see {@link #RPM}). */
	public static final IntegerProperty SPEED = IntegerProperty.create("speed", 0, 4);
	public static final double[] RPM = {0, 2, 6, 15, 30};

	private final boolean rotor;

	public JointBlock(boolean rotor, Properties properties) {
		super(properties);
		this.rotor = rotor;
		BlockState state = defaultBlockState().setValue(FACING, Direction.UP);
		state = rotor ? state.setValue(SPEED, 2) : state.setValue(DOWN_ANGLE, 0).setValue(UP_ANGLE, 6);
		registerDefaultState(state);
	}

	public boolean isRotor() {
		return rotor;
	}

	@Override
	public com.starwake.ship.panel.PanelGeometry.Shape shapeOf(BlockState state) {
		return shape();
	}

	@Override
	public int orientOf(BlockState state) {
		return orient(state);
	}

	public JointShape shape() {
		return rotor ? JointShape.ROTOR : JointShape.HINGE;
	}

	/** Orientation index for the shaped model: up = facing. */
	public static int orient(BlockState state) {
		Direction up = state.getValue(FACING);
		return PanelGeometry.orient(up, up.getAxis() == Direction.Axis.Y ? Direction.NORTH : Direction.UP);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		// Both joints share one property list so the state definition is known before the constructor runs.
		builder.add(FACING, DOWN_ANGLE, UP_ANGLE, SPEED);
	}

	@Override
	public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getClickedFace());
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (level.isClientSide()) return InteractionResult.SUCCESS;
		BlockState next;
		Component message;
		if (rotor) {
			next = state.setValue(SPEED, (state.getValue(SPEED) + 1) % 5);
			message = Component.translatable("message.starwake.rotor", String.format("%.0f", RPM[next.getValue(SPEED)]));
		} else {
			IntegerProperty which = player.isSecondaryUseActive() ? DOWN_ANGLE : UP_ANGLE;
			next = state.setValue(which, (state.getValue(which) + 1) % 24);
			message = Component.translatable("message.starwake.hinge", next.getValue(DOWN_ANGLE) * 15, next.getValue(UP_ANGLE) * 15);
		}
		level.setBlock(pos, next, Block.UPDATE_ALL);
		level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.4f, 1.2f);
		player.sendOverlayMessage(message);
		return InteractionResult.SUCCESS;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return PanelGeometry.shape(shape(), orient(state));
	}

	@Override
	protected BlockState rotate(BlockState state, Rotation rotation) {
		return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
	}

	@Override
	protected BlockState mirror(BlockState state, Mirror mirror) {
		return state.rotate(mirror.getRotation(state.getValue(FACING)));
	}
}
