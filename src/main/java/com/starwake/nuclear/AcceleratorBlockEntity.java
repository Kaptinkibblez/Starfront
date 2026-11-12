package com.starwake.nuclear;

import com.starwake.machine.EnergyNode;
import com.starwake.machine.MachineBlock;
import com.starwake.machine.MachineBlockEntity;
import com.starwake.machine.MachineMenu;
import com.starwake.metal.Composition;
import com.starwake.metal.MetalItems;
import com.starwake.registry.ModNuclear;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Particle Accelerator (M8g): heavy-ion fusion-evaporation for the elements no reactor can breed (Md to Og).
 * A target element and a beam element (the real pairs: Og = Cf + Ca-48, Fl = Pu + Ca, Hs = Pb + Fe...) make
 * one sample of the product for an enormous amount of energy (the nuclear data; GJ per sample). The beam item
 * lasts 16 samples; the target is used up per sample. Products are the element items (samples for superheavies).
 */
public class AcceleratorBlockEntity extends MachineBlockEntity {
	public static final MachineMenu.Layout LAYOUT = new MachineMenu.Layout(
			List.of(new int[] {30, 18}, new int[] {30, 40}, new int[] {120, 29}),
			new int[] {0, 1}, new int[] {2}, new int[] {}, new int[] {72, 33}, MachineMenu.Layout.NO_TANKS);
	public static final int TARGET = 0, BEAM = 1, OUTPUT = 2, BEAM_USES = 16;

	private String product = "";
	private long energyNeeded, energyDone;
	private int beamLeft;
	private String beamElement = "";

	public AcceleratorBlockEntity(BlockPos pos, BlockState state) {
		super(ModNuclear.ACCELERATOR_BE, pos, state);
	}

	@Override
	public MachineMenu.Layout layout() {
		return LAYOUT;
	}

	@Override
	protected MenuType<MachineMenu> menuType() {
		return ModNuclear.ACCELERATOR_MENU;
	}

	@Override
	public Role energyRole() {
		return Role.CONSUMER;
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return slot < 2 && !Nuclear.elementOf(stack).isEmpty();
	}

	private static String base(ItemStack stack) {
		return Nuclear.elementOf(stack);
	}

	@Override
	protected void work(ServerLevel level) {
		powerKw = 0;
		boolean working = false;
		if (product.isEmpty()) {
			ItemStack target = items.get(TARGET), beam = items.get(BEAM);
			String b = beamLeft > 0 ? beamElement : base(beam);
			var route = Nuclear.get().collision(base(target), b).orElse(null);
			if (route != null && items.get(OUTPUT).getCount() < 64) {
				if (beamLeft <= 0) {
					beam.shrink(1);
					beamElement = b;
					beamLeft = BEAM_USES;
				}
				beamLeft--;
				target.shrink(1);
				product = route.product();
				energyNeeded = Math.round(route.energy());
				energyDone = 0;
				setChanged();
			}
		}
		if (!product.isEmpty()) {
			if (drawPower(spec().power())) {
				working = true;
				energyDone += EnergyNode.perTick(spec().power());
			}
			if (energyDone >= energyNeeded) {
				ItemStack made = FissionReactorBlockEntity.elementItem(product, ItemStack.EMPTY);
				ItemStack out = items.get(OUTPUT);
				if (out.isEmpty()) items.set(OUTPUT, made);
				else if (ItemStack.isSameItemSameComponents(out, made)) out.grow(1);
				else return;
				product = "";
				energyNeeded = energyDone = 0;
				setChanged();
			}
		}
		progressMax = 1000;
		progress = energyNeeded <= 0 ? 0 : (int) Math.min(1000, energyDone * 1000 / Math.max(1, energyNeeded));
		if (getBlockState().getValue(MachineBlock.LIT) != working) level.setBlockAndUpdate(worldPosition, getBlockState().setValue(MachineBlock.LIT, working));
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		product = input.getStringOr("product", "");
		energyNeeded = input.getLongOr("energy_needed", 0);
		energyDone = input.getLongOr("energy_done", 0);
		beamLeft = input.getIntOr("beam_left", 0);
		beamElement = input.getStringOr("beam_element", "");
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putString("product", product);
		output.putLong("energy_needed", energyNeeded);
		output.putLong("energy_done", energyDone);
		output.putInt("beam_left", beamLeft);
		output.putString("beam_element", beamElement);
	}
}
