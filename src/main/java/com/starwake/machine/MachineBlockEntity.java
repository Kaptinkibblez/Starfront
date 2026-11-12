package com.starwake.machine;

import com.starwake.logistics.ConveyorBlock;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * Base for grid machines with an inventory and a screen (M8b): an energy buffer, slots with roles
 * from a {@link MachineMenu.Layout} (inputs accept from any side, outputs come out of any side,
 * so hoppers and conveyors just work), synced screen data, and automatic output: every 10 ticks
 * finished items are pushed into neighbouring inventories or onto conveyors leading away.
 * Numbers come from {@link MachineSpec}.
 */
public abstract class MachineBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer, EnergyNode {
	protected NonNullList<ItemStack> items;
	protected long energy;
	protected int progress, progressMax;
	/** kW being produced (+) or drawn (-) this tick, for the screen. */
	protected double powerKw;
	protected final int[] values = new int[MachineMenu.COUNT];
	private final ContainerData data = new ContainerData() {
		@Override
		public int get(int index) {
			return values[index];
		}

		@Override
		public void set(int index, int value) {
			values[index] = value;
		}

		@Override
		public int getCount() {
			return MachineMenu.COUNT;
		}
	};

	protected MachineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
		this.items = NonNullList.withSize(layout().size(), ItemStack.EMPTY);
	}

	public abstract MachineMenu.Layout layout();

	protected abstract net.minecraft.world.inventory.MenuType<MachineMenu> menuType();

	/** Machine logic, server side, once per tick. */
	protected abstract void work(ServerLevel level);

	public MachineSpec spec() {
		return MachineSpec.of(getBlockState().getBlock());
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, MachineBlockEntity machine) {
		if (!(level instanceof ServerLevel server)) return;
		PowerNetworks.Network network = PowerNetworks.networkOf(server, pos);
		machine.work(server);
		if ((server.getGameTime() + pos.asLong()) % 10 == 0) machine.eject(server);
		int[] v = machine.values;
		MachineMenu.write(v, MachineMenu.ENERGY, (int) (machine.energy / 1000));
		MachineMenu.write(v, MachineMenu.CAPACITY, (int) (machine.energyCapacity() / 1000));
		v[MachineMenu.PROGRESS] = machine.progress;
		v[MachineMenu.PROGRESS_MAX] = machine.progressMax;
		MachineMenu.write(v, MachineMenu.POWER, (int) Math.round(machine.powerKw * 1000));
		MachineMenu.write(v, MachineMenu.SUPPLY, (int) Math.min(Integer.MAX_VALUE, Math.round(EnergyNode.kw(network.lastGeneration) * 1000)));
		MachineMenu.write(v, MachineMenu.DEMAND, (int) Math.min(Integer.MAX_VALUE, Math.round(EnergyNode.kw(network.lastDemand) * 1000)));
		machine.writeData(v);
	}

	/** A screen button pressed (menu button id); return true if handled. Server side. */
	public boolean onButton(int id, net.minecraft.world.entity.player.Player player) {
		return false;
	}

	/** Extra screen data (tank gauges) for subclasses. */
	protected void writeData(int[] values) {}

	// --- Energy -----------------------------------------------------------------------------------

	@Override
	public long energyStored() {
		return energy;
	}

	@Override
	public void setEnergyStored(long joules) {
		energy = Math.clamp(joules, 0, energyCapacity());
		setChanged();
	}

	@Override
	public long energyCapacity() {
		return spec().bufferJ();
	}

	@Override
	public long maxEnergyInput() {
		return energyRole() == Role.GENERATOR ? 0 : EnergyNode.perTick(spec().io());
	}

	@Override
	public long maxEnergyOutput() {
		return energyRole() == Role.CONSUMER ? 0 : EnergyNode.perTick(spec().io());
	}

	/** Draws a tick's worth of power for work at {@code kw}; false (and nothing drawn) if short. */
	protected boolean drawPower(double kw) {
		long need = EnergyNode.perTick(kw);
		if (energy < need) return false;
		energy -= need;
		powerKw = -kw;
		return true;
	}

	@Override
	public void setRemoved() {
		super.setRemoved();
		if (level != null) PowerNetworks.invalidate(level, worldPosition);
	}

	@Override
	public void clearRemoved() {
		super.clearRemoved();
		if (level != null) PowerNetworks.invalidate(level, worldPosition);
	}

	// --- Output -----------------------------------------------------------------------------------

	/** Pushes output slots into neighbouring inventories, or onto conveyors that lead away. */
	protected void eject(ServerLevel level) {
		for (int slot : layout().outputs()) {
			ItemStack stack = items.get(slot);
			if (stack.isEmpty()) continue;
			for (Direction d : Direction.values()) {
				if (stack.isEmpty()) break;
				BlockPos next = worldPosition.relative(d);
				BlockState there = level.getBlockState(next);
				if (there.getBlock() instanceof ConveyorBlock && there.getValue(ConveyorBlock.FACING) == d) {
					ItemStack out = stack.split(Math.min(stack.getCount(), 8));
					ItemEntity entity = new ItemEntity(level, next.getX() + 0.5, next.getY() + 0.2, next.getZ() + 0.5, out, 0, 0, 0);
					entity.setPickUpDelay(20);
					level.addFreshEntity(entity);
					continue;
				}
				if (d == Direction.UP) continue;
				var storage = ItemStorage.SIDED.find(level, next, d.getOpposite());
				if (storage == null || level.getBlockEntity(next) instanceof MachineBlockEntity) continue;
				try (Transaction tx = Transaction.openOuter()) {
					long moved = storage.insert(ItemVariant.of(stack), stack.getCount(), tx);
					if (moved > 0) {
						stack.shrink((int) moved);
						tx.commit();
					}
				}
			}
			items.set(slot, stack);
			setChanged();
		}
	}

	// --- Container ----------------------------------------------------------------------------------

	@Override
	public int getContainerSize() {
		return items.size();
	}

	@Override
	protected NonNullList<ItemStack> getItems() {
		return items;
	}

	@Override
	protected void setItems(NonNullList<ItemStack> items) {
		this.items = items;
	}

	@Override
	public int[] getSlotsForFace(Direction side) {
		int[] all = new int[items.size()];
		for (int i = 0; i < all.length; i++) all[i] = i;
		return all;
	}

	@Override
	public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
		return canPlaceItem(slot, stack);
	}

	@Override
	public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
		for (int o : layout().outputs()) if (o == slot) return true;
		return false;
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		for (int o : layout().outputs()) if (o == slot) return false;
		return true;
	}

	@Override
	protected Component getDefaultName() {
		return getBlockState().getBlock().getName();
	}

	@Override
	protected AbstractContainerMenu createMenu(int id, Inventory inventory) {
		return new MachineMenu(menuType(), layout(), id, inventory, this, data);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		items = NonNullList.withSize(layout().size(), ItemStack.EMPTY);
		ContainerHelper.loadAllItems(input, items);
		energy = input.getLongOr("energy", 0);
		progress = input.getIntOr("progress", 0);
		progressMax = input.getIntOr("progress_max", 0);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		ContainerHelper.saveAllItems(output, items);
		output.putLong("energy", energy);
		output.putInt("progress", progress);
		output.putInt("progress_max", progressMax);
	}
}
