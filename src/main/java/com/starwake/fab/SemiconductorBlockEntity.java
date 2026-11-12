package com.starwake.fab;

import com.starwake.machine.EnergyNode;
import com.starwake.machine.MachineBlock;
import com.starwake.machine.MachineBlockEntity;
import com.starwake.machine.MachineMenu;
import com.starwake.metal.Composition;
import com.starwake.metal.MetalForm;
import com.starwake.metal.MetalItems;
import com.starwake.process.ChemicalTank;
import com.starwake.process.Species;
import com.starwake.registry.ModComponents;
import com.starwake.registry.ModFabrication;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Semiconductor machines (M8e-1), one block entity for two blocks.
 *
 * <p><b>Crystal Puller</b> (Czochralski): melts electronic-grade silicon (no impurity above the 10 ppm
 * our compositions resolve, i.e. at least 99.999%) under argon, adds a dopant and pulls a single
 * crystal that is sliced into ten wafers per kg. Boron (or Ga, Al) makes p-type, phosphorus (or As,
 * Sb) n-type. One dopant item dopes {@link #DOSES} kg of silicon; the dopant shows up in the wafer's
 * composition at the 10 ppm floor. Uses 0.05 kg argon and {@link #PULL_J_PER_KG} per kg.
 *
 * <p><b>Clean Fab</b> (lithography, implantation, metallisation, dicing, packaging): a doped wafer plus a
 * dopant of the opposite type (the junctions) plus a high-purity interconnect metal (Al or Cu wire,
 * 99.99%+) becomes {@link #CHIPS_PER_WAFER} microchips. One dopant item serves {@link #DOSES} wafers;
 * one wire item serves {@link #WIRE_WAFERS}.
 */
public class SemiconductorBlockEntity extends MachineBlockEntity {
	public static final MachineMenu.Layout PULLER = new MachineMenu.Layout(
			List.of(new int[] {30, 22}, new int[] {30, 44}, new int[] {120, 30}),
			new int[] {0, 1}, new int[] {2}, new int[] {}, new int[] {72, 35}, new int[] {150, -1, -1});
	public static final MachineMenu.Layout FAB = new MachineMenu.Layout(
			List.of(new int[] {30, 22}, new int[] {30, 44}, new int[] {50, 44}, new int[] {120, 30}),
			new int[] {0, 1, 2}, new int[] {3}, new int[] {}, new int[] {72, 35}, MachineMenu.Layout.NO_TANKS);
	public static final int IDLE = 0, WORKING = 1, IMPURE = 2, NO_DOPANT = 3, NO_ARGON = 4, NO_METAL = 5, BLOCKED = 6, SAME_TYPE = 7;
	/** Data slot (in the tank block, after the gauges) holding the dopant doses left. */
	public static final int DOSES_LEFT = MachineMenu.TANKS + 3, METAL_LEFT = MachineMenu.TANKS + 4;
	public static final int DOSES = 100, WIRE_WAFERS = 8, CHIPS_PER_WAFER = 8, WAFERS_PER_KG = 10;
	public static final double PULL_J_PER_KG = 30e6, FAB_J_PER_WAFER = 5e6, ARGON_PER_KG = 0.05;
	/** Impurities allowed in electronic-grade silicon, ppm (our composition resolution). */
	public static final int EG_IMPURITY_PPM = 10;
	public static final Set<String> P_TYPE = Set.of("B", "Ga", "Al"), N_TYPE = Set.of("P", "As", "Sb");

	private final boolean fab;
	private final ChemicalTank argon;
	/** Dopant element and doses left, interconnect metal and wafers left. */
	private String dopant = "";
	private int doses;
	private String metal = "";
	private int metalLeft;
	private ItemStack batch = ItemStack.EMPTY;
	private long energyNeeded, energyDone;
	private int status;

	public SemiconductorBlockEntity(BlockPos pos, BlockState state) {
		super(ModFabrication.SEMICONDUCTOR_BE, pos, state);
		this.fab = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().equals("clean_fab");
		this.argon = new ChemicalTank(8, true, false, s -> s.equals("Ar"), this::setChanged);
	}

	/** Read from the block state: {@code layout()} runs in the superclass constructor, before {@link #fab} is set. */
	private boolean isFab() {
		return BuiltInRegistries.BLOCK.getKey(getBlockState().getBlock()).getPath().equals("clean_fab");
	}

	@Override
	public MachineMenu.Layout layout() {
		return isFab() ? FAB : PULLER;
	}

	@Override
	protected MenuType<MachineMenu> menuType() {
		return fab ? ModFabrication.CLEAN_FAB_MENU : ModFabrication.CRYSTAL_PULLER_MENU;
	}

	@Override
	public Role energyRole() {
		return Role.CONSUMER;
	}

	public Storage<FluidVariant> fluidStorage() {
		return argon;
	}

	/** The dopant element an item supplies (a pure-ish element item or powder), or null. */
	static String dopantOf(ItemStack stack) {
		Composition c = MetalItems.compositionOf(stack).orElse(null);
		if (c == null || c.isEmpty()) return null;
		String base = c.base();
		return P_TYPE.contains(base) || N_TYPE.contains(base) ? base : null;
	}

	static boolean pType(String element) {
		return P_TYPE.contains(element);
	}

	/** Electronic grade: silicon with no impurity above {@link #EG_IMPURITY_PPM}. */
	public static boolean electronicGrade(Composition c) {
		return c.base().equals("Si") && c.percent("Si") >= 100 - EG_IMPURITY_PPM / 1e4 - 1e-9;
	}

	/** A wafer's dopant (the largest non-Si element), or null for intrinsic silicon. */
	public static String waferDopant(ItemStack wafer) {
		Composition c = wafer.get(ModComponents.COMPOSITION);
		if (c == null) return null;
		String best = null;
		double most = 0;
		for (Composition.Part p : c.parts()) {
			if (!p.element().equals("Si") && p.percent() > most && (P_TYPE.contains(p.element()) || N_TYPE.contains(p.element()))) {
				most = p.percent();
				best = p.element();
			}
		}
		return best;
	}

	private static boolean interconnect(ItemStack stack) {
		Composition c = MetalItems.compositionOf(stack).orElse(null);
		return c != null && (c.base().equals("Al") || c.base().equals("Cu")) && c.percent(c.base()) >= 99.99 - 1e-9;
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		if (fab) {
			return switch (slot) {
				case 0 -> stack.is(ModFabrication.SILICON_WAFER);
				case 1 -> dopantOf(stack) != null;
				case 2 -> interconnect(stack);
				default -> false;
			};
		}
		return switch (slot) {
			case 0 -> MetalItems.compositionOf(stack).map(c -> c.base().equals("Si")).orElse(false);
			case 1 -> dopantOf(stack) != null;
			default -> false;
		};
	}

	@Override
	protected void work(ServerLevel level) {
		powerKw = 0;
		boolean working = false;
		int output = fab ? 3 : 2;
		// Load a dopant (and interconnect metal) when the current one runs out.
		ItemStack d = items.get(1);
		if (doses <= 0 && !d.isEmpty() && dopantOf(d) != null) {
			dopant = dopantOf(d);
			doses = DOSES;
			d.shrink(1);
			setChanged();
		}
		if (fab && metalLeft <= 0 && interconnect(items.get(2))) {
			metal = MetalItems.compositionOf(items.get(2)).orElseThrow().base();
			metalLeft = WIRE_WAFERS;
			items.get(2).shrink(1);
			setChanged();
		}
		if (batch.isEmpty()) {
			energyNeeded = energyDone = 0;
			status = start(level);
		}
		if (!batch.isEmpty()) {
			status = WORKING;
			if (energyDone < energyNeeded && drawPower(spec().power())) {
				working = true;
				energyDone += EnergyNode.perTick(spec().power());
			}
			if (energyDone >= energyNeeded) {
				ItemStack slot = items.get(output);
				if (slot.isEmpty()) items.set(output, batch);
				else if (ItemStack.isSameItemSameComponents(slot, batch) && slot.getCount() + batch.getCount() <= slot.getMaxStackSize()) slot.grow(batch.getCount());
				else status = BLOCKED;
				if (status != BLOCKED) {
					batch = ItemStack.EMPTY;
					energyNeeded = energyDone = 0;
					status = IDLE;
				}
				setChanged();
			}
		}
		progressMax = (int) Math.min(32000, Math.max(1, energyNeeded / Math.max(1, EnergyNode.perTick(spec().power()))));
		progress = energyNeeded <= 0 ? 0 : (int) Math.min(progressMax, energyDone * progressMax / Math.max(1, energyNeeded));
		if (getBlockState().getValue(MachineBlock.LIT) != working) {
			level.setBlockAndUpdate(worldPosition, getBlockState().setValue(MachineBlock.LIT, working));
		}
	}

	/** Takes the next charge if everything it needs is there; returns the status. */
	private int start(ServerLevel level) {
		ItemStack in = items.get(0);
		if (in.isEmpty()) return IDLE;
		ItemStack out = items.get(fab ? 3 : 2);
		if (!out.isEmpty() && out.getCount() > out.getMaxStackSize() - (fab ? CHIPS_PER_WAFER : WAFERS_PER_KG)) return BLOCKED;
		if (doses <= 0) return NO_DOPANT;
		if (fab) {
			String type = waferDopant(in);
			if (type != null && pType(type) == pType(dopant)) return SAME_TYPE;
			if (metalLeft <= 0) return NO_METAL;
			in.shrink(1);
			doses--;
			metalLeft--;
			batch = new ItemStack(ModFabrication.MICROCHIP, CHIPS_PER_WAFER);
			energyNeeded = Math.round(FAB_J_PER_WAFER);
		} else {
			Composition c = MetalItems.compositionOf(in).orElse(null);
			if (c == null || !electronicGrade(c)) return IMPURE;
			double kg = MetalItems.formOf(in).map(f -> f.kg).orElse(1.0);
			if (argon.kg("Ar") < ARGON_PER_KG * kg - 1e-9) return NO_ARGON;
			argon.take("Ar", ARGON_PER_KG * kg);
			in.shrink(1);
			doses--;
			Map<String, Double> w = new HashMap<>();
			w.put("Si", 1.0 - EG_IMPURITY_PPM / 1e6);
			w.put(dopant, EG_IMPURITY_PPM / 1e6);
			batch = new ItemStack(ModFabrication.SILICON_WAFER, (int) Math.max(1, Math.round(WAFERS_PER_KG * kg)));
			batch.set(ModComponents.COMPOSITION, Composition.of(w));
			energyNeeded = Math.round(PULL_J_PER_KG * kg);
		}
		energyDone = 0;
		setChanged();
		return WORKING;
	}

	@Override
	protected void writeData(int[] v) {
		v[MachineMenu.STATUS] = status;
		int base = MachineMenu.TANKS;
		v[base] = (int) Math.min(32000, Math.round(argon.totalKg() * 100));
		v[base + 1] = (int) argon.capacityKg();
		v[base + 2] = argon.totalKg() > 0 ? Species.get("Ar").map(Species.Info::index).orElse(-1) : -1;
		v[DOSES_LEFT] = doses;
		v[METAL_LEFT] = metalLeft;
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		argon.read(input, "argon");
		dopant = input.getStringOr("dopant", "");
		doses = input.getIntOr("doses", 0);
		metal = input.getStringOr("metal", "");
		metalLeft = input.getIntOr("metal_left", 0);
		batch = input.read("batch", ItemStack.CODEC).orElse(ItemStack.EMPTY);
		energyNeeded = input.getLongOr("energy_needed", 0);
		energyDone = input.getLongOr("energy_done", 0);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		argon.write(output, "argon");
		output.putString("dopant", dopant);
		output.putInt("doses", doses);
		output.putString("metal", metal);
		output.putInt("metal_left", metalLeft);
		if (!batch.isEmpty()) output.store("batch", ItemStack.CODEC, batch);
		output.putLong("energy_needed", energyNeeded);
		output.putLong("energy_done", energyDone);
	}
}
