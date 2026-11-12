package com.starwake.machine;

import com.starwake.registry.ModMachines;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;

/**
 * Combustion Generator (M8b): the starter power source. Burns anything a furnace burns (coal,
 * charcoal, wood, blaze rods, lava...) for {@code fuel} x the furnace burn time, producing
 * {@code power} kW while its buffer has room. It only lights a new item when the buffer can
 * take it, so idle grids don't waste fuel.
 */
public class CombustionGeneratorBlockEntity extends MachineBlockEntity {
	public static final MachineMenu.Layout LAYOUT = new MachineMenu.Layout(List.of(new int[] {80, 44}), new int[] {0}, new int[] {}, new int[] {0}, false);

	private int burnLeft;

	public CombustionGeneratorBlockEntity(BlockPos pos, BlockState state) {
		super(ModMachines.COMBUSTION_GENERATOR_BE, pos, state);
	}

	@Override
	public MachineMenu.Layout layout() {
		return LAYOUT;
	}

	@Override
	protected net.minecraft.world.inventory.MenuType<MachineMenu> menuType() {
		return ModMachines.COMBUSTION_GENERATOR_MENU;
	}

	@Override
	public Role energyRole() {
		return Role.GENERATOR;
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return stack.has(DataComponents.COOKING_FUEL);
	}

	@Override
	protected void work(ServerLevel level) {
		MachineSpec spec = spec();
		long perTick = EnergyNode.perTick(spec.power());
		powerKw = 0;
		if (burnLeft <= 0 && energy + perTick * 20 <= energyCapacity()) {
			ItemStack fuel = items.get(0);
			int burn = fuel.isEmpty() ? 0 : ResolvableInt.getFromItem(fuel, DataComponents.COOKING_FUEL, CookingFuel::burnTime, getLootContext(level), 0);
			if (burn > 0) {
				burnLeft = progressMax = (int) Math.round(burn * spec.fuel());
				ItemStackTemplate remainder = fuel.getItem().getCraftingRemainder();
				fuel.shrink(1);
				if (remainder != null) {
					if (fuel.isEmpty()) items.set(0, remainder.create());
					else Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY() + 1, worldPosition.getZ(), remainder.create());
				}
				setChanged();
			}
		}
		if (burnLeft > 0) {
			if (energy < energyCapacity()) {
				burnLeft--;
				energy = Math.min(energyCapacity(), energy + perTick);
				powerKw = spec.power();
			}
		}
		progress = burnLeft;
		boolean lit = burnLeft > 0;
		if (getBlockState().getValue(MachineBlock.LIT) != lit) level.setBlockAndUpdate(worldPosition, getBlockState().setValue(MachineBlock.LIT, lit));
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		burnLeft = input.getIntOr("burn", 0);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putInt("burn", burnLeft);
	}
}
