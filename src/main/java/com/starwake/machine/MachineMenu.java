package com.starwake.machine;

import java.util.List;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The menu behind every machine screen (M8b): the machine's slots (from its {@link Layout}), the
 * player's inventory, and synced data: energy, capacity, progress, power and the grid's supply
 * and demand. Data slots travel as shorts, so 32-bit values use two slots ({@link #read}).
 */
public class MachineMenu extends AbstractContainerMenu {
	/** Data slot indices. */
	/** Top of the player inventory: the screen keeps an info strip above it. */
	public static final int INVENTORY_Y = 104;
	public static final int ENERGY = 0, CAPACITY = 2, PROGRESS = 4, PROGRESS_MAX = 5, POWER = 6, SUPPLY = 8, DEMAND = 10, STATUS = 12;
	/** Up to three tank gauges (inlet, gas outlet, liquid outlet): amount in centi-kg, capacity in kg, main species index. */
	public static final int TANKS = 13, COUNT = TANKS + 9;

	/**
	 * Slot positions and roles of a machine.
	 *
	 * @param arrow progress arrow position {x, y}, or null
	 * @param tanks gauge x positions for the inlet, gas and liquid tanks (-1 = none)
	 */
	public record Layout(List<int[]> slots, int[] inputs, int[] outputs, int[] fuel, int @org.jspecify.annotations.Nullable [] arrow, int[] tanks) {
		public static final int[] NO_TANKS = {-1, -1, -1};

		public int size() {
			return slots.size();
		}

		boolean isOutput(int i) {
			for (int o : outputs) if (o == i) return true;
			return false;
		}
	}

	public final Layout layout;
	private final Container container;
	private final ContainerData data;

	/** Client side. */
	public MachineMenu(MenuType<?> type, Layout layout, int id, Inventory inventory) {
		this(type, layout, id, inventory, new SimpleContainer(layout.size()), new SimpleContainerData(COUNT));
	}

	public MachineMenu(MenuType<?> type, Layout layout, int id, Inventory inventory, Container container, ContainerData data) {
		super(type, id);
		this.layout = layout;
		this.container = container;
		this.data = data;
		for (int i = 0; i < layout.size(); i++) {
			int[] xy = layout.slots().get(i);
			addSlot(layout.isOutput(i) ? new Slot(container, i, xy[0], xy[1]) {
				@Override
				public boolean mayPlace(ItemStack stack) {
					return false;
				}
			} : new Slot(container, i, xy[0], xy[1]) {
				@Override
				public boolean mayPlace(ItemStack stack) {
					return container.canPlaceItem(getContainerSlot(), stack);
				}
			});
		}
		addStandardInventorySlots(inventory, 8, INVENTORY_Y);
		addDataSlots(data);
	}

	/** A 32-bit value stored across two data slots. */
	public int read(int index) {
		return (data.get(index) << 16) | (data.get(index + 1) & 0xFFFF);
	}

	public int get(int index) {
		return data.get(index);
	}

	static void write(int[] values, int index, int value) {
		values[index] = value >> 16;
		values[index + 1] = (short) (value & 0xFFFF);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = slots.get(index);
		if (!slot.hasItem()) return ItemStack.EMPTY;
		ItemStack stack = slot.getItem();
		ItemStack copy = stack.copy();
		int machine = layout.size();
		if (index < machine) {
			if (!moveItemStackTo(stack, machine, slots.size(), true)) return ItemStack.EMPTY;
		} else if (!moveItemStackTo(stack, 0, machine, false)) {
			return ItemStack.EMPTY;
		}
		if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
		else slot.setChanged();
		return copy;
	}

	@Override
	public boolean stillValid(Player player) {
		return container.stillValid(player);
	}
}
