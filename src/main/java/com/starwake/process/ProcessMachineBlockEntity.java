package com.starwake.process;

import com.mojang.serialization.Codec;
import com.starwake.machine.MachineBlockEntity;
import com.starwake.machine.MachineMenu;
import com.starwake.metal.Composition;
import com.starwake.metal.MetalForm;
import com.starwake.metal.MetalItems;
import com.starwake.planet.Hazards;
import com.starwake.planet.Planets;
import com.starwake.registry.ModComponents;
import com.starwake.registry.ModProcess;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A process-chemistry machine (M8c-1): one class, eight unit operations, each a {@link Process}.
 * A cycle takes one item (1 kg; a raw ore chunk is 2 kg) from the charge slot, runs it through
 * {@link ProcessEngine} (crush, separate, or react + route by phase) and adds the products to
 * output buffers, from which whole kilograms come out as items (bulk materials or metal ingots);
 * gases and liquids go to outlet tanks for pipes. Reagent slots (smelter, converter) are fed in
 * exact stoichiometric amounts: each reagent item becomes a "lot" from which only what reactions
 * need is drawn, along with its share of ash or impurities. Oxygen comes from the air on a
 * breathable world, otherwise from the inlet tank. A full off-gas tank vents (toxic gases make
 * hazard clouds).
 */
public class ProcessMachineBlockEntity extends MachineBlockEntity {
	public enum Process {
		CRUSHER("crusher", 0, new ProcessEngine.Out[] {ProcessEngine.Out.PRIMARY}, BulkItem.Kind.CRUSHED_ORE, false, false, false),
		FLOTATION("flotation", 0, new ProcessEngine.Out[] {ProcessEngine.Out.PRIMARY, ProcessEngine.Out.SECONDARY}, BulkItem.Kind.CONCENTRATE,
				true, false, false),
		MAGNETIC("magnetic", 0, new ProcessEngine.Out[] {ProcessEngine.Out.PRIMARY, ProcessEngine.Out.SECONDARY}, BulkItem.Kind.CONCENTRATE,
				false, false, false),
		GRAVITY("gravity", 0, new ProcessEngine.Out[] {ProcessEngine.Out.PRIMARY, ProcessEngine.Out.SECONDARY}, BulkItem.Kind.CONCENTRATE,
				true, false, false),
		ROASTER("roaster", 0, new ProcessEngine.Out[] {ProcessEngine.Out.PRIMARY, ProcessEngine.Out.FLUE}, BulkItem.Kind.CALCINE, true, true, true),
		COKE_OVEN("coke_oven", 0, new ProcessEngine.Out[] {ProcessEngine.Out.PRIMARY}, BulkItem.Kind.CHAR, false, true, true),
		SMELTER("smelter", 2, new ProcessEngine.Out[] {ProcessEngine.Out.METAL, ProcessEngine.Out.CONDENSATE, ProcessEngine.Out.MATTE,
				ProcessEngine.Out.SLAG, ProcessEngine.Out.FLUE}, BulkItem.Kind.SLAG, false, true, true),
		CONVERTER("converter", 1, new ProcessEngine.Out[] {ProcessEngine.Out.METAL, ProcessEngine.Out.SLAG, ProcessEngine.Out.FLUE},
				BulkItem.Kind.SLAG, true, true, false);

		public final String machine;
		public final int reagents;
		public final ProcessEngine.Out[] outputs;
		public final BulkItem.Kind primary;
		public final boolean inlet, gas, liquid;

		Process(String machine, int reagents, ProcessEngine.Out[] outputs, BulkItem.Kind primary, boolean inlet, boolean gas, boolean liquid) {
			this.machine = machine;
			this.reagents = reagents;
			this.outputs = outputs;
			this.primary = primary;
			this.inlet = inlet;
			this.gas = gas;
			this.liquid = liquid;
		}

		public boolean pyro() {
			return this == SMELTER || this == CONVERTER;
		}

		public boolean separator() {
			return this == FLOTATION || this == MAGNETIC || this == GRAVITY;
		}

		public MachineMenu.Layout layout() {
			return LAYOUTS.get(this);
		}

		public static Process of(BlockState state) {
			return valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().toUpperCase());
		}
	}

	private static final Map<Process, MachineMenu.Layout> LAYOUTS = new EnumMap<>(Process.class);

	static {
		int[] sep = {-1, -1, -1};
		LAYOUTS.put(Process.CRUSHER, layout(List.of(xy(40, 28), xy(104, 28)), 1, xy(66, 33), new int[] {-1, -1, -1}));
		LAYOUTS.put(Process.FLOTATION, layout(List.of(xy(40, 28), xy(100, 18), xy(100, 40)), 1, xy(66, 33), new int[] {150, -1, -1}));
		LAYOUTS.put(Process.MAGNETIC, layout(List.of(xy(40, 28), xy(100, 18), xy(100, 40)), 1, xy(66, 33), sep));
		LAYOUTS.put(Process.GRAVITY, layout(List.of(xy(40, 28), xy(100, 18), xy(100, 40)), 1, xy(66, 33), new int[] {150, -1, -1}));
		LAYOUTS.put(Process.ROASTER, layout(List.of(xy(34, 28), xy(92, 18), xy(92, 40)), 1, xy(60, 33), new int[] {126, 140, 154}));
		LAYOUTS.put(Process.COKE_OVEN, layout(List.of(xy(34, 28), xy(92, 28)), 1, xy(60, 33), new int[] {-1, 140, 154}));
		LAYOUTS.put(Process.SMELTER, layout(List.of(xy(24, 18), xy(24, 42), xy(44, 42), xy(76, 18), xy(96, 18), xy(76, 42), xy(96, 42),
				xy(116, 30)), 3, xy(46, 22), new int[] {-1, 140, 154}));
		LAYOUTS.put(Process.CONVERTER, layout(List.of(xy(24, 18), xy(24, 42), xy(76, 18), xy(76, 42), xy(96, 30)), 2, xy(46, 22),
				new int[] {126, 140, -1}));
	}

	private static int[] xy(int x, int y) {
		return new int[] {x, y};
	}

	private static MachineMenu.Layout layout(List<int[]> slots, int firstOutput, int[] arrow, int[] tanks) {
		int[] inputs = new int[firstOutput];
		for (int i = 0; i < firstOutput; i++) inputs[i] = i;
		int[] outputs = new int[slots.size() - firstOutput];
		for (int i = 0; i < outputs.length; i++) outputs[i] = firstOutput + i;
		return new MachineMenu.Layout(slots, inputs, outputs, new int[] {}, arrow, tanks);
	}

	private static final Codec<Map<String, Map<String, Double>>> BUFFERS_CODEC =
			Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(Codec.STRING, Codec.DOUBLE));
	private static final Codec<List<Map<String, Double>>> LOTS_CODEC = Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).listOf();
	/** Stall when any buffer holds this much (outputs blocked). */
	private static final double BUFFER_LIMIT = 6;

	private final Process process;
	private final Map<ProcessEngine.Out, Map<String, Double>> buffers = new EnumMap<>(ProcessEngine.Out.class);
	private final List<Map<String, Double>> lots = new ArrayList<>();
	private final ChemicalTank inlet, gasTank, liquidTank;
	private final Storage<FluidVariant> fluids;

	public ProcessMachineBlockEntity(BlockPos pos, BlockState state) {
		super(ModProcess.PROCESS_MACHINE_BE, pos, state);
		this.process = Process.of(state);
		this.inlet = process.inlet ? new ChemicalTank(16, true, false, s -> true, this::setChanged) : null;
		this.gasTank = process.gas ? new ChemicalTank(32, false, true, s -> true, this::setChanged) : null;
		this.liquidTank = process.liquid ? new ChemicalTank(16, false, true, s -> true, this::setChanged) : null;
		List<ChemicalTank> tanks = new ArrayList<>();
		for (ChemicalTank t : new ChemicalTank[] {inlet, gasTank, liquidTank}) if (t != null) tanks.add(t);
		this.fluids = new CombinedStorage<>(tanks);
	}

	public Process process() {
		return process;
	}

	public Storage<FluidVariant> fluidStorage() {
		return fluids;
	}

	@Override
	public MachineMenu.Layout layout() {
		return Process.of(getBlockState()).layout();
	}

	@Override
	protected MenuType<MachineMenu> menuType() {
		return ModProcess.menu(process);
	}

	@Override
	public Role energyRole() {
		return Role.CONSUMER;
	}

	// --- Inputs ---------------------------------------------------------------------------------------

	/** The species masses (kg) one item of this stack brings, if any. */
	public static Optional<Map<String, Double>> material(ItemStack stack) {
		if (stack.isEmpty()) return Optional.empty();
		Assay assay = stack.get(ModComponents.ASSAY);
		if (assay != null && !assay.isEmpty()) return Optional.of(assay.masses(1.0));
		var feed = Species.feed(BuiltInRegistries.ITEM.getKey(stack.getItem()));
		if (feed.isPresent()) return Optional.of(feed.get().assay().masses(feed.get().mass()));
		var comp = MetalItems.compositionOf(stack);
		if (comp.isPresent()) {
			double kg = MetalItems.formOf(stack).map(f -> f.kg).orElse(1.0);
			Map<String, Double> out = new HashMap<>();
			for (Composition.Part p : comp.get().parts()) out.put(p.element(), p.percent() / 100 * kg);
			return Optional.of(out);
		}
		return Optional.empty();
	}

	private static boolean rawOre(ItemStack stack) {
		return Species.feed(BuiltInRegistries.ITEM.getKey(stack.getItem())).map(f -> f.mass() > 1).orElse(false);
	}

	/** What a machine accepts in its charge slot. */
	public static boolean acceptsCharge(Process process, ItemStack stack) {
		if (process == Process.CRUSHER) return rawOre(stack);
		if (process.separator()) return stack.getItem() instanceof BulkItem;
		if (rawOre(stack)) return false;
		if (!process.pyro() && MetalItems.compositionOf(stack).isPresent() && !(stack.getItem() instanceof BulkItem)) return false;
		return material(stack).isPresent();
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		if (slot == 0) return acceptsCharge(process, stack);
		if (slot <= process.reagents) return !rawOre(stack) && material(stack).isPresent();
		return false;
	}

	// --- Work -----------------------------------------------------------------------------------------

	@Override
	protected void work(ServerLevel level) {
		powerKw = 0;
		emit();
		ItemStack charge = items.get(0);
		boolean blocked = buffers.values().stream().anyMatch(b -> total(b) > BUFFER_LIMIT);
		boolean ready = !charge.isEmpty() && acceptsCharge(process, charge) && !blocked && hasWater(charge);
		boolean working = false;
		if (ready) {
			progressMax = Math.max(1, (int) Math.round(20 / Math.max(0.05, spec().speed())));
			if (drawPower(spec().power())) {
				working = true;
				if (++progress >= progressMax) {
					progress = 0;
					cycle(level);
					emit();
				}
			}
		} else {
			progress = 0;
		}
		if (getBlockState().getValue(com.starwake.machine.MachineBlock.LIT) != working) {
			level.setBlockAndUpdate(worldPosition, getBlockState().setValue(com.starwake.machine.MachineBlock.LIT, working));
		}
	}

	private boolean hasWater(ItemStack charge) {
		if (!process.separator()) return true;
		var sep = Reactions.separation(process.machine).orElse(null);
		if (sep == null) return false;
		return sep.water() <= 0 || inlet != null && inlet.kg("H2O") >= sep.water() * 1.0;
	}

	private void cycle(ServerLevel level) {
		ItemStack charge = items.get(0);
		Map<String, Double> pool = new HashMap<>(material(charge).orElse(Map.of()));
		charge.shrink(1);
		setChanged();
		switch (process) {
			case CRUSHER -> merge(ProcessEngine.Out.PRIMARY, pool);
			case FLOTATION, MAGNETIC, GRAVITY -> {
				var sep = Reactions.separation(process.machine).orElseThrow();
				double mass = total(pool);
				if (sep.water() > 0) inlet.take("H2O", sep.water() * mass);
				boolean frother = false;
				if (sep.frother().isPresent() && inlet != null) {
					var f = sep.frother().get();
					double need = f.kg() * mass;
					if (inlet.kg(f.species()) >= need) {
						inlet.take(f.species(), need);
						frother = true;
					}
				}
				ProcessEngine.separate(sep, pool, frother).forEach(this::merge);
			}
			default -> {
				Supply supply = new Supply(level);
				ProcessEngine.react(process.machine, pool, supply);
				var routed = ProcessEngine.route(pool, spec().temperature(), process.pyro(), process == Process.SMELTER, supply.air(), supply);
				routed.forEach((out, species) -> {
					switch (out) {
						case GAS -> species.forEach((s, kg) -> overflow(level, s, gasTank == null ? kg : gasTank.add(s, kg)));
						case LIQUID -> species.forEach((s, kg) -> overflow(level, s, liquidTank == null ? kg : liquidTank.add(s, kg)));
						default -> merge(outputFor(out), species);
					}
				});
			}
		}
	}

	/** Phases without their own slot fall back to one that exists (e.g. condensate in a converter). */
	private ProcessEngine.Out outputFor(ProcessEngine.Out out) {
		for (ProcessEngine.Out o : process.outputs) if (o == out) return out;
		if (out == ProcessEngine.Out.CONDENSATE || out == ProcessEngine.Out.MATTE) return ProcessEngine.Out.METAL;
		if (out == ProcessEngine.Out.PRIMARY) return process.outputs[0];
		return process.outputs.length > 1 ? process.outputs[1] : process.outputs[0];
	}

	private void overflow(ServerLevel level, String species, double kg) {
		if (kg > 1e-6) GasHazards.vent(level, worldPosition, species, kg);
	}

	private void merge(ProcessEngine.Out out, Map<String, Double> species) {
		Map<String, Double> buffer = buffers.computeIfAbsent(out, k -> new HashMap<>());
		species.forEach((s, kg) -> {
			if (kg > 1e-12) buffer.merge(s, kg, Double::sum);
		});
	}

	private static double total(Map<String, Double> m) {
		double t = 0;
		for (double v : m.values()) t += v;
		return t;
	}

	/** Moves whole kilograms out of the buffers into the output slots. */
	private void emit() {
		for (int i = 0; i < process.outputs.length; i++) {
			ProcessEngine.Out out = process.outputs[i];
			Map<String, Double> buffer = buffers.get(out);
			if (buffer == null) continue;
			int slot = process.reagents + 1 + i;
			int guard = 4;
			while (total(buffer) >= 1.0 - 1e-9 && guard-- > 0) {
				ItemStack made = make(out, buffer);
				ItemStack there = items.get(slot);
				if (there.isEmpty()) items.set(slot, made);
				else if (ItemStack.isSameItemSameComponents(there, made) && there.getCount() < there.getMaxStackSize()) there.grow(1);
				else break;
				double t = total(buffer);
				buffer.replaceAll((k, v) -> v * (t - 1) / t);
				buffer.values().removeIf(v -> v < 1e-12);
				setChanged();
			}
		}
	}

	private ItemStack make(ProcessEngine.Out out, Map<String, Double> buffer) {
		if (out == ProcessEngine.Out.METAL || out == ProcessEngine.Out.CONDENSATE) {
			Map<String, Double> elements = new HashMap<>();
			buffer.forEach((s, kg) -> Species.get(s).ifPresent(info -> info.elements().forEach((el, f) -> elements.merge(el, f * kg, Double::sum))));
			return MetalItems.stack(MetalForm.INGOT, Composition.of(elements), 1);
		}
		BulkItem.Kind kind = switch (out) {
			case PRIMARY -> process.primary;
			case SECONDARY -> BulkItem.Kind.TAILINGS;
			case MATTE -> BulkItem.Kind.MATTE;
			case FLUE -> BulkItem.Kind.FLUE_DUST;
			default -> BulkItem.Kind.SLAG;
		};
		return BulkItem.stack(ModProcess.bulk(kind), Assay.of(buffer), 1);
	}

	// --- Reagent supply ----------------------------------------------------------------------------------

	/** Reagent lots, reagent slots, the inlet tank and the air. */
	private final class Supply implements ProcessEngine.Supply {
		private final boolean air;

		Supply(ServerLevel level) {
			Hazards hazards = Planets.forDimension(level.registryAccess(), level.dimension()).map(p -> p.planet().hazards()).orElse(Hazards.EARTHLIKE);
			this.air = hazards.breathable() && !com.starwake.galaxy.SolarSystem.isSpace(level.dimension());
		}

		boolean air() {
			return air || inlet != null && inlet.kg("O2") > 0;
		}

		@Override
		public double available(String species) {
			double kg = 0;
			for (Map<String, Double> lot : lots) kg += lot.getOrDefault(species, 0.0);
			for (int s = 1; s <= process.reagents; s++) {
				ItemStack stack = items.get(s);
				kg += material(stack).map(m -> m.getOrDefault(species, 0.0)).orElse(0.0) * stack.getCount();
			}
			if (inlet != null) kg += inlet.kg(species);
			if (species.equals("O2") && air) kg += 1e9;
			return kg;
		}

		@Override
		public void draw(String species, double kg, Map<String, Double> pool) {
			double left = kg;
			left = fromLots(species, left, pool);
			for (int s = 1; s <= process.reagents && left > 1e-12; s++) {
				ItemStack stack = items.get(s);
				while (left > 1e-12 && !stack.isEmpty()) {
					Map<String, Double> m = material(stack).orElse(Map.of());
					if (m.getOrDefault(species, 0.0) <= 0) break;
					lots.add(new HashMap<>(m));
					stack.shrink(1);
					left = fromLots(species, left, pool);
				}
			}
			if (left > 1e-12 && inlet != null) left -= inlet.take(species, left);
			if (left > 1e-12 && species.equals("O2") && air) ProcessEngine.add(pool, "O2", left);
			setChanged();
		}

		private double fromLots(String species, double left, Map<String, Double> pool) {
			for (var it = lots.iterator(); it.hasNext() && left > 1e-12; ) {
				Map<String, Double> lot = it.next();
				double has = lot.getOrDefault(species, 0.0);
				if (has <= 0) continue;
				double take = Math.min(has, left);
				double f = take / has;
				for (var e : lot.entrySet()) {
					double moved = e.getValue() * f;
					ProcessEngine.add(pool, e.getKey(), moved);
					e.setValue(e.getValue() - moved);
				}
				lot.values().removeIf(v -> v < 1e-9);
				if (lot.isEmpty()) it.remove();
				left -= take;
			}
			return left;
		}
	}

	// --- Screen data, removal, save -----------------------------------------------------------------------

	@Override
	protected void writeData(int[] v) {
		ChemicalTank[] tanks = {inlet, gasTank, liquidTank};
		for (int t = 0; t < 3; t++) {
			ChemicalTank tank = tanks[t];
			int base = MachineMenu.TANKS + 3 * t;
			v[base] = tank == null ? 0 : (int) Math.min(32000, Math.round(tank.totalKg() * 100));
			v[base + 1] = tank == null ? 0 : (int) tank.capacityKg();
			String main = tank == null ? null : tank.main();
			v[base + 2] = main == null ? -1 : Species.get(main).map(Species.Info::index).orElse(-1);
		}
	}

	@Override
	public void preRemoveSideEffects(BlockPos pos, BlockState state) {
		super.preRemoveSideEffects(pos, state);
		if (level instanceof ServerLevel server) {
			for (ChemicalTank tank : new ChemicalTank[] {gasTank, liquidTank, inlet}) {
				if (tank != null) tank.contents().forEach((s, droplets) -> GasHazards.vent(server, pos, s, droplets / (double) Chemicals.KG));
			}
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		buffers.clear();
		input.read("buffers", BUFFERS_CODEC).ifPresent(m -> m.forEach((k, v) -> buffers.put(ProcessEngine.Out.valueOf(k), new HashMap<>(v))));
		lots.clear();
		input.read("lots", LOTS_CODEC).ifPresent(l -> l.forEach(m -> lots.add(new HashMap<>(m))));
		if (inlet != null) inlet.read(input, "inlet");
		if (gasTank != null) gasTank.read(input, "gas");
		if (liquidTank != null) liquidTank.read(input, "liquid");
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		Map<String, Map<String, Double>> b = new HashMap<>();
		buffers.forEach((k, v) -> b.put(k.name(), v));
		output.store("buffers", BUFFERS_CODEC, b);
		output.store("lots", LOTS_CODEC, lots);
		if (inlet != null) inlet.write(output, "inlet");
		if (gasTank != null) gasTank.write(output, "gas");
		if (liquidTank != null) liquidTank.write(output, "liquid");
	}
}
