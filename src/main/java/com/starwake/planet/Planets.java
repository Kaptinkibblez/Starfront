package com.starwake.planet;

import com.starwake.StarwakeMod;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.registry.DynamicRegistries;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

/**
 * Owns the {@code starwake:planet} dynamic registry and fast lookups into it.
 *
 * Planets are data-driven: dropping a JSON file into a datapack adds a planet with no code
 * changes. The registry is synced to clients so client-side features (star map, HUD) can read it.
 */
public final class Planets {
	public static final ResourceKey<Registry<Planet>> REGISTRY_KEY =
			ResourceKey.createRegistryKey(StarwakeMod.id("planet"));

	/**
	 * Dimension -> planet index. World-level dynamic registries are fixed for the lifetime of a
	 * server, so it's rebuilt once on server start, keeping per-entity lookups O(1).
	 */
	private static Map<ResourceKey<Level>, Holder.Reference<Planet>> byDimension = Map.of();

	private Planets() {}

	public static void init() {
		DynamicRegistries.registerSynced(REGISTRY_KEY, Planet.CODEC);

		ServerLifecycleEvents.SERVER_STARTING.register(Planets::rebuildIndex);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> byDimension = Map.of());
	}

	private static void rebuildIndex(MinecraftServer server) {
		Map<ResourceKey<Level>, Holder.Reference<Planet>> index = new HashMap<>();
		registry(server).listElements().forEach(planet -> index.put(planet.value().dimension(), planet));
		byDimension = Map.copyOf(index);
		StarwakeMod.LOGGER.info("Loaded {} planet(s): {}", index.size(),
				index.values().stream().map(p -> p.key().identifier().toString()).toList());
	}

	public static Registry<Planet> registry(MinecraftServer server) {
		return server.registryAccess().lookupOrThrow(REGISTRY_KEY);
	}

	/** The planet whose surface is the given dimension, if any. */
	public static Optional<Holder.Reference<Planet>> forDimension(ResourceKey<Level> dimension) {
		return Optional.ofNullable(byDimension.get(dimension));
	}

	public static boolean isPlanet(ResourceKey<Level> dimension) {
		return byDimension.containsKey(dimension);
	}

	/** Gravity multiplier for a dimension: the planet's gravity, or 1.0 anywhere else. */
	public static double gravityOf(ResourceKey<Level> dimension) {
		Holder.Reference<Planet> planet = byDimension.get(dimension);
		return planet == null ? 1.0 : planet.value().gravity();
	}
}
