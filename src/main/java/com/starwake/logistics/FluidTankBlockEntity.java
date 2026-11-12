package com.starwake.logistics;

import com.starwake.machine.MachineSpec;
import com.starwake.process.ChemicalTank;
import com.starwake.process.Chemicals;
import com.starwake.process.GasHazards;
import com.starwake.registry.ModMachines;
import java.util.List;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.fluid.base.SingleFluidStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A Fluid Tank's contents (M8b), exposed to pipes and buckets through Fabric's FluidStorage.
 *
 * <p>M8c-2: water and chemicals go into a mixing {@link ChemicalTank} (acid poured onto water makes
 * dilute acid; pipes see one solution fluid that carries its composition). Anything else (lava,
 * other mods' fluids) goes into a plain single-fluid store. The two never hold fluid at once.
 */
public class FluidTankBlockEntity extends BlockEntity {
	private ChemicalTank chemicals;
	private SingleFluidStorage other;
	private Storage<FluidVariant> combined;

	public FluidTankBlockEntity(BlockPos pos, BlockState state) {
		super(ModMachines.FLUID_TANK_BE, pos, state);
	}

	private long capacityDroplets() {
		double buckets = MachineSpec.of(getBlockState().getBlock()).capacity();
		return (long) ((buckets <= 0 ? 32 : buckets) * FluidConstants.BUCKET);
	}

	private void build() {
		if (combined != null) return;
		long cap = capacityDroplets();
		other = new SingleFluidStorage() {
			@Override
			protected long getCapacity(FluidVariant variant) {
				return cap;
			}

			@Override
			protected boolean canInsert(FluidVariant variant) {
				return Chemicals.contents(variant).isEmpty() && chemicals.total() == 0;
			}

			@Override
			protected void onFinalCommit() {
				setChanged();
			}
		};
		chemicals = new ChemicalTank(cap / (double) Chemicals.KG, true, true, s -> other.isResourceBlank(), this::setChanged).mixed();
		combined = new CombinedStorage<>(List.of(chemicals, other));
	}

	public Storage<FluidVariant> storage() {
		build();
		return combined;
	}

	public ChemicalTank chemicals() {
		build();
		return chemicals;
	}

	/** What the tank holds (blank if empty). */
	public FluidVariant resource() {
		build();
		if (chemicals.total() > 0) return chemicals.mixtureVariant();
		return other.getResource();
	}

	public long amount() {
		build();
		return chemicals.total() > 0 ? chemicals.total() : other.getAmount();
	}

	public long capacity() {
		return capacityDroplets();
	}

	/** Breaking a tank of a toxic chemical releases it (M8c hazards). */
	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		super.preRemoveSideEffects(pos, state);
		if (level instanceof ServerLevel server && chemicals != null) {
			chemicals.contents().forEach((s, droplets) -> GasHazards.vent(server, pos, s, droplets / (double) Chemicals.KG));
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		build();
		other.readValue(input);
		chemicals.read(input, "chemicals");
		// 8b saves kept water and chemicals in the single store: move them into the mixing tank.
		if (!other.isResourceBlank() && !Chemicals.contents(other.getResource()).isEmpty()) {
			Chemicals.contents(other.getResource()).forEach((s, f) -> chemicals.add(s, f * other.getAmount() / (double) Chemicals.KG));
			other.variant = FluidVariant.blank();
			other.amount = 0;
		}
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		build();
		other.writeValue(output);
		chemicals.write(output, "chemicals");
	}
}
