package com.starwake.process;

import com.mojang.serialization.Codec;
import com.starwake.machine.EnergyNode;
import com.starwake.machine.MachineBlockEntity;
import com.starwake.machine.MachineMenu;
import com.starwake.metal.Composition;
import com.starwake.metal.MetalForm;
import com.starwake.metal.MetalItem;
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
 * A process-chemistry machine (M8c): one class, every unit operation, each a {@link Process}.
 *
 * <p>A cycle takes its feed (one item from the charge slot, and/or a slice of the inlet tank's
 * solution), runs it through {@link ProcessEngine} (crush, separate, react + route by phase,
 * evaporate, electrorefine) and holds the products as <i>pending</i> until the cycle's energy has
 * been delivered: electrolysis needs electrons x Faraday x voltage, evaporation 0.8 MJ per kg of
 * water, everything else runs for the spec's time. Then products go to output buffers, from which
 * whole kilograms come out as items (bulk materials or metal ingots); gases go to the off-gas tank,
 * solutions and liquids to the liquid tank.
 *
 * <p>Reagent slots are fed in exact stoichiometric amounts: each reagent item becomes a "lot" from
 * which only what reactions need is drawn, along with its share of ash or impurities. A catalyst
 * slot must hold the reaction's catalyst (vanadium for the contact process, cryolite for the
 * aluminium bath, potash for the alkaline electrolyser) and is never consumed. Oxygen comes from the
 * air on a breathable world, otherwise from the inlet tank. A full off-gas tank vents (toxic gases
 * make hazard clouds).
 */
public class ProcessMachineBlockEntity extends MachineBlockEntity {
	/** How a machine works out a cycle. */
	public enum Mode { CRUSH, SEPARATE, PYRO, DRY, WET, EVAPORATE, ELECTRO, AIR, SX }

	public enum Process {
		CRUSHER("crusher", Mode.CRUSH, 1, false, 0, outs(ProcessEngine.Out.PRIMARY), BulkItem.Kind.CRUSHED_ORE, false, false, false, 0),
		FLOTATION("flotation", Mode.SEPARATE, 1, false, 0, outs(ProcessEngine.Out.PRIMARY, ProcessEngine.Out.SECONDARY), BulkItem.Kind.CONCENTRATE,
				true, false, false, 0),
		MAGNETIC("magnetic", Mode.SEPARATE, 1, false, 0, outs(ProcessEngine.Out.PRIMARY, ProcessEngine.Out.SECONDARY), BulkItem.Kind.CONCENTRATE,
				false, false, false, 0),
		GRAVITY("gravity", Mode.SEPARATE, 1, false, 0, outs(ProcessEngine.Out.PRIMARY, ProcessEngine.Out.SECONDARY), BulkItem.Kind.CONCENTRATE,
				true, false, false, 0),
		ROASTER("roaster", Mode.DRY, 1, false, 0, outs(ProcessEngine.Out.PRIMARY, ProcessEngine.Out.FLUE), BulkItem.Kind.CALCINE, true, true, true, 0),
		COKE_OVEN("coke_oven", Mode.DRY, 1, false, 0, outs(ProcessEngine.Out.PRIMARY), BulkItem.Kind.CHAR, false, true, true, 0),
		SMELTER("smelter", Mode.PYRO, 1, false, 2, outs(ProcessEngine.Out.METAL, ProcessEngine.Out.CONDENSATE, ProcessEngine.Out.MATTE,
				ProcessEngine.Out.SLAG, ProcessEngine.Out.FLUE), BulkItem.Kind.SLAG, false, true, true, 0),
		CONVERTER("converter", Mode.PYRO, 1, false, 1, outs(ProcessEngine.Out.METAL, ProcessEngine.Out.SLAG, ProcessEngine.Out.FLUE),
				BulkItem.Kind.SLAG, true, true, false, 0),
		// M8e-1: submerged-arc furnace (metallurgical silicon, ~2000 C).
		ARC_FURNACE("arc_furnace", Mode.PYRO, 1, false, 1, outs(ProcessEngine.Out.METAL, ProcessEngine.Out.SLAG, ProcessEngine.Out.FLUE),
				BulkItem.Kind.SLAG, false, true, false, 0),
		// M8c-2: wet chemistry and electrochemistry.
		REACTOR("reactor", Mode.WET, 2, true, 1, outs(ProcessEngine.Out.PRIMARY), BulkItem.Kind.CRYSTALS, true, true, true, 1),
		LEACH("leach", Mode.WET, 1, false, 0, outs(ProcessEngine.Out.PRIMARY), BulkItem.Kind.RESIDUE, true, true, true, 1),
		EVAPORATOR("evaporator", Mode.EVAPORATE, 0, false, 0, outs(ProcessEngine.Out.PRIMARY, ProcessEngine.Out.SECONDARY,
				ProcessEngine.Out.BITTERN), BulkItem.Kind.CRYSTALS, true, true, true, 2),
		CRYSTALLIZER("crystallizer", Mode.WET, 0, false, 0, outs(ProcessEngine.Out.PRIMARY), BulkItem.Kind.CRYSTALS, true, false, true, 1),
		ELECTROLYSIS("electrolysis", Mode.ELECTRO, 2, true, 1, outs(ProcessEngine.Out.METAL, ProcessEngine.Out.PRIMARY), BulkItem.Kind.SLIMES,
				true, true, true, 1),
		CHLORINATOR("chlorinator", Mode.DRY, 1, false, 1, outs(ProcessEngine.Out.PRIMARY), BulkItem.Kind.RESIDUE, true, true, true, 0),
		KROLL("kroll", Mode.DRY, 0, false, 1, outs(ProcessEngine.Out.METAL, ProcessEngine.Out.PRIMARY), BulkItem.Kind.CRYSTALS,
				true, true, false, 1),
		// M8c-3a: cryogenic air separation of whatever the planet's air holds.
		AIR_SEPARATOR("air_separator", Mode.AIR, 0, false, 0, outs(), BulkItem.Kind.CRYSTALS, true, true, true, 1),
		// M8c-3b: counter-current solvent-extraction battery (extract -> gas-side tank, raffinate -> liquid tank).
		SOLVENT_EXTRACTION("solvent_extraction", Mode.SX, 0, false, 0, outs(), BulkItem.Kind.CRYSTALS, true, true, true, 1);

		public final String machine;
		public final Mode mode;
		/** 0 = no charge slot, 1 = charge required, 2 = optional (inlet-fed). */
		public final int charge;
		public final boolean catalyst;
		public final int reagents;
		public final ProcessEngine.Out[] outputs;
		public final BulkItem.Kind primary;
		public final boolean inlet, gas, liquid;
		/** kg taken from the inlet tank per cycle (0 = the inlet only supplies drawn reactants). */
		public final double inletFeed;

		Process(String machine, Mode mode, int charge, boolean catalyst, int reagents, ProcessEngine.Out[] outputs, BulkItem.Kind primary,
				boolean inlet, boolean gas, boolean liquid, double inletFeed) {
			this.machine = machine;
			this.mode = mode;
			this.charge = charge;
			this.catalyst = catalyst;
			this.reagents = reagents;
			this.outputs = outputs;
			this.primary = primary;
			this.inlet = inlet;
			this.gas = gas;
			this.liquid = liquid;
			this.inletFeed = inletFeed;
		}

		private static ProcessEngine.Out[] outs(ProcessEngine.Out... outs) {
			return outs;
		}

		public int chargeSlot() {
			return charge > 0 ? 0 : -1;
		}

		public int catalystSlot() {
			return catalyst ? (charge > 0 ? 1 : 0) : -1;
		}

		public int firstReagent() {
			return (charge > 0 ? 1 : 0) + (catalyst ? 1 : 0);
		}

		public int firstOutput() {
			return firstReagent() + reagents;
		}

		public boolean pyro() {
			return mode == Mode.PYRO;
		}

		public boolean separator() {
			return mode == Mode.SEPARATE;
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
		int[] none = {-1, -1, -1};
		LAYOUTS.put(Process.CRUSHER, layout(List.of(xy(40, 28), xy(104, 28)), 1, xy(66, 33), none));
		LAYOUTS.put(Process.FLOTATION, layout(List.of(xy(40, 28), xy(100, 18), xy(100, 40)), 1, xy(66, 33), new int[] {150, -1, -1}));
		LAYOUTS.put(Process.MAGNETIC, layout(List.of(xy(40, 28), xy(100, 18), xy(100, 40)), 1, xy(66, 33), none));
		LAYOUTS.put(Process.GRAVITY, layout(List.of(xy(40, 28), xy(100, 18), xy(100, 40)), 1, xy(66, 33), new int[] {150, -1, -1}));
		LAYOUTS.put(Process.ROASTER, layout(List.of(xy(34, 28), xy(92, 18), xy(92, 40)), 1, xy(60, 33), new int[] {126, 140, 154}));
		LAYOUTS.put(Process.COKE_OVEN, layout(List.of(xy(34, 28), xy(92, 28)), 1, xy(60, 33), new int[] {-1, 140, 154}));
		LAYOUTS.put(Process.SMELTER, layout(List.of(xy(24, 18), xy(24, 42), xy(44, 42), xy(76, 18), xy(96, 18), xy(76, 42), xy(96, 42),
				xy(116, 30)), 3, xy(46, 22), new int[] {-1, 140, 154}));
		LAYOUTS.put(Process.CONVERTER, layout(List.of(xy(24, 18), xy(24, 42), xy(76, 18), xy(76, 42), xy(96, 30)), 2, xy(46, 22),
				new int[] {126, 140, -1}));
		LAYOUTS.put(Process.REACTOR, layout(List.of(xy(24, 18), xy(24, 42), xy(44, 42), xy(92, 28)), 3, xy(58, 22), new int[] {126, 140, 154}));
		LAYOUTS.put(Process.LEACH, layout(List.of(xy(34, 28), xy(92, 28)), 1, xy(58, 33), new int[] {126, 140, 154}));
		LAYOUTS.put(Process.EVAPORATOR, layout(List.of(xy(80, 18), xy(80, 40), xy(100, 29)), 0, xy(50, 33), new int[] {126, 140, 154}));
		LAYOUTS.put(Process.CRYSTALLIZER, layout(List.of(xy(92, 28)), 0, xy(58, 33), new int[] {126, -1, 154}));
		LAYOUTS.put(Process.ELECTROLYSIS, layout(List.of(xy(24, 18), xy(24, 42), xy(44, 42), xy(92, 18), xy(92, 42)), 3, xy(58, 22),
				new int[] {126, 140, 154}));
		LAYOUTS.put(Process.CHLORINATOR, layout(List.of(xy(34, 18), xy(34, 42), xy(92, 28)), 2, xy(58, 33), new int[] {126, 140, 154}));
		LAYOUTS.put(Process.AIR_SEPARATOR, layout(List.of(), 0, xy(80, 33), new int[] {40, 126, 148}));
		LAYOUTS.put(Process.SOLVENT_EXTRACTION, layout(List.of(), 0, xy(60, 28), new int[] {30, 140, 156}));
		LAYOUTS.put(Process.KROLL, layout(List.of(xy(34, 28), xy(92, 18), xy(92, 42)), 1, xy(58, 33), new int[] {126, 140, -1}));
		LAYOUTS.put(Process.ARC_FURNACE, layout(List.of(xy(24, 18), xy(24, 42), xy(76, 18), xy(76, 42), xy(96, 30)), 2, xy(46, 22),
				new int[] {-1, 140, -1}));
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
	/** Cryogenic air separation, J per kg of air processed (~0.11 kWh). */
	private static final double AIR_SEPARATION_J_PER_KG = 0.4e6;

	private final Process process;
	private final Map<ProcessEngine.Out, Map<String, Double>> buffers = new EnumMap<>(ProcessEngine.Out.class);
	private final List<Map<String, Double>> lots = new ArrayList<>();
	/** Products of the running cycle, released when its energy is delivered. */
	private Map<ProcessEngine.Out, Map<String, Double>> pending;
	private long energyNeeded, energyDone;
	/** Uranium through this machine and its U-235 (kg), for isotope tracking. */
	private double uKg, u235Kg;
	private final ChemicalTank inlet, gasTank, liquidTank;
	private final Storage<FluidVariant> fluids;

	public ProcessMachineBlockEntity(BlockPos pos, BlockState state) {
		super(ModProcess.PROCESS_MACHINE_BE, pos, state);
		this.process = Process.of(state);
		this.inlet = process.inlet ? new ChemicalTank(16, true, false, s -> true, this::setChanged) : null;
		this.gasTank = process.gas ? new ChemicalTank(32, false, true, s -> true, this::setChanged) : null;
		if (process == Process.SOLVENT_EXTRACTION) gasTank.mixed(); // the extract is a solution
		this.liquidTank = process.liquid ? new ChemicalTank(16, false, true, s -> true, this::setChanged) : null;
		if (liquidTank != null) liquidTank.mixed();
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
			for (Composition.Part p : comp.get().parts()) out.merge(DIATOMIC.getOrDefault(p.element(), p.element()), p.percent() / 100 * kg, Double::sum);
			return Optional.of(out);
		}
		return Optional.empty();
	}

	/** Gaseous elements dissolved in a metal are the molecular species (O -> O2). */
	private static final Map<String, String> DIATOMIC = Map.of("H", "H2", "N", "N2", "O", "O2", "F", "F2", "Cl", "Cl2");

	private static boolean rawOre(ItemStack stack) {
		return Species.feed(BuiltInRegistries.ITEM.getKey(stack.getItem())).map(f -> f.mass() > 1).orElse(false);
	}

	private static boolean metal(ItemStack stack) {
		return stack.getItem() instanceof MetalItem || MetalItems.compositionOf(stack).isPresent() && !(stack.getItem() instanceof BulkItem)
				&& Species.feed(BuiltInRegistries.ITEM.getKey(stack.getItem())).isEmpty();
	}

	/** What a machine accepts in its charge slot. */
	public static boolean acceptsCharge(Process process, ItemStack stack) {
		if (process == Process.CRUSHER) return rawOre(stack);
		if (process.separator()) return stack.getItem() instanceof BulkItem;
		if (rawOre(stack)) return false;
		// Metals go anywhere chemistry can use them (aqua regia on platinum sponge, refining, remelting).
		return material(stack).isPresent();
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		if (slot == process.chargeSlot()) return acceptsCharge(process, stack);
		if (slot == process.catalystSlot()) return material(stack).isPresent();
		if (slot >= process.firstReagent() && slot < process.firstOutput()) return !rawOre(stack) && material(stack).isPresent();
		return false;
	}

	/** Whether the catalyst slot holds a species or element. */
	private boolean catalystPresent(String what) {
		int slot = process.catalystSlot();
		if (slot < 0) return false;
		Map<String, Double> m = material(items.get(slot)).orElse(Map.of());
		if (m.getOrDefault(what, 0.0) > 0) return true;
		for (String s : m.keySet()) {
			if (Species.get(s).map(i -> i.elements().containsKey(what)).orElse(false)) return true;
		}
		return false;
	}

	// --- Work -----------------------------------------------------------------------------------------

	@Override
	protected void work(ServerLevel level) {
		powerKw = 0;
		emit();
		boolean working = false;
		if (pending == null && ready()) start(level);
		if (pending != null) {
			if (drawPower(spec().power())) {
				working = true;
				energyDone += EnergyNode.perTick(spec().power());
				progress = (int) Math.min(progressMax, energyDone * progressMax / Math.max(1, energyNeeded));
				if (energyDone >= energyNeeded) {
					release(level, pending);
					pending = null;
					progress = 0;
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

	private boolean ready() {
		if (buffers.values().stream().anyMatch(b -> total(b) > BUFFER_LIMIT)) return false;
		boolean hasCharge = process.charge > 0 && !items.get(0).isEmpty() && acceptsCharge(process, items.get(0));
		boolean hasFeed = process.inletFeed > 0 && inlet != null && inlet.totalKg() > 0.05;
		if (process.mode == Mode.AIR) {
			// Piped-in gas (natural gas from a well) first, else the planet's air.
			return level != null && (hasFeed || !com.starwake.planet.Atmospheres.of(level.dimension()).isEmpty())
					&& gasTank.totalKg() < gasTank.capacityKg() - 1;
		}
		if (process.mode == Mode.SX) {
			return hasFeed && Reactions.sxFamilies().values().stream().anyMatch(f -> f.species().stream().anyMatch(sp -> inlet.kg(sp) > 1e-6))
					&& gasTank.totalKg() < gasTank.capacityKg() - 2 && liquidTank.totalKg() < liquidTank.capacityKg() - 2;
		}
		boolean go = switch (process.charge) {
			case 1 -> hasCharge;
			case 2 -> hasCharge || hasFeed;
			default -> hasFeed;
		};
		if (!go) return false;
		if (process == Process.ELECTROLYSIS && hasCharge && metal(items.get(0))) {
			// Electrorefining needs an electrolyte in the cell (acid sulfate, nitrate, chloride...).
			if (inlet == null || inlet.contents().keySet().stream().noneMatch(s -> !s.equals("H2O")
					&& Species.get(s).map(Species.Info::soluble).orElse(false))) return false;
		}
		// (Inlet-fed machines with reagent slots, like the Kroll reactor, need no reagent for inlet-only
		// reactions such as Siemens deposition: start() checks that some reaction can run.)
		if (process.separator()) {
			var sep = Reactions.separation(process.machine).orElse(null);
			return sep != null && (sep.water() <= 0 || inlet != null && inlet.kg("H2O") >= sep.water());
		}
		return true;
	}

	/** Takes this cycle's feed, works out the products and how much energy they need. */
	private void start(ServerLevel level) {
		Map<String, Double> pool = new HashMap<>();
		ItemStack charge = process.charge > 0 ? items.get(0) : ItemStack.EMPTY;
		boolean refine = process == Process.ELECTROLYSIS && metal(charge);
		boolean useCharge = !charge.isEmpty() && acceptsCharge(process, charge);
		boolean useInlet = process.inletFeed > 0 && inlet != null && !refine;
		if (useCharge) material(charge).orElse(Map.of()).forEach((k, v) -> pool.merge(k, v, Double::sum));
		if (useInlet) inlet.peek(process.inletFeed).forEach((k, v) -> pool.merge(k, v, Double::sum));
		// Chemistry machines wait until something can react (catalyst in, reagents piped in), so a
		// charge put in first isn't wasted. Smelters may just remelt; coke ovens just drive off volatiles.
		boolean chemical = process.mode == Mode.WET || process.mode == Mode.DRY && process != Process.COKE_OVEN
				|| process.mode == Mode.ELECTRO && !refine;
		if (chemical && !ProcessEngine.canReact(process.machine, pool, new Supply(level), this::catalystPresent) && !dissolves(pool)) return;
		// Uranium isotope ledger (M8c-3b): what comes in sets the U-235 share of what goes out.
		if (uraniumHeld() <= 1e-12 && pending == null) uKg = u235Kg = 0;
		double uCharge = useCharge ? Isotopes.uraniumKg(material(charge).orElse(Map.of())) : 0;
		double uInlet = useInlet ? Isotopes.uraniumKg(inlet.peek(process.inletFeed)) : 0;
		uKg += uCharge + uInlet;
		u235Kg += uCharge * (useCharge ? Isotopes.of(charge) : 0) + uInlet * (useInlet ? inlet.u235() : 0);
		if (useCharge) charge.shrink(1);
		if (useInlet) inlet.slice(process.inletFeed);
		double joules = 0;
		Map<ProcessEngine.Out, Map<String, Double>> out;
		switch (process.mode) {
			case CRUSH -> {
				out = new EnumMap<>(ProcessEngine.Out.class);
				out.put(ProcessEngine.Out.PRIMARY, pool);
			}
			case SEPARATE -> {
				var sep = Reactions.separation(process.machine).orElseThrow();
				double mass = total(pool);
				if (sep.water() > 0) inlet.take("H2O", sep.water() * mass);
				boolean frother = false;
				if (sep.frother().isPresent() && inlet != null) {
					var f = sep.frother().get();
					if (inlet.kg(f.species()) >= f.kg() * mass) {
						inlet.take(f.species(), f.kg() * mass);
						frother = true;
					}
				}
				out = ProcessEngine.separate(sep, pool, frother);
			}
			case AIR -> {
				// One cycle processes `rate` kg of air (less where it's thin); 0.4 MJ per kg (cryogenic).
				double kg;
				if (pool.isEmpty()) {
					double density = com.starwake.planet.Planets.airDensityOf(level.dimension());
					double air = spec().rate() * Math.min(1, density);
					com.starwake.planet.Atmospheres.of(level.dimension()).forEach((k, v) -> pool.merge(k, v * air, Double::sum));
					kg = air;
				} else {
					kg = total(pool);
				}
				out = new EnumMap<>(ProcessEngine.Out.class);
				for (var e : pool.entrySet()) {
					ProcessEngine.Out o = e.getKey().equals("H2O") ? ProcessEngine.Out.LIQUID : ProcessEngine.Out.GAS;
					out.computeIfAbsent(o, k -> new HashMap<>()).put(e.getKey(), e.getValue());
				}
				joules = kg * AIR_SEPARATION_J_PER_KG;
			}
			case SX -> {
				out = solventExtract(pool);
				joules = 0;
			}
			case EVAPORATE -> {
				out = ProcessEngine.evaporate(pool);
				joules = pool.getOrDefault("H2O", 0.0) * ProcessEngine.EVAPORATION_J_PER_KG;
			}
			case ELECTRO -> {
				if (refine) {
					double[] e = new double[1];
					out = ProcessEngine.electrorefine(pool, e);
					joules = e[0];
				} else {
					Supply supply = new Supply(level);
					joules = ProcessEngine.react(process.machine, pool, supply, this::catalystPresent);
					out = ProcessEngine.routeWet(pool, true);
				}
			}
			case WET -> {
				Supply supply = new Supply(level);
				ProcessEngine.react(process.machine, pool, supply, this::catalystPresent);
				out = ProcessEngine.routeWet(pool, false);
			}
			default -> {
				Supply supply = new Supply(level);
				ProcessEngine.react(process.machine, pool, supply, this::catalystPresent);
				boolean metalOut = List.of(process.outputs).contains(ProcessEngine.Out.METAL);
				out = ProcessEngine.route(pool, spec().temperature(), process.pyro(), process == Process.SMELTER || process == Process.ARC_FURNACE, supply.air(), supply);
				if (!process.pyro() && metalOut) {
					// Kroll: metal products are a sponge (or a condensed vapour, like potassium), not a calcine.
					for (ProcessEngine.Out o : List.of(ProcessEngine.Out.PRIMARY, ProcessEngine.Out.FLUE, ProcessEngine.Out.CONDENSATE)) {
						Map<String, Double> part = out.getOrDefault(o, new HashMap<>());
						for (var it = part.entrySet().iterator(); it.hasNext(); ) {
							var e = it.next();
							if (Species.get(e.getKey()).map(Species.Info::metallic).orElse(false)) {
								out.computeIfAbsent(ProcessEngine.Out.METAL, k -> new HashMap<>()).merge(e.getKey(), e.getValue(), Double::sum);
								it.remove();
							}
						}
					}
				}
			}
		}
		pending = out;
		long perTick = Math.max(1, EnergyNode.perTick(spec().power()));
		long timed = Math.round(20 / Math.max(0.05, spec().speed())) * perTick;
		energyNeeded = Math.max(timed, Math.round(joules));
		energyDone = 0;
		progressMax = (int) Math.min(32000, Math.max(1, energyNeeded / perTick));
		setChanged();
	}

	private void release(ServerLevel level, Map<ProcessEngine.Out, Map<String, Double>> out) {
		double f = u235Fraction();
		for (var e : out.entrySet()) {
			ChemicalTank t = e.getKey() == ProcessEngine.Out.GAS ? gasTank : e.getKey() == ProcessEngine.Out.LIQUID ? liquidTank : null;
			if (t != null) t.mixUranium(Isotopes.uraniumKg(e.getValue()), f);
		}
		out.forEach((o, species) -> {
			switch (o) {
				case GAS -> species.forEach((s, kg) -> overflow(level, s, gasTank == null ? kg : gasTank.add(s, kg)));
				case LIQUID -> species.forEach((s, kg) -> overflow(level, s, liquidTank == null ? kg : liquidTank.add(s, kg)));
				default -> merge(outputFor(o), species);
			}
		});
		setChanged();
	}

	/** Phases without their own slot fall back to one that exists (e.g. condensate in a converter). */
	private ProcessEngine.Out outputFor(ProcessEngine.Out out) {
		if (process.outputs.length == 0) return out;
		for (ProcessEngine.Out o : process.outputs) if (o == out) return out;
		boolean hasMetal = List.of(process.outputs).contains(ProcessEngine.Out.METAL);
		if ((out == ProcessEngine.Out.CONDENSATE || out == ProcessEngine.Out.MATTE) && hasMetal) return ProcessEngine.Out.METAL;
		if (out == ProcessEngine.Out.PRIMARY || out == ProcessEngine.Out.METAL) return process.outputs[0];
		return process.outputs.length > 1 ? process.outputs[process.outputs.length - 1] : process.outputs[0];
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
			int slot = process.firstOutput() + i;
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
		ItemStack made = makeItem(out, buffer);
		if (Isotopes.uraniumKg(buffer) > 0) Isotopes.stamp(made, u235Fraction());
		return made;
	}

	private ItemStack makeItem(ProcessEngine.Out out, Map<String, Double> buffer) {
		ItemStack element = elementProduct(out, buffer);
		if (!element.isEmpty()) return element;
		if (process == Process.EVAPORATOR) return BulkItem.stack(ModProcess.bulk(BulkItem.Kind.CRYSTALS), Assay.of(buffer), 1);
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

	/**
	 * Refined elements are purity-tracked products (M8c-3a): a solid output that is mostly one element
	 * (cemented cadmium, hydrogen-reduced germanium or molybdenum, precipitated selenium) comes out as
	 * that element's item (non-metals) or as metal dust (metals), carrying its full composition.
	 */
	private ItemStack elementProduct(ProcessEngine.Out out, Map<String, Double> buffer) {
		if (out == ProcessEngine.Out.METAL || out == ProcessEngine.Out.CONDENSATE) return ItemStack.EMPTY;
		String main = null;
		double most = 0, total = total(buffer);
		for (var e : buffer.entrySet()) {
			if (e.getValue() > most) {
				most = e.getValue();
				main = e.getKey();
			}
		}
		if (main == null || most < 0.5 * total) return ItemStack.EMPTY;
		Species.Info info = Species.get(main).orElse(null);
		if (info == null || info.kind() != Species.Kind.METAL && info.kind() != Species.Kind.ELEMENT) return ItemStack.EMPTY;
		Map<String, Double> elements = new HashMap<>();
		buffer.forEach((sp, kg) -> Species.get(sp).ifPresent(i -> i.elements().forEach((el, f) -> elements.merge(el, f * kg, Double::sum))));
		Composition comp = Composition.of(elements);
		var element = com.starwake.chem.Chemistry.element(main).orElse(null);
		if (info.kind() == Species.Kind.ELEMENT && element != null && element.form().equals("powder")) {
			ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(element.item()));
			stack.set(ModComponents.COMPOSITION, comp);
			return stack;
		}
		if (info.kind() == Species.Kind.METAL) return MetalItems.stack(MetalForm.DUST, comp, 1);
		return ItemStack.EMPTY;
	}

	/** U-235 share of the uranium that has gone through this machine. */
	private double u235Fraction() {
		return uKg > 1e-12 ? u235Kg / uKg : Isotopes.NATURAL;
	}

	private double uraniumHeld() {
		return Isotopes.uraniumKg(contents());
	}

	/**
	 * Plain dissolution counts as leaching (M8c-3b): a Leach Tank also runs when its charge holds a
	 * soluble salt and there is water to dissolve it (chromate calcine, chlororhodate...).
	 */
	private boolean dissolves(Map<String, Double> pool) {
		if (process != Process.LEACH) return false;
		boolean water = pool.getOrDefault("H2O", 0.0) > 0 || inlet != null && inlet.kg("H2O") > 0.1;
		return water && pool.keySet().stream().anyMatch(sp -> !sp.equals("H2O") && Species.get(sp).map(Species.Info::soluble).orElse(false));
	}

	// --- Solvent extraction (M8c-3b) ---------------------------------------------------------------------

	/** The cut: the lightest species sent to the extract (empty = automatic, between the first two present). */
	private String cut = "";

	public String cut() {
		return cut;
	}

	/** Sneak-use with an empty hand: step the cut to the next species of the families. */
	public net.minecraft.network.chat.Component cycleCut() {
		java.util.List<String> options = new java.util.ArrayList<>();
		options.add("");
		for (var f : Reactions.sxFamilies().values()) options.addAll(f.species().subList(1, f.species().size()));
		cut = options.get((options.indexOf(cut) + 1) % options.size());
		setChanged();
		if (cut.isEmpty()) return net.minecraft.network.chat.Component.translatable("message.starwake.sx.auto");
		return net.minecraft.network.chat.Component.translatable("message.starwake.sx.cut",
				Species.get(cut).map(Species.Info::displayName).orElse(net.minecraft.network.chat.Component.literal(cut)));
	}

	/**
	 * One battery pass. For each family present, species split between extract and raffinate by an
	 * ideal counter-current cascade around the cut: extracted share = 1 / (1 + (D_cut / D_i)^(N/2)),
	 * with D from the family's separation factors and N stages (half extraction, half scrubbing).
	 * Extracting consumes the family's reagents per mole (P507: 3 NaOH + 3 HCl -> 3 NaCl + 3 H2O), drawn
	 * from the feed and inlet; without enough, less is extracted.
	 */
	private Map<ProcessEngine.Out, Map<String, Double>> solventExtract(Map<String, Double> pool) {
		Map<String, Double> extract = new HashMap<>(), raffinate = new HashMap<>(pool);
		for (var family : Reactions.sxFamilies().values()) {
			Map<String, Double> logD = family.logD();
			List<String> present = family.species().stream().filter(sp -> pool.getOrDefault(sp, 0.0) > 1e-9).toList();
			if (present.isEmpty()) continue;
			double cutLog;
			if (logD.containsKey(cut)) {
				int i = family.species().indexOf(cut);
				cutLog = (logD.get(family.species().get(i - 1)) + logD.get(cut)) / 2;
			} else if (present.size() >= 2) {
				cutLog = (logD.get(present.get(0)) + logD.get(present.get(1))) / 2;
			} else {
				continue;
			}
			// Moles to extract, then scale down to the reagents available.
			Map<String, Double> want = new HashMap<>();
			double moles = 0;
			for (String sp : present) {
				double share = 1 / (1 + Math.pow(10, -(logD.get(sp) - cutLog) * family.stages() / 2.0));
				double kg = pool.get(sp) * share;
				want.put(sp, kg);
				moles += kg / (Species.require(sp).molarMass() / 1000);
			}
			double scale = 1;
			for (var r : family.perMole().entrySet()) {
				double need = moles * r.getValue() * Species.require(r.getKey()).molarMass() / 1000;
				double have = raffinate.getOrDefault(r.getKey(), 0.0) + (inlet == null ? 0 : inlet.kg(r.getKey()));
				if (need > 0) scale = Math.min(scale, have / need);
			}
			for (var r : family.perMole().entrySet()) {
				double need = moles * scale * r.getValue() * Species.require(r.getKey()).molarMass() / 1000;
				double fromPool = Math.min(need, raffinate.getOrDefault(r.getKey(), 0.0));
				ProcessEngine.add(raffinate, r.getKey(), -fromPool);
				if (need - fromPool > 1e-12 && inlet != null) inlet.take(r.getKey(), need - fromPool);
			}
			for (var p : family.products().entrySet()) {
				ProcessEngine.add(raffinate, p.getKey(), moles * scale * p.getValue() * Species.require(p.getKey()).molarMass() / 1000);
			}
			for (var w : want.entrySet()) {
				ProcessEngine.add(extract, w.getKey(), w.getValue() * scale);
				ProcessEngine.add(raffinate, w.getKey(), -w.getValue() * scale);
			}
		}
		// The strip liquor: half the water carries the extract.
		double water = raffinate.getOrDefault("H2O", 0.0);
		if (!extract.isEmpty() && water > 0) {
			extract.put("H2O", water / 2);
			ProcessEngine.add(raffinate, "H2O", -water / 2);
		}
		Map<ProcessEngine.Out, Map<String, Double>> out = new EnumMap<>(ProcessEngine.Out.class);
		out.put(ProcessEngine.Out.GAS, extract);
		out.put(ProcessEngine.Out.LIQUID, raffinate);
		return out;
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
			for (int s = process.firstReagent(); s < process.firstOutput(); s++) {
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
			for (int s = process.firstReagent(); s < process.firstOutput() && left > 1e-12; s++) {
				ItemStack stack = items.get(s);
				while (left > 1e-12 && !stack.isEmpty()) {
					Map<String, Double> m = material(stack).orElse(Map.of());
					if (m.getOrDefault(species, 0.0) <= 0) break;
					lots.add(new HashMap<>(m));
					stack.shrink(1);
					left = fromLots(species, left, pool);
				}
			}
			if (left > 1e-12 && inlet != null) {
				double got = inlet.take(species, left);
				ProcessEngine.add(pool, species, got);
				left -= got;
			}
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

	/** Everything inside (kg per species): buffers, the running cycle, reagent lots and tanks. For radiation. */
	public Map<String, Double> contents() {
		Map<String, Double> all = new HashMap<>();
		buffers.values().forEach(b -> b.forEach((k, v) -> all.merge(k, v, Double::sum)));
		if (pending != null) pending.values().forEach(b -> b.forEach((k, v) -> all.merge(k, v, Double::sum)));
		lots.forEach(l -> l.forEach((k, v) -> all.merge(k, v, Double::sum)));
		for (ChemicalTank t : new ChemicalTank[] {inlet, gasTank, liquidTank}) {
			if (t != null) t.contents().forEach((k, v) -> all.merge(k, v / (double) Chemicals.KG, Double::sum));
		}
		return all;
	}

	// --- Screen data, removal, save -----------------------------------------------------------------------

	@Override
	protected void writeData(int[] v) {
		v[MachineMenu.STATUS] = cut.isEmpty() ? 0 : Species.get(cut).map(i -> i.index() + 1).orElse(0);
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
		pending = null;
		input.read("pending", BUFFERS_CODEC).ifPresent(m -> {
			pending = new EnumMap<>(ProcessEngine.Out.class);
			m.forEach((k, v) -> pending.put(ProcessEngine.Out.valueOf(k), new HashMap<>(v)));
		});
		energyNeeded = input.getLongOr("energy_needed", 0);
		energyDone = input.getLongOr("energy_done", 0);
		cut = input.getStringOr("cut", "");
		uKg = input.getDoubleOr("u_kg", 0);
		u235Kg = input.getDoubleOr("u235_kg", 0);
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
		if (pending != null) {
			Map<String, Map<String, Double>> p = new HashMap<>();
			pending.forEach((k, v) -> p.put(k.name(), v));
			output.store("pending", BUFFERS_CODEC, p);
		}
		output.putLong("energy_needed", energyNeeded);
		output.putLong("energy_done", energyDone);
		if (!cut.isEmpty()) output.putString("cut", cut);
		if (uKg > 0) {
			output.putDouble("u_kg", uKg);
			output.putDouble("u235_kg", u235Kg);
		}
		if (inlet != null) inlet.write(output, "inlet");
		if (gasTank != null) gasTank.write(output, "gas");
		if (liquidTank != null) liquidTank.write(output, "liquid");
	}
}
