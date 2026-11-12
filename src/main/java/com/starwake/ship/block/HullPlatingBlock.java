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
}
