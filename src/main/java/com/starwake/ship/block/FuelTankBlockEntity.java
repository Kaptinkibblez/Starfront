package com.starwake.ship.block;

import com.starwake.process.ChemicalTank;
import com.starwake.registry.ModBlockEntities;
import com.starwake.ship.data.ShipStats;
import com.starwake.ship.fuel.Propellants;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A fuel tank (M3a): propellant units for the thrusters and, since M8f, fusion units for the quantum and
 * jump drives. Capacity comes from ship stats (and the tank's quality, M8e-2); fusion capacity is ten times
 * the propellant capacity. Gases piped in or poured from Gas Cylinders (H2, O2, CH4, D2, He3) land in a
 * small insert-only {@link ChemicalTank} and burn into units as soon as a matching pair is there
 * ({@link Propellants}); unpaired gas waits for its partner.
 */
public class FuelTankBlockEntity extends BlockEntity {
	private int fuel;
	private int fusion;
	private boolean converting;
	private final ChemicalTank gases = new ChemicalTank(40, true, false, Propellants.SPECIES::contains, this::onGasChange);

	public FuelTankBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.FUEL_TANK, pos, state);
	}

	public int fuel() {
		return fuel;
	}

	public int fusion() {
		return fusion;
	}

	public Storage<FluidVariant> fluidStorage() {
		return gases;
	}

	public int capacity() {
		// M8e-2: a better-made tank holds more.
		return com.starwake.ship.data.ShipMaterials.withQuality(ShipStats.get(getBlockState()), com.starwake.ship.data.ShipMaterials.quality(this))
				.fuelCapacity();
	}

	public int fusionCapacity() {
		return Propellants.fusionCapacity(capacity());
	}

	/** Burns whatever pairs the gas tank holds into units. */
	private void onGasChange() {
		if (converting) return;
		converting = true;
		try {
			Map<String, Double> buffer = new HashMap<>();
			for (String s : Propellants.SPECIES) if (gases.kg(s) > 0) buffer.put(s, gases.kg(s));
			Map<String, Double> before = new HashMap<>(buffer);
			int[] made = Propellants.convert(buffer, capacity() - fuel, fusionCapacity() - fusion);
			if (made[0] > 0 || made[1] > 0) {
				fuel += made[0];
				fusion += made[1];
				before.forEach((s, kg) -> gases.take(s, kg - buffer.getOrDefault(s, 0.0)));
			}
		} finally {
			converting = false;
		}
		setChanged();
	}

	/**
	 * Consumes fuel items from the stack into the tank, never overfilling.
	 *
	 * @param all fill with as many items as fit (sneak-click); otherwise one item
	 * @return units added
	 */
	public int fill(ItemStack stack, int unitsPerItem, boolean all) {
		int room = capacity() - fuel;
		int items = Math.min(all ? stack.getCount() : 1, room / unitsPerItem);
		if (items <= 0) {
			return 0;
		}
		stack.shrink(items);
		fuel += items * unitsPerItem;
		setChanged();
		return items * unitsPerItem;
	}

	/** Adds raw fuel units (used when a ship is disassembled). Returns units accepted. */
	public int refill(int units) {
		int added = Math.max(0, Math.min(units, capacity() - fuel));
		fuel += added;
		setChanged();
		return added;
	}

	/** Adds fusion units (disassembly). Returns units accepted. */
	public int refillFusion(int units) {
		int added = Math.max(0, Math.min(units, fusionCapacity() - fusion));
		fusion += added;
		setChanged();
		return added;
	}

	/** Used when a ship is assembled: the tank's fuel moves into the ship. */
	public int drain() {
		int drained = fuel;
		fuel = 0;
		setChanged();
		return drained;
	}

	public int drainFusion() {
		int drained = fusion;
		fusion = 0;
		setChanged();
		return drained;
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putInt("fuel", fuel);
		output.putInt("fusion", fusion);
		gases.write(output, "gases");
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		fuel = input.getIntOr("fuel", 0);
		fusion = input.getIntOr("fusion", 0);
		converting = true;
		gases.read(input, "gases");
		converting = false;
	}
}
