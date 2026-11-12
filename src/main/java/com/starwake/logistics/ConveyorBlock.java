package com.starwake.logistics;

import com.starwake.machine.MachineSpec;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Conveyor Belt (M8b): a low belt that carries whatever lies on it (items, mobs, players) toward
 * its FACING at {@code rate} blocks per second, centring items on the belt. At the end, items are
 * fed into the inventory in front (chests, machines, anything with Fabric item storage); an item
 * that doesn't fit waits. Machines drop their output onto a belt that leads away from them.
 */
public class ConveyorBlock extends Block {
	public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
	private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 3, 16);

	public ConveyorBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier effects, boolean precise) {
		if (entity.getY() > pos.getY() + 0.5 || entity.isShiftKeyDown()) return;
		Direction facing = state.getValue(FACING);
		double speed = MachineSpec.of(this).rate() / 20.0;
		Vec3 v = entity.getDeltaMovement();
		double along = facing.getStepX() != 0 ? v.x * facing.getStepX() : v.z * facing.getStepZ();
		double push = Math.max(along, speed);
		// Centre across the belt.
		double across = facing.getAxis() == Direction.Axis.X ? (pos.getZ() + 0.5 - entity.getZ()) : (pos.getX() + 0.5 - entity.getX());
		double cx = facing.getStepX() * push + (facing.getAxis() == Direction.Axis.Z ? across * 0.2 : 0);
		double cz = facing.getStepZ() * push + (facing.getAxis() == Direction.Axis.X ? across * 0.2 : 0);
		entity.setDeltaMovement(cx, v.y, cz);
		if (!(entity instanceof ItemEntity item) || level.isClientSide()) return;
		// Feed the inventory ahead once the item reaches the end of the belt.
		double progress = facing.getStepX() != 0 ? (entity.getX() - pos.getX() - 0.5) * facing.getStepX()
				: (entity.getZ() - pos.getZ() - 0.5) * facing.getStepZ();
		if (progress < 0.3) return;
		BlockPos ahead = pos.relative(facing);
		if (level.getBlockState(ahead).getBlock() instanceof ConveyorBlock) return;
		var storage = ItemStorage.SIDED.find(level, ahead, facing.getOpposite());
		if (storage == null) {
			entity.setDeltaMovement(0, v.y, 0);
			return;
		}
		ItemStack stack = item.getItem();
		try (Transaction tx = Transaction.openOuter()) {
			long moved = storage.insert(ItemVariant.of(stack), stack.getCount(), tx);
			if (moved > 0) {
				tx.commit();
				ItemStack rest = stack.copy();
				rest.shrink((int) moved);
				if (rest.isEmpty()) item.discard();
				else item.setItem(rest);
			} else {
				entity.setDeltaMovement(0, v.y, 0);
			}
		}
	}
}
