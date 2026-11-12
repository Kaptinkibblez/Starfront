package com.starwake.planet;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import com.starwake.galaxy.SolarSystem;
import com.starwake.planet.proc.ProceduralPlanets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;

/**
 * What each planet's air is made of (M8c-3a), as species mass fractions. Data in
 * {@code data/<ns>/starwake/atmosphere/<id>.json} ({@code composition}), looked up by the planet's
 * id first (overworld, veyra), then its archetype (lush, toxic...). The planet's own roll then
 * adjusts it: a breathable planet gets Earth's 23% oxygen, a non-breathable one loses its free
 * oxygen. Airless planets (air density 0) and space have none. Server-side; cached per dimension.
 */
public final class Atmospheres {
	record Air(Map<String, Double> composition) {
		static final Codec<Air> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).fieldOf("composition").forGetter(Air::composition)
		).apply(i, Air::new));
	}

	/** Earth's O2 mass fraction. */
	private static final double EARTH_O2 = 0.2314;
	private static Map<String, Map<String, Double>> byId = Map.of();
	private static final Map<ResourceKey<Level>, Map<String, Double>> CACHE = new ConcurrentHashMap<>();

	private Atmospheres() {}

	public static void init() {
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(StarwakeMod.id("atmosphere"),
				new SimpleJsonResourceReloadListener<>(Air.CODEC, FileToIdConverter.json("starwake/atmosphere")) {
					@Override
					protected void apply(Map<Identifier, Air> loaded, ResourceManager manager, ProfilerFiller profiler) {
						Map<String, Map<String, Double>> map = new HashMap<>();
						loaded.forEach((id, air) -> map.put(id.getPath(), normalised(air.composition())));
						byId = Map.copyOf(map);
						CACHE.clear();
					}
				});
	}

	/** The air of a dimension: species -> mass fraction (empty if there is none). */
	public static Map<String, Double> of(ResourceKey<Level> dimension) {
		return CACHE.computeIfAbsent(dimension, Atmospheres::compute);
	}

	private static Map<String, Double> compute(ResourceKey<Level> dimension) {
		if (SolarSystem.isSpace(dimension)) return Map.of();
		var ref = Planets.forDimension(dimension).orElse(null);
		if (ref == null) return byId.getOrDefault("overworld", Map.of());
		Planet planet = ref.planet();
		if (planet.airDensity() <= 0) return Map.of();
		Map<String, Double> base = byId.get(ref.key().identifier().getPath());
		if (base == null) {
			for (var g : ProceduralPlanets.generated(planet.system())) {
				if (g.ref().planet().dimension().equals(dimension)) base = byId.get(g.archetype().identifier().getPath());
			}
		}
		if (base == null) base = byId.getOrDefault("overworld", Map.of());
		Map<String, Double> air = new LinkedHashMap<>(base);
		double o2 = air.getOrDefault("O2", 0.0);
		if (planet.hazards().breathable() && o2 < 0.15) {
			air.replaceAll((k, v) -> v * (1 - EARTH_O2) / Math.max(1e-9, 1 - o2));
			air.put("O2", EARTH_O2);
		} else if (!planet.hazards().breathable() && o2 > 0.02) {
			air.remove("O2");
		}
		return normalised(air);
	}

	private static Map<String, Double> normalised(Map<String, Double> in) {
		double total = 0;
		for (double v : in.values()) total += v;
		Map<String, Double> out = new LinkedHashMap<>();
		double t = total;
		in.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).forEach(e -> out.put(e.getKey(), e.getValue() / t));
		return Map.copyOf(out);
	}
}
