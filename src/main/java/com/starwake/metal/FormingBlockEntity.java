package com.starwake.metal;

import com.starwake.machine.EnergyNode;
import com.starwake.machine.MachineBlock;
import com.starwake.machine.MachineBlockEntity;
import com.starwake.machine.MachineMenu;
import com.starwake.registry.ModMetalworks;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * Forming machines (M8d-2): Rolling Mill, Wire Drawer, Extrusion Press and Forging Press share this
 * block entity. Routes come from {@code data/starwake/starwake/forming/<machine>.json}: a piece of one
 * form (slab, rod, billet...) becomes another after a total true strain, taken one pass at a time
 * ({@code pass_strain}). Mass is conserved (a 10 kg slab rolls into ten 1 kg plates).
 *
 * <p>Cold mode: every pass adds cold work (thickness reduction since the last anneal, see
 * {@link Tempers#pass}); a piece that goes past its crack limit comes out cracked (scrap, remelt it),
 * so long routes need annealing in between, which means taking the half-formed piece (it keeps its
 * progress) to the Heat Treatment Furnace. Hot mode: the machine first reheats the piece to 75% of its
 * solidus (650 J/kg/K) and then works it with recrystallisation, so nothing hardens or cracks; hot-worked
 * steel comes out about normalised.
 *
 * <p>Energy per pass = flow stress x strain x volume, doubled for mill losses (the flow stress hot is 15%
 * of the cold tensile strength), at least one second of the machine's power.
 */
public class FormingBlockEntity extends MachineBlockEntity {
	public static final MachineMenu.Layout LAYOUT = new MachineMenu.Layout(
			List.of(new int[] {30, 22}, new int[] {62, 22}, new int[] {120, 22}),
			new int[] {0}, new int[] {2}, new int[] {}, new int[] {88, 27}, MachineMenu.Layout.NO_TANKS);
	public static final int INPUT = 0, PIECE = 1, OUTPUT = 2;
	public static final int IDLE = 0, WORKING = 1, HEATING = 2, CRACKED = 3, BLOCKED = 4;
	/** Data slots (the tank block is unused here): mode, progress x100, route strain x100, hot-work temperature. */
	public static final int MODE = MachineMenu.TANKS, PROGRESS_STRAIN = MachineMenu.TANKS + 1, ROUTE_STRAIN = MachineMenu.TANKS + 2,
			HOT_T = MachineMenu.TANKS + 3;

	private boolean hot;
	private boolean heated;
	private boolean heatingPass;
	private double passStrain;
	private long energyNeeded, energyDone;
	private int status;

	public FormingBlockEntity(BlockPos pos, BlockState state) {
		super(ModMetalworks.FORMING_BE, pos, state);
	}

	@Override
	public MachineMenu.Layout layout() {
		return LAYOUT;
	}

	public String machine() {
		return BuiltInRegistries.BLOCK.getKey(getBlockState().getBlock()).getPath();
	}

	@Override
	protected MenuType<MachineMenu> menuType() {
		return ModMetalworks.formingMenu(getBlockState().getBlock());
	}

	@Override
	public Role energyRole() {
		return Role.CONSUMER;
	}

	private Tempers.@Nullable Route route(ItemStack stack) {
		Tempers.Forming f = Tempers.forming(machine());
		if (f == null || stack.isEmpty() || MetalItems.compositionOf(stack).isEmpty()) return null;
		return MetalItems.formOf(stack).map(f::route).orElse(null);
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return slot == INPUT && (level == null || level.isClientSide() || route(stack) != null);
	}

	@Override
	public boolean onButton(int id, Player player) {
		if (id != 0) return false;
		hot = !hot;
		setChanged();
		return true;
	}

	@Override
	protected void work(ServerLevel level) {
		powerKw = 0;
		boolean working = false;
		ItemStack piece = items.get(PIECE);
		if (piece.isEmpty() && energyNeeded == 0) {
			ItemStack in = items.get(INPUT);
			if (route(in) != null) {
				items.set(PIECE, in.split(1));
				piece = items.get(PIECE);
				heated = false;
				setChanged();
			}
		}
		Tempers.Route route = route(piece);
		if (piece.isEmpty() || route == null) {
			status = IDLE;
			energyNeeded = energyDone = 0;
		} else {
			Metallurgy.Analysis a = Metallurgy.analyze(MetalItems.compositionOf(piece).orElseThrow());
			Tempers.Temper t = Tempers.of(piece);
			MetalForm form = MetalItems.formOf(piece).orElseThrow();
			double kg = form.kg;
			if (energyNeeded == 0) {
				// Plan the next pass (or the reheat before the first hot pass).
				Tempers.Forming f = Tempers.forming(machine());
				double left = route.strain() - t.progress();
				passStrain = Math.max(0.01, Math.min(f == null ? left : f.passStrain(), left));
				MaterialProperties p = Tempers.properties(a, t, form);
				double flow = p.tensileStrength() * 1e6 * (hot ? 0.15 : 1.0);
				double volume = kg / Math.max(1000, p.density() * 1000);
				double joules = 2 * flow * passStrain * volume;
				heatingPass = hot && !heated;
				if (heatingPass) joules = 650.0 * (Tempers.hotWorkTemperature(a) - 25) * kg;
				energyNeeded = Math.max(Math.round(joules), EnergyNode.perTick(spec().power()) * 20);
				energyDone = 0;
			}
			if (drawPower(spec().power())) {
				working = true;
				energyDone += EnergyNode.perTick(spec().power());
			}
			status = heatingPass ? HEATING : WORKING;
			if (energyDone >= energyNeeded) {
				if (heatingPass) {
					heated = true;
				} else {
					Tempers.Temper next = Tempers.pass(a, t, passStrain, hot);
					ItemStack worked = piece.copy();
					Tempers.set(worked, next);
					items.set(PIECE, worked);
				}
				energyNeeded = energyDone = 0;
				setChanged();
			}
			finish();
		}
		values[MachineMenu.STATUS] = status;
		progressMax = (int) Math.min(32000, Math.max(1, energyNeeded / Math.max(1, EnergyNode.perTick(spec().power()))));
		progress = energyNeeded <= 0 ? 0 : (int) Math.min(progressMax, energyDone * progressMax / Math.max(1, energyNeeded));
		if (getBlockState().getValue(MachineBlock.LIT) != working) {
			level.setBlockAndUpdate(worldPosition, getBlockState().setValue(MachineBlock.LIT, working));
		}
	}

	/** Moves a finished (or cracked) piece to the output, split into the target form. */
	private void finish() {
		ItemStack piece = items.get(PIECE);
		Tempers.Route route = route(piece);
		if (route == null || energyNeeded != 0) return;
		Tempers.Temper t = Tempers.of(piece);
		boolean cracked = t.state().equals("cracked");
		if (!cracked && t.progress() < route.strain() - 1e-4) return;
		ItemStack out;
		if (cracked) {
			out = piece.copy();
			Tempers.set(out, t.withProgress(0));
		} else {
			MetalForm from = MetalForm.byId(route.from()), to = MetalForm.byId(route.to());
			int n = Math.max(1, (int) Math.round(from.kg / to.kg));
			out = MetalItems.stack(to, MetalItems.compositionOf(piece).orElseThrow(), n);
			Tempers.set(out, t.withProgress(0));
		}
		ItemStack slot = items.get(OUTPUT);
		if (!slot.isEmpty() && (!ItemStack.isSameItemSameComponents(slot, out) || slot.getCount() + out.getCount() > slot.getMaxStackSize())) {
			status = BLOCKED;
			return;
		}
		if (slot.isEmpty()) items.set(OUTPUT, out);
		else slot.grow(out.getCount());
		items.set(PIECE, ItemStack.EMPTY);
		heated = false;
		status = cracked ? CRACKED : IDLE;
		setChanged();
	}

	@Override
	protected void writeData(int[] v) {
		v[MODE] = hot ? 1 : 0;
		ItemStack piece = items.get(PIECE).isEmpty() ? items.get(INPUT) : items.get(PIECE);
		Tempers.Route route = route(piece);
		v[PROGRESS_STRAIN] = piece.isEmpty() ? 0 : Math.round(Tempers.of(piece).progress() * 100);
		v[ROUTE_STRAIN] = route == null ? 0 : (int) Math.round(route.strain() * 100);
		v[HOT_T] = piece.isEmpty() ? 0 : MetalItems.compositionOf(piece).map(c -> Tempers.hotWorkTemperature(Metallurgy.analyze(c))).orElse(0);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		hot = input.getBooleanOr("hot", false);
		heated = input.getBooleanOr("heated", false);
		heatingPass = input.getBooleanOr("heating_pass", false);
		passStrain = input.getDoubleOr("pass_strain", 0);
		energyNeeded = input.getLongOr("energy_needed", 0);
		energyDone = input.getLongOr("energy_done", 0);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putBoolean("hot", hot);
		output.putBoolean("heated", heated);
		output.putBoolean("heating_pass", heatingPass);
		output.putDouble("pass_strain", passStrain);
		output.putLong("energy_needed", energyNeeded);
		output.putLong("energy_done", energyDone);
	}
}
