package com.starwake.metal;

import com.starwake.machine.EnergyNode;
import com.starwake.machine.MachineBlock;
import com.starwake.machine.MachineBlockEntity;
import com.starwake.machine.MachineMenu;
import com.starwake.process.BulkItem;
import com.starwake.process.ProcessMachineBlockEntity;
import com.starwake.process.Species;
import com.starwake.registry.ModComponents;
import com.starwake.registry.ModMetalworks;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Sintering Press (M8d-1): powder metallurgy for metals too refractory to melt (tungsten, molybdenum,
 * tantalum) and for cermets like WC-Co. Everything in the four powder slots (metal dust, element
 * powders, coke as a carbon source) is blended by mass, pressed and sintered in hydrogen below its
 * melting point, then cut into 1 kg "sintered" ingots. Energy: {@link #J_PER_KG} per kg.
 */
public class SinterPressBlockEntity extends MachineBlockEntity {
	public static final MachineMenu.Layout LAYOUT = new MachineMenu.Layout(
			List.of(new int[] {24, 18}, new int[] {44, 18}, new int[] {24, 40}, new int[] {44, 40}, new int[] {110, 29}),
			new int[] {0, 1, 2, 3}, new int[] {4}, new int[] {}, new int[] {72, 33}, MachineMenu.Layout.NO_TANKS);
	/** Pressing + sintering energy per kg (hydrogen furnace at ~2000 C for hours, amortised). */
	public static final double J_PER_KG = 3.0e6;

	private Map<String, Double> batch;
	private int lastSignature;
	private long stableSince;
	private long energyNeeded, energyDone;

	public SinterPressBlockEntity(BlockPos pos, BlockState state) {
		super(ModMetalworks.SINTER_PRESS_BE, pos, state);
	}

	@Override
	public MachineMenu.Layout layout() {
		return LAYOUT;
	}

	@Override
	protected MenuType<MachineMenu> menuType() {
		return ModMetalworks.SINTER_PRESS_MENU;
	}

	@Override
	public Role energyRole() {
		return Role.CONSUMER;
	}

	/** Powders: metal dust, element powders, coke/charcoal (carbon). */
	public static boolean isPowder(ItemStack stack) {
		if (stack.getItem() instanceof MetalItem m) return m.form() == MetalForm.DUST;
		if (stack.getItem() instanceof BulkItem b) return b.kind() == BulkItem.Kind.CHAR;
		return MetalItems.formOf(stack).map(f -> f == MetalForm.DUST).orElse(false);
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return slot < 4 && isPowder(stack);
	}

	/** Element masses (kg) in a stack of powder. */
	private static Map<String, Double> elements(ItemStack stack) {
		Map<String, Double> out = new HashMap<>();
		var comp = MetalItems.compositionOf(stack);
		if (comp.isPresent()) {
			double kg = MetalItems.formOf(stack).map(f -> f.kg).orElse(1.0) * stack.getCount();
			for (Composition.Part p : comp.get().parts()) out.merge(p.element(), p.percent() / 100 * kg, Double::sum);
			return out;
		}
		ProcessMachineBlockEntity.material(stack).ifPresent(m -> m.forEach((sp, kg) -> Species.get(sp).ifPresent(info ->
				info.elements().forEach((el, f) -> out.merge(el, f * kg * stack.getCount(), Double::sum)))));
		// Volatiles burn off in the hydrogen furnace: keep only what stays solid.
		out.remove("H");
		out.remove("O");
		out.remove("N");
		return out;
	}

	@Override
	protected void work(ServerLevel level) {
		powerKw = 0;
		boolean working = false;
		// Wait until the powders have stopped changing for 2 s, so a blend loaded slot by slot is pressed whole.
		int signature = 1;
		for (int s = 0; s < 4; s++) signature = signature * 31 + ItemStack.hashItemAndComponents(items.get(s)) * 7 + items.get(s).getCount();
		if (signature != lastSignature) {
			lastSignature = signature;
			stableSince = level.getGameTime();
		}
		if (batch == null && level.getGameTime() - stableSince >= 40) {
			double total = 0;
			Map<String, Double> blend = new HashMap<>();
			for (int s = 0; s < 4; s++) {
				ItemStack stack = items.get(s);
				if (stack.isEmpty()) continue;
				elements(stack).forEach((el, kg) -> blend.merge(el, kg, Double::sum));
			}
			for (double v : blend.values()) total += v;
			ItemStack out = items.get(4);
			if (total >= 1 - 1e-9 && out.isEmpty()) {
				for (int s = 0; s < 4; s++) items.set(s, ItemStack.EMPTY);
				batch = blend;
				energyNeeded = Math.round(J_PER_KG * total);
				energyDone = 0;
				setChanged();
			}
		}
		if (batch != null) {
			if (drawPower(spec().power())) {
				working = true;
				energyDone += EnergyNode.perTick(spec().power());
				if (energyDone >= energyNeeded) {
					double total = 0;
					for (double v : batch.values()) total += v;
					int n = (int) Math.floor(total + 1e-6);
					ItemStack made = MetalItems.stack(MetalForm.INGOT, Composition.of(batch), Math.max(1, Math.min(64, n)));
					made.set(ModComponents.CONDITION, "sintered");
					items.set(4, made);
					batch = null;
					energyNeeded = energyDone = 0;
					setChanged();
				}
			}
		}
		progressMax = (int) Math.min(32000, Math.max(1, energyNeeded / Math.max(1, EnergyNode.perTick(spec().power()))));
		progress = energyNeeded <= 0 ? 0 : (int) Math.min(progressMax, energyDone * progressMax / Math.max(1, energyNeeded));
		if (getBlockState().getValue(MachineBlock.LIT) != working) {
			level.setBlockAndUpdate(worldPosition, getBlockState().setValue(MachineBlock.LIT, working));
		}
	}

	@Override
	protected void loadAdditional(net.minecraft.world.level.storage.ValueInput input) {
		super.loadAdditional(input);
		batch = input.read("batch", com.mojang.serialization.Codec.unboundedMap(com.mojang.serialization.Codec.STRING,
				com.mojang.serialization.Codec.DOUBLE)).map(HashMap::new).orElse(null);
		energyNeeded = input.getLongOr("energy_needed", 0);
		energyDone = input.getLongOr("energy_done", 0);
	}

	@Override
	protected void saveAdditional(net.minecraft.world.level.storage.ValueOutput output) {
		super.saveAdditional(output);
		if (batch != null) output.store("batch", com.mojang.serialization.Codec.unboundedMap(com.mojang.serialization.Codec.STRING,
				com.mojang.serialization.Codec.DOUBLE), batch);
		output.putLong("energy_needed", energyNeeded);
		output.putLong("energy_done", energyDone);
	}
}
