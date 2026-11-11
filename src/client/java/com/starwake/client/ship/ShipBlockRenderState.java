package com.starwake.client.ship;

import com.starwake.ship.ShipStructure;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Render state for one ship block. Extends vanilla's moving-block state (used for pistons) but
 * answers neighbour lookups from the ship itself, so faces hidden between adjacent ship blocks
 * are culled at any orientation.
 *
 * Tessellation happens in the block's own (unrotated) frame, so a neighbour query at
 * {@code blockPos + d} means "the ship block at {@code local + d}". {@code blockPos} is the
 * world cell under the block's rotated centre, used for lighting.
 */
final class ShipBlockRenderState extends MovingBlockRenderState {
	private final ShipStructure structure;
	final BlockPos local;

	ShipBlockRenderState(ShipStructure structure, BlockPos local, BlockState state) {
		this.structure = structure;
		this.local = local;
		this.blockState = state;
		this.randomSeedPos = local;
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		BlockState state = structure.get(local.getX() + pos.getX() - blockPos.getX(),
				local.getY() + pos.getY() - blockPos.getY(), local.getZ() + pos.getZ() - blockPos.getZ());
		return state == null ? Blocks.AIR.defaultBlockState() : state;
	}

	@Override
	public int getMinY() {
		return blockPos.getY() - local.getY() + structure.min().getY() - 1;
	}

	@Override
	public int getHeight() {
		return structure.size().getY() + 2;
	}
}
