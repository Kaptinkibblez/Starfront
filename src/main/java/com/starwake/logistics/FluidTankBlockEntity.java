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

	/** Breaking a tank of a toxic chemical releases it (M8c hazards). */
	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		super.preRemoveSideEffects(pos, state);
		if (level instanceof net.minecraft.server.level.ServerLevel server && storage != null && !storage.isResourceBlank()) {
			com.starwake.process.Chemicals.speciesOf(storage.getResource()).ifPresent(s ->
					com.starwake.process.GasHazards.vent(server, pos, s, storage.getAmount() / (double) com.starwake.process.Chemicals.KG));
		}
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
