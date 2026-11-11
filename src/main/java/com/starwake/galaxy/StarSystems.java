package com.starwake.galaxy;

import com.mojang.serialization.Codec;
import com.starwake.StarwakeMod;
import com.starwake.network.GalaxyInfoPayload;
import com.starwake.network.SystemInfoPayload;
import com.starwake.planet.Hazards;
import com.starwake.planet.Planet;
import com.starwake.planet.PlanetRef;
import com.starwake.planet.Planets;
import com.starwake.planet.proc.PlanetArchetype;
import com.starwake.planet.proc.ProceduralPlanets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.registry.DynamicRegistries;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;
import xyz.nucleoid.fantasy.util.VoidChunkGenerator;

/**
 * Star systems beyond Solace (M6), server side.
 *
 * <p>{@link #plan} lays out a system from its {@link Galaxy.Star}: the star (and binary
 * companion) as bodies at the centre of the system's own space, then planets on orbits spread
 * logarithmically (as real systems are) and scaled to the star's habitable zone (the square root
 * of its luminosity), each given an archetype whose scaled orbit band fits: red dwarfs crowd
 * their planets close in, blue giants push them far out, remnants keep only barren and frozen
 * worlds. Planet values come from {@code ProceduralPlanets.create}, as for Solace.
 *
 * <p>{@link #open} makes a system real: its space becomes a Fantasy void level
 * ({@code starwake:<id>/space}) and every planet its own level ({@code starwake:<id>/p<n>}).
 * Opened systems are remembered ({@link State}) and reopened on server start, so players and
 * ships left there come back.
 */
public final class StarSystems {
	/** 1 solar radius in space blocks (Solace's radius). */
	public static final double SOLAR_RADIUS = 40_000;
	/** Space dimensions end near 30M blocks; keep everything well inside. */
	private static final double MAX_ORBIT = 26_000_000;
	private static final ResourceKey<Biome> SPACE_BIOME = ResourceKey.create(Registries.BIOME, StarwakeMod.id("space"));
	private static final Hazards SPACE_HAZARDS = new Hazards(false, 0, -120);

	/** A planned system: its star bodies and generated planets. */
	public record Plan(List<PlanetRef> stars, List<ProceduralPlanets.Generated> planets) {}

	private static long galaxySeed;
	private static final Map<String, Plan> PLANS = new HashMap<>();

	private StarSystems() {}

	public static void init() {
		DynamicRegistries.registerSynced(StarClass.REGISTRY_KEY, StarClass.CODEC);
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			galaxySeed = Galaxy.seedFor(server.getWorldGenSettings().options().seed());
			Galaxy.configure(galaxySeed, server.registryAccess());
			PLANS.clear();
		});
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			for (String id : List.copyOf(state(server).systems)) open(server, id);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			Galaxy.reset();
			PLANS.clear();
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sender.sendPacket(new GalaxyInfoPayload(galaxySeed)));
	}

	// ---------------------------------------------------------------------------------------

	/** Makes a system playable (idempotent). False if the id is unknown. */
	public static boolean open(MinecraftServer server, String id) {
		if (id.equals(Galaxy.HOME) || ProceduralPlanets.isOpen(id)) return true;
		Galaxy.Star star = Galaxy.star(id).orElse(null);
		if (star == null) return false;
		Plan plan = plan(server.registryAccess(), star);
		ProceduralPlanets.addSystem(id, plan.planets(), plan.stars());

		Fantasy fantasy = Fantasy.get(server);
		RuntimeLevelConfig space = new RuntimeLevelConfig()
				.setDimensionType(ResourceKey.create(Registries.DIMENSION_TYPE, StarwakeMod.id("space")))
				.setGenerator(new VoidChunkGenerator(server, SPACE_BIOME))
				.setSeed(star.seed())
				.setShouldTickTime(false)
				.setMirrorOverworldGameRules(true)
				.setMirrorOverworldDifficulty(true);
		fantasy.getOrOpenPersistentLevel(SolarSystem.spaceOf(id).identifier(), space);
		ProceduralPlanets.openLevels(server, plan.planets());
		Planets.rebuildIndex(server);
		State state = state(server);
		if (state.systems.add(id)) state.setDirty();
		ProceduralPlanets.broadcast(server);
		StarwakeMod.LOGGER.info("Opened system {} ({}, {} planets)", id, star.name(), plan.planets().size());
		plan.planets().forEach(ProceduralPlanets::log);
		return true;
	}

	/** Lays out a system (cached; deterministic). */
	public static synchronized Plan plan(RegistryAccess access, Galaxy.Star star) {
		Plan cached = PLANS.get(star.id());
		if (cached != null) return cached;
		RandomSource r = RandomSource.create(RandomSupport.mixStafford13(star.seed() ^ 0x504C414E4554L));
		String id = star.id();
		ResourceKey<net.minecraft.world.level.Level> spaceKey = SolarSystem.spaceOf(id);

		// The star, and its companion on a close orbit (planets circle both, outside it).
		List<PlanetRef> stars = new ArrayList<>();
		double starRadius = Math.clamp(star.radius() * SOLAR_RADIUS, 32, 900_000);
		stars.add(starRef(id + "/star", star.name(), spaceKey, Vec3.ZERO, starRadius, star.color(), id));
		double minOrbit = Math.max(starRadius * 8, 150_000);
		double luminosity = star.luminosity();
		if (star.companion().isPresent()) {
			Galaxy.Companion c = star.companion().get();
			double cr = Math.clamp(c.radius() * SOLAR_RADIUS, 32, 900_000);
			double separation = Math.max((starRadius + cr) * 6, 300_000 + r.nextDouble() * 900_000);
			double angle = r.nextDouble() * Math.PI * 2;
			stars.add(starRef(id + "/companion", star.name() + " B", spaceKey,
					new Vec3(Math.cos(angle) * separation, 0, Math.sin(angle) * separation), cr, c.color() | 0xFF000000, id));
			minOrbit = Math.max(minOrbit, separation * 3);
			luminosity += c.luminosity();
		}
		double scale = Math.sqrt(Math.max(luminosity, 1.0e-6));
		double maxOrbit = Math.min(MAX_ORBIT, Math.max(minOrbit * 4, 14_000_000 * scale));

		// Planets: log-spaced orbits, each with an archetype whose scaled band fits.
		var archetypes = access.lookupOrThrow(PlanetArchetype.REGISTRY_KEY).listElements()
				.sorted(java.util.Comparator.comparing(h -> h.key().identifier().toString())).toList();
		StarClass cls = Galaxy.starClass(star.starClass());
		boolean remnant = cls != null && cls.remnant();
		List<Vec3> taken = new ArrayList<>();
		stars.forEach(s -> taken.add(s.planet().body().orElseThrow().position()));
		List<ProceduralPlanets.Generated> planets = new ArrayList<>();
		double distance = minOrbit;
		for (int n = 1; n <= star.planets() && !archetypes.isEmpty(); n++) {
			distance *= 1.35 + r.nextDouble() * 1.1;
			if (distance > maxOrbit) break;
			Holder.Reference<PlanetArchetype> archetype = fit(r, archetypes, distance, scale, remnant);
			Identifier pid = StarwakeMod.id(id + "/p" + n);
			long seed = RandomSupport.mixStafford13(star.seed() + n * 0x9E3779B97F4A7C15L);
			planets.add(ProceduralPlanets.create(archetype, pid, id, seed, taken, distance, scale));
		}
		Plan plan = new Plan(List.copyOf(stars), List.copyOf(planets));
		PLANS.put(star.id(), plan);
		return plan;
	}

	/** Weighted pick among archetypes whose scaled orbit band contains the distance (nearest band otherwise). */
	private static Holder.Reference<PlanetArchetype> fit(RandomSource r, List<Holder.Reference<PlanetArchetype>> all, double distance,
			double scale, boolean remnant) {
		List<Holder.Reference<PlanetArchetype>> pool = new ArrayList<>();
		for (var a : all) {
			String path = a.key().identifier().getPath();
			if (remnant && !path.equals("barren") && !path.equals("frozen")) continue;
			double min = a.value().orbit().min() * 1.0e6 * scale, max = a.value().orbit().max() * 1.0e6 * scale;
			if (distance >= min && distance <= max) pool.add(a);
		}
		if (!pool.isEmpty()) return ProceduralPlanets.pickWeighted(r, pool);
		Holder.Reference<PlanetArchetype> best = all.getFirst();
		double bestGap = Double.MAX_VALUE;
		for (var a : all) {
			String path = a.key().identifier().getPath();
			if (remnant && !path.equals("barren") && !path.equals("frozen")) continue;
			double min = a.value().orbit().min() * 1.0e6 * scale, max = a.value().orbit().max() * 1.0e6 * scale;
			double gap = distance < min ? min / distance : distance / max;
			if (gap < bestGap) {
				bestGap = gap;
				best = a;
			}
		}
		return best;
	}

	private static PlanetRef starRef(String path, String name, ResourceKey<net.minecraft.world.level.Level> space, Vec3 position,
			double radius, int color, String system) {
		Body body = new Body(Optional.of(Component.literal(name)), position, radius, 0, 20000, color | 0xFF000000,
				color | 0xFF000000, color | 0xFF000000, true);
		Planet planet = new Planet(Component.literal(name), space, 0.15, false, Optional.of(body), 0, SPACE_HAZARDS, Optional.empty(), system);
		return new PlanetRef(ResourceKey.create(Planets.REGISTRY_KEY, StarwakeMod.id(path)), planet);
	}

	// ---------------------------------------------------------------------------------------
	// Map details (server -> client on request)
	// ---------------------------------------------------------------------------------------

	/** What the galaxy map shows for a system's planets. */
	public static @Nullable SystemInfoPayload info(RegistryAccess access, String id) {
		Galaxy.Star star = Galaxy.star(id).orElse(null);
		if (star == null) return null;
		List<SystemInfoPayload.PlanetInfo> list = new ArrayList<>();
		if (star.home()) {
			for (SolarSystem.Entry e : SolarSystem.bodies(access, Galaxy.HOME)) {
				if (e.body().star()) continue;
				list.add(SystemInfoPayload.PlanetInfo.of(e.planet()));
			}
		} else {
			for (var g : plan(access, star).planets()) list.add(SystemInfoPayload.PlanetInfo.of(g.ref().planet()));
		}
		list.sort(java.util.Comparator.comparingDouble(SystemInfoPayload.PlanetInfo::orbit));
		return new SystemInfoPayload(id, list);
	}

	// ---------------------------------------------------------------------------------------

	/** Which systems have been opened in this world. */
	public static final class State extends SavedData {
		public static final Codec<State> CODEC = Codec.STRING.listOf().xmap(list -> {
			State s = new State();
			s.systems.addAll(list);
			return s;
		}, s -> List.copyOf(s.systems));
		public static final SavedDataType<State> TYPE = new SavedDataType<>(StarwakeMod.id("galaxy"), State::new, CODEC,
				DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
		final Set<String> systems = new LinkedHashSet<>();
	}

	private static State state(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(State.TYPE);
	}
}
