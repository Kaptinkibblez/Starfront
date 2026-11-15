package com.starwake.galaxy;

import com.starwake.planet.Planet;
import com.starwake.planet.Planets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The star system in {@code starwake:space}: every planet that has a {@link Body}, plus the
 * geometry shared by the server (atmosphere entry/exit, quantum routes) and the client
 * (rendering, HUD markers).
 *
 * <h2>Sphere <-> flat planet mapping</h2>
 * A planet's dimension is a flat world, so its sphere in space is unrolled around the
 * equator: longitude = planet X / worldRadius, latitude = planet Z / worldRadius. Leaving the
 * atmosphere at planet (X, Z) puts the ship just outside the sphere at that longitude/latitude,
 * and entering the sphere at some point lands the ship above the matching (X, Z). The local
 * frame (east, up, north) at that point maps the ship's rotation and velocity across, so a
 * ship climbing out of a planet emerges pointing away from it, and one diving into a planet
 * arrives nose-down. (Scaffold; a real spherical planet is a much later overhaul.)
 */
public final class SolarSystem {
	/**
	 * A planet (or star) placed in space. {@code orbit} is null for stars (they stay put) and bodies without a star.
	 */
	public record Entry(ResourceKey<Planet> key, Planet planet, Body body, @Nullable Orbit orbit) {
		public Entry(ResourceKey<Planet> key, Planet planet, Body body) {
			this(key, planet, body, null);
		}

		/** Where the body really is at game time {@code t} (the moving orbit; its neighbourhood stays at body.position). */
		public Vec3 at(double t) {
			return orbit == null ? body.position() : orbit.at(t);
		}

		/** How far the body has moved from its neighbourhood at time {@code t}. */
		public Vec3 drift(double t) {
			return orbit == null ? Vec3.ZERO : orbit.at(t).subtract(body.position());
		}

		public Component displayName() {
			return body.name().orElse(planet.name());
		}

		public ResourceKey<Level> dimension() {
			return planet.dimension();
		}

		/** Ships can enter this body by flying into it. */
		public boolean enterable() {
			return !body.star() && planet.destination();
		}
	}

	private static @Nullable List<com.starwake.planet.PlanetRef> cachedFor;
	private static java.util.Map<String, List<Entry>> cached = java.util.Map.of();

	private SolarSystem() {}

	// ---------------------------------------------------------------------------------------
	// Which system (M6)
	// ---------------------------------------------------------------------------------------

	/** The space dimension of a star system. */
	public static ResourceKey<Level> spaceOf(String system) {
		if (system.equals(Galaxy.HOME)) return com.starwake.ship.SpaceTravel.SPACE;
		return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
				com.starwake.StarwakeMod.id(system + "/space"));
	}

	/** True for any system's space dimension. */
	public static boolean isSpace(ResourceKey<Level> dimension) {
		if (dimension.equals(com.starwake.ship.SpaceTravel.SPACE)) return true;
		var id = dimension.identifier();
		return id.getNamespace().equals(com.starwake.StarwakeMod.MOD_ID) && id.getPath().endsWith("/space");
	}

	/** The system a dimension belongs to: its space, or the system of the planet on it. */
	public static String systemOf(RegistryAccess access, ResourceKey<Level> dimension) {
		if (dimension.equals(com.starwake.ship.SpaceTravel.SPACE)) return Galaxy.HOME;
		if (isSpace(dimension)) {
			String path = dimension.identifier().getPath();
			return path.substring(0, path.length() - "/space".length());
		}
		return Planets.forDimension(access, dimension).map(p -> p.planet().system()).orElse(Galaxy.HOME);
	}

	public static String systemOf(Level level) {
		return systemOf(level.registryAccess(), level.dimension());
	}

	// ---------------------------------------------------------------------------------------

	/** Every body of one system (cached per planet list). */
	public static synchronized List<Entry> bodies(RegistryAccess access, String system) {
		List<com.starwake.planet.PlanetRef> planets = Planets.all(access);
		if (planets != cachedFor) {
			java.util.Map<String, List<Entry>> bySystem = new java.util.HashMap<>();
			for (var ref : planets) {
				ref.planet().body().ifPresent(body -> bySystem.computeIfAbsent(ref.planet().system(), k -> new ArrayList<>())
						.add(new Entry(ref.key(), ref.planet(), body)));
			}
			bySystem.replaceAll((k, v) -> List.copyOf(withOrbits(v)));
			cached = java.util.Map.copyOf(bySystem);
			cachedFor = planets;
		}
		return cached.getOrDefault(system, List.of());
	}

	/** The bodies of the system this level is in. */
	public static List<Entry> bodies(Level level) {
		return bodies(level.registryAccess(), systemOf(level));
	}

	/** Any body in any loaded system, by key. */
	public static Optional<Entry> get(RegistryAccess access, ResourceKey<Planet> key) {
		for (var ref : Planets.all(access)) {
			if (ref.key().equals(key) && ref.planet().body().isPresent()) {
				for (Entry e : bodies(access, ref.planet().system())) if (e.key().equals(key)) return Optional.of(e);
			}
		}
		return Optional.empty();
	}

	// ---------------------------------------------------------------------------------------
	// Orbits (M9): real Kepler period ratios, sped up; positions from game time on both sides
	// ---------------------------------------------------------------------------------------
	//
	// Every planet circles its system's centre (the star; the stars' mass-weighted centre for a binary) in
	// the plane of the space slab, starting where its JSON position puts it at game time 0. Period =
	// PERIOD_AT_1MM x (a / 1 Mm)^1.5 / sqrt(M): the Overworld (1.8 Mm from Solace) takes ~7 in-game days,
	// Veyra (5.6 Mm) ~40.
	//
	// Local frames: each body keeps a fixed neighbourhood in the space dimension around its JSON position
	// (where you fly, where stations and builds stand); every point belongs to the nearest one. Space there is
	// the body's own frame, carried along its orbit (quantum travel crosses between them). So the directions
	// and distances you see and fly by (other bodies, phases, quantum routes) follow the moving orbits, while
	// nothing built in space is ever left behind.
	//
	// Moons circle their planet (period MOON_PERIOD_AT_100KM x (d / 100 km)^1.5 / sqrt(planet mass)); their
	// neighbourhoods sit ~1,000 km from the planet's. A ship flying into a moon's capture radius (where the
	// moon really is) moves into the moon's neighbourhood at the matching point, and back out again
	// (SpaceTravel.tickMoonFrames).

	/** Orbital period at 1 Mm around one solar mass, in ticks (3 in-game days). */
	public static final double PERIOD_AT_1MM = 72_000;
	/** A moon's period at 100 km around an Overworld-mass planet, in ticks (4 in-game days). */
	public static final double MOON_PERIOD_AT_100KM = 96_000;
	/** Extra room around a moon in which space is the moon's frame (a ship flying into it transfers there). */
	public static final double MOON_CAPTURE = 20_000;

	/** A circular orbit in the slab's plane, around a fixed centre or around another orbit (moons). */
	public record Orbit(Vec3 centre, double radius, double phase, double height, double period, @Nullable Orbit around) {
		public Vec3 at(double t) {
			double a = phase + 2 * Math.PI * (t / period);
			Vec3 c = around == null ? centre : around.at(t);
			return new Vec3(c.x + radius * Math.cos(a), around == null ? height : c.y, c.z + radius * Math.sin(a));
		}
	}

	private static List<Entry> withOrbits(List<Entry> entries) {
		double mass = 0, cx = 0, cz = 0;
		for (Entry e : entries) {
			if (!e.body().star()) continue;
			mass += e.body().mass();
			cx += e.body().position().x * e.body().mass();
			cz += e.body().position().z * e.body().mass();
		}
		if (mass <= 0) return entries;
		Vec3 centre = new Vec3(cx / mass, 0, cz / mass);
		java.util.Map<ResourceKey<Planet>, Entry> planets = new java.util.HashMap<>();
		List<Entry> out = new ArrayList<>();
		for (Entry e : entries) {
			if (e.body().star() || e.body().moonOrbit().isPresent()) continue;
			Vec3 p = e.body().position();
			double radius = Math.hypot(p.x - centre.x, p.z - centre.z);
			double period = PERIOD_AT_1MM * Math.pow(radius / 1.0e6, 1.5) / Math.sqrt(mass);
			planets.put(e.key(), new Entry(e.key(), e.planet(), e.body(),
					new Orbit(centre, radius, Math.atan2(p.z - centre.z, p.x - centre.x), p.y, Math.max(period, 2400), null)));
		}
		for (Entry e : entries) {
			if (e.body().star()) {
				out.add(e);
			} else if (e.body().moonOrbit().isPresent()) {
				Entry parent = e.planet().parent().map(planets::get).orElse(null);
				if (parent == null || parent.orbit() == null) {
					out.add(e);
					continue;
				}
				var m = e.body().moonOrbit().get();
				// Planet mass relative to the Overworld's, from surface gravity and radius (M ~ g r^2).
				double rel = Math.max(0.01, parent.planet().gravity() * Math.pow(parent.body().radius() / 4000.0, 2));
				double period = MOON_PERIOD_AT_100KM * Math.pow(m.distance() / 1.0e5, 1.5) / Math.sqrt(rel);
				out.add(new Entry(e.key(), e.planet(), e.body(),
						new Orbit(Vec3.ZERO, m.distance(), m.phase(), 0, Math.max(period, 2400), parent.orbit())));
			} else {
				out.add(planets.get(e.key()));
			}
		}
		return out;
	}

	/**
	 * Orbit time: the Overworld clock's total ticks, the same on every level and on both sides. Sleeping or
	 * {@code /time add} moves the planets along with the day; with the daylight cycle off they stand still.
	 */
	public static double time(Level level) {
		return level.getOverworldClockTime();
	}

	/**
	 * The body whose frame a point of the space dimension is in: the one whose neighbourhood centre (its JSON
	 * position) is nearest. Neighbourhoods are hundreds of km apart, so nobody flies across a boundary except
	 * through quantum travel or a moon transfer.
	 */
	public static @Nullable Entry frameOf(List<Entry> bodies, Vec3 physical) {
		Entry best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Entry e : bodies) {
			double d = physical.distanceToSqr(e.body().position());
			if (d < bestDistance) {
				bestDistance = d;
				best = e;
			}
		}
		return best;
	}

	/**
	 * A point of the space dimension in system coordinates (where it really is at time {@code t}): carried along
	 * the orbit of the body whose neighbourhood it's in.
	 */
	public static Vec3 toSystem(List<Entry> bodies, Vec3 physical, double t) {
		Entry frame = frameOf(bodies, physical);
		return frame == null ? physical : physical.add(frame.drift(t));
	}

	/** Radius around a moon (where it really is) inside which space is the moon's own frame. */
	public static double captureRadius(Entry moon) {
		return moon.body().entryRadius() + MOON_CAPTURE;
	}

	public static Vec3 toSystem(Level level, Vec3 physical) {
		return toSystem(bodies(level), physical, time(level));
	}

	/** The point of the space dimension inside {@code body}'s neighbourhood that is at system point {@code system}. */
	public static Vec3 toPhysical(Entry body, Vec3 system, double t) {
		return system.subtract(body.drift(t));
	}

	public static Optional<Entry> forDimension(RegistryAccess access, ResourceKey<Level> dimension) {
		return bodies(access, systemOf(access, dimension)).stream().filter(e -> !e.body().star() && e.dimension().equals(dimension)).findFirst();
	}

	/** The star the planets are lit by, if the system has one. */
	public static Optional<Entry> star(Level level) {
		return bodies(level).stream().filter(e -> e.body().star()).findFirst();
	}

	/** The enterable body whose atmosphere is closest to {@code pos}, in this level's system. */
	public static Optional<Entry> nearest(Level level, Vec3 pos) {
		Entry best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Entry e : bodies(level)) {
			if (!e.enterable()) continue;
			double d = pos.distanceTo(e.body().position()) - e.body().entryRadius();
			if (d < bestDistance) {
				bestDistance = d;
				best = e;
			}
		}
		return Optional.ofNullable(best);
	}

	// ---------------------------------------------------------------------------------------
	// Sphere <-> flat world
	// ---------------------------------------------------------------------------------------

	/** Rotation taking a planet's (east, up, north) frame to space at a longitude/latitude. */
	public static Quaternionf planetToSpace(double lon, double lat) {
		float sl = (float) Math.sin(lon), cl = (float) Math.cos(lon);
		float sp = (float) Math.sin(lat), cp = (float) Math.cos(lat);
		Vector3f east = new Vector3f(-sl, 0, cl);
		Vector3f up = new Vector3f(cp * cl, sp, cp * sl);
		Vector3f north = new Vector3f(-sp * cl, cp, -sp * sl);
		return new Quaternionf().setFromNormalized(new Matrix3f(east, up, north)).normalize();
	}

	/** Unit vector from a body's centre at a longitude/latitude. */
	public static Vec3 radial(double lon, double lat) {
		return new Vec3(Math.cos(lat) * Math.cos(lon), Math.sin(lat), Math.cos(lat) * Math.sin(lon));
	}

	/**
	 * Latitude for planet Z, clamped so the exit point stays within {@code maxYOffset} blocks of
	 * the body's centre height (space is a slab, not a cube).
	 */
	public static double latitude(Body body, double planetZ, double distance, double maxYOffset) {
		double max = Math.asin(Mth.clamp(maxYOffset / distance, 0, 1));
		return Mth.clamp(planetZ / body.worldRadius(), -max, max);
	}

	/** [longitude, latitude] of a point in space as seen from a body's centre. */
	public static double[] lonLat(Body body, Vec3 pos) {
		Vec3 d = pos.subtract(body.position()).normalize();
		return new double[] {Math.atan2(d.z, d.x), Math.asin(Mth.clamp(d.y, -1, 1))};
	}

	// ---------------------------------------------------------------------------------------
	// Quantum routes
	// ---------------------------------------------------------------------------------------

	/**
	 * Where a quantum jump toward {@code target} from {@code from} drops out: on the straight
	 * line to its centre, {@code standoff} blocks outside its atmosphere.
	 */
	public static Vec3 arrivalPoint(Entry target, Vec3 from, double standoff) {
		return arrivalPoint(target.body().position(), target, from, standoff);
	}

	/** {@link #arrivalPoint} around an explicit centre (system coordinates: the target's moving position). */
	public static Vec3 arrivalPoint(Vec3 centre, Entry target, Vec3 from, double standoff) {
		Vec3 back = from.subtract(centre).normalize();
		return centre.add(back.scale(target.body().entryRadius() + standoff));
	}

	/** The first body (other than {@code ignore}) whose sphere the segment passes through. */
	public static Optional<Entry> obstruction(Level level, Vec3 from, Vec3 to, @Nullable ResourceKey<Planet> ignore) {
		return obstruction(level, from, to, ignore, time(level));
	}

	/** {@link #obstruction} in system coordinates at time {@code t} (bodies where they really are). */
	public static Optional<Entry> obstruction(Level level, Vec3 from, Vec3 to, @Nullable ResourceKey<Planet> ignore, double t) {
		Vec3 seg = to.subtract(from);
		double len2 = seg.lengthSqr();
		for (Entry e : bodies(level)) {
			if (e.key().equals(ignore)) continue;
			Vec3 c = e.at(t);
			double u = len2 == 0 ? 0 : Mth.clamp(c.subtract(from).dot(seg) / len2, 0, 1);
			double r = e.body().entryRadius() * (e.body().star() ? 1.5 : 1.0);
			if (from.add(seg.scale(u)).distanceToSqr(c) < r * r) return Optional.of(e);
		}
		return Optional.empty();
	}

	/**
	 * Quantum travel profile: accelerate over {@code ramp} ticks to {@code speed}, cruise, then
	 * decelerate over {@code ramp} ticks. Returns distance covered after {@code t} ticks.
	 */
	public static double travelled(double distance, double speed, double ramp, double t) {
		double total = travelTicks(distance, speed, ramp);
		double v = Math.min(speed, distance / ramp); // short hops never reach cruise speed
		double r = Math.min(ramp, total / 2);
		if (t <= 0) return 0;
		if (t >= total) return distance;
		if (t < r) return v * t * t / (2 * r);
		if (t > total - r) return distance - v * (total - t) * (total - t) / (2 * r);
		return v * (t - r / 2);
	}

	/** Duration of a quantum jump in ticks (see {@link #travelled}). */
	public static double travelTicks(double distance, double speed, double ramp) {
		double v = Math.min(speed, distance / ramp);
		return distance / v + ramp;
	}

	/** "3,842 km" / "812 m" style distance for HUDs and consoles (1 block = 1 m). */
	public static String formatDistance(double blocks) {
		if (blocks >= 1.0e9) return String.format("%.2f Gm", blocks / 1.0e9);
		if (blocks >= 10_000) return String.format("%,d km", Math.round(blocks / 1000));
		if (blocks >= 1000) return String.format("%.1f km", blocks / 1000);
		return String.format("%d m", Math.round(blocks));
	}

	/** "2:05" style duration from ticks. */
	public static String formatTime(double ticks) {
		long s = Math.max(0, Math.round(ticks / 20));
		return String.format("%d:%02d", s / 60, s % 60);
	}
}
