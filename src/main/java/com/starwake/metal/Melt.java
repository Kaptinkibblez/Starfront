package com.starwake.metal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import com.starwake.process.Species;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * A molten metal bath (M8d-1): element masses (kg) with a slag layer (species kg) on top.
 *
 * <p>Steel refining follows {@link Rules} (datapack {@code starwake/steelmaking/default.json}): blown
 * oxygen oxidises elements in Ellingham order down to their residual floors, each to its oxide in the
 * slag (carbon to CO gas); with argon dilution (AOD) carbon burns before chromium. Phosphorus only
 * leaves into a basic (lime) slag. Leftover oxygen dissolves (up to saturation), then burns iron to
 * FeO. Aluminium or silicon in the bath take dissolved oxygen out as Al2O3/SiO2 (deoxidation); a
 * deoxidised bath under lime slag loses sulfur to CaS (desulfurisation).
 *
 * <p>{@link #plan} is the target-grade assistant: the additions (kg of each element, or of the base
 * to dilute) that bring the bath to the middle of a grade's spec, plus which elements to burn down
 * by blowing.
 */
public final class Melt {
	/** Refining numbers, from JSON. */
	public record Rules(List<String> order, List<String> aodOrder, Map<String, String> oxides, Map<String, Double> residual, double aodCarbon,
			double oxygenSaturation, double blowKgPerS, double limePerP2o5, double desulfuriseFraction, double sulfurFloor,
			double deoxidisedOxygen) {
		static final Codec<Rules> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.listOf().fieldOf("order").forGetter(Rules::order),
				Codec.STRING.listOf().fieldOf("aod_order").forGetter(Rules::aodOrder),
				Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf("oxides").forGetter(Rules::oxides),
				Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).fieldOf("residual").forGetter(Rules::residual),
				Codec.DOUBLE.fieldOf("aod_carbon").forGetter(Rules::aodCarbon),
				Codec.DOUBLE.fieldOf("oxygen_saturation").forGetter(Rules::oxygenSaturation),
				Codec.DOUBLE.fieldOf("blow_kg_per_s").forGetter(Rules::blowKgPerS),
				Codec.DOUBLE.fieldOf("lime_per_p2o5").forGetter(Rules::limePerP2o5),
				Codec.DOUBLE.fieldOf("desulfurise_fraction").forGetter(Rules::desulfuriseFraction),
				Codec.DOUBLE.fieldOf("sulfur_floor").forGetter(Rules::sulfurFloor),
				Codec.DOUBLE.fieldOf("deoxidised_oxygen").forGetter(Rules::deoxidisedOxygen)
		).apply(i, Rules::new));
	}

	private static Rules rules = new Rules(List.of("Al", "Si", "Mn", "Cr", "C", "P", "Fe"), List.of("Al", "Si", "C", "Mn", "Cr", "P", "Fe"),
			Map.of("Al", "Al2O3", "Si", "SiO2", "Mn", "MnO", "Cr", "Cr2O3", "C", "CO", "P", "P2O5", "Fe", "FeO"), Map.of("C", 0.02), 0.008, 0.06,
			0.25, 3, 0.5, 0.002, 0.004);

	/** Base metals that react with air when molten (pick up O and N outside a vacuum or argon). */
	public static final java.util.Set<String> REACTIVE = java.util.Set.of("Ti", "Zr", "Hf", "Nb", "Ta", "V", "U", "Th", "Be", "Y", "Sc", "La",
			"Ce", "Pr", "Nd", "Sm", "Gd", "Dy", "Li", "Mg", "Ca");

	public static void init() {
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(StarwakeMod.id("steelmaking"),
				new SimpleJsonResourceReloadListener<>(Rules.CODEC, FileToIdConverter.json("starwake/steelmaking")) {
					@Override
					protected void apply(Map<Identifier, Rules> loaded, ResourceManager manager, ProfilerFiller profiler) {
						Rules r = loaded.get(StarwakeMod.id("default"));
						if (r != null) rules = r;
					}
				});
	}

	public static Rules rules() {
		return rules;
	}

	public final Map<String, Double> metal = new LinkedHashMap<>();
	public final Map<String, Double> slag = new LinkedHashMap<>();

	public double mass() {
		double m = 0;
		for (double v : metal.values()) m += v;
		return m;
	}

	public double slagMass() {
		double m = 0;
		for (double v : slag.values()) m += v;
		return m;
	}

	public double percent(String element) {
		double m = mass();
		return m <= 0 ? 0 : 100 * metal.getOrDefault(element, 0.0) / m;
	}

	public Composition composition() {
		return metal.isEmpty() ? new Composition(List.of()) : Composition.of(metal);
	}

	/** Liquidus estimate (C): the analysed solidus plus 50, or 0 when empty. */
	public double liquidus() {
		return metal.isEmpty() ? 0 : Metallurgy.analyze(composition()).properties().solidus() + 50;
	}

	/** Liquidus the bath would have after adding this metal. */
	public double liquidusWith(Composition c, double kg) {
		Map<String, Double> m = new HashMap<>(metal);
		for (Composition.Part p : c.parts()) m.merge(p.element(), p.percent() / 100 * kg, Double::sum);
		return Metallurgy.analyze(Composition.of(m)).properties().solidus() + 50;
	}

	/** Energy (J) to heat a kg of charge from 25 C to the bath temperature and melt it. */
	public static double meltEnergyPerKg(double bathTemperature) {
		return 650 * Math.max(0, bathTemperature - 25) + 300_000;
	}

	/** Adds molten metal; in air, reactive bases pick up oxygen and nitrogen. */
	public void add(Composition c, double kg, boolean exposedToAir) {
		for (Composition.Part p : c.parts()) addElement(p.element(), p.percent() / 100 * kg);
		if (exposedToAir && REACTIVE.contains(composition().base())) {
			addElement("O", 0.005 * kg);
			addElement("N", 0.002 * kg);
		}
	}

	public void addElement(String element, double kg) {
		if (kg > 1e-12) metal.merge(element, kg, Double::sum);
	}

	public void addSlag(String species, double kg) {
		if (kg > 1e-12) slag.merge(species, kg, Double::sum);
	}

	/** Puts a slag item's contents back on the bath. */
	public void addSlagAll(net.minecraft.world.item.ItemStack slagItem) {
		var assay = slagItem.get(com.starwake.registry.ModComponents.ASSAY);
		if (assay != null) assay.masses(1.0).forEach(this::addSlag);
	}

	private double take(String element, double kg) {
		double has = metal.getOrDefault(element, 0.0);
		double t = Math.min(has, kg);
		if (has - t <= 1e-12) metal.remove(element);
		else metal.put(element, has - t);
		return t;
	}

	private static double massFraction(String species, String element) {
		return Species.get(species).map(s -> s.elements().getOrDefault(element, 0.0)).orElse(0.0);
	}

	/**
	 * Blows oxygen into the bath. Returns the gases released (CO) in kg. {@code aod} = argon dilution.
	 */
	public Map<String, Double> blow(double oxygenKg, boolean aod) {
		Map<String, Double> gas = new HashMap<>();
		double o2 = oxygenKg;
		double total = mass();
		if (total <= 0) return gas;
		for (String el : aod ? rules.aodOrder() : rules.order()) {
			if (o2 <= 1e-12) break;
			String oxide = rules.oxides().get(el);
			if (oxide == null || !metal.containsKey(el)) continue;
			double floorPct = el.equals("C") && aod ? rules.aodCarbon() : rules.residual().getOrDefault(el, 0.0);
			if (el.equals("Fe")) {
				// Dissolve oxygen up to saturation, then burn iron to FeO.
				double dissolved = metal.getOrDefault("O", 0.0), sat = rules.oxygenSaturation() / 100 * total;
				double toDissolve = Math.max(0, Math.min(o2, sat - dissolved));
				addElement("O", toDissolve);
				o2 -= toDissolve;
				floorPct = 0;
			}
			if (el.equals("P")) {
				// Phosphorus needs a basic slag: lime >= limePerP2o5 x the P2O5 formed.
				double lime = slag.getOrDefault("CaO", 0.0);
				double p2o5Room = Math.max(0, lime / rules.limePerP2o5() - slag.getOrDefault("P2O5", 0.0));
				double pMax = p2o5Room * massFraction("P2O5", "P");
				double removableP = Math.max(0, metal.getOrDefault("P", 0.0) - floorPct / 100 * total);
				floorPct = 100 * (metal.getOrDefault("P", 0.0) - Math.min(removableP, pMax)) / total;
			}
			double removable = Math.max(0, metal.getOrDefault(el, 0.0) - floorPct / 100 * total);
			if (removable <= 1e-12) continue;
			double fEl = massFraction(oxide, el), fO = massFraction(oxide, "O");
			double oPerKg = fO / fEl;
			double kgEl = Math.min(removable, o2 / oPerKg);
			take(el, kgEl);
			double oxideKg = kgEl / fEl;
			o2 -= kgEl * oPerKg;
			if (Species.get(oxide).map(s -> s.kind() == Species.Kind.GAS).orElse(false)) gas.merge(oxide, oxideKg, Double::sum);
			else addSlag(oxide, oxideKg);
		}
		return gas;
	}

	/** Deoxidation and desulfurisation; call about once a second. */
	public void settle() {
		double total = mass();
		if (total <= 0) return;
		// Al, then Si, take dissolved oxygen into the slag.
		for (String[] d : new String[][] {{"Al", "Al2O3"}, {"Si", "SiO2"}}) {
			double o = metal.getOrDefault("O", 0.0) - rules.deoxidisedOxygen() / 100 * total * 0.5;
			double el = metal.getOrDefault(d[0], 0.0);
			if (o <= 1e-9 || el <= 1e-9) continue;
			double fEl = massFraction(d[1], d[0]), fO = massFraction(d[1], "O");
			double kgEl = Math.min(el, o * fEl / fO);
			take(d[0], kgEl);
			take("O", kgEl * fO / fEl);
			addSlag(d[1], kgEl / fEl);
		}
		// Desulfurisation under a lime slag once deoxidised: S + CaO -> CaS + O (the O goes to Al/Si next time).
		double lime = slag.getOrDefault("CaO", 0.0);
		double s = metal.getOrDefault("S", 0.0) - rules.sulfurFloor() / 100 * total;
		if (lime > 0.01 && s > 1e-9 && metal.getOrDefault("O", 0.0) <= rules.deoxidisedOxygen() / 100 * total) {
			double kgS = Math.min(s * rules.desulfuriseFraction(), lime * massFraction("CaS", "S") / massFraction("CaO", "Ca") * massFraction("CaO", "Ca"));
			double caS = kgS / massFraction("CaS", "S");
			double caoUsed = caS * massFraction("CaS", "Ca") / massFraction("CaO", "Ca");
			take("S", kgS);
			slag.merge("CaO", -Math.min(caoUsed, lime), Double::sum);
			slag.values().removeIf(v -> v <= 1e-12);
			addSlag("CaS", caS);
			addElement("O", caoUsed * massFraction("CaO", "O"));
		}
	}

	/** Pours kg of metal out (proportional to the bath composition). */
	public Composition tap(double kg) {
		double total = mass();
		Composition c = composition();
		double f = Math.min(1, kg / total);
		metal.replaceAll((k, v) -> v * (1 - f));
		metal.values().removeIf(v -> v <= 1e-12);
		return c;
	}

	/** Takes up to kg of slag (proportional). */
	public Map<String, Double> tapSlag(double kg) {
		double total = slagMass();
		Map<String, Double> out = new HashMap<>();
		if (total <= 0) return out;
		double f = Math.min(1, kg / total);
		slag.forEach((k, v) -> out.put(k, v * f));
		slag.replaceAll((k, v) -> v * (1 - f));
		slag.values().removeIf(v -> v <= 1e-12);
		return out;
	}

	// --- Target-grade assistant -------------------------------------------------------------------------

	/** One suggestion: add kg of an element (positive), or burn it down by blowing (kg < 0). */
	public record Step(String element, double kg) {}

	/** Elements that blowing can lower. */
	public static final java.util.Set<String> BLOWABLE = java.util.Set.of("C", "Si", "Mn", "P", "Al", "Ti", "V", "O");

	/**
	 * Additions (kg) bringing the bath to the middle of each element's spec range: elements below are
	 * added, the base is added to dilute elements above that can't be blown out; elements above that
	 * can be blown out are listed as negative steps.
	 */
	public static List<Step> plan(Map<String, Double> bath, AlloyGrade grade) {
		List<Step> steps = new ArrayList<>();
		double mass = 0;
		for (double v : bath.values()) mass += v;
		if (mass <= 0) return steps;
		// Elements with a real minimum are aimed at the middle of their range; those whose minimum is 0
		// (N, P, S, C in low-carbon grades...) are caps: never added, only kept at or below 80% of the max.
		Map<String, Double> target = new HashMap<>(), cap = new HashMap<>();
		double alloys = 0;
		for (var e : grade.ranges().entrySet()) {
			if (e.getValue().min() <= 0) {
				cap.put(e.getKey(), e.getValue().max() / 100 * 0.8);
				alloys += Math.min(bath.getOrDefault(e.getKey(), 0.0) / mass, e.getValue().max() / 100 * 0.8);
				continue;
			}
			double t = (e.getValue().min() + e.getValue().max()) / 200;
			target.put(e.getKey(), t);
			alloys += t;
		}
		target.put(grade.base(), Math.max(0, 1 - alloys));
		// Final mass F: blowable elements burn down to their target share of F, other elements must not
		// exceed their share (dilute with more of everything), then fill every target element up to t*F.
		double f = mass;
		Map<String, Double> blown = new HashMap<>();
		Map<String, Double> add = new HashMap<>();
		for (int iter = 0; iter < 100; iter++) {
			blown.clear();
			add.clear();
			double required = 0, kept = 0;
			for (var e : bath.entrySet()) {
				String el = e.getKey();
				double t = target.containsKey(el) ? target.get(el) : cap.getOrDefault(el, 0.0);
				if (BLOWABLE.contains(el) && !el.equals(grade.base())) {
					double keep = Math.min(e.getValue(), t * f);
					if (e.getValue() - keep > 1e-6) blown.put(el, e.getValue() - keep);
					kept += keep;
				} else {
					kept += e.getValue();
					double share = t > 0 ? t : grade.maxOther() / 100 / 2;
					if (!el.equals(grade.base())) required = Math.max(required, e.getValue() / Math.max(share, 1e-4));
				}
			}
			double added = 0;
			for (var t : target.entrySet()) {
				double have = bath.getOrDefault(t.getKey(), 0.0) - blown.getOrDefault(t.getKey(), 0.0);
				double x = t.getValue() * f - have;
				if (x > 1e-6) {
					add.put(t.getKey(), x);
					added += x;
				}
			}
			double next = Math.max(kept + added, required);
			if (Math.abs(next - f) < 1e-6) break;
			f = next;
		}
		add.forEach((el, kg) -> steps.add(new Step(el, kg)));
		blown.forEach((el, kg) -> steps.add(new Step(el, -kg)));
		steps.sort((a, b) -> Double.compare(Math.abs(b.kg()), Math.abs(a.kg())));
		return steps;
	}

}
