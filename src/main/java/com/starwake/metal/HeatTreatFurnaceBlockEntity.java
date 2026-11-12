package com.starwake.metal;

import com.starwake.machine.EnergyNode;
import com.starwake.machine.MachineBlock;
import com.starwake.machine.MachineBlockEntity;
import com.starwake.machine.MachineMenu;
import com.starwake.registry.ModMetalworks;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Heat Treatment Furnace (M8d-2): runs one {@link Tempers.Program} on a whole stack of metal at a time
 * (anneal, normalise, harden with a water/oil/air quench, temper, solution treat, age, stress relieve).
 * The furnace temperature is the grade's or family's for most programs (data in the grade JSON
 * {@code treatment} and {@code heat_treatment/default.json}); tempering and ageing use the operator's
 * setpoint. Energy = 650 J/kg/K x (T - 25) x 1.2 (soak losses), at least two seconds. Pieces hotter than
 * the furnace's limit (spec {@code temperature}) wait.
 */
public class HeatTreatFurnaceBlockEntity extends MachineBlockEntity {
	public static final MachineMenu.Layout LAYOUT = new MachineMenu.Layout(
			List.of(new int[] {30, 22}, new int[] {62, 22}, new int[] {120, 22}),
			new int[] {0}, new int[] {2}, new int[] {}, new int[] {88, 27}, MachineMenu.Layout.NO_TANKS);
	public static final int INPUT = 0, LOAD = 1, OUTPUT = 2;
	public static final int IDLE = 0, HEATING = 1, TOO_HOT = 2, BLOCKED = 3;
	public static final int PROGRAM = MachineMenu.TANKS, SETPOINT = MachineMenu.TANKS + 1, FURNACE_T = MachineMenu.TANKS + 2;
	public static final int BUTTON_PREV = 0, BUTTON_NEXT = 1, BUTTON_COLDER = 2, BUTTON_HOTTER = 3;

	private Tempers.Program program = Tempers.Program.ANNEAL;
	private int setpoint = 550;
	private int furnaceT;
	private Tempers.Program plannedProgram = Tempers.Program.ANNEAL;
	private int plannedSetpoint = 550;
	private long energyNeeded, energyDone;
	private int status;

	public HeatTreatFurnaceBlockEntity(BlockPos pos, BlockState state) {
		super(ModMetalworks.HEAT_TREAT_BE, pos, state);
	}

	@Override
	public MachineMenu.Layout layout() {
		return LAYOUT;
	}

	@Override
	protected MenuType<MachineMenu> menuType() {
		return ModMetalworks.HEAT_TREAT_MENU;
	}

	@Override
	public Role energyRole() {
		return Role.CONSUMER;
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return slot == INPUT && MetalItems.compositionOf(stack).isPresent();
	}

	@Override
	public boolean onButton(int id, Player player) {
		Tempers.Program[] all = Tempers.Program.values();
		switch (id) {
			case BUTTON_PREV -> program = all[(program.ordinal() + all.length - 1) % all.length];
			case BUTTON_NEXT -> program = all[(program.ordinal() + 1) % all.length];
			case BUTTON_COLDER -> setpoint = Math.max(100, setpoint - 25);
			case BUTTON_HOTTER -> setpoint = Math.min((int) spec().temperature(), setpoint + 25);
			default -> {
				return false;
			}
		}
		setChanged();
		return true;
	}

	private int temperatureFor(ItemStack stack) {
		return MetalItems.compositionOf(stack).map(c -> Tempers.programTemperature(Metallurgy.analyze(c), program, setpoint)).orElse(0);
	}

	@Override
	protected void work(ServerLevel level) {
		powerKw = 0;
		boolean working = false;
		ItemStack load = items.get(LOAD);
		if (load.isEmpty()) {
			energyNeeded = energyDone = 0;
			ItemStack in = items.get(INPUT);
			status = IDLE;
			if (!in.isEmpty() && items.get(OUTPUT).isEmpty()) {
				if (temperatureFor(in) > spec().temperature()) {
					status = TOO_HOT;
				} else {
					items.set(LOAD, in.copy());
					items.set(INPUT, ItemStack.EMPTY);
					load = items.get(LOAD);
					setChanged();
				}
			}
		}
		if (!load.isEmpty()) {
			// (Re)plan when a batch goes in or the program/setpoint changes under it: energy done so far still counts.
			if (energyNeeded == 0 || plannedProgram != program || plannedSetpoint != setpoint) {
				furnaceT = Math.min(temperatureFor(load), (int) spec().temperature());
				double kg = MetalItems.formOf(load).map(f -> f.kg).orElse(1.0) * load.getCount();
				energyNeeded = Math.max(Math.round(650.0 * Math.max(0, furnaceT - 25) * kg * 1.2), EnergyNode.perTick(spec().power()) * 40);
				plannedProgram = program;
				plannedSetpoint = setpoint;
				setChanged();
			}
			status = HEATING;
			if (energyDone < energyNeeded && drawPower(spec().power())) {
				working = true;
				energyDone += EnergyNode.perTick(spec().power());
			}
			if (energyDone >= energyNeeded) {
				ItemStack out = load.copy();
				MetalForm form = MetalItems.formOf(out).orElse(MetalForm.INGOT);
				Metallurgy.Analysis a = Metallurgy.analyze(MetalItems.compositionOf(out).orElseThrow());
				Tempers.set(out, Tempers.heatTreat(a, Tempers.of(out), program, furnaceT, form).withProgress(Tempers.of(out).progress()));
				ItemStack slot = items.get(OUTPUT);
				if (slot.isEmpty()) {
					items.set(OUTPUT, out);
				} else if (ItemStack.isSameItemSameComponents(slot, out) && slot.getCount() + out.getCount() <= slot.getMaxStackSize()) {
					slot.grow(out.getCount());
				} else {
					status = BLOCKED;
					out = null;
				}
				if (out != null) {
					items.set(LOAD, ItemStack.EMPTY);
					energyNeeded = energyDone = 0;
					status = IDLE;
					setChanged();
				}
			}
		}
		values[MachineMenu.STATUS] = status;
		progressMax = (int) Math.min(32000, Math.max(1, energyNeeded / Math.max(1, EnergyNode.perTick(spec().power()))));
		progress = energyNeeded <= 0 ? 0 : (int) Math.min(progressMax, energyDone * progressMax / Math.max(1, energyNeeded));
		if (getBlockState().getValue(MachineBlock.LIT) != working) {
			level.setBlockAndUpdate(worldPosition, getBlockState().setValue(MachineBlock.LIT, working));
		}
	}

	@Override
	protected void writeData(int[] v) {
		v[PROGRAM] = program.ordinal();
		v[SETPOINT] = setpoint;
		ItemStack shown = items.get(LOAD).isEmpty() ? items.get(INPUT) : items.get(LOAD);
		v[FURNACE_T] = !items.get(LOAD).isEmpty() ? furnaceT : shown.isEmpty() ? 0 : temperatureFor(shown);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		int p = input.getIntOr("program", 0);
		program = Tempers.Program.values()[Math.floorMod(p, Tempers.Program.values().length)];
		setpoint = input.getIntOr("setpoint", 550);
		furnaceT = input.getIntOr("furnace_t", 0);
		plannedProgram = Tempers.Program.values()[Math.floorMod(input.getIntOr("planned_program", p), Tempers.Program.values().length)];
		plannedSetpoint = input.getIntOr("planned_setpoint", setpoint);
		energyNeeded = input.getLongOr("energy_needed", 0);
		energyDone = input.getLongOr("energy_done", 0);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putInt("program", program.ordinal());
		output.putInt("setpoint", setpoint);
		output.putInt("furnace_t", furnaceT);
		output.putInt("planned_program", plannedProgram.ordinal());
		output.putInt("planned_setpoint", plannedSetpoint);
		output.putLong("energy_needed", energyNeeded);
		output.putLong("energy_done", energyDone);
	}
}
