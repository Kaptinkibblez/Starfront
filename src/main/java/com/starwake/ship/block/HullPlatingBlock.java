package com.starwake.ship.block;

import com.starwake.fab.MaterialBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Hull Plating (M1; carries its alloy since M8e-1): the basic starship construction block. Made in the
 * Fabricator from metal plates, it keeps the plates' composition and temper on a
 * {@link MaterialBlockEntity}: the ship's mass, re-entry heat limit and impact armour follow the alloy
 * ({@code ship/data/ShipMaterials}). Plating without an alloy (old worlds, the provisional crafting
 * recipe) counts as mild steel.
 */
public class HullPlatingBlock extends BaseEntityBlock {
	public HullPlatingBlock(Properties properties) {
		super(properties);
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new MaterialBlockEntity(pos, state);
	}

	/** M10 paint: use a dye to paint the hull block, a water bucket to strip it back to bare metal. */
	@Override
	protected net.minecraft.world.InteractionResult useItemOn(net.minecraft.world.item.ItemStack stack, BlockState state,
			net.minecraft.world.level.Level level, BlockPos pos, net.minecraft.world.entity.player.Player player,
			net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
		net.minecraft.world.item.DyeColor dye = stack.get(net.minecraft.core.component.DataComponents.DYE);
		boolean strip = stack.is(net.minecraft.world.item.Items.WATER_BUCKET);
		if ((dye == null && !strip) || !(level.getBlockEntity(pos) instanceof MaterialBlockEntity be)) {
			return super.useItemOn(stack, state, level, pos, player, hand, hit);
		}
		int rgb = strip ? -1 : dye.getTextureDiffuseColor() & 0xFFFFFF;
		if (be.paint() == rgb) return net.minecraft.world.InteractionResult.PASS;
		if (!level.isClientSide()) {
			be.setPaint(rgb);
			if (!strip && !player.isCreative()) stack.shrink(1);
			level.playSound(null, pos, strip ? net.minecraft.sounds.SoundEvents.BUCKET_EMPTY : net.minecraft.sounds.SoundEvents.DYE_USE,
					net.minecraft.sounds.SoundSource.BLOCKS, 0.6f, 1.2f);
		}
		return net.minecraft.world.InteractionResult.SUCCESS;
	}
}
