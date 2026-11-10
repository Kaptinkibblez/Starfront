package com.starwake.ship.block;

import com.starwake.registry.ModBlockEntities;
import com.starwake.ship.data.ShipStats;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Holds a fuel tank's fuel units. Capacity is looked up from ship stats so JSON can retune it. */
public class FuelTankBlockEntity extends BlockEntity {
	private int fuel;

	public FuelTankBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.FUEL_TANK, pos, state);
	}

	public int fuel() {
		return fuel;
	}

	public int capacity() {
		return ShipStats.get(getBlockState()).fuelCapacity();
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

	/** Used when a ship is assembled: the tank's fuel moves into the ship. */
	public int drain() {
		int drained = fuel;
		fuel = 0;
		setChanged();
		return drained;
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putInt("fuel", fuel);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		fuel = input.getIntOr("fuel", 0);
	}
}
