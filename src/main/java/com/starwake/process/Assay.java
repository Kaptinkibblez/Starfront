package com.starwake.process;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What a bulk material is made of (M8c): chemical species with mass fractions in parts per
 * billion, largest first. Parts per billion keep traces like radium in uraninite (0.3 ppm) or
 * gold in copper ore (8 ppm). Values are kept to 3 significant figures so steady production
 * stacks. Stored on bulk items as the {@code starwake:assay} component.
 */
public record Assay(List<Part> parts) {
	public static final long SCALE = 1_000_000_000L;

	public record Part(String species, int ppb) {
		public double fraction() {
			return ppb / (double) SCALE;
		}
	}

	public static final Codec<Assay> CODEC = Codec.unboundedMap(Codec.STRING, Codec.INT).xmap(Assay::ofPpb, Assay::toMap);
	public static final StreamCodec<ByteBuf, Assay> STREAM_CODEC = ByteBufCodecs.<ByteBuf, String, Integer, Map<String, Integer>>map(
			HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_INT).map(Assay::ofPpb, Assay::toMap);
	public static final Assay EMPTY = new Assay(List.of());

	public Assay {
		parts = List.copyOf(parts);
	}

	/** From masses (any unit), normalised and rounded to 3 significant figures. */
	public static Assay of(Map<String, Double> masses) {
		double total = 0;
		for (double v : masses.values()) total += Math.max(0, v);
		if (total <= 0) return EMPTY;
		Map<String, Integer> ppb = new HashMap<>();
		for (var e : masses.entrySet()) {
			double f = Math.max(0, e.getValue()) / total;
			if (f <= 0) continue;
			int v = (int) Math.round(round3(f) * SCALE);
			if (v > 0) ppb.put(e.getKey(), v);
		}
		return ofPpb(ppb);
	}

	private static double round3(double f) {
		if (f <= 0) return 0;
		double scale = Math.pow(10, 2 - Math.floor(Math.log10(f)));
		return Math.round(f * scale) / scale;
	}

	static Assay ofPpb(Map<String, Integer> raw) {
		List<Part> parts = new ArrayList<>();
		long sum = 0;
		for (var e : raw.entrySet()) {
			if (e.getValue() > 0) {
				parts.add(new Part(e.getKey(), e.getValue()));
				sum += e.getValue();
			}
		}
		if (parts.isEmpty()) return EMPTY;
		parts.sort(Comparator.comparingInt(Part::ppb).reversed().thenComparing(Part::species));
		if (sum != SCALE) {
			Part first = parts.getFirst();
			parts.set(0, new Part(first.species(), (int) Math.max(1, first.ppb() + SCALE - sum)));
		}
		return new Assay(parts);
	}

	Map<String, Integer> toMap() {
		Map<String, Integer> map = new LinkedHashMap<>();
		for (Part p : parts) map.put(p.species(), p.ppb());
		return map;
	}

	public boolean isEmpty() {
		return parts.isEmpty();
	}

	public double fraction(String species) {
		for (Part p : parts) if (p.species().equals(species)) return p.fraction();
		return 0;
	}

	/** kg of each species in {@code mass} kg of this material. */
	public Map<String, Double> masses(double mass) {
		Map<String, Double> out = new LinkedHashMap<>();
		for (Part p : parts) out.put(p.species(), p.fraction() * mass);
		return out;
	}

	/** Element mass fractions, summed over species. */
	public Map<String, Double> elements() {
		Map<String, Double> out = new HashMap<>();
		for (Part p : parts) {
			Species.get(p.species()).ifPresent(s -> s.elements().forEach((el, f) -> out.merge(el, f * p.fraction(), Double::sum)));
		}
		return out;
	}

	/** The species that names this material: the largest that isn't common gangue. */
	public String mainSpecies() {
		for (Part p : parts) {
			if (!GANGUE.contains(p.species())) return p.species();
		}
		return parts.isEmpty() ? "" : parts.getFirst().species();
	}

	private static final java.util.Set<String> GANGUE = java.util.Set.of("SiO2", "Al2O3", "KAlSi3O8", "CaCO3", "MgO", "CaO");

	/** "CuFeS2 59.5 · SiO2 21.0 · ..." */
	public String format(int n) {
		return parts.stream().limit(n).map(p -> p.species() + " " + percent(p.fraction() * 100)).collect(Collectors.joining(" · "));
	}

	public static String percent(double pct) {
		if (pct >= 10) return String.format("%.1f", pct);
		if (pct >= 0.1) return String.format("%.2f", pct);
		if (pct >= 0.0001) return String.format("%.4f", pct);
		return String.format("%.1e", pct);
	}
}
