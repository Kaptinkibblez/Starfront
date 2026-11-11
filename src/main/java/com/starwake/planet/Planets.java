package com.starwake.planet;

import com.starwake.StarwakeMod;
import com.starwake.planet.proc.ProceduralPlanets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.registry.DynamicRegistries;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Every planet in the game and fast lookups into them.
 *
 * Two sources (M5):
 * <ul>
 *   <li>the {@code starwake:planet} dynamic registry: hand-authored planets in datapacks
 *       (Overworld, Veyra, space itself), synced to clients;</li>
 *   <li>procedural planets generated from the world seed ({@link ProceduralPlanets}), sent to
 *       clients in a payload on join.</li>
 * </ul>
 * {@link #all} merges both (cached per registry instance and procedural generation), and the
 * server keeps a dimension index so per-entity lookups stay O(1).
 */
public final class Planets {
	public static final ResourceKey<Registry<Planet>> REGISTRY_KEY =
			ResourceKey.createRegistryKey(StarwakeMod.id("planet"));

	/** Server dimension -> planet index, rebuilt once the procedural planets exist. */
	private static Map<ResourceKey<Level>, PlanetRef> byDimension = Map.of();

	private static @Nullable Registry<Planet> cachedRegistry;
	private static int cachedGeneration = -1;
	private static List<PlanetRef> cachedAll = List.of();
	private static Map<ResourceKey<Level>, PlanetRef> cachedIndex = Map.of();

	private Planets() {}

	public static void init() {
		DynamicRegistries.registerSynced(REGISTRY_KEY, Planet.CODEC);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> byDimension = Map.of());
	}

	/** Called by {@link ProceduralPlanets} after it generates the system (server start). */
	public static void rebuildIndex(MinecraftServer server) {
		byDimension = index(server.registryAccess());
		StarwakeMod.LOGGER.info("Loaded {} planet(s): {}", byDimension.size(),
				byDimension.values().stream().map(p -> p.key().identifier().toString()).toList());
	}

	public static Registry<Planet> registry(MinecraftServer server) {
		return server.registryAccess().lookupOrThrow(REGISTRY_KEY);
	}

	/** Registry planets followed by procedural ones (either side). */
	public static synchronized List<PlanetRef> all(RegistryAccess access) {
		Registry<Planet> registry = access.lookup(REGISTRY_KEY).orElse(null);
		if (registry != cachedRegistry || ProceduralPlanets.generation() != cachedGeneration) {
			List<PlanetRef> list = new ArrayList<>();
			if (registry != null) registry.listElements().forEach(ref -> list.add(new PlanetRef(ref.key(), ref.value())));
			list.addAll(ProceduralPlanets.planets());
			Map<ResourceKey<Level>, PlanetRef> index = new HashMap<>();
			for (PlanetRef ref : list) index.putIfAbsent(ref.planet().dimension(), ref);
			cachedAll = List.copyOf(list);
			cachedIndex = Map.copyOf(index);
			cachedRegistry = registry;
			cachedGeneration = ProceduralPlanets.generation();
		}
		return cachedAll;
	}

	public static synchronized Map<ResourceKey<Level>, PlanetRef> index(RegistryAccess access) {
		all(access);
		return cachedIndex;
	}

	public static Optional<PlanetRef> get(RegistryAccess access, ResourceKey<Planet> key) {
		return all(access).stream().filter(p -> p.key().equals(key)).findFirst();
	}

	/** The planet whose surface is this dimension (either side). */
	public static Optional<PlanetRef> forDimension(RegistryAccess access, ResourceKey<Level> dimension) {
		return Optional.ofNullable(index(access).get(dimension));
	}

	/** Server-side: the planet whose surface is the given dimension, if any. */
	public static Optional<PlanetRef> forDimension(ResourceKey<Level> dimension) {
		return Optional.ofNullable(byDimension.get(dimension));
	}

	public static boolean isPlanet(ResourceKey<Level> dimension) {
		return byDimension.containsKey(dimension);
	}

	/** Air density for a dimension: the planet's, 0 in space, 1.0 anywhere else. */
	public static double airDensityOf(ResourceKey<Level> dimension) {
		PlanetRef planet = byDimension.get(dimension);
		if (planet == null) return 1.0;
		return planet.planet().destination() ? planet.planet().airDensity() : 0.0;
	}

	/** Gravity multiplier for a dimension: the planet's gravity, or 1.0 anywhere else. */
	public static double gravityOf(ResourceKey<Level> dimension) {
		PlanetRef planet = byDimension.get(dimension);
		return planet == null ? 1.0 : planet.planet().gravity();
	}

	/** Environment hazards for a dimension (server side). */
	public static Hazards hazardsOf(ResourceKey<Level> dimension) {
		PlanetRef planet = byDimension.get(dimension);
		return planet == null ? Hazards.EARTHLIKE : planet.planet().hazards();
	}
}
