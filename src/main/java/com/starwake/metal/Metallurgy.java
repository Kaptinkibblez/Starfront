package com.starwake.metal;

import com.starwake.chem.Chemistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.registry.DynamicRegistries;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The metallurgy model (M8a): turns a {@link Composition} into a named material with properties.
 *
 * <ol>
 *   <li><b>Grade.</b> Inside an exact grade's spec: that grade's real handbook properties.</li>
 *   <li><b>Off-spec.</b> Close to a grade (violation &lt;= 2.5 range-widths): the grade's
 *       properties, penalised by how far out it is, and the tooltip says which elements miss.</li>
 *   <li><b>Pure / alloy.</b> Otherwise estimated from the base metal's model
 *       ({@link AlloyBase}): solid-solution strengthening ~ sqrt(wt%), ductility and conductivity
 *       losses, corrosion thresholds, embrittlement. Named by a generic family if one matches.</li>
 * </ol>
 * Density is always computed from the composition (ideal mixing by mass). Results are cached per
 * composition. Grades and base models are synced dynamic registries bound on server start and on
 * client join.
 */
public final class Metallurgy {
	public enum Kind { GRADE, OFF_SPEC, PURE, ALLOY, UNKNOWN }

	/** An element outside a grade's range (for the off-spec tooltip). */
	public record Deviation(String element, double actual, double min, double max) {}

	public record Analysis(Composition composition, Kind kind, @Nullable Identifier gradeId, @Nullable AlloyGrade grade,
			@Nullable AlloyGrade family, MaterialProperties properties, List<Deviation> deviations, double purity, int color,
			Component name) {
		public boolean estimated() {
			return kind != Kind.GRADE;
		}

		/** The family id ("stainless", "superalloy"...) for gates, or the base element. */
		public String familyId() {
			if (grade != null) return grade.family();
			if (family != null) return family.family();
			return composition.base();
		}
	}

	private record Grade(Identifier id, AlloyGrade grade) {}

	private record Data(List<Grade> grades, Map<String, AlloyBase> bases) {}

	private static volatile Data data = new Data(List.of(), Map.of());
	private static final Map<Composition, Analysis> CACHE = new ConcurrentHashMap<>();

	private Metallurgy() {}

	public static void init() {
		DynamicRegistries.registerSynced(AlloyGrade.REGISTRY_KEY, AlloyGrade.CODEC);
		DynamicRegistries.registerSynced(AlloyBase.REGISTRY_KEY, AlloyBase.CODEC);
		ServerLifecycleEvents.SERVER_STARTED.register(server -> bind(server.registryAccess()));
	}

	/** Reads grades and base models from a registry access (server start, client join). */
	public static void bind(RegistryAccess access) {
		List<Grade> grades = new ArrayList<>();
		access.lookup(AlloyGrade.REGISTRY_KEY).ifPresent(r -> r.listElements().forEach(h ->
				grades.add(new Grade(h.key().identifier(), h.value()))));
		grades.sort(Comparator.comparingInt((Grade g) -> g.grade().order()).thenComparing(g -> g.id().toString()));
		Map<String, AlloyBase> bases = new HashMap<>();
		access.lookup(AlloyBase.REGISTRY_KEY).ifPresent(r -> r.listElements().forEach(h -> bases.put(h.value().element(), h.value())));
		data = new Data(List.copyOf(grades), Map.copyOf(bases));
		CACHE.clear();
	}

	/** Every grade, in listing order. */
	public static List<Map.Entry<Identifier, AlloyGrade>> grades() {
		return data.grades().stream().map(g -> Map.entry(g.id(), g.grade())).toList();
	}

	public static Optional<AlloyGrade> grade(Identifier id) {
		return data.grades().stream().filter(g -> g.id().equals(id)).map(Grade::grade).findFirst();
	}

	public static Analysis analyze(Composition c) {
		if (c.isEmpty()) {
			return new Analysis(c, Kind.UNKNOWN, null, null, null, new MaterialProperties(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), List.of(), 0,
					0xB0B0B0, Component.translatable("metal.starwake.unknown"));
		}
		if (CACHE.size() > 8192) CACHE.clear();
		return CACHE.computeIfAbsent(c, Metallurgy::compute);
	}

	private static Analysis compute(Composition c) {
		Data d = data;
		String base = c.base();
		double purity = c.parts().getFirst().percent();
		double density = density(c);

		// 1. Exact grade (most specific spec wins).
		Grade exact = null;
		double exactDist = Double.MAX_VALUE;
		for (Grade g : d.grades()) {
			if (!g.grade().exact() || !g.grade().base().equals(base) || violation(g.grade(), c) > 1e-9) continue;
			double dist = nominalDistance(g.grade(), c) - g.grade().ranges().size() * 100;
			if (exact == null || dist < exactDist) {
				exact = g;
				exactDist = dist;
			}
		}
		if (exact != null) {
			AlloyGrade g = exact.grade();
			return new Analysis(c, Kind.GRADE, exact.id(), g, null, g.properties().orElseGet(() -> model(c, d)), List.of(), purity,
					g.color(), g.name());
		}

		// Generic family name (first match in listing order: most specific first).
		AlloyGrade family = null;
		for (Grade g : d.grades()) {
			if (!g.grade().exact() && g.grade().base().equals(base) && violation(g.grade(), c) <= 1e-9) {
				family = g.grade();
				break;
			}
		}

		// 2. Off-spec: near an exact grade.
		Grade near = null;
		double best = 2.5;
		for (Grade g : d.grades()) {
			if (!g.grade().exact() || !g.grade().base().equals(base) || g.grade().properties().isEmpty()) continue;
			double v = violation(g.grade(), c);
			if (v <= best) {
				best = v;
				near = g;
			}
		}
		if (near != null) {
			AlloyGrade g = near.grade();
			// Density: the grade's real value, scaled by how the composition shifts ideal-mixing density.
			double ref = density(g.nominalComposition());
			MaterialProperties p = penalise(g.properties().get(), best)
					.withDensity(ref > 0 ? g.properties().get().density() * density / ref : density);
			return new Analysis(c, Kind.OFF_SPEC, near.id(), g, family, p, deviations(g, c), purity, g.color(),
					Component.translatable("metal.starwake.off_spec", g.name()));
		}

		// 3. Estimated.
		MaterialProperties p = model(c, d);
		if (purity >= 99.0) {
			Component name = Chemistry.element(base).map(e -> (Component) Component.translatable("element.starwake." + e.name()))
					.orElse(Component.literal(base));
			return new Analysis(c, Kind.PURE, null, null, family, p, List.of(), purity, blendColor(c), name);
		}
		Component name = family != null ? family.name() : Component.translatable("metal.starwake.alloy",
				c.parts().stream().filter(x -> x.percent() >= 1.0).limit(3).map(Composition.Part::element).collect(Collectors.joining("-")));
		return new Analysis(c, Kind.ALLOY, null, null, family, p, List.of(), purity, family != null ? family.color() : blendColor(c), name);
	}

	/** How far outside a grade's spec, in range-widths (0 = inside). */
	public static double violation(AlloyGrade g, Composition c) {
		if (!g.base().equals(c.base())) return Double.MAX_VALUE;
		double v = 0;
		for (var e : g.ranges().entrySet()) {
			double w = c.percent(e.getKey());
			double min = e.getValue().min(), max = e.getValue().max();
			double width = Math.max(max - min, 0.05 + 0.1 * max);
			if (w < min - 1e-9) v += (min - w) / width;
			else if (w > max + 1e-9) v += (w - max) / width;
		}
		double other = 0;
		for (Composition.Part p : c.parts()) {
			if (!p.element().equals(g.base()) && !g.ranges().containsKey(p.element())) other += p.percent();
		}
		if (other > g.maxOther() + 1e-9) v += (other - g.maxOther()) / 0.5;
		return v;
	}

	private static double nominalDistance(AlloyGrade g, Composition c) {
		double d = 0;
		for (var e : g.ranges().entrySet()) {
			double mid = (e.getValue().min() + e.getValue().max()) / 2;
			double width = Math.max(e.getValue().max() - e.getValue().min(), 0.05);
			d += Math.abs(c.percent(e.getKey()) - mid) / width;
		}
		return d;
	}

	private static List<Deviation> deviations(AlloyGrade g, Composition c) {
		List<Deviation> out = new ArrayList<>();
		for (var e : g.ranges().entrySet()) {
			double w = c.percent(e.getKey());
			if (w < e.getValue().min() - 1e-9 || w > e.getValue().max() + 1e-9) {
				out.add(new Deviation(e.getKey(), w, e.getValue().min(), e.getValue().max()));
			}
		}
		double other = 0;
		for (Composition.Part p : c.parts()) {
			if (!p.element().equals(g.base()) && !g.ranges().containsKey(p.element())) other += p.percent();
		}
		if (other > g.maxOther() + 1e-9) out.add(new Deviation("*", other, 0, g.maxOther()));
		return out;
	}

	private static MaterialProperties penalise(MaterialProperties p, double v) {
		double f = Math.min(v, 2.5);
		return new MaterialProperties(p.density(), p.yieldStrength() * Math.max(0.5, 1 - 0.08 * f), p.tensileStrength() * Math.max(0.5, 1 - 0.06 * f),
				p.elongation() * Math.max(0.3, 1 - 0.12 * f), p.modulus(), p.hardness() * Math.max(0.6, 1 - 0.05 * f), p.solidus(),
				p.serviceTemp() - 25 * f, p.conductivity(), Math.max(0, p.corrosion() - 0.6 * f));
	}

	/** Estimated properties from the base metal's model. */
	private static MaterialProperties model(Composition c, Data d) {
		String base = c.base();
		AlloyBase b = d.bases().get(base);
		Chemistry.Element baseEl = Chemistry.element(base).orElse(null);
		double baseMeltC = baseEl == null || baseEl.meltingPoint() <= 0 ? 1000 : baseEl.meltingPoint() - 273.15;
		MaterialProperties pure = b != null ? b.pure() : new MaterialProperties(baseEl == null ? 5 : baseEl.density(), 40, 60, 1, 100, 50,
				baseMeltC, 0.4 * baseMeltC, 5, 5);
		double strength = 0, duct = 0, k = 0, corrosion = pure.corrosion(), service = 0, solidus = 0;
		boolean brittle = false;
		for (Composition.Part part : c.parts()) {
			if (part.element().equals(base)) continue;
			double w = part.percent();
			AlloyBase.Solute s = b == null ? AlloyBase.Solute.DEFAULT : b.solute(part.element());
			strength += s.strength() * Math.sqrt(Math.min(w, s.cap()));
			duct += s.ductility() * w;
			k += s.conductivity() * w;
			corrosion += s.corrosion() * w;
			if (s.threshold().isPresent() && w >= s.threshold().get()) corrosion += s.bonus();
			service += s.service() * Math.min(w, s.cap());
			double defaultDepression = Chemistry.element(part.element())
					.map(e -> e.meltingPoint() > 0 ? Math.max(0, (baseMeltC - (e.meltingPoint() - 273.15)) * 0.01) : 3.0).orElse(3.0);
			solidus += s.solidus().orElse(defaultDepression) * w;
			if (s.brittleAbove().isPresent() && w > s.brittleAbove().get()) brittle = true;
		}
		double ys = pure.yieldStrength() + strength;
		double uts = Math.max(pure.tensileStrength() + 1.15 * strength, ys * 1.05);
		double el = Math.clamp(pure.elongation() * Math.exp(-duct), 0.2, 70);
		if (brittle) {
			el = Math.min(el, 1);
			uts *= 0.6;
			ys = uts * 0.9;
		}
		double sol = Math.max(pure.solidus() - solidus, 0.4 * pure.solidus());
		double svc = Math.min(pure.serviceTemp() + service, sol * 0.8);
		return new MaterialProperties(density(c), ys, uts, el, pure.modulus(), Math.max(pure.hardness(), uts / 3.2), sol, svc,
				pure.conductivity() / (1 + k), Math.clamp(corrosion, 0, 10));
	}

	/** Density by ideal mixing by mass: 1 / sum(w_i / rho_i), over the condensed elements. */
	public static double density(Composition c) {
		double inv = 0, used = 0;
		for (Composition.Part p : c.parts()) {
			double rho = Chemistry.element(p.element()).map(Chemistry.Element::density).orElse(0.0);
			if (rho < 0.5) continue;
			double w = p.ppm() / (double) Composition.SCALE;
			inv += w / rho;
			used += w;
		}
		return inv <= 0 ? 0 : used / inv;
	}

	/** Mass-weighted element colours, pulled toward metallic grey. */
	private static int blendColor(Composition c) {
		double r = 0, g = 0, b = 0;
		for (Composition.Part p : c.parts()) {
			int col = Chemistry.element(p.element()).map(Chemistry.Element::color).orElse(0xA0A0A0);
			double w = p.ppm() / (double) Composition.SCALE;
			r += w * ((col >> 16) & 0xFF);
			g += w * ((col >> 8) & 0xFF);
			b += w * (col & 0xFF);
		}
		double k = 0.25;
		int R = (int) Math.round(r * (1 - k) + 176 * k), G = (int) Math.round(g * (1 - k) + 176 * k), B = (int) Math.round(b * (1 - k) + 176 * k);
		return (R << 16) | (G << 8) | B;
	}
}
