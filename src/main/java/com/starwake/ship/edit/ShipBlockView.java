package com.starwake.ship.edit;

import com.starwake.ship.ShipStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * A ship's blocks seen as a {@link BlockGetter} in ship-local coordinates (Milestone 4). Used
 * where vanilla needs a "level" to ask about a block, e.g. mining speed
 * ({@code BlockState.getDestroyProgress}), so the same rules apply on and off ships.
 */
public record ShipBlockView(ShipStructure structure) implements BlockGetter {
	@Override
	public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
		return null;
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		BlockState state = structure.get(pos);
		return state == null ? Blocks.AIR.defaultBlockState() : state;
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return getBlockState(pos).getFluidState();
	}

	@Override
	public int getHeight() {
		return structure.size().getY() + 2;
	}

	@Override
	public int getMinY() {
		return structure.min().getY() - 1;
	}
}
