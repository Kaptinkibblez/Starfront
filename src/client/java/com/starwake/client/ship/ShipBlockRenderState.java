package com.starwake.client.ship;

import com.starwake.ship.ShipStructure;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Render state for one ship block. Extends vanilla's moving-block state (used for pistons) but
 * answers neighbour lookups from the whole ship, so faces hidden between adjacent ship blocks
 * are culled. {@code blockPos} is the block's current world cell, used for lighting.
 */
final class ShipBlockRenderState extends MovingBlockRenderState {
	/** Shared per ship: where local (0,0,0) currently sits in the world. */
	static final class Frame {
		final ShipStructure structure;
		BlockPos worldBase = BlockPos.ZERO;

		Frame(ShipStructure structure) {
			this.structure = structure;
		}
	}

	final BlockPos local;
	private final Frame frame;

	ShipBlockRenderState(Frame frame, BlockPos local, BlockState state) {
		this.frame = frame;
		this.local = local;
		this.blockState = state;
		this.randomSeedPos = local;
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		BlockState state = frame.structure.get(pos.getX() - frame.worldBase.getX(),
				pos.getY() - frame.worldBase.getY(), pos.getZ() - frame.worldBase.getZ());
		return state == null ? Blocks.AIR.defaultBlockState() : state;
	}

	@Override
	public int getMinY() {
		return frame.worldBase.getY() - 1;
	}

	@Override
	public int getHeight() {
		return frame.structure.size().getY() + 2;
	}
}
