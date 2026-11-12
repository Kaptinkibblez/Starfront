package com.starwake.logistics;

import com.starwake.registry.ModMachines;
import java.util.EnumMap;
import java.util.Map;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Fluid Pipe (M8b): connects to pipes and to anything with Fabric fluid storage (tanks, machines,
 * cauldrons). Use a pipe arm that touches a storage to toggle it to <b>extract</b>: that side
 * pulls {@code rate} buckets per second from the storage and the pipe network delivers it to every
 * other connected storage. Pipes only tick when they have an extract side.
 */
public class FluidPipeBlock extends Block implements EntityBlock {
	public static final Map<Direction, EnumProperty<PipeSide>> SIDES = new EnumMap<>(Direction.class);
	private static final VoxelShape CORE = Block.box(5, 5, 5, 11, 11, 11);
	private static final Map<Direction, VoxelShape> ARMS = Shapes.rotateAll(Block.box(5, 5, 0, 11, 11, 5));

	static {
		for (Direction d : Direction.values()) SIDES.put(d, EnumProperty.create(d.getSerializedName(), PipeSide.class));
	}

	public FluidPipeBlock(Properties properties) {
		super(properties);
		BlockState state = stateDefinition.any();
		for (var p : SIDES.values()) state = state.setValue(p, PipeSide.NONE);
		registerDefaultState(state);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		for (Direction d : Direction.values()) {
			// SIDES is filled by the static initializer before any instance exists.
			builder.add(SIDES.get(d));
		}
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		VoxelShape shape = CORE;
		for (Direction d : Direction.values()) {
			if (state.getValue(SIDES.get(d)) != PipeSide.NONE) shape = Shapes.or(shape, ARMS.get(d));
		}
		return shape;
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		BlockState state = defaultBlockState();
		for (Direction d : Direction.values()) {
			state = state.setValue(SIDES.get(d), connects(context.getLevel(), context.getClickedPos().relative(d), d) ? PipeSide.CONNECTED : PipeSide.NONE);
		}
		return state;
	}

	@Override
	protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
			BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
		PipeSide current = state.getValue(SIDES.get(direction));
		boolean connected = connects(level, neighbourPos, direction);
		PipeSide next = !connected ? PipeSide.NONE : current == PipeSide.EXTRACT && !(neighbourState.getBlock() instanceof FluidPipeBlock)
				? PipeSide.EXTRACT : PipeSide.CONNECTED;
		return state.setValue(SIDES.get(direction), next);
	}

	private static boolean connects(LevelReader level, BlockPos pos, Direction from) {
		if (level.getBlockState(pos).getBlock() instanceof FluidPipeBlock) return true;
		return level instanceof Level l && FluidStorage.SIDED.find(l, pos, from.getOpposite()) != null;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		Vec3 local = hit.getLocation().subtract(Vec3.atCenterOf(pos));
		Direction side = Direction.getApproximateNearest(local.x, local.y, local.z);
		PipeSide current = state.getValue(SIDES.get(side));
		if (current == PipeSide.NONE || level.getBlockState(pos.relative(side)).getBlock() instanceof FluidPipeBlock) return InteractionResult.PASS;
		if (!level.isClientSide()) {
			PipeSide next = current == PipeSide.EXTRACT ? PipeSide.CONNECTED : PipeSide.EXTRACT;
			level.setBlockAndUpdate(pos, state.setValue(SIDES.get(side), next));
			FluidNetworks.invalidate(level, pos);
			player.sendOverlayMessage(Component.translatable(next == PipeSide.EXTRACT ? "message.starwake.pipe.extract" : "message.starwake.pipe.connect",
					Component.translatable("direction.starwake." + side.getSerializedName())));
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new FluidPipeBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		if (level.isClientSide() || type != ModMachines.FLUID_PIPE_BE) return null;
		for (var p : SIDES.values()) {
			if (state.getValue(p) == PipeSide.EXTRACT) return (l, pos, s, be) -> FluidNetworks.pump((net.minecraft.server.level.ServerLevel) l, pos, s);
		}
		return null;
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		FluidNetworks.invalidate(level, pos);
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, net.minecraft.server.level.ServerLevel level, BlockPos pos, boolean movedByPiston) {
		FluidNetworks.invalidate(level, pos);
	}
}
