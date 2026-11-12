package com.starwake.nuclear;

import com.starwake.machine.EnergyNode;
import com.starwake.machine.MachineBlock;
import com.starwake.machine.MachineBlockEntity;
import com.starwake.machine.MachineMenu;
import com.starwake.process.ProcessMachineBlockEntity;
import com.starwake.process.Isotopes;
import com.starwake.registry.ModNuclear;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Gas Centrifuge (M8g): a short cascade section for uranium hexafluoride. It takes two 1 kg lots of UF6 (the
 * fluorinator's sublimate, stamped with its U-235 share) per cycle and splits them at a cut of one half: the
 * heads' U-235/U-238 ratio is the feed's x {@code centrifuge_beta}, and the tails take what's left (mass balance).
 * Feed the product back in to keep enriching (natural 0.72% -> 5% in about four passes, each halving the mass),
 * or the tails for depleted uranium. Energy per kg of feed from the nuclear data. Fluorinate UF4 with F2 in the
 * Roasting Kiln; reduce enriched UF6 back with H2 there too.
 */
public class GasCentrifugeBlockEntity extends MachineBlockEntity {
	public static final MachineMenu.Layout LAYOUT = new MachineMenu.Layout(
			List.of(new int[] {34, 28}, new int[] {110, 18}, new int[] {110, 40}),
			new int[] {0}, new int[] {1, 2}, new int[] {}, new int[] {66, 33}, MachineMenu.Layout.NO_TANKS);
	public static final int FEED = 0, HEADS = 1, TAILS = 2;
	public static final int SHARE_IN = MachineMenu.TANKS + 3, SHARE_HEADS = MachineMenu.TANKS + 4;

	private ItemStack feed = ItemStack.EMPTY;
	private long energyNeeded, energyDone;
	private double lastIn, lastHeads;

	public GasCentrifugeBlockEntity(BlockPos pos, BlockState state) {
		super(ModNuclear.GAS_CENTRIFUGE_BE, pos, state);
	}

	@Override
	public MachineMenu.Layout layout() {
		return LAYOUT;
	}

	@Override
	protected MenuType<MachineMenu> menuType() {
		return ModNuclear.GAS_CENTRIFUGE_MENU;
	}

	@Override
	public Role energyRole() {
		return Role.CONSUMER;
	}

	static boolean hexafluoride(ItemStack stack) {
		Map<String, Double> m = ProcessMachineBlockEntity.material(stack).orElse(Map.of());
		double total = m.values().stream().mapToDouble(Double::doubleValue).sum();
		return total > 0 && m.getOrDefault("UF6", 0.0) / total > 0.5;
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return slot == FEED && hexafluoride(stack);
	}

	/** Heads share for a feed share x and separation factor beta on the abundance ratio. */
	public static double heads(double x, double beta) {
		double r = x / (1 - x) * beta;
		return r / (1 + r);
	}

	@Override
	protected void work(ServerLevel level) {
		powerKw = 0;
		boolean working = false;
		if (feed.isEmpty()) {
			ItemStack in = items.get(FEED);
			if (in.getCount() >= 2 && hexafluoride(in) && room(HEADS, in) && room(TAILS, in)) {
				feed = in.split(2);
				energyNeeded = Math.max(EnergyNode.perTick(spec().power()) * 20, Math.round(Nuclear.get().centrifugeJPerKg() * 2));
				energyDone = 0;
				setChanged();
			}
		}
		if (!feed.isEmpty()) {
			if (drawPower(spec().power())) {
				working = true;
				energyDone += EnergyNode.perTick(spec().power());
			}
			if (energyDone >= energyNeeded) {
				double x = Isotopes.of(feed);
				double xp = heads(x, Nuclear.get().centrifugeBeta());
				double xt = Math.max(0, 2 * x - xp);
				ItemStack heads = feed.copyWithCount(1), tails = feed.copyWithCount(1);
				Isotopes.stamp(heads, xp);
				Isotopes.stamp(tails, xt);
				put(HEADS, heads);
				put(TAILS, tails);
				lastIn = x;
				lastHeads = xp;
				feed = ItemStack.EMPTY;
				energyNeeded = energyDone = 0;
				setChanged();
			}
		}
		progressMax = (int) Math.min(32000, Math.max(1, energyNeeded / Math.max(1, EnergyNode.perTick(spec().power()))));
		progress = energyNeeded <= 0 ? 0 : (int) Math.min(progressMax, energyDone * progressMax / Math.max(1, energyNeeded));
		if (getBlockState().getValue(MachineBlock.LIT) != working) level.setBlockAndUpdate(worldPosition, getBlockState().setValue(MachineBlock.LIT, working));
	}

	private boolean room(int slot, ItemStack like) {
		ItemStack s = items.get(slot);
		return s.isEmpty() || s.getCount() < s.getMaxStackSize() - 1;
	}

	private void put(int slot, ItemStack stack) {
		ItemStack s = items.get(slot);
		if (s.isEmpty()) items.set(slot, stack);
		else if (ItemStack.isSameItemSameComponents(s, stack) && s.getCount() < s.getMaxStackSize()) s.grow(1);
		else net.minecraft.world.Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 1, worldPosition.getZ() + 0.5, stack);
	}

	@Override
	protected void writeData(int[] v) {
		v[SHARE_IN] = (int) Math.round(lastIn * 10000);
		v[SHARE_HEADS] = (int) Math.round(lastHeads * 10000);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		feed = input.read("feed", ItemStack.CODEC).orElse(ItemStack.EMPTY);
		energyNeeded = input.getLongOr("energy_needed", 0);
		energyDone = input.getLongOr("energy_done", 0);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		if (!feed.isEmpty()) output.store("feed", ItemStack.CODEC, feed);
		output.putLong("energy_needed", energyNeeded);
		output.putLong("energy_done", energyDone);
	}
}
