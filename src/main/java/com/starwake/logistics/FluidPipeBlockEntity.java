package com.starwake.logistics;

import com.starwake.process.Chemicals;
import com.starwake.registry.ModMachines;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A pipe's block entity (M8b): lets pipes with an extract side tick. M8c-3a: holds an optional
 * species filter for its extract sides (sneak-use an extract arm to cycle through what the source
 * holds), so an air separator's oxygen and argon can be piped to different tanks.
 */
public class FluidPipeBlockEntity extends BlockEntity {
	private String filter = "";

	public FluidPipeBlockEntity(BlockPos pos, BlockState state) {
		super(ModMachines.FLUID_PIPE_BE, pos, state);
	}

	public String filter() {
		return filter;
	}

	public void setFilter(String filter) {
		this.filter = filter == null ? "" : filter;
		setChanged();
	}

	/** Whether this pipe may pull a fluid: anything without a filter, else only the filtered species. */
	public boolean allows(FluidVariant variant) {
		return filter.isEmpty() || Chemicals.speciesOf(variant).map(filter::equals).orElse(false);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		filter = input.getStringOr("filter", "");
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		if (!filter.isEmpty()) output.putString("filter", filter);
	}
}
