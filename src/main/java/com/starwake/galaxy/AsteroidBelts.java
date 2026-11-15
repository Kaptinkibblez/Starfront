package com.starwake.galaxy;

import com.starwake.StarwakeMod;
import com.starwake.planet.Hazards;
import com.starwake.planet.Planet;
import com.starwake.planet.PlanetRef;
import com.starwake.planet.Planets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/**
 * Asteroid belts (M9): each system has a belt between its rocky and outer planets (2.5 Mm x sqrt(luminosity), pushed
 * clear of planet orbits) with three asteroid fields, each a quantum destination ({@link Body#field()}, radius 800 m).
 * Fields don't orbit (a belt is a statistical ring; they're its densest clumps), so their neighbourhoods are fixed.
 *
 * Asteroids are real blocks, placed into a space chunk the first time it generates (so they appear on approach, at no
 * cost until then): ~150 lumpy bodies per field (one every ~120 m), mostly small (3-25 blocks across the radius), of real types:
 * S (olivine/pyroxene chondrite with metal flecks, a regolith skin), C (dark serpentine clay with ice pockets), M
 * (kamacite iron-nickel with PGM-rich sperrylite traces) and icy (packed and blue ice over serpentine).
 */
public final class AsteroidBelts {
	public static final double FIELD_RADIUS = 800;
	private static final int ASTEROIDS = 150;
	private static final Hazards SPACE = new Hazards(false, 0, -120);

	private AsteroidBelts() {}

	public static void init() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_LOAD.register(AsteroidBelts::onChunkLoad);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_LEVEL_TICK.register(AsteroidBelts::drain);
	}

	/** One asteroid's part in one chunk, waiting to be placed. */
	private record Pending(ServerLevel level, Asteroid asteroid, int x0, int z0) {}

	/**
	 * Chunks are loading when the event fires (placing then would ask for the chunk being loaded and wait on itself),
	 * so placements wait for the end of the level's tick: a few chunks' worth per tick.
	 */
	private static final java.util.ArrayDeque<Pending> PENDING = new java.util.ArrayDeque<>();

	private static void drain(ServerLevel level) {
		int budget = 24;
		for (var it = PENDING.iterator(); it.hasNext() && budget > 0; ) {
			Pending p = it.next();
			if (p.level() != level) continue;
			it.remove();
			if (!level.hasChunk(p.x0() >> 4, p.z0() >> 4)) continue; // unloaded again before its turn (rare): that part is lost
			place(level, p.asteroid(), p.x0(), p.z0(), p.x0() + 15, p.z0() + 15);
			budget--;
		}
	}

	// ---------------------------------------------------------------------------------------
	// Placement of belts and fields
	// ---------------------------------------------------------------------------------------

	/**
	 * The fields of a system's belt. {@code orbits} = planet orbit radii (the belt keeps 250 km clear of them);
	 * {@code taken} = neighbourhood centres already used (fields stay 400 km clear, and are added).
	 */
	public static List<PlanetRef> fields(String system, String starName, ResourceKey<net.minecraft.world.level.Level> space,
			double scale, long seed, List<Double> orbits, List<Vec3> taken) {
		RandomSource r = RandomSource.create(seed ^ 0x6A09E667F3BCC909L);
		double belt = 2.5e6 * scale * (0.9 + r.nextDouble() * 0.2);
		for (int pass = 0; pass < 20; pass++) {
			final double b = belt;
			if (orbits.stream().noneMatch(o -> Math.abs(o - b) < 250_000)) break;
			belt += 300_000;
		}
		List<PlanetRef> out = new ArrayList<>();
		String[] letters = {"A", "B", "C"};
		for (int i = 0; i < letters.length; i++) {
			Vec3 at = Vec3.ZERO;
			for (int attempt = 0; attempt < 30; attempt++) {
				double angle = r.nextDouble() * Math.PI * 2;
				Vec3 p = new Vec3(Math.cos(angle) * belt, (r.nextDouble() - 0.5) * 200, Math.sin(angle) * belt);
				at = p;
				if (taken.stream().allMatch(t -> t.distanceTo(p) > 400_000)) break;
			}
			taken.add(at);
			Body body = new Body(Optional.empty(), at, FIELD_RADIUS, 0, 1000, 0xFF7A7068, 0xFF4A443E, 0xFF9A9088, false, 1.0,
					Optional.empty(), false, true);
			Component name = Component.translatable("planet.starwake.belt_field", starName, letters[i]);
			Planet planet = new Planet(name, space, 0.05, true, Optional.of(body), 0, SPACE,
					Optional.of(Component.translatable("planet.starwake.asteroid_field")), system);
			Identifier id = StarwakeMod.id((system.equals(Galaxy.HOME) ? "solace" : system) + "_belt_" + letters[i].toLowerCase());
			out.add(new PlanetRef(ResourceKey.create(Planets.REGISTRY_KEY, id), planet));
		}
		return out;
	}

	// ---------------------------------------------------------------------------------------
	// Asteroids
	// ---------------------------------------------------------------------------------------

	enum Type { S, C, M, ICY }

	/** One asteroid: centre, base radius, type, shape seed. */
	record Asteroid(Vec3 centre, double radius, Type type, long seed) {
		/** Lumpy surface: the radius in a direction, from three low-frequency waves. */
		double radiusToward(double dx, double dy, double dz, double length) {
			double nx = dx / length, ny = dy / length, nz = dz / length;
			double a = (seed & 0xFF) / 40.0, b = ((seed >> 8) & 0xFF) / 40.0, c = ((seed >> 16) & 0xFF) / 40.0;
			double lump = 0.18 * Math.sin(nx * 3.1 + a) * Math.cos(ny * 2.7 + b)
					+ 0.12 * Math.sin(ny * 5.3 + c) * Math.sin(nz * 4.1 + a)
					+ 0.08 * Math.cos(nz * 7.7 + b) * Math.sin(nx * 6.3 + c);
			double elongation = 1 + 0.35 * Math.abs(Math.sin(a)) * nx * nx; // many asteroids are elongated
			return radius * (1 + lump) * elongation;
		}
	}

	private static final Map<ResourceKey<Planet>, List<Asteroid>> CACHE = new ConcurrentHashMap<>();

	static List<Asteroid> asteroids(SolarSystem.Entry field) {
		return CACHE.computeIfAbsent(field.key(), key -> {
			RandomSource r = RandomSource.create(key.identifier().hashCode() * 0x9E3779B97F4A7C15L);
			List<Asteroid> list = new ArrayList<>();
			Vec3 c = field.body().position();
			for (int i = 0; i < ASTEROIDS; i++) {
				double u = r.nextDouble();
				double radius = 3 + 22 * u * u * u;
				Vec3 at;
				do {
					at = new Vec3(r.nextDouble() * 2 - 1, (r.nextDouble() * 2 - 1) * 0.25, r.nextDouble() * 2 - 1);
				} while (at.lengthSqr() > 1);
				double roll = r.nextDouble();
				Type type = roll < 0.45 ? Type.S : roll < 0.8 ? Type.C : roll < 0.92 ? Type.M : Type.ICY;
				list.add(new Asteroid(c.add(at.scale(FIELD_RADIUS - 40)), radius, type, r.nextLong()));
			}
			return List.copyOf(list);
		});
	}

	/** A freshly generated space chunk: place the parts of any asteroids inside it (next tick, with light and sync). */
	private static void onChunkLoad(ServerLevel level, LevelChunk chunk, boolean generated) {
		if (!generated || !SolarSystem.isSpace(level.dimension())) return;
		ChunkPos pos = chunk.getPos();
		int x0 = pos.getMinBlockX(), z0 = pos.getMinBlockZ(), x1 = x0 + 15, z1 = z0 + 15;
		List<Asteroid> here = new ArrayList<>();
		for (SolarSystem.Entry e : SolarSystem.bodies(level)) {
			if (!e.body().field()) continue;
			Vec3 c = e.body().position();
			if (c.x + FIELD_RADIUS + 40 < x0 || c.x - FIELD_RADIUS - 40 > x1 || c.z + FIELD_RADIUS + 40 < z0 || c.z - FIELD_RADIUS - 40 > z1) continue;
			for (Asteroid a : asteroids(e)) {
				double reach = a.radius() * 1.7;
				if (a.centre().x + reach < x0 || a.centre().x - reach > x1 || a.centre().z + reach < z0 || a.centre().z - reach > z1) continue;
				here.add(a);
			}
		}
		for (Asteroid a : here) PENDING.add(new Pending(level, a, x0, z0));
	}

	private static void place(ServerLevel level, Asteroid a, int x0, int z0, int x1, int z1) {
		double reach = a.radius() * 1.7;
		Vec3 c = a.centre();
		int minX = Math.max(x0, (int) Math.floor(c.x - reach)), maxX = Math.min(x1, (int) Math.ceil(c.x + reach));
		int minZ = Math.max(z0, (int) Math.floor(c.z - reach)), maxZ = Math.min(z1, (int) Math.ceil(c.z + reach));
		int minY = Math.max(level.getMinY() + 1, (int) Math.floor(c.y - reach)), maxY = Math.min(level.getMaxY() - 1, (int) Math.ceil(c.y + reach));
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int y = minY; y <= maxY; y++) {
					double dx = x + 0.5 - c.x, dy = y + 0.5 - c.y, dz = z + 0.5 - c.z;
					double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
					if (d < 1e-6) d = 1e-6;
					double surface = a.radiusToward(dx, dy, dz, d);
					if (d > surface) continue;
					long h = (x * 73856093L) ^ (y * 19349663L) ^ (z * 83492791L) ^ a.seed();
					double roll = ((h >>> 11) & 0xFFFF) / 65536.0;
					level.setBlock(p.set(x, y, z), material(a.type(), surface - d, roll), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
				}
			}
		}
	}

	/** What a cell is made of, by type, depth below the surface and a per-cell roll. */
	private static BlockState material(Type type, double depth, double roll) {
		boolean skin = depth < 1.2;
		return switch (type) {
			case S -> skin && roll < 0.7 ? block("fine_regolith") : roll < 0.08 ? block("kamacite_ore") : block("olivine_ore");
			case C -> roll < 0.12 && !skin ? Blocks.PACKED_ICE.defaultBlockState() : block("serpentine_ore");
			case M -> roll < 0.015 ? block("sperrylite_ore") : roll < 0.1 ? block("olivine_ore") : block("kamacite_ore");
			case ICY -> skin ? (roll < 0.5 ? block("serpentine_ore") : Blocks.PACKED_ICE.defaultBlockState())
					: roll < 0.25 ? Blocks.BLUE_ICE.defaultBlockState() : Blocks.PACKED_ICE.defaultBlockState();
		};
	}

	private static final Map<String, BlockState> BLOCKS = new ConcurrentHashMap<>();

	private static BlockState block(String id) {
		return BLOCKS.computeIfAbsent(id, k -> BuiltInRegistries.BLOCK.getOptional(StarwakeMod.id(k))
				.map(Block::defaultBlockState).orElse(Blocks.STONE.defaultBlockState()));
	}
}
