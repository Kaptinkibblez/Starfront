package com.starwake.logistics;

import com.starwake.machine.MachineSpec;
import com.starwake.registry.ModMachines;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.base.SingleFluidStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** A Fluid Tank's contents (M8b), exposed to pipes and buckets through Fabric's FluidStorage. */
public class FluidTankBlockEntity extends BlockEntity {
	private SingleFluidStorage storage;

	public FluidTankBlockEntity(BlockPos pos, BlockState state) {
		super(ModMachines.FLUID_TANK_BE, pos, state);
	}

	public SingleFluidStorage storage() {
		if (storage == null) {
			double buckets = MachineSpec.of(getBlockState().getBlock()).capacity();
			storage = SingleFluidStorage.withFixedCapacity((long) ((buckets <= 0 ? 32 : buckets) * FluidConstants.BUCKET), this::setChanged);
		}
		return storage;
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		storage().readValue(input);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		storage().writeValue(output);
	}
}
