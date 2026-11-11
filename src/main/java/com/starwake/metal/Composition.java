package com.starwake.metal;

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
 * What a piece of metal is made of (M8a): element symbols with mass fractions in parts per
 * million, summing to 1,000,000, largest first (the first is the base metal). Fractions are kept
 * to 10 ppm (0.001 wt%), so trace boron in a superalloy survives while equal batches still stack.
 * Stored on items as the {@code starwake:composition} component.
 */
public record Composition(List<Part> parts) {
	public static final int SCALE = 1_000_000, STEP = 10;

	public record Part(String element, int ppm) {
		public double percent() {
			return ppm / 10_000.0;
		}
	}

	public static final Codec<Composition> CODEC = Codec.unboundedMap(Codec.STRING, Codec.INT)
			.xmap(Composition::ofPpm, Composition::toPpmMap);
	public static final StreamCodec<ByteBuf, Composition> STREAM_CODEC = ByteBufCodecs.<ByteBuf, String, Integer, Map<String, Integer>>map(
			HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_INT).map(Composition::ofPpm, Composition::toPpmMap);

	public Composition {
		parts = List.copyOf(parts);
	}

	/** From weights in any unit (wt%, kg...): normalised and rounded. */
	public static Composition of(Map<String, Double> weights) {
		double total = 0;
		for (double w : weights.values()) total += Math.max(0, w);
		if (total <= 0) return new Composition(List.of());
		Map<String, Integer> ppm = new HashMap<>();
		for (var e : weights.entrySet()) {
			int v = (int) Math.round(Math.max(0, e.getValue()) / total * SCALE / STEP) * STEP;
			if (v > 0) ppm.merge(e.getKey(), v, Integer::sum);
		}
		return ofPpm(ppm);
	}

	/** wt% for named elements; the base element takes the balance. */
	public static Composition withBalance(String base, Map<String, Double> percents) {
		Map<String, Double> w = new HashMap<>(percents);
		double rest = 0;
		for (double v : percents.values()) rest += v;
		w.merge(base, Math.max(0, 100 - rest), Double::sum);
		return of(w);
	}

	public static Composition pure(String element) {
		return new Composition(List.of(new Part(element, SCALE)));
	}

	static Composition ofPpm(Map<String, Integer> raw) {
		List<Part> parts = new ArrayList<>();
		long sum = 0;
		for (var e : raw.entrySet()) {
			if (e.getValue() > 0) {
				parts.add(new Part(e.getKey(), e.getValue()));
				sum += e.getValue();
			}
		}
		if (parts.isEmpty()) return new Composition(List.of());
		parts.sort(Comparator.comparingInt(Part::ppm).reversed().thenComparing(Part::element));
		if (sum != SCALE) {
			// Rescale (old or hand-written data) and give rounding error to the base.
			List<Part> scaled = new ArrayList<>();
			long acc = 0;
			for (int i = 1; i < parts.size(); i++) {
				int v = (int) Math.round(parts.get(i).ppm() * (double) SCALE / sum / STEP) * STEP;
				if (v > 0) {
					scaled.add(new Part(parts.get(i).element(), v));
					acc += v;
				}
			}
			scaled.addFirst(new Part(parts.getFirst().element(), (int) (SCALE - acc)));
			parts = scaled;
			parts.sort(Comparator.comparingInt(Part::ppm).reversed().thenComparing(Part::element));
		}
		return new Composition(parts);
	}

	Map<String, Integer> toPpmMap() {
		Map<String, Integer> map = new LinkedHashMap<>();
		for (Part p : parts) map.put(p.element(), p.ppm());
		return map;
	}

	public boolean isEmpty() {
		return parts.isEmpty();
	}

	/** The base (largest) element, or "" if empty. */
	public String base() {
		return parts.isEmpty() ? "" : parts.getFirst().element();
	}

	/** wt% of an element (0 if absent). */
	public double percent(String element) {
		for (Part p : parts) if (p.element().equals(element)) return p.percent();
		return 0;
	}

	/** Mixes compositions by mass (e.g. melting several ingots together). */
	public static Composition mix(Map<Composition, Double> masses) {
		Map<String, Double> w = new HashMap<>();
		masses.forEach((c, m) -> c.parts().forEach(p -> w.merge(p.element(), p.ppm() * m, Double::sum)));
		return of(w);
	}

	/** "Fe 71.6 · Cr 18.4 · Ni 8.9 · C 0.05" (the first n parts). */
	public String format(int n) {
		return parts.stream().limit(n).map(p -> p.element() + " " + formatPercent(p.percent())).collect(Collectors.joining(" · "));
	}

	public static String formatPercent(double pct) {
		if (pct >= 10) return String.format("%.1f", pct);
		if (pct >= 1) return String.format("%.2f", pct);
		if (pct >= 0.1) return String.format("%.2f", pct);
		return String.format("%.3f", pct);
	}

	/** Parses "Fe:72,Cr:18,Ni:8,C:0.08" (wt%; missing base balance is not added). */
	public static Composition parse(String text) {
		Map<String, Double> w = new HashMap<>();
		for (String token : text.split("[,\\s]+")) {
			if (token.isBlank()) continue;
			String[] kv = token.split(":");
			if (kv.length != 2) throw new IllegalArgumentException("Expected Element:percent, got " + token);
			w.merge(kv[0].trim(), Double.parseDouble(kv[1].trim()), Double::sum);
		}
		return of(w);
	}
}
