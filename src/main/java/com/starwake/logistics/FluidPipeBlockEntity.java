package com.starwake.logistics;

import com.starwake.registry.ModMachines;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Marker block entity so pipes with an extract side can tick (M8b); it holds no state. */
public class FluidPipeBlockEntity extends BlockEntity {
	public FluidPipeBlockEntity(BlockPos pos, BlockState state) {
		super(ModMachines.FLUID_PIPE_BE, pos, state);
	}
}
