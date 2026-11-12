package com.starwake.nuclear;

import com.starwake.chem.Chemistry;
import com.starwake.machine.EnergyNode;
import com.starwake.machine.MachineBlock;
import com.starwake.machine.MachineBlockEntity;
import com.starwake.machine.MachineMenu;
import com.starwake.metal.Composition;
import com.starwake.metal.MetalForm;
import com.starwake.metal.MetalItems;
import com.starwake.process.ChemicalTank;
import com.starwake.process.Isotopes;
import com.starwake.process.Species;
import com.starwake.registry.ModComponents;
import com.starwake.registry.ModNuclear;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * Fission Reactor (M8g): a grid generator. Four fuel slots take uranium metal (ingots, rods... with a
 * {@code starwake:u235} share); the moderator tank holds light or heavy water. The core is critical when the
 * fuel's average U-235 share reaches the moderator's minimum ({@link Nuclear#minEnrichment}: ~3% with H2O,
 * natural uranium with D2O) and no redstone signal holds the rods in. Running, it delivers its rated power and
 * burns U-235 at power / efficiency / 8.2e13 J/kg (x burn_scale): each fuel item loses U-235, breeds Pu
 * (conversion ~0.6 per U-235 burned, from U-238) and fills with fission products (Cs, Sr, Zr, Mo, Tc, Xe...
 * by real yields), all in its composition. A piece whose U-235 share falls below spent_fraction of the minimum
 * moves to the spent slot (pyroprocess it in the Electrolysis Cell: uranium plates out, Pu and the fission
 * products separate). The irradiation slot transmutes a target element along real capture chains (U -> Pu ->
 * Am -> Cm -> Bk -> Cf -> Es -> Fm, Bi -> Po, Th -> Pa -> U) while the core runs.
 */
public class FissionReactorBlockEntity extends MachineBlockEntity {
	public static final MachineMenu.Layout LAYOUT = new MachineMenu.Layout(
			List.of(new int[] {24, 18}, new int[] {42, 18}, new int[] {24, 36}, new int[] {42, 36}, new int[] {80, 18}, new int[] {120, 18},
					new int[] {120, 40}),
			new int[] {0, 1, 2, 3, 4}, new int[] {5, 6}, new int[] {}, new int[] {94, 23}, new int[] {150, -1, -1});
	public static final int FUEL_SLOTS = 4, TARGET = 4, SPENT = 5, PRODUCT = 6;
	public static final int OFF = 0, RUNNING = 1, NO_MODERATOR = 2, SUBCRITICAL = 3, NO_FUEL = 4, SCRAM = 5;
	/** Data slots: average U-235 share x 10000, irradiation progress %. */
	public static final int ENRICHMENT = MachineMenu.TANKS + 3, IRRADIATION = MachineMenu.TANKS + 4;

	private final ChemicalTank moderator = new ChemicalTank(16, true, false, s -> s.equals("H2O") || s.equals("D2O"), this::setChanged);
	private double irradiated;
	/** U-235 burned per fuel slot since the composition was last updated (kg). */
	private final double[] burnup = new double[FUEL_SLOTS];
	private double enrichment;
	private int status;

	public FissionReactorBlockEntity(BlockPos pos, BlockState state) {
		super(ModNuclear.FISSION_REACTOR_BE, pos, state);
	}

	@Override
	public MachineMenu.Layout layout() {
		return LAYOUT;
	}

	@Override
	protected MenuType<MachineMenu> menuType() {
		return ModNuclear.FISSION_REACTOR_MENU;
	}

	@Override
	public Role energyRole() {
		return Role.GENERATOR;
	}

	public Storage<FluidVariant> fluidStorage() {
		return moderator;
	}

	static boolean uranium(ItemStack stack) {
		return MetalItems.compositionOf(stack).map(c -> c.percent("U") > 50).orElse(false);
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		if (slot < FUEL_SLOTS) return uranium(stack);
		if (slot == TARGET) return Nuclear.get().irradiationOf(Nuclear.elementOf(stack)).isPresent();
		return false;
	}

	/** The moderator in use (D2O wins when there's at least 1 kg of it), or null. */
	private String moderatorSpecies() {
		if (moderator.kg("D2O") >= 1) return "D2O";
		if (moderator.kg("H2O") >= 1) return "H2O";
		return null;
	}

	@Override
	protected void work(ServerLevel level) {
		Nuclear n = Nuclear.get();
		powerKw = 0;
		// Average U-235 share of the uranium in the core, by uranium mass.
		double kgU = 0, kg235 = 0;
		for (int s = 0; s < FUEL_SLOTS; s++) {
			ItemStack fuel = items.get(s);
			if (!uranium(fuel)) continue;
			double u = uraniumKg(fuel);
			kgU += u;
			kg235 += u * Isotopes.of(fuel);
		}
		enrichment = kgU <= 0 ? 0 : kg235 / kgU;
		String mod = moderatorSpecies();
		boolean signal = level.hasNeighborSignal(worldPosition);
		double min = mod == null ? 1 : n.minEnrichment().getOrDefault(mod, 1.0);
		status = kgU <= 0 ? NO_FUEL : mod == null ? NO_MODERATOR : signal ? SCRAM : enrichment < min ? SUBCRITICAL : RUNNING;
		boolean running = status == RUNNING;
		if (running && energy < energyCapacity()) {
			long perTick = EnergyNode.perTick(spec().power());
			energy = Math.min(energyCapacity(), energy + perTick);
			powerKw = spec().power();
			if (level.getGameTime() % 20 == 0) burn(n, min, kg235);
		}
		if (running && level.getGameTime() % 20 == 0) irradiate(n);
		progressMax = 100;
		progress = (int) Math.round(Math.min(1, enrichment / Math.max(1e-6, min)) * 100);
		if (getBlockState().getValue(MachineBlock.LIT) != running) level.setBlockAndUpdate(worldPosition, getBlockState().setValue(MachineBlock.LIT, running));
	}

	private static double uraniumKg(ItemStack stack) {
		double kg = MetalItems.formOf(stack).map(f -> f.kg).orElse(1.0) * stack.getCount();
		return kg * MetalItems.compositionOf(stack).map(c -> c.percent("U") / 100).orElse(0.0);
	}

	/** One second of burn-up, shared among the fuel by U-235 content. */
	private void burn(Nuclear n, double min, double kg235) {
		if (kg235 <= 0) return;
		double fissioned = spec().power() * 1000 / n.efficiency() / n.joulesPerKg() * n.burnScale();
		for (int s = 0; s < FUEL_SLOTS; s++) {
			ItemStack fuel = items.get(s);
			if (!uranium(fuel)) continue;
			double u = uraniumKg(fuel), f = Isotopes.of(fuel);
			double share = u * f / kg235;
			double burned = Math.min(u * f, fissioned * share);
			double itemKg = MetalItems.formOf(fuel).map(fm -> fm.kg).orElse(1.0) * fuel.getCount();
			double newF = Math.max(0, (u * f - burned) / Math.max(1e-9, u - burned * (1 + n.conversion())));
			ItemStack next = fuel.copy();
			// The U-235 share drops every second; the burn-up products are folded into the composition once
			// they reach 100 ppm (smaller steps would vanish in the composition's 10 ppm rounding).
			burnup[s] += burned;
			if (burnup[s] >= 1e-4 * itemKg) {
				double b = burnup[s], bred = b * n.conversion();
				Composition c = MetalItems.compositionOf(fuel).orElseThrow();
				Map<String, Double> mass = new HashMap<>();
				for (Composition.Part p : c.parts()) mass.put(p.element(), p.percent() / 100 * itemKg);
				// U-235 fissions into fission products; U-238 captures into Pu.
				mass.merge("U", -(b + bred), Double::sum);
				mass.merge("Pu", bred, Double::sum);
				n.fissionYields().forEach((el, y) -> mass.merge(el, b * y, Double::sum));
				next.set(ModComponents.COMPOSITION, Composition.of(mass));
				burnup[s] = 0;
			}
			Isotopes.stamp(next, newF);
			if (newF < min * n.spentFraction() && items.get(SPENT).isEmpty()) {
				burnup[s] = 0;
				items.set(SPENT, next);
				items.set(s, ItemStack.EMPTY);
			} else {
				items.set(s, next);
			}
		}
		setChanged();
	}

	/** Neutron capture in the target slot. */
	private void irradiate(Nuclear n) {
		ItemStack target = items.get(TARGET);
		var chain = target.isEmpty() ? null : n.irradiationOf(Nuclear.elementOf(target)).orElse(null);
		if (chain == null) {
			irradiated = 0;
			return;
		}
		irradiated += 1;
		if (irradiated < chain.seconds()) return;
		ItemStack product = elementItem(chain.product(), target);
		ItemStack out = items.get(PRODUCT);
		if (!out.isEmpty() && (!ItemStack.isSameItemSameComponents(out, product) || out.getCount() >= out.getMaxStackSize())) return;
		target.shrink(1);
		if (out.isEmpty()) items.set(PRODUCT, product);
		else out.grow(1);
		irradiated = 0;
		setChanged();
	}

	/** One item of an element, in the same form as the target where possible (metal items keep their form). */
	static ItemStack elementItem(String symbol, ItemStack like) {
		if (like.getItem() instanceof com.starwake.metal.MetalItem m) return MetalItems.stack(m.form(), Composition.pure(symbol), 1);
		var element = Chemistry.element(symbol).orElse(null);
		if (element == null) return MetalItems.stack(MetalForm.INGOT, Composition.pure(symbol), 1);
		return new ItemStack(BuiltInRegistries.ITEM.getValue(element.item()));
	}

	@Override
	protected void writeData(int[] v) {
		v[MachineMenu.STATUS] = status;
		v[MachineMenu.TANKS] = (int) Math.min(32000, Math.round(moderator.totalKg() * 100));
		v[MachineMenu.TANKS + 1] = (int) moderator.capacityKg();
		String main = moderator.main();
		v[MachineMenu.TANKS + 2] = main == null ? -1 : Species.get(main).map(Species.Info::index).orElse(-1);
		v[ENRICHMENT] = (int) Math.round(enrichment * 10000);
		var chain = Nuclear.get().irradiationOf(Nuclear.elementOf(items.get(TARGET))).orElse(null);
		v[IRRADIATION] = chain == null ? 0 : (int) Math.round(irradiated / chain.seconds() * 100);
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		moderator.read(input, "moderator");
		irradiated = input.getDoubleOr("irradiated", 0);
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		moderator.write(output, "moderator");
		output.putDouble("irradiated", irradiated);
	}
}
