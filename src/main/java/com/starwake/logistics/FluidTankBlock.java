package com.starwake.logistics;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorageUtil;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * Fluid Tank (M8b): holds one fluid, {@code capacity} buckets' worth (the spec's capacity field is
 * read as m3 = buckets). Buckets fill and empty it; pipes connect to it on any side; use with an
 * empty hand to read the gauge; comparators read the fill level.
 */
public class FluidTankBlock extends Block implements EntityBlock {
	public FluidTankBlock(Properties properties) {
		super(properties);
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new FluidTankBlockEntity(pos, state);
	}

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
			BlockHitResult hit) {
		if (stack.getItem() instanceof com.starwake.process.GasCylinderItem && level.getBlockEntity(pos) instanceof FluidTankBlockEntity tank) {
			return com.starwake.process.GasCylinderItem.fillFrom(tank.storage(), player, hand) ? InteractionResult.SUCCESS : InteractionResult.TRY_WITH_EMPTY_HAND;
		}
		if (level.getBlockEntity(pos) instanceof FluidTankBlockEntity tank && FluidStorageUtil.interactWithFluidStorage(tank.storage(), player, hand)) {
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.TRY_WITH_EMPTY_HAND;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof FluidTankBlockEntity tank) {
			var r = tank.resource();
			Component fluid = r.isBlank() ? Component.translatable("message.starwake.tank.empty_fluid") : FluidVariantAttributes.getName(r);
			Component msg = Component.translatable("message.starwake.tank", fluid,
					String.format("%.2f", tank.amount() / (double) FluidConstants.BUCKET), String.format("%.0f", tank.capacity() / (double) FluidConstants.BUCKET));
			var mix = tank.chemicals();
			if (mix.contents().size() > 1) {
				StringBuilder parts = new StringBuilder(" -");
				mix.contents().entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(4).forEach(e ->
						parts.append(String.format(" %s %.1f%%", com.starwake.process.Species.get(e.getKey()).map(i -> i.formula()).orElse(e.getKey()),
								100.0 * e.getValue() / mix.total())));
				msg = msg.copy().append(parts.toString());
			}
			player.sendOverlayMessage(msg);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	protected boolean hasAnalogOutputSignal(BlockState state) {
		return true;
	}

	@Override
	protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
		return level.getBlockEntity(pos) instanceof FluidTankBlockEntity tank && tank.capacity() > 0
				? (int) (15 * tank.amount() / tank.capacity()) : 0;
	}
}
