package com.starwake.process;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * The chemistry of a process batch (M8c), independent of blocks so it can be tested and reused.
 *
 * <p><b>Reactions.</b> A machine's reactions run in priority order on a <i>pool</i> (species ->
 * kg). Each runs as far as its reactants allow. Reactants listed in the reaction's {@code draw}
 * may be fed from the machine's {@link Supply} (reagent slots, inlet tank, the air), but only to
 * react with something already in the pool, so a smelter takes exactly the coke and limestone a
 * batch of ore needs. Mass is conserved exactly (molar masses from formulas).
 *
 * <p><b>Routing.</b> After reacting, every species goes to an output phase:
 * <ul>
 *   <li>gases at room temperature -> GAS (steam is vented);</li>
 *   <li>volatile at the machine's temperature: metals condense (CONDENSATE, or LIQUID for mercury),
 *       other volatiles collect as FLUE dust (As2O3, selenium...);</li>
 *   <li>pyro machines (smelter, converter): sulfides -> MATTE (converter: dissolved in the metal);
 *       metals -> METAL, except lithophile oxide-formers (Ti, Al, REE, U...) -> SLAG, and chalcophile
 *       traces (Ag, Se, Te...) -> MATTE when there is one; oxides, silicates and the rest -> SLAG;
 *       carbon saturates the iron (pig iron, ~4.3%) and the rest burns (or stays in the slag
 *       without air);</li>
 *   <li>roaster and coke oven: solids -> PRIMARY (calcine, coke), liquids at room temperature ->
 *       LIQUID (pine oil, tar, mercury).</li>
 * </ul>
 */
public final class ProcessEngine {
	public enum Out { PRIMARY, SECONDARY, BITTERN, METAL, CONDENSATE, MATTE, SLAG, FLUE, GAS, LIQUID }

	/** Where a machine gets drawable reactants. */
	public interface Supply {
		/** kg of a species available to draw. */
		double available(String species);

		/** Moves kg of a species (plus whatever comes with it, e.g. coke ash) into the pool. */
		void draw(String species, double kg, Map<String, Double> pool);
	}

	public static final Supply NONE = new Supply() {
		@Override
		public double available(String species) {
			return 0;
		}

		@Override
		public void draw(String species, double kg, Map<String, Double> pool) {}
	};

	private static final double EPS = 1e-12;
	/** Carbon in carbon-saturated iron (pig iron), mass fraction. */
	private static final double PIG_IRON_C = 0.043;

	private ProcessEngine() {}

	public static void react(String machine, Map<String, Double> pool, Supply supply) {
		react(machine, pool, supply, c -> true);
	}

	/**
	 * Runs a machine's reactions; reactions with a catalyst only run if {@code catalyst} says it's
	 * present. Returns the electrical energy the reactions need (J; electrolysis).
	 */
	/** Whether any of a machine's reactions can run on this pool (catalyst present, reactants available). */
	/** Above this much water (kg) a charge counts as wet: molten-salt cells won't run. */
	private static final double WET = 0.02;

	public static boolean canReact(String machine, Map<String, Double> pool, Supply supply, java.util.function.Predicate<String> catalyst) {
		for (Reactions.Reaction r : Reactions.forMachine(machine)) {
			if (r.catalyst().isPresent() && !catalyst.test(r.catalyst().get())) continue;
			if (r.anhydrous() && pool.getOrDefault("H2O", 0.0) > WET) continue;
			if (extent(r, pool, supply) > EPS) return true;
		}
		return false;
	}

	public static double react(String machine, Map<String, Double> pool, Supply supply, java.util.function.Predicate<String> catalyst) {
		double energy = 0;
		for (Reactions.Reaction r : Reactions.forMachine(machine)) {
			if (r.catalyst().isPresent() && !catalyst.test(r.catalyst().get())) continue;
			if (r.anhydrous() && pool.getOrDefault("H2O", 0.0) > WET) continue;
			double extent = extent(r, pool, supply);
			if (extent <= EPS) continue;
			for (Reactions.Term t : r.reactants()) {
				if (!r.draw().contains(t.species())) continue;
				double need = extent * t.kgPerMole() - pool.getOrDefault(t.species(), 0.0);
				if (need > EPS) supply.draw(t.species(), need, pool);
			}
			extent = Math.min(extent, extent(r, pool, NONE, true));
			if (extent <= EPS) continue;
			for (Reactions.Term t : r.reactants()) add(pool, t.species(), -extent * t.kgPerMole());
			for (Reactions.Term t : r.products()) add(pool, t.species(), extent * t.kgPerMole());
			energy += extent * r.joulesPerMole();
		}
		return energy;
	}

	/**
	 * Wet routing (products at ~25 C): water and soluble species form the solution (LIQUID);
	 * room-temperature gases -> GAS; other room-temperature liquids (TiCl4, acids) -> LIQUID; metals
	 * (electrolysis cathodes) -> METAL when the machine has one; other solids -> PRIMARY.
	 */
	public static Map<Out, Map<String, Double>> routeWet(Map<String, Double> pool, boolean metalOut) {
		Map<Out, Map<String, Double>> out = new EnumMap<>(Out.class);
		for (var e : pool.entrySet()) {
			Species.Info s = Species.get(e.getKey()).orElse(null);
			if (s == null || e.getValue() <= 0) continue;
			Out o;
			if (s.kind() == Species.Kind.GAS || s.gasAtRoom()) o = s.soluble() && pool.getOrDefault("H2O", 0.0) > e.getValue() ? Out.LIQUID : Out.GAS;
			else if (s.soluble() || s.liquidAtRoom()) o = Out.LIQUID;
			else if (metalOut && s.metallic()) o = Out.METAL;
			else o = Out.PRIMARY;
			out.computeIfAbsent(o, k -> new HashMap<>()).merge(s.id(), e.getValue(), Double::sum);
		}
		// Beyond its solubility a salt crystallises out of the solution (lithium carbonate, gypsum...).
		Map<String, Double> liquid = out.get(Out.LIQUID);
		if (liquid != null) {
			double water = liquid.getOrDefault("H2O", 0.0);
			for (var it = liquid.entrySet().iterator(); it.hasNext(); ) {
				var e = it.next();
				Species.Info s = Species.require(e.getKey());
				double limit = s.solubilityLimit() * water;
				if (e.getValue() > limit + EPS) {
					out.computeIfAbsent(Out.PRIMARY, k -> new HashMap<>()).merge(e.getKey(), e.getValue() - limit, Double::sum);
					if (limit <= EPS) it.remove();
					else e.setValue(limit);
				}
			}
		}
		return out;
	}

	/** Energy to evaporate water, J/kg (a triple-effect evaporator: about a third of the latent heat). */
	public static final double EVAPORATION_J_PER_KG = 0.8e6;

	/**
	 * Evaporator, fractional crystallisation: the water boils off (condensate -> LIQUID) and salts
	 * crystallise in the order their solubility is exceeded, collected in three cuts: PRIMARY = what
	 * comes out before the main salt starts (carbonates, gypsum, insolubles), SECONDARY = the main salt
	 * (the most abundant one with a solubility limit: halite from seawater) until the next salt starts,
	 * BITTERN = everything after (potash, magnesium salts, lithium, bromide, borate).
	 */
	public static Map<Out, Map<String, Double>> evaporate(Map<String, Double> pool) {
		Map<Out, Map<String, Double>> out = new EnumMap<>(Out.class);
		double water = pool.getOrDefault("H2O", 0.0);
		String main = null;
		double most = 0;
		for (var e : pool.entrySet()) {
			Species.Info s = Species.get(e.getKey()).orElse(null);
			if (s == null || s.kind() == Species.Kind.WATER || !s.soluble() || Double.isInfinite(s.solubilityLimit())) continue;
			if (e.getValue() > most) {
				most = e.getValue();
				main = s.id();
			}
		}
		// Water left when the main salt starts (w1) and when the next one starts (w2).
		double w1 = main == null ? 0 : Math.min(water, most / Species.require(main).solubilityLimit());
		double w2 = 0;
		for (var e : pool.entrySet()) {
			Species.Info s = Species.get(e.getKey()).orElse(null);
			if (s == null || s.id().equals(main) || !s.soluble() || s.kind() == Species.Kind.WATER || Double.isInfinite(s.solubilityLimit())) continue;
			double w = e.getValue() / s.solubilityLimit();
			if (w < w1) w2 = Math.max(w2, w);
		}
		for (var e : pool.entrySet()) {
			Species.Info s = Species.get(e.getKey()).orElse(null);
			double kg = e.getValue();
			if (s == null || kg <= 0) continue;
			if (s.kind() == Species.Kind.WATER) {
				add(out, Out.LIQUID, s.id(), kg);
			} else if (s.gasAtRoom() || s.kind() == Species.Kind.GAS) {
				add(out, Out.GAS, s.id(), kg);
			} else if (!s.soluble()) {
				add(out, Out.PRIMARY, s.id(), kg);
			} else {
				double p1 = precipitated(s, kg, w1, water), p2 = precipitated(s, kg, w2, water);
				add(out, Out.PRIMARY, s.id(), p1);
				add(out, Out.SECONDARY, s.id(), p2 - p1);
				add(out, Out.BITTERN, s.id(), kg - p2);
			}
		}
		return out;
	}

	/** kg of a dissolved species crystallised once the water is down to {@code w} kg. */
	private static double precipitated(Species.Info s, double kg, double w, double water) {
		if (Double.isInfinite(s.solubilityLimit())) return w <= EPS ? kg : 0;
		if (w >= water - EPS) return Math.max(0, kg - s.solubilityLimit() * water);
		return Math.max(0, kg - s.solubilityLimit() * w);
	}

	private static void add(Map<Out, Map<String, Double>> out, Out o, String species, double kg) {
		if (kg > EPS) out.computeIfAbsent(o, k -> new HashMap<>()).merge(species, kg, Double::sum);
	}

	/** Cell voltage for electrorefining (V). */
	public static final double REFINING_VOLTAGE = 0.3;

	/**
	 * Electrorefining an impure metal anode: the base metal plates out pure (METAL); elements nobler
	 * than it (higher standard potential: gold, silver, platinum metals, selenium, tellurium) and
	 * those with insoluble sulfates (lead, tin) fall as anode slimes (PRIMARY); less noble ones
	 * dissolve into the electrolyte (LIQUID bleed). Returns the energy needed in {@code energy[0]}.
	 */
	public static Map<Out, Map<String, Double>> electrorefine(Map<String, Double> pool, double[] energy) {
		Map<Out, Map<String, Double>> out = new EnumMap<>(Out.class);
		String base = null;
		double most = 0;
		for (var e : pool.entrySet()) {
			if (e.getValue() > most) {
				most = e.getValue();
				base = e.getKey();
			}
		}
		if (base == null) return out;
		Species.Info b = Species.require(base);
		double baseE = Double.isNaN(b.potential()) ? 0 : b.potential();
		for (var e : pool.entrySet()) {
			Species.Info s = Species.get(e.getKey()).orElse(null);
			if (s == null) continue;
			Out o;
			if (s.id().equals(base)) o = Out.METAL;
			else if (java.util.Set.of("Pb", "Sn", "Se", "Te", "S", "O", "C", "Si").contains(s.id())
					|| !Double.isNaN(s.potential()) && s.potential() > baseE) o = Out.PRIMARY;
			else o = Out.LIQUID;
			out.computeIfAbsent(o, k -> new HashMap<>()).merge(s.id(), e.getValue(), Double::sum);
		}
		// Two electrons per atom for copper/nickel/zinc-type refining.
		energy[0] = most / (b.molarMass() / 1000) * 2 * Reactions.Reaction.FARADAY * REFINING_VOLTAGE;
		return out;
	}

	private static double extent(Reactions.Reaction r, Map<String, Double> pool, Supply supply) {
		return extent(r, pool, supply, false);
	}

	/** Moles of reaction possible. A reaction needs at least one non-drawn reactant in the pool. */
	private static double extent(Reactions.Reaction r, Map<String, Double> pool, Supply supply, boolean poolOnly) {
		double extent = Double.MAX_VALUE;
		boolean anchored = false;
		for (Reactions.Term t : r.reactants()) {
			double have = pool.getOrDefault(t.species(), 0.0);
			if (r.draw().contains(t.species())) {
				if (!poolOnly) have += supply.available(t.species());
			} else {
				anchored = true;
			}
			extent = Math.min(extent, have / t.kgPerMole());
		}
		return anchored || poolOnly ? extent : 0;
	}

	static void add(Map<String, Double> pool, String species, double kg) {
		double v = pool.getOrDefault(species, 0.0) + kg;
		if (v <= 1e-9) pool.remove(species);
		else pool.put(species, v);
	}

	/**
	 * Routes a reacted pool to output phases.
	 *
	 * @param pyro        smelter/converter style (molten bath) vs roaster/coke oven (solids)
	 * @param matte       the machine has a matte output (smelter)
	 * @param air         burning is possible (breathable air or an oxygen supply)
	 */
	public static Map<Out, Map<String, Double>> route(Map<String, Double> pool, double temperature, boolean pyro, boolean matte, boolean air,
			Supply carbon) {
		Map<Out, Map<String, Double>> out = new EnumMap<>(Out.class);
		boolean hasMatte = matte && pool.keySet().stream().anyMatch(s -> Species.get(s).map(i -> i.kind() == Species.Kind.SULFIDE).orElse(false));
		// Is there a real metal bath, or only traces? Without a bath, noble metals dissolve in the matte
		// (gold, platinum metals and molybdenum go with copper matte, not into a separate button).
		double matteMass = 0, metalMass = 0;
		for (var e : pool.entrySet()) {
			Species.Info s = Species.get(e.getKey()).orElse(null);
			if (s == null) continue;
			if (s.kind() == Species.Kind.SULFIDE) matteMass += e.getValue();
			else if (s.kind() == Species.Kind.METAL && !Species.SLAG_FORMERS.contains(s.id()) && s.boiling() >= temperature) metalMass += e.getValue();
		}
		boolean bath = metalMass > 0.05 * Math.max(matteMass, 1e-9) || !hasMatte;
		double iron = 0;
		for (var e : pool.entrySet()) {
			Species.Info s = Species.get(e.getKey()).orElse(null);
			if (s == null || e.getValue() <= 0) continue;
			String id = s.id();
			double kg = e.getValue();
			Out o;
			if (s.kind() == Species.Kind.WATER) {
				o = temperature > s.boiling() ? null : Out.LIQUID; // steam is vented
			} else if (s.gasAtRoom() || s.kind() == Species.Kind.GAS) {
				o = Out.GAS;
			} else if (id.equals("C") && pyro) {
				continue; // handled below
			} else if (s.boiling() < temperature) {
				if (s.metallic() || s.kind() == Species.Kind.ORGANIC) o = s.liquidAtRoom() || s.kind() == Species.Kind.ORGANIC ? Out.LIQUID : Out.CONDENSATE;
				else if (s.liquidAtRoom()) o = Out.LIQUID;
				else o = Out.FLUE;
				if (!pyro && o == Out.CONDENSATE) o = Out.FLUE;
			} else if (!pyro) {
				o = s.kind() == Species.Kind.ORGANIC && s.liquidAtRoom() ? Out.LIQUID : Out.PRIMARY;
			} else if (s.kind() == Species.Kind.SULFIDE) {
				o = hasMatte ? Out.MATTE : Out.METAL;
			} else if (s.kind() == Species.Kind.METAL || s.kind() == Species.Kind.ELEMENT) {
				// Above ~1800 C carbon reduces silica and silicon stays metallic (arc furnace); cooler baths oxidise it to slag.
				if (Species.SLAG_FORMERS.contains(id) && !(id.equals("Si") && temperature >= 1800)) o = Out.SLAG;
				else if (hasMatte && (Species.CHALCOPHILE.contains(id) || !bath)) o = Out.MATTE;
				else o = Out.METAL;
			} else if (s.kind() == Species.Kind.ORGANIC) {
				o = Out.GAS;
			} else {
				o = Out.SLAG;
			}
			if (o == null) continue;
			out.computeIfAbsent(o, k -> new HashMap<>()).merge(id, kg, Double::sum);
			if (o == Out.METAL && id.equals("Fe")) iron += kg;
		}
		if (pyro) {
			double c = pool.getOrDefault("C", 0.0);
			if (iron > 0) {
				// Carbon-saturated iron: draw coke to reach ~4.3% C if the batch didn't bring enough.
				double target = iron * PIG_IRON_C / (1 - PIG_IRON_C);
				if (c < target) {
					Map<String, Double> extra = new HashMap<>();
					carbon.draw("C", Math.min(target - c, carbon.available("C")), extra);
					c += extra.getOrDefault("C", 0.0);
					extra.remove("C");
					extra.forEach((k, v) -> out.computeIfAbsent(Out.SLAG, x -> new HashMap<>()).merge(k, v, Double::sum));
				}
				double dissolved = Math.min(c, target);
				if (dissolved > 0) out.computeIfAbsent(Out.METAL, k -> new HashMap<>()).merge("C", dissolved, Double::sum);
				c -= dissolved;
			}
			if (c > 1e-9) {
				if (air) out.computeIfAbsent(Out.GAS, k -> new HashMap<>()).merge("CO2", c * 44.0095 / 12.011, Double::sum);
				else out.computeIfAbsent(Out.SLAG, k -> new HashMap<>()).merge("C", c, Double::sum);
			}
		}
		return out;
	}

	/**
	 * Mineral separation: selected species report to PRIMARY (concentrate) at the method's
	 * recovery, everything else to SECONDARY (tailings) except what is entrained. Trace elements
	 * are locked in the mineral grains, so they follow the selected minerals' overall recovery.
	 */
	public static Map<Out, Map<String, Double>> separate(Reactions.Separation sep, Map<String, Double> pool, boolean frother) {
		Map<Out, Map<String, Double>> out = new EnumMap<>(Out.class);
		double selectedIn = 0, selectedOut = 0;
		Map<String, Double> traces = new HashMap<>();
		for (var e : pool.entrySet()) {
			Species.Info s = Species.get(e.getKey()).orElse(null);
			if (s == null) continue;
			double kg = e.getValue();
			boolean element = s.kind() == Species.Kind.METAL || s.kind() == Species.Kind.ELEMENT;
			double r;
			if (sep.minDensity() > 0 && s.density() >= sep.minDensity() && !element) r = sep.recovery();
			else if (sep.minDensity() > 0 && element && s.density() >= sep.minDensity() && kg > 0.01) r = sep.recovery();
			else if (sep.select().stream().anyMatch(s::has)) r = frother && sep.frother().isPresent() ? sep.frother().get().recovery() : sep.recovery();
			else if (sep.weak().stream().anyMatch(s::has)) r = sep.weakRecovery();
			else if (element) {
				traces.put(s.id(), kg);
				continue;
			} else r = sep.entrainment();
			boolean selected = r > sep.entrainment();
			if (selected) {
				selectedIn += kg;
				selectedOut += kg * r;
			}
			out.computeIfAbsent(Out.PRIMARY, k -> new HashMap<>()).merge(s.id(), kg * r, Double::sum);
			out.computeIfAbsent(Out.SECONDARY, k -> new HashMap<>()).merge(s.id(), kg * (1 - r), Double::sum);
		}
		double traceRecovery = selectedIn > 0 ? selectedOut / selectedIn : sep.entrainment();
		traces.forEach((id, kg) -> {
			out.computeIfAbsent(Out.PRIMARY, k -> new HashMap<>()).merge(id, kg * traceRecovery, Double::sum);
			out.computeIfAbsent(Out.SECONDARY, k -> new HashMap<>()).merge(id, kg * (1 - traceRecovery), Double::sum);
		});
		return out;
	}
}
