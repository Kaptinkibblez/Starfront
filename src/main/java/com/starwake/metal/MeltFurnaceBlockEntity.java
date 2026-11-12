package com.starwake.metal;

import com.mojang.serialization.Codec;
import com.starwake.machine.EnergyNode;
import com.starwake.machine.MachineBlock;
import com.starwake.machine.MachineBlockEntity;
import com.starwake.machine.MachineMenu;
import com.starwake.network.MeltStatePayload;
import com.starwake.process.Assay;
import com.starwake.process.BulkItem;
import com.starwake.process.ChemicalTank;
import com.starwake.process.Chemicals;
import com.starwake.process.GasHazards;
import com.starwake.process.ProcessMachineBlockEntity;
import com.starwake.process.Species;
import com.starwake.registry.ModMetalworks;
import com.starwake.registry.ModProcess;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Melting furnaces (M8d-1): the Induction Furnace (open to air, up to {@code temperature} = 1750 C:
 * steels, copper, aluminium, nickel alloys) and the Vacuum Arc Furnace (vacuum, up to 3100 C:
 * titanium, zirconium, niobium, tantalum, molybdenum). Both hold a {@link Melt} bath.
 *
 * <p>Charges (any metal item) melt one at a time for their real heating + melting energy, unless the
 * bath would then be more refractory than the furnace can reach. Reactive metals melted in air pick up
 * oxygen and nitrogen (an argon inlet prevents it). Fluxes (lime, limestone, fluorspar) go into the
 * slag. Oxygen in the inlet is blown through the bath (induction only; with at least as much argon:
 * AOD). Slag comes off as 1 kg slag items. With tapping on, the bath pours 1 kg ingots, or 10 kg
 * billets/slabs/castings when a mould is in the mould slot, all "as cast".
 */
public class MeltFurnaceBlockEntity extends MachineBlockEntity {
	public static final MachineMenu.Layout LAYOUT = new MachineMenu.Layout(
			List.of(new int[] {24, 18}, new int[] {24, 42}, new int[] {62, 42}, new int[] {96, 22}, new int[] {96, 44}),
			new int[] {0, 1, 2}, new int[] {3, 4}, new int[] {}, new int[] {46, 22}, new int[] {126, 142, -1});
	public static final int CHARGE = 0, FLUX = 1, MOULD = 2, OUTPUT = 3, SLAG = 4;
	/** Screen status codes. */
	public static final int IDLE = 0, MELTING = 1, TOO_REFRACTORY = 2, FULL = 3, BLOWING = 4, NO_POWER = 5;
	private static final Codec<Map<String, Double>> MAP = Codec.unboundedMap(Codec.STRING, Codec.DOUBLE);

	private final boolean vacuum;
	private final Melt melt = new Melt();
	private final ChemicalTank inlet, offGas;
	private final Storage<FluidVariant> fluids;
	private boolean tapping;
	private String target = "";
	private long energyNeeded, energyDone;
	private int status;

	public MeltFurnaceBlockEntity(BlockPos pos, BlockState state) {
		super(ModMetalworks.MELT_FURNACE_BE, pos, state);
		this.vacuum = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().equals("vacuum_arc_furnace");
		this.inlet = new ChemicalTank(16, true, false, s -> Species.get(s).map(i -> i.kind() == Species.Kind.GAS || i.gasAtRoom()).orElse(false),
				this::setChanged);
		this.offGas = new ChemicalTank(16, false, true, s -> true, this::setChanged);
		this.fluids = new CombinedStorage<>(List.of(inlet, offGas));
	}

	public Storage<FluidVariant> fluidStorage() {
		return fluids;
	}

	public Melt melt() {
		return melt;
	}

	@Override
	public MachineMenu.Layout layout() {
		return LAYOUT;
	}

	@Override
	protected MenuType<MachineMenu> menuType() {
		return vacuum ? ModMetalworks.VACUUM_ARC_MENU : ModMetalworks.INDUCTION_MENU;
	}

	@Override
	public Role energyRole() {
		return Role.CONSUMER;
	}

	public double maxTemperature() {
		return spec().temperature();
	}

	public double capacityKg() {
		return spec().capacity() > 0 ? spec().capacity() : 64;
	}

	// --- Slots ------------------------------------------------------------------------------------------

	public static double kgOf(ItemStack stack) {
		return MetalItems.formOf(stack).map(f -> f.kg).orElse(1.0);
	}

	private static boolean isFlux(ItemStack stack) {
		return ProcessMachineBlockEntity.material(stack).map(m -> m.keySet().stream().anyMatch(s -> s.equals("CaO") || s.equals("CaCO3")
				|| s.equals("CaF2"))).orElse(false) && MetalItems.compositionOf(stack).isEmpty();
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return switch (slot) {
			case CHARGE -> MetalItems.compositionOf(stack).isPresent();
			case FLUX -> isFlux(stack);
			case MOULD -> ModMetalworks.mouldForm(stack) != null;
			default -> false;
		};
	}

	// --- Work -------------------------------------------------------------------------------------------

	@Override
	protected void work(ServerLevel level) {
		powerKw = 0;
		boolean working = false;
		status = IDLE;
		ItemStack charge = items.get(CHARGE);
		Optional<Composition> comp = MetalItems.compositionOf(charge);
		if (comp.isPresent()) {
			double kg = kgOf(charge);
			double liquidus = melt.liquidusWith(comp.get(), kg);
			if (melt.mass() + kg > capacityKg() + 1e-9) {
				status = FULL;
			} else if (liquidus > maxTemperature()) {
				status = TOO_REFRACTORY;
			} else {
				if (energyNeeded <= 0) {
					energyNeeded = Math.round(Melt.meltEnergyPerKg(Math.max(liquidus, melt.liquidus())) * kg);
					energyDone = 0;
				}
				if (drawPower(spec().power())) {
					working = true;
					status = MELTING;
					energyDone += EnergyNode.perTick(spec().power());
					if (energyDone >= energyNeeded) {
						melt.add(comp.get(), kg, !vacuum && inlet.kg("Ar") < 0.01);
						charge.shrink(1);
						energyNeeded = energyDone = 0;
						setChanged();
					}
				} else {
					status = NO_POWER;
				}
			}
		} else {
			energyNeeded = energyDone = 0;
		}
		progressMax = (int) Math.min(32000, Math.max(1, energyNeeded / Math.max(1, EnergyNode.perTick(spec().power()))));
		progress = energyNeeded <= 0 ? 0 : (int) Math.min(progressMax, energyDone * progressMax / Math.max(1, energyNeeded));

		if ((level.getGameTime() + worldPosition.asLong()) % 20 == 0 && melt.mass() > 0) {
			secondly(level);
			if (status == IDLE && inlet.kg("O2") > 0 && !vacuum) status = BLOWING;
		}
		if ((level.getGameTime() + worldPosition.asLong()) % 10 == 0) sync(level);
		if (getBlockState().getValue(MachineBlock.LIT) != (working || melt.mass() > 0)) {
			level.setBlockAndUpdate(worldPosition, getBlockState().setValue(MachineBlock.LIT, working || melt.mass() > 0));
		}
	}

	/** Fluxing, blowing, settling, slag and metal tapping, once a second. */
	private void secondly(ServerLevel level) {
		ItemStack flux = items.get(FLUX);
		if (!flux.isEmpty() && melt.slagMass() < 8) {
			Map<String, Double> m = ProcessMachineBlockEntity.material(flux).orElse(Map.of());
			m.forEach((s, kg) -> {
				if (s.equals("CaCO3") || s.equals("MgCO3")) {
					// Carbonates calcine in the bath: CaCO3 -> CaO + CO2, MgCO3 -> MgO + CO2.
					String oxide = s.equals("CaCO3") ? "CaO" : "MgO";
					double ox = kg * Species.require(oxide).molarMass() / Species.require(s).molarMass();
					melt.addSlag(oxide, ox);
					offGas.add("CO2", kg - ox);
				} else {
					melt.addSlag(s, kg);
				}
			});
			flux.shrink(1);
		}
		// The oxygen lance goes in after meltdown (nothing waiting to melt).
		if (!vacuum && inlet.kg("O2") > 1e-6 && items.get(CHARGE).isEmpty()) {
			boolean aod = inlet.kg("Ar") >= inlet.kg("O2");
			double o2 = inlet.take("O2", Math.min(inlet.kg("O2"), Melt.rules().blowKgPerS()));
			if (aod) inlet.take("Ar", o2);
			Map<String, Double> gas = melt.blow(o2, aod);
			gas.forEach((s, kg) -> {
				// CO burns to CO2 above the bath in an open furnace.
				if (s.equals("CO")) {
					double co2 = kg * Species.require("CO2").molarMass() / Species.require("CO").molarMass();
					double over = offGas.add("CO2", co2);
					if (over > 0) GasHazards.vent(level, worldPosition, "CO2", over);
				} else {
					double over = offGas.add(s, kg);
					if (over > 0) GasHazards.vent(level, worldPosition, s, over);
				}
			});
		}
		melt.settle();
		// Slag off in 1 kg lots.
		ItemStack slagOut = items.get(SLAG);
		if (melt.slagMass() >= 1 && slagOut.getCount() < slagOut.getMaxStackSize()) {
			ItemStack made = BulkItem.stack(ModProcess.bulk(BulkItem.Kind.SLAG), Assay.of(melt.tapSlag(1)), 1);
			if (slagOut.isEmpty()) items.set(SLAG, made);
			else if (ItemStack.isSameItemSameComponents(slagOut, made)) slagOut.grow(1);
			else melt.addSlagAll(made); // a different slag is waiting in the slot: keep this lot in the bath
		}
		// Tap metal.
		if (tapping) {
			MetalForm form = ModMetalworks.mouldForm(items.get(MOULD));
			if (form == null) form = MetalForm.INGOT;
			ItemStack out = items.get(OUTPUT);
			if (melt.mass() >= form.kg - 1e-9 && (out.isEmpty() || out.getCount() < out.getMaxStackSize())) {
				Composition c = melt.tap(form.kg);
				ItemStack made = MetalItems.stack(form, c, 1);
				Tempers.set(made, Tempers.Temper.of("as_cast"));
				if (out.isEmpty()) items.set(OUTPUT, made);
				else if (ItemStack.isSameItemSameComponents(out, made)) out.grow(1);
				else melt.add(c, form.kg, false); // different metal than the last tap: pour it back
			}
		}
		setChanged();
	}

	// --- Buttons and sync -------------------------------------------------------------------------------

	/** Menu buttons: 0 toggle tapping, 1 next target grade, 2 previous target grade, 3 clear target. */
	@Override
	public boolean onButton(int id, Player player) {
		switch (id) {
			case 0 -> tapping = !tapping;
			case 1, 2 -> {
				List<String> grades = targetGrades();
				if (grades.isEmpty()) return true;
				int i = grades.indexOf(target);
				i = id == 1 ? (i + 1) % grades.size() : (i <= 0 ? grades.size() - 1 : i - 1);
				target = grades.get(i);
			}
			case 3 -> target = "";
			default -> {
				return false;
			}
		}
		setChanged();
		if (level instanceof ServerLevel server) sync(server);
		return true;
	}

	/** Exact grades a player can aim for, in listing order. */
	public static List<String> targetGrades() {
		return Metallurgy.grades().stream().filter(e -> e.getValue().exact()).map(e -> e.getKey().toString()).toList();
	}

	private void sync(ServerLevel level) {
		MeltStatePayload payload = new MeltStatePayload(worldPosition, new HashMap<>(melt.metal), new HashMap<>(melt.slag), melt.liquidus(),
				maxTemperature(), target, tapping, vacuum, status);
		for (ServerPlayer p : level.players()) {
			if (p.containerMenu instanceof MachineMenu m && m.container() == this) ServerPlayNetworking.send(p, payload);
		}
	}

	@Override
	protected void writeData(int[] v) {
		v[MachineMenu.STATUS] = status;
		ChemicalTank[] tanks = {inlet, offGas, null};
		for (int t = 0; t < 3; t++) {
			ChemicalTank tank = tanks[t];
			int base = MachineMenu.TANKS + 3 * t;
			v[base] = tank == null ? 0 : (int) Math.min(32000, Math.round(tank.totalKg() * 100));
			v[base + 1] = tank == null ? 0 : (int) tank.capacityKg();
			String main = tank == null ? null : tank.main();
			v[base + 2] = main == null ? -1 : Species.get(main).map(Species.Info::index).orElse(-1);
		}
	}

	// --- Removal and save -------------------------------------------------------------------------------

	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		super.preRemoveSideEffects(pos, state);
		if (level instanceof ServerLevel server) {
			offGas.contents().forEach((s, d) -> GasHazards.vent(server, pos, s, d / (double) Chemicals.KG));
			// The bath freezes into a skull: drop it as as-cast ingots.
			while (melt.mass() >= 1) {
				ItemStack skull = MetalItems.stack(MetalForm.INGOT, melt.tap(1), 1);
				Tempers.set(skull, Tempers.Temper.of("as_cast"));
				net.minecraft.world.Containers.dropItemStack(server, pos.getX(), pos.getY(), pos.getZ(), skull);
			}
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		melt.metal.clear();
		melt.slag.clear();
		input.read("bath", MAP).ifPresent(melt.metal::putAll);
		input.read("slag", MAP).ifPresent(melt.slag::putAll);
		tapping = input.getBooleanOr("tapping", false);
		target = input.getStringOr("target", "");
		energyNeeded = input.getLongOr("energy_needed", 0);
		energyDone = input.getLongOr("energy_done", 0);
		inlet.read(input, "inlet");
		offGas.read(input, "gas");
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.store("bath", MAP, melt.metal);
		output.store("slag", MAP, melt.slag);
		output.putBoolean("tapping", tapping);
		output.putString("target", target);
		output.putLong("energy_needed", energyNeeded);
		output.putLong("energy_done", energyDone);
		inlet.write(output, "inlet");
		offGas.write(output, "gas");
	}
}
