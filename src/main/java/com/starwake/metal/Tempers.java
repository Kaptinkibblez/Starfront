package com.starwake.metal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import com.starwake.registry.ModComponents;
import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Tempers (M8d-2): what working and heat treatment have done to a piece of metal, and the strength
 * that follows.
 *
 * <p>An item's {@link Temper} (component {@code starwake:temper}) has a state (annealed, as_cast,
 * sintered, hot_worked, cold_worked, stress_relieved, normalized, quenched, tempered, solution, aged,
 * cracked), the cold work since the last anneal (0..1, thickness reduction), a treatment temperature,
 * the current hardness of quenched or tempered steel, forming progress (true strain) for pieces mid-route, and
 * the as-quenched hardness (so tempering again at a higher temperature starts from the quench).
 *
 * <p>Properties start from the grade's handbook table for the state ({@link AlloyGrade#tempers()};
 * estimated compositions count as annealed) and follow physical rules where the table stops:
 * <ul>
 *   <li>Cold work: UTS rises as uts0 (1 + 0.6 h (1 - e^-3cw)) with the family's work-hardening factor
 *   h, yield climbs toward 95% of it, elongation falls as e^-5cw. Past the crack limit
 *   (0.25 + 1.2% per % of annealed elongation, max 0.9; brittle metals almost none) it cracks.</li>
 *   <li>Quenching steel (Fe base, C >= 0.2%, Ni < 6%): Grossmann ideal diameter D_I = 0.42 sqrt(C)
 *   x product of (1 + f_i wt%_i), critical diameter D = D_I (1 - e^(-1.4 H^0.6)) for the quench
 *   severity H; martensite hardness HRC = 35 + 50 C (max 66), scaled by (D / section)^1.5 toward the
 *   normalised hardness when the section is too thick. Water-quenching C >= 0.5% steel cracks.</li>
 *   <li>Tempering: HV falls toward annealed with ((T - 150) / 550)^0.9; UTS ~ 3.3 HV, yield 0.88 UTS
 *   (0.75 as quenched), elongation 30 - 0.05 HV.</li>
 *   <li>Ageing works only for grades with an aged temper; off-temperature ageing under- or over-ages.</li>
 * </ul>
 * Numbers live in {@code data/starwake/starwake/heat_treatment/default.json} and the grade JSON.
 */
public final class Tempers {
	public record Temper(String state, float coldWork, int temperature, float hardness, float progress, float quenched) {
		/** No component: the grade's reference ("as supplied") temper, i.e. the analysis' properties. */
		public static final Temper REFERENCE = new Temper("", 0, 0, 0, 0, 0);
		public static final Codec<Temper> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("state").forGetter(Temper::state),
				Codec.FLOAT.optionalFieldOf("cold_work", 0f).forGetter(Temper::coldWork),
				Codec.INT.optionalFieldOf("temperature", 0).forGetter(Temper::temperature),
				Codec.FLOAT.optionalFieldOf("hardness", 0f).forGetter(Temper::hardness),
				Codec.FLOAT.optionalFieldOf("progress", 0f).forGetter(Temper::progress),
				Codec.FLOAT.optionalFieldOf("quenched", 0f).forGetter(Temper::quenched)
		).apply(i, Temper::new));
		public static final StreamCodec<ByteBuf, Temper> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Temper::state,
				ByteBufCodecs.FLOAT, Temper::coldWork, ByteBufCodecs.VAR_INT, Temper::temperature, ByteBufCodecs.FLOAT, Temper::hardness,
				ByteBufCodecs.FLOAT, Temper::progress, ByteBufCodecs.FLOAT, Temper::quenched, Temper::new);

		public static Temper of(String state) {
			return new Temper(state, 0, 0, 0, 0, 0);
		}

		public Temper withProgress(float p) {
			return new Temper(state, coldWork, temperature, hardness, p, quenched);
		}
	}

	/** Heat treatment data. */
	public record Rules(Map<String, Map<String, Double>> temperatures, Map<String, Double> severity, Map<String, Double> section,
			Map<String, Double> hardenability, double carbonIdealDiameter, Map<String, Double> workHardening, double waterCrackCarbon,
			double oilCrackCarbon) {
		static final Codec<Rules> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(Codec.STRING, Codec.DOUBLE)).fieldOf("temperatures").forGetter(Rules::temperatures),
				Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).fieldOf("severity").forGetter(Rules::severity),
				Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).fieldOf("section").forGetter(Rules::section),
				Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).fieldOf("hardenability").forGetter(Rules::hardenability),
				Codec.DOUBLE.fieldOf("carbon_ideal_diameter").forGetter(Rules::carbonIdealDiameter),
				Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).fieldOf("work_hardening").forGetter(Rules::workHardening),
				Codec.DOUBLE.fieldOf("water_crack_carbon").forGetter(Rules::waterCrackCarbon),
				Codec.DOUBLE.fieldOf("oil_crack_carbon").forGetter(Rules::oilCrackCarbon)
		).apply(i, Rules::new));
	}

	/** A forming route: from form, to form, the true strain it takes. */
	public record Route(String from, String to, double strain) {
		static final Codec<Route> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("from").forGetter(Route::from),
				Codec.STRING.fieldOf("to").forGetter(Route::to),
				Codec.DOUBLE.fieldOf("strain").forGetter(Route::strain)
		).apply(i, Route::new));
	}

	public record Forming(double passStrain, List<Route> routes) {
		static final Codec<Forming> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.DOUBLE.fieldOf("pass_strain").forGetter(Forming::passStrain),
				Route.CODEC.listOf().fieldOf("routes").forGetter(Forming::routes)
		).apply(i, Forming::new));

		public @Nullable Route route(MetalForm from) {
			for (Route r : routes) if (r.from().equals(from.id)) return r;
			return null;
		}
	}

	private static Rules rules = new Rules(Map.of("default", Map.of("anneal", 0.45)), Map.of("water", 1.0, "oil", 0.35, "air", 0.02),
			Map.of(), Map.of(), 0.42, Map.of("default", 1.0), 0.5, 0.9);
	private static Map<String, Forming> forming = Map.of();

	private Tempers() {}

	public static void init() {
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(StarwakeMod.id("heat_treatment"),
				new SimpleJsonResourceReloadListener<>(Rules.CODEC, FileToIdConverter.json("starwake/heat_treatment")) {
					@Override
					protected void apply(Map<Identifier, Rules> loaded, ResourceManager manager, ProfilerFiller profiler) {
						Rules r = loaded.get(StarwakeMod.id("default"));
						if (r != null) rules = r;
					}
				});
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(StarwakeMod.id("forming"),
				new SimpleJsonResourceReloadListener<>(Forming.CODEC, FileToIdConverter.json("starwake/forming")) {
					@Override
					protected void apply(Map<Identifier, Forming> loaded, ResourceManager manager, ProfilerFiller profiler) {
						Map<String, Forming> m = new HashMap<>();
						loaded.forEach((id, f) -> m.put(id.getPath(), f));
						forming = Map.copyOf(m);
					}
				});
	}

	public static Rules rules() {
		return rules;
	}

	public static @Nullable Forming forming(String machine) {
		return forming.get(machine);
	}

	// --- Item access ------------------------------------------------------------------------------------

	public static Temper of(ItemStack stack) {
		Temper t = stack.get(ModComponents.TEMPER);
		if (t != null) return t;
		String legacy = stack.get(ModComponents.CONDITION);
		return legacy != null ? Temper.of(legacy) : Temper.REFERENCE;
	}

	public static void set(ItemStack stack, Temper t) {
		stack.remove(ModComponents.CONDITION);
		if (t.state().isEmpty() && t.progress() == 0) stack.remove(ModComponents.TEMPER);
		else stack.set(ModComponents.TEMPER, t);
	}

	/** Properties of a metal item in its temper. */
	public static MaterialProperties properties(ItemStack stack) {
		Composition c = MetalItems.compositionOf(stack).orElse(null);
		if (c == null) return new MaterialProperties(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
		MetalForm form = MetalItems.formOf(stack).orElse(MetalForm.INGOT);
		return properties(Metallurgy.analyze(c), of(stack), form);
	}

	// --- Properties --------------------------------------------------------------------------------------

	private static AlloyGrade.TemperProps strength(MaterialProperties p) {
		return new AlloyGrade.TemperProps(p.yieldStrength(), p.tensileStrength(), p.elongation(), p.hardness());
	}

	private static MaterialProperties with(MaterialProperties p, AlloyGrade.TemperProps s) {
		return new MaterialProperties(p.density(), s.yieldStrength(), s.tensileStrength(), s.elongation(), p.modulus(), s.hardness(),
				p.solidus(), p.serviceTemp(), p.conductivity(), p.corrosion());
	}

	private static AlloyGrade.TemperProps scale(AlloyGrade.TemperProps s, double y, double u, double e, double h) {
		return new AlloyGrade.TemperProps(s.yieldStrength() * y, s.tensileStrength() * u, Math.max(0.1, s.elongation() * e), s.hardness() * h);
	}

	/** The table entry for a state, if the analysed grade has one (scaled when the piece is off-spec). */
	private static AlloyGrade.@Nullable TemperProps table(Metallurgy.Analysis a, String state) {
		AlloyGrade g = a.grade();
		if (g == null) return null;
		AlloyGrade.TemperProps t = g.tempers().get(state);
		if (t == null) return null;
		if (!a.estimated() || g.properties().isEmpty()) return t;
		// Off-spec: carry the analysis' penalty over from the reference state.
		AlloyGrade.TemperProps ref = g.tempers().get(g.referenceState());
		if (ref == null || ref.tensileStrength() <= 0) return t;
		double k = a.properties().tensileStrength() / ref.tensileStrength();
		double ke = ref.elongation() <= 0 ? 1 : a.properties().elongation() / ref.elongation();
		return scale(t, k, k, ke, k);
	}

	/** Annealed strength: the table, or the analysis when the composition matches no grade's annealed state. */
	public static AlloyGrade.TemperProps annealed(Metallurgy.Analysis a) {
		AlloyGrade.TemperProps t = table(a, "annealed");
		if (t != null) return t;
		if (a.grade() != null && !a.grade().referenceState().equals("annealed")) {
			// Grade known only in a hardened state: soften by typical factors.
			AlloyGrade.TemperProps ref = strength(a.properties());
			return scale(ref, 0.55, 0.75, 1.6, 0.7);
		}
		return strength(a.properties());
	}

	public static MaterialProperties properties(Metallurgy.Analysis a, Temper t, MetalForm form) {
		MaterialProperties base = a.properties();
		AlloyGrade.TemperProps ann = annealed(a);
		String family = a.familyId();
		AlloyGrade.TemperProps s = switch (t.state()) {
			case "" -> strength(base);
			case "annealed" -> ann;
			case "cracked" -> scale(ann, 0.3, 0.3, 0.3, 1);
			case "as_cast" -> orElse(table(a, "as_cast"), scale(ann, 0.85, 0.8, 0.5, 0.95));
			case "sintered" -> orElse(table(a, "sintered"), scale(ann, 0.9, 0.85, 0.4, 0.95));
			case "hot_worked" -> orElse(table(a, "hot_worked"), steel(a) ? normalized(a, ann) : ann);
			case "normalized" -> normalized(a, ann);
			case "cold_worked" -> coldWorked(ann, t.coldWork(), family);
			case "stress_relieved" -> scale(coldWorked(ann, t.coldWork(), family), 0.95, 1.0, 1.15, 1.0);
			case "solution" -> orElse(table(a, "solution"), ann);
			case "aged" -> aged(a, ann, t.temperature());
			case "quenched", "tempered" -> t.hardness() > 0 ? fromHardness(t.hardness(), t.state().equals("quenched")) : ann;
			default -> ann;
		};
		return with(base, s);
	}

	private static AlloyGrade.TemperProps orElse(AlloyGrade.@Nullable TemperProps t, AlloyGrade.TemperProps fallback) {
		return t != null ? t : fallback;
	}

	private static AlloyGrade.TemperProps normalized(Metallurgy.Analysis a, AlloyGrade.TemperProps ann) {
		AlloyGrade.TemperProps t = table(a, "normalized");
		if (t != null) return t;
		return steel(a) ? scale(ann, 1.15, 1.08, 0.92, 1.08) : ann;
	}

	/** Strain hardening from the annealed state. */
	public static AlloyGrade.TemperProps coldWorked(AlloyGrade.TemperProps ann, double cw, String family) {
		double h = rules.workHardening().getOrDefault(family, rules.workHardening().getOrDefault("default", 1.0));
		double uts = ann.tensileStrength() * (1 + 0.6 * h * (1 - Math.exp(-3 * cw)));
		double ys = ann.yieldStrength() + (0.95 * uts - ann.yieldStrength()) * (1 - Math.exp(-5 * cw));
		double el = Math.max(1, ann.elongation() * Math.exp(-5 * cw));
		return new AlloyGrade.TemperProps(ys, uts, el, ann.hardness() * uts / Math.max(1, ann.tensileStrength()));
	}

	/** Cold work (thickness reduction) a metal takes before it cracks. */
	public static double crackLimit(Metallurgy.Analysis a) {
		double el = annealed(a).elongation();
		if (el < 5) return el * 0.01;
		return Math.min(0.9, 0.25 + el * 0.012);
	}

	private static AlloyGrade.TemperProps aged(Metallurgy.Analysis a, AlloyGrade.TemperProps ann, int temperature) {
		AlloyGrade.TemperProps peak = table(a, "aged");
		if (peak == null) return orElse(table(a, "solution"), ann);
		int ageT = treatmentTemperature(a, "age");
		if (temperature <= 0 || Math.abs(temperature - ageT) <= 60) return peak;
		AlloyGrade.TemperProps sol = orElse(table(a, "solution"), ann);
		if (temperature < ageT) {
			// Under-aged: partway from solution treated to peak.
			double f = Math.max(0, 1 - (ageT - 60 - temperature) / 150.0);
			return blend(sol, peak, f);
		}
		// Over-aged: the precipitates coarsen and soften toward annealed.
		double f = Math.min(1, (temperature - ageT - 60) / 200.0);
		return blend(peak, ann, f);
	}

	private static AlloyGrade.TemperProps blend(AlloyGrade.TemperProps a, AlloyGrade.TemperProps b, double f) {
		return new AlloyGrade.TemperProps(a.yieldStrength() + (b.yieldStrength() - a.yieldStrength()) * f,
				a.tensileStrength() + (b.tensileStrength() - a.tensileStrength()) * f, a.elongation() + (b.elongation() - a.elongation()) * f,
				a.hardness() + (b.hardness() - a.hardness()) * f);
	}

	/** Steel strength from its hardness (quenched or tempered martensite). */
	private static AlloyGrade.TemperProps fromHardness(double hv, boolean asQuenched) {
		double uts = 3.3 * hv;
		double ys = (asQuenched ? 0.75 : 0.88) * uts;
		double el = Math.max(1, Math.min(30, 30 - 0.05 * hv)) * (asQuenched ? 0.5 : 1);
		return new AlloyGrade.TemperProps(ys, uts, el, hv);
	}

	// --- Steel hardening ---------------------------------------------------------------------------------

	/** Hardenable by quenching: an iron base with enough carbon to form martensite (and not austenitic). */
	public static boolean steel(Metallurgy.Analysis a) {
		Composition c = a.composition();
		return c.base().equals("Fe") && c.percent("C") >= 0.2 && c.percent("C") < 2.1 && c.percent("Ni") < 6;
	}

	/** Grossmann ideal critical diameter (inches). */
	public static double idealDiameter(Composition c) {
		double d = rules.carbonIdealDiameter() * Math.sqrt(Math.max(0.05, c.percent("C")));
		for (var e : rules.hardenability().entrySet()) d *= 1 + e.getValue() * Math.min(c.percent(e.getKey()), e.getKey().equals("Cr") ? 3 : 2);
		return d;
	}

	/** Hardness (HV) of a steel piece of this section quenched in this medium; NaN if it cracks. */
	public static double quench(Metallurgy.Analysis a, String medium, MetalForm form) {
		Composition c = a.composition();
		double carbon = c.percent("C");
		double section = rules.section().getOrDefault(form.id, 1.0);
		if (medium.equals("water") && carbon >= rules.waterCrackCarbon()) return Double.NaN;
		if (medium.equals("oil") && carbon >= rules.oilCrackCarbon() && section >= 2) return Double.NaN;
		double h = rules.severity().getOrDefault(medium, 0.35);
		double critical = idealDiameter(c) * (1 - Math.exp(-1.4 * Math.pow(h, 0.6)));
		double hrc = Math.min(66, 35 + 50 * Math.min(carbon, 0.62));
		double hvMartensite = 160 + 2.0 * hrc + 0.115 * hrc * hrc;
		double hvSoft = normalized(a, annealed(a)).hardness();
		double m = Math.min(1, Math.pow(critical / section, 1.5));
		return hvSoft + (hvMartensite - hvSoft) * m;
	}

	/** Tempered hardness from as-quenched. */
	public static double temper(Metallurgy.Analysis a, double hvQuenched, int temperature) {
		double hvSoft = annealed(a).hardness();
		double f = Math.max(0, Math.min(1, (temperature - 150) / 550.0));
		return Math.max(hvSoft, hvQuenched - (hvQuenched - hvSoft) * Math.pow(f, 0.9));
	}

	/** Program temperature (C) for a grade or its family; fractions in the default row are of the solidus. */
	public static int treatmentTemperature(Metallurgy.Analysis a, String program) {
		AlloyGrade g = a.grade();
		if (g != null && g.treatment().containsKey(program)) return g.treatment().get(program);
		Double t = rules.temperatures().getOrDefault(a.familyId(), Map.of()).get(program);
		if (t == null && g != null) t = rules.temperatures().getOrDefault(g.family(), Map.of()).get(program);
		if (t != null) return (int) Math.round(t);
		double frac = rules.temperatures().getOrDefault("default", Map.of()).getOrDefault(program, 0.45);
		return (int) Math.round(frac * (a.properties().solidus() + 273) - 273);
	}

	/** The state a piece is really in: the grade's reference state when it carries no temper. */
	public static String state(Metallurgy.Analysis a, Temper t) {
		if (!t.state().isEmpty()) return t.state();
		return a.grade() != null && a.kind() == Metallurgy.Kind.GRADE ? a.grade().referenceState() : "annealed";
	}

	// --- Processing ------------------------------------------------------------------------------------

	/** Hot-working temperature (C): 75% of the solidus on the absolute scale. */
	public static int hotWorkTemperature(Metallurgy.Analysis a) {
		return (int) Math.round(0.75 * (a.properties().solidus() + 273) - 273);
	}

	/** Too brittle to deform even hot (cast iron, carbides): annealed elongation under 1%. */
	public static boolean unworkable(Metallurgy.Analysis a) {
		return annealed(a).elongation() < 1;
	}

	/**
	 * One forming pass of true strain {@code strain}. Hot: recrystallises as it goes (no hardening,
	 * never cracks unless unworkable). Cold: thickness reduction adds to the cold work since the last
	 * anneal; past {@link #crackLimit} the piece cracks.
	 */
	public static Temper pass(Metallurgy.Analysis a, Temper t, double strain, boolean hot) {
		float progress = (float) (t.progress() + strain);
		if (unworkable(a)) return new Temper("cracked", t.coldWork(), 0, 0, progress, 0);
		if (hot) return new Temper("hot_worked", 0, 0, 0, progress, 0);
		double before = state(a, t).equals("cold_worked") || state(a, t).equals("stress_relieved") ? t.coldWork() : 0;
		double cw = 1 - (1 - before) * Math.exp(-strain);
		if (cw > crackLimit(a)) return new Temper("cracked", (float) cw, 0, 0, progress, 0);
		return new Temper("cold_worked", (float) cw, 0, 0, progress, 0);
	}

	/** Heat-treatment programs (the furnace's program index). */
	public enum Program {
		ANNEAL("anneal"), NORMALIZE("normalize"), HARDEN_WATER("harden"), HARDEN_OIL("harden"), HARDEN_AIR("harden"), TEMPER("temper"),
		SOLUTION("solution"), AGE("age"), STRESS_RELIEVE("stress_relieve");

		public final String key;

		Program(String key) {
			this.key = key;
		}

		/** Programs whose temperature the operator sets (the others use the grade's own). */
		public boolean setTemperature() {
			return this == TEMPER || this == AGE;
		}

		public String medium() {
			return this == HARDEN_WATER ? "water" : this == HARDEN_OIL ? "oil" : "air";
		}
	}

	/** The furnace temperature for a program: the setpoint for temper/age, else the grade's or family's. */
	public static int programTemperature(Metallurgy.Analysis a, Program p, int setpoint) {
		return p.setTemperature() ? setpoint : treatmentTemperature(a, p.key);
	}

	/** The temper after a heat-treatment program at {@code temperature}. */
	public static Temper heatTreat(Metallurgy.Analysis a, Temper t, Program p, int temperature, MetalForm form) {
		String now = state(a, t);
		if (now.equals("cracked")) return t;
		AlloyGrade g = a.grade();
		boolean ageable = g != null && g.tempers().containsKey("aged");
		return switch (p) {
			case ANNEAL -> Temper.of("annealed");
			case NORMALIZE -> Temper.of(steel(a) ? "normalized" : "annealed");
			case HARDEN_WATER, HARDEN_OIL, HARDEN_AIR -> {
				if (steel(a)) {
					double hv = quench(a, p.medium(), form);
					if (Double.isNaN(hv)) yield new Temper("cracked", 0, 0, 0, 0, 0);
					yield new Temper("quenched", 0, temperature, (float) hv, 0, (float) hv);
				}
				// Quenching anything else from its treatment temperature is a solution treatment (or an anneal).
				yield Temper.of(ageable || (g != null && g.tempers().containsKey("solution")) ? "solution" : "annealed");
			}
			case TEMPER -> {
				if ((now.equals("quenched") || now.equals("tempered")) && t.quenched() > 0) {
					int highest = Math.max(temperature, now.equals("tempered") ? t.temperature() : 0);
					yield new Temper("tempered", 0, highest, (float) temper(a, t.quenched(), highest), 0, t.quenched());
				}
				yield now.equals("cold_worked") ? new Temper("stress_relieved", t.coldWork(), temperature, 0, 0, 0) : t;
			}
			case SOLUTION -> Temper.of(ageable || (g != null && g.tempers().containsKey("solution")) ? "solution" : "annealed");
			case AGE -> {
				if (!ageable) yield t;
				if (now.equals("solution")) yield new Temper("aged", 0, temperature, 0, 0, 0);
				// Ageing again only matters hotter (over-ageing).
				if (now.equals("aged")) yield new Temper("aged", 0, Math.max(temperature, t.temperature()), 0, 0, 0);
				yield t;
			}
			case STRESS_RELIEVE -> {
				if (now.equals("cold_worked")) yield new Temper("stress_relieved", t.coldWork(), temperature, 0, 0, 0);
				if (now.equals("quenched") && t.quenched() > 0) {
					yield new Temper("tempered", 0, temperature, (float) temper(a, t.quenched(), temperature), 0, t.quenched());
				}
				yield t;
			}
		};
	}

	// --- Tooltip ----------------------------------------------------------------------------------------

	public static @Nullable Component describe(Temper t) {
		return switch (t.state()) {
			case "" -> null;
			case "cold_worked" -> Component.translatable("metal.starwake.temper.cold_worked", Math.round(t.coldWork() * 100));
			case "stress_relieved" -> Component.translatable("metal.starwake.temper.stress_relieved", Math.round(t.coldWork() * 100));
			case "tempered" -> Component.translatable("metal.starwake.temper.tempered", t.temperature(), Math.round(t.hardness()));
			case "quenched" -> Component.translatable("metal.starwake.temper.quenched", Math.round(t.hardness()));
			case "aged" -> Component.translatable("metal.starwake.temper.aged", t.temperature());
			default -> Component.translatable("metal.starwake.condition." + t.state());
		};
	}
}
