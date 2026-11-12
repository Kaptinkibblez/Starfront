package com.starwake.ship.block;

import com.starwake.fab.MaterialBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * A ship component block (power plant, cooler, shield generator, quantum and jump drives; M3f, Mk tiers
 * in M8e-2). Its stats come from ship_stats JSON; the {@link MaterialBlockEntity} remembers the alloy and
 * quality it was fabricated with, which scales them ({@code ship/data/ShipMaterials}).
 */
public class ShipComponentBlock extends Block implements EntityBlock {
	public ShipComponentBlock(Properties properties) {
		super(properties);
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new MaterialBlockEntity(pos, state);
	}
}
