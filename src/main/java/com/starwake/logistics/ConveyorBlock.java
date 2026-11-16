package com.starwake.logistics;

import com.starwake.machine.MachineSpec;
import com.starwake.registry.ModMachines;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
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
 * Belts (M8b, rebuilt in M9): every belt piece holds its items in a {@link ConveyorBlockEntity} and draws them riding
 * along. Kinds:
 * <ul>
 * <li>{@code BELT}: straight on toward FACING; takes items from the back, the sides (merging at the middle) and from
 * above (items dropped on it). Belts turn corners by feeding a belt at their side.</li>
 * <li>{@code SPLITTER}: sends items in turn straight on, left and right (skipping sides with nothing to take them).</li>
 * <li>{@code FILTER}: items matching its filter item leave to the right (or left); the rest go straight on.
 * Use with an item sets the filter, empty hand swaps the side, sneak with an empty hand clears it.</li>
 * <li>{@code INCLINE} / {@code DECLINE}: climb or drop one block over one block of run.</li>
 * <li>{@code LIFT}: carries items straight up; stack lifts into a column, the top one hands out toward FACING.</li>
 * </ul>
 * Living things standing on a flat belt are pushed along it (not on lifts).
 */
public class ConveyorBlock extends Block implements EntityBlock {
	public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;

	public enum Kind {
		BELT, SPLITTER, FILTER, INCLINE, DECLINE, LIFT
	}

	private static final VoxelShape FLAT = Block.box(0, 0, 0, 16, 3, 16);
	private static final VoxelShape LIFT_SHAPE = Block.box(2, 0, 2, 14, 16, 14);

	public final Kind kind;

	public ConveyorBlock(Kind kind, Properties properties) {
		super(properties);
		this.kind = kind;
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	/** Registry factories. */
	public static ConveyorBlock belt(Properties p) {
		return new ConveyorBlock(Kind.BELT, p);
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
	}

	/** Where items leaving by {@code out} go. */
	public static BlockPos outputPos(BlockState state, BlockPos pos, Direction out) {
		Kind kind = state.getBlock() instanceof ConveyorBlock b ? b.kind : Kind.BELT;
		return switch (kind) {
			case INCLINE -> pos.relative(out).above();
			case LIFT -> pos.relative(out);
			default -> pos.relative(out);
		};
	}

	// --- Shapes -------------------------------------------------------------------------------------------

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return switch (kind) {
			case LIFT -> LIFT_SHAPE;
			case INCLINE, DECLINE -> ramp(state.getValue(FACING), kind == Kind.INCLINE);
			default -> FLAT;
		};
	}

	/** A two-step stair under the sloped belt (high side toward FACING on an incline). */
	private static VoxelShape ramp(Direction facing, boolean up) {
		Direction high = up ? facing : facing.getOpposite();
		VoxelShape low = Block.box(0, 0, 0, 16, 8, 16);
		VoxelShape top = switch (high) {
			case NORTH -> Block.box(0, 8, 0, 16, 16, 8);
			case SOUTH -> Block.box(0, 8, 8, 16, 16, 16);
			case WEST -> Block.box(0, 8, 0, 8, 16, 16);
			default -> Block.box(8, 8, 0, 16, 16, 16);
		};
		return Shapes.or(low, top);
	}

	// --- Block entity -----------------------------------------------------------------------------------------

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new ConveyorBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		return type == ModMachines.CONVEYOR_BE ? (l, p, s, be) -> ConveyorBlockEntity.tick(l, p, s, (ConveyorBlockEntity) be) : null;
	}

	@Override
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof ConveyorBlockEntity belt) {
			for (ItemStack stack : belt.drops()) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.3, pos.getZ() + 0.5, stack);
			belt.items().clear();
		}
		return super.playerWillDestroy(level, pos, state, player);
	}

	// --- Interaction ----------------------------------------------------------------------------------------------

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
			BlockHitResult hit) {
		if (kind != Kind.FILTER || stack.isEmpty()) return InteractionResult.TRY_WITH_EMPTY_HAND;
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof ConveyorBlockEntity belt) {
			belt.setFilter(stack);
			player.sendOverlayMessage(Component.translatable("message.starwake.belt_filter.set", stack.getHoverName(), side(belt.left())));
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (kind != Kind.FILTER) return InteractionResult.PASS;
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof ConveyorBlockEntity belt) {
			if (player.isShiftKeyDown()) {
				belt.setFilter(ItemStack.EMPTY);
				player.sendOverlayMessage(Component.translatable("message.starwake.belt_filter.cleared"));
			} else {
				belt.setLeft(!belt.left());
				player.sendOverlayMessage(Component.translatable("message.starwake.belt_filter.side", side(belt.left())));
			}
		}
		return InteractionResult.SUCCESS;
	}

	private static Component side(boolean left) {
		return Component.translatable(left ? "message.starwake.belt_filter.left" : "message.starwake.belt_filter.right");
	}

	// --- Things on the belt --------------------------------------------------------------------------------------

	@Override
	protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effects, boolean precise) {
		if (entity instanceof ItemEntity item) {
			if (!level.isClientSide() && item.isAlive() && level.getBlockEntity(pos) instanceof ConveyorBlockEntity belt) {
				int taken = belt.absorb(item.getItem());
				if (taken > 0) item.discard();
			}
			return;
		}
		if (kind == Kind.LIFT || entity.isShiftKeyDown()) return;
		if (kind == Kind.BELT || kind == Kind.SPLITTER || kind == Kind.FILTER) {
			if (entity.getY() > pos.getY() + 0.5) return;
		}
		Direction facing = state.getValue(FACING);
		double speed = MachineSpec.of(this).rate() / 20.0;
		Vec3 v = entity.getDeltaMovement();
		double along = facing.getStepX() != 0 ? v.x * facing.getStepX() : v.z * facing.getStepZ();
		double push = Math.max(along, speed);
		double across = facing.getAxis() == Direction.Axis.X ? (pos.getZ() + 0.5 - entity.getZ()) : (pos.getX() + 0.5 - entity.getX());
		double cx = facing.getStepX() * push + (facing.getAxis() == Direction.Axis.Z ? across * 0.2 : 0);
		double cz = facing.getStepZ() * push + (facing.getAxis() == Direction.Axis.X ? across * 0.2 : 0);
		entity.setDeltaMovement(cx, v.y, cz);
	}

	@Override
	protected boolean propagatesSkylightDown(BlockState state) {
		return kind != Kind.LIFT;
	}
}
