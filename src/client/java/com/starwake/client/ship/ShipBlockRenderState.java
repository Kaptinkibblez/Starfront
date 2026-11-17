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

	private final com.starwake.ship.articulation.Articulations joints;
	private final int group;

	/** {@code group}: this block's articulation group; neighbours in another group count as air (they move apart). */
	ShipBlockRenderState(ShipStructure structure, BlockPos local, BlockState state, com.starwake.ship.articulation.Articulations joints, int group) {
		this.structure = structure;
		this.joints = joints;
		this.group = group;
		this.local = local;
		this.blockState = state;
		this.randomSeedPos = local;
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		int x = local.getX() + pos.getX() - blockPos.getX(), y = local.getY() + pos.getY() - blockPos.getY(), z = local.getZ() + pos.getZ() - blockPos.getZ();
		BlockState state = structure.get(x, y, z);
		if (state != null && !joints.isEmpty() && joints.groupOf(BlockPos.asLong(x, y, z)) != group) return Blocks.AIR.defaultBlockState();
		return state == null ? Blocks.AIR.defaultBlockState() : state;
	}

	/** M10: the paint carried in this block's saved block-entity data (-1 = bare metal). */
	int paint() {
		var entry = structure.entry(local);
		if (entry == null) return -1;
		return entry.blockEntity().flatMap(t -> t.getCompound("components")).flatMap(c -> c.getInt("starwake:paint")).orElse(-1);
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
