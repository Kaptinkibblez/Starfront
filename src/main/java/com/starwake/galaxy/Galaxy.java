package com.starwake.galaxy;

import com.starwake.StarwakeMod;
import com.starwake.planet.proc.PlanetNames;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The galaxy (M6): an endless field of star systems generated on demand, sector by sector, from
 * the galaxy seed (derived from the world seed). Coordinates are light-years; Solace, the home
 * system, sits at the origin.
 *
 * <p>Each 10 ly sector holds about four stars, the real stellar density near the Sun (0.004
 * stars per cubic light-year), clumped by a large-scale density field so there are clusters and
 * voids. Star classes and their frequencies come from the {@link StarClass} registry, so most
 * stars are red dwarfs and blue giants are rare.
 *
 * <p>Both sides run this code: the server sends clients the galaxy seed
 * ({@code GalaxyInfoPayload}) and the star classes are a synced registry, so the galaxy map is
 * generated client-side with no network traffic. Planet details are server-side
 * ({@code StarSystems}).
 */
public final class Galaxy {
	/** Sector edge, light-years. */
	public static final double SECTOR = 10.0;
	/** Mean stars per sector (0.004 stars/ly^3 x 1000 ly^3). */
	public static final double STARS_PER_SECTOR = 4.0;
	/** The home system's id. */
	public static final String HOME = "solace";

	/** A companion star in a binary system. */
	public record Companion(ResourceKey<StarClass> starClass, double temperature, double luminosity, double radius, double mass, int color) {}

	/** One star system as seen from afar: position, star(s) and planet count. */
	public record Star(String id, int sx, int sy, int sz, int index, Vec3 position, String name, ResourceKey<StarClass> starClass,
			double temperature, double luminosity, double radius, double mass, int color, Optional<Companion> companion, int planets,
			long seed) {
		public boolean home() {
			return id.equals(HOME);
		}

		public double distanceTo(Star other) {
			return position.distanceTo(other.position);
		}
	}

	private static long seed;
	private static boolean configured;
	private static List<Holder.Reference<StarClass>> classes = List.of();
	private static double totalWeight;
	private static final Map<Long, List<Star>> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, List<Star>> eldest) {
			return size() > 4096;
		}
	};

	private Galaxy() {}

	/** The galaxy seed for a world seed (what clients receive; the world seed stays private). */
	public static long seedFor(long worldSeed) {
		return RandomSupport.mixStafford13(worldSeed ^ 0x5741524B47414C41L);
	}

	/** Set up from the galaxy seed and the star class registry (server start, or client join). */
	public static synchronized void configure(long galaxySeed, RegistryAccess access) {
		Registry<StarClass> registry = access.lookupOrThrow(StarClass.REGISTRY_KEY);
		List<Holder.Reference<StarClass>> list = new ArrayList<>(registry.listElements().toList());
		list.sort(Comparator.comparing(h -> h.key().identifier().toString())); // deterministic order
		classes = List.copyOf(list);
		totalWeight = classes.stream().mapToDouble(c -> c.value().weight()).sum();
		seed = galaxySeed;
		configured = !classes.isEmpty();
		CACHE.clear();
		StarwakeMod.LOGGER.info("Galaxy configured: {} star classes", classes.size());
	}

	public static boolean configured() {
		return configured;
	}

	public static synchronized void reset() {
		configured = false;
		CACHE.clear();
	}

	public static @Nullable StarClass starClass(ResourceKey<StarClass> key) {
		for (var c : classes) if (c.key().equals(key)) return c.value();
		return null;
	}

	// ---------------------------------------------------------------------------------------

	/** System id: {@code s<x>_<y>_<z>_<index>} (sector coordinates), or {@code solace}. */
	public static String idOf(int x, int y, int z, int index) {
		return "s" + x + "_" + y + "_" + z + "_" + index;
	}

	/** The stars of one sector (cached). */
	public static synchronized List<Star> sector(int x, int y, int z) {
		if (!configured) return List.of();
		long key = (((long) x & 0x1FFFFF) << 42) | (((long) y & 0x1FFFFF) << 21) | ((long) z & 0x1FFFFF);
		List<Star> cached = CACHE.get(key);
		if (cached != null) return cached;
		List<Star> stars = generateSector(x, y, z);
		CACHE.put(key, stars);
		return stars;
	}

	public static Optional<Star> star(String id) {
		if (id.equals(HOME)) return Optional.of(home());
		try {
			String[] p = id.substring(1).split("_");
			int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]), i = Integer.parseInt(p[3]);
			return sector(x, y, z).stream().filter(s -> s.index() == i).findFirst();
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}

	/** Every star within {@code radius} ly of {@code centre}. */
	public static List<Star> within(Vec3 centre, double radius) {
		List<Star> out = new ArrayList<>();
		int x0 = floor((centre.x - radius) / SECTOR), x1 = floor((centre.x + radius) / SECTOR);
		int y0 = floor((centre.y - radius) / SECTOR), y1 = floor((centre.y + radius) / SECTOR);
		int z0 = floor((centre.z - radius) / SECTOR), z1 = floor((centre.z + radius) / SECTOR);
		double r2 = radius * radius;
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					for (Star s : sector(x, y, z)) if (s.position().distanceToSqr(centre) <= r2) out.add(s);
				}
			}
		}
		return out;
	}

	private static int floor(double v) {
		return (int) Math.floor(v);
	}

	/** Solace: a G2 star like the Sun, at the origin. */
	public static Star home() {
		ResourceKey<StarClass> g = classes.stream().filter(c -> c.value().symbol().equals("G")).map(Holder.Reference::key).findFirst()
				.orElse(classes.isEmpty() ? null : classes.getFirst().key());
		return new Star(HOME, 0, 0, 0, 0, Vec3.ZERO, "Solace", g, 5772, 1.0, 1.0, 1.0, 0xFFFFF1C8, Optional.empty(), 6, seed);
	}

	// ---------------------------------------------------------------------------------------

	private static List<Star> generateSector(int x, int y, int z) {
		long sectorSeed = RandomSupport.mixStafford13(seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L));
		RandomSource r = RandomSource.create(sectorSeed);
		// Poisson-distributed star count around the local density.
		double mean = STARS_PER_SECTOR * density(x, y, z);
		int count = 0;
		double l = Math.exp(-mean), p = 1;
		do {
			count++;
			p *= r.nextDouble();
		} while (p > l && count < 40);
		count--;

		List<Star> stars = new ArrayList<>();
		boolean homeSector = x == 0 && y == 0 && z == 0;
		if (homeSector) stars.add(home());
		for (int i = 1; i <= count; i++) {
			Vec3 position = new Vec3((x + r.nextDouble()) * SECTOR, (y + r.nextDouble()) * SECTOR, (z + r.nextDouble()) * SECTOR);
			if (homeSector && position.length() < 2.5) continue; // keep Solace's neighbourhood clear
			long starSeed = RandomSupport.mixStafford13(sectorSeed + i * 0x9E3779B97F4A7C15L);
			stars.add(createStar(idOf(x, y, z, i), x, y, z, i, position, starSeed));
		}
		return List.copyOf(stars);
	}

	private static Star createStar(String id, int x, int y, int z, int index, Vec3 position, long starSeed) {
		RandomSource r = RandomSource.create(starSeed);
		Holder.Reference<StarClass> cls = pickClass(r);
		StarClass c = cls.value();
		// Luminosity is log-distributed within the class; temperature, radius and mass follow it.
		double f = r.nextDouble();
		double luminosity = logLerp(c.luminosity().min(), c.luminosity().max(), f);
		double temperature = lerp(c.temperature().min(), c.temperature().max(), f);
		double radius = logLerp(c.radius().min(), c.radius().max(), f);
		double mass = logLerp(c.mass().min(), c.mass().max(), f);
		Optional<Companion> companion = Optional.empty();
		if (r.nextDouble() < c.binary()) {
			Holder.Reference<StarClass> other = pickClass(r);
			double g = r.nextDouble();
			double l2 = logLerp(other.value().luminosity().min(), other.value().luminosity().max(), g);
			if (l2 <= luminosity) {
				companion = Optional.of(new Companion(other.key(), lerp(other.value().temperature().min(), other.value().temperature().max(), g),
						l2, logLerp(other.value().radius().min(), other.value().radius().max(), g),
						logLerp(other.value().mass().min(), other.value().mass().max(), g), other.value().color()));
			}
		}
		int planets = Math.max(0, c.planets().pickInt(r));
		String name = PlanetNames.generate(r);
		return new Star(id, x, y, z, index, position, name, cls.key(), temperature, luminosity, radius, mass, c.color() | 0xFF000000,
				companion, planets, starSeed);
	}

	private static Holder.Reference<StarClass> pickClass(RandomSource r) {
		double roll = r.nextDouble() * totalWeight;
		for (var c : classes) {
			roll -= c.value().weight();
			if (roll <= 0) return c;
		}
		return classes.getLast();
	}

	/** Large-scale density field (0.3..1.7): trilinear noise over 5-sector cells. */
	private static double density(int x, int y, int z) {
		double gx = x / 5.0, gy = y / 5.0, gz = z / 5.0;
		int ix = floor(gx), iy = floor(gy), iz = floor(gz);
		double fx = gx - ix, fy = gy - iy, fz = gz - iz;
		double v = 0;
		for (int dx = 0; dx <= 1; dx++) {
			for (int dy = 0; dy <= 1; dy++) {
				for (int dz = 0; dz <= 1; dz++) {
					double w = (dx == 0 ? 1 - fx : fx) * (dy == 0 ? 1 - fy : fy) * (dz == 0 ? 1 - fz : fz);
					v += w * lattice(ix + dx, iy + dy, iz + dz);
				}
			}
		}
		return 0.3 + 1.4 * v;
	}

	private static double lattice(int x, int y, int z) {
		long h = RandomSupport.mixStafford13(seed + x * 73856093L + y * 19349663L + z * 83492791L);
		return (h >>> 11) * 0x1.0p-53;
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	private static double logLerp(double a, double b, double t) {
		if (a <= 0 || b <= 0) return lerp(a, b, t);
		return Math.exp(Math.log(a) + (Math.log(b) - Math.log(a)) * t);
	}
}
