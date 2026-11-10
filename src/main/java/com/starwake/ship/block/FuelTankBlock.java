package com.starwake.ship.block;

import com.starwake.ship.data.ShipFuels;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Fuel Tank: stores ship fuel. Right-click with a fuel item (see ship_fuel JSON) to load it,
 * or with an empty hand to read the gauge. Capacity comes from ship_stats JSON.
 */
public class FuelTankBlock extends Block implements EntityBlock {
	public FuelTankBlock(Properties properties) {
		super(properties);
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new FuelTankBlockEntity(pos, state);
	}

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
			InteractionHand hand, BlockHitResult hit) {
		int unitsPerItem = ShipFuels.unitsFor(stack);
		if (unitsPerItem <= 0) {
			return InteractionResult.TRY_WITH_EMPTY_HAND;
		}
		if (!(level.getBlockEntity(pos) instanceof FuelTankBlockEntity tank)) {
			return InteractionResult.PASS;
		}
		if (!level.isClientSide()) {
			int added = tank.fill(stack, unitsPerItem, player.isShiftKeyDown());
			player.sendOverlayMessage(added > 0
					? Component.translatable("message.starwake.fuel_tank.filled", added, tank.fuel(), tank.capacity())
					: Component.translatable("message.starwake.fuel_tank.full", tank.fuel(), tank.capacity()));
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof FuelTankBlockEntity tank) {
			player.sendOverlayMessage(Component.translatable("message.starwake.fuel_tank.gauge", tank.fuel(), tank.capacity()));
		}
		return InteractionResult.SUCCESS;
	}
}
