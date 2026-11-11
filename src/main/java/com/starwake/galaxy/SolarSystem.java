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
	/** A planet (or star) placed in space. */
	public record Entry(ResourceKey<Planet> key, Planet planet, Body body) {
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
	private static List<Entry> cached = List.of();

	private SolarSystem() {}

	/** Every body in space: registry and procedural planets (cached per planet list). */
	public static synchronized List<Entry> bodies(RegistryAccess access) {
		List<com.starwake.planet.PlanetRef> planets = Planets.all(access);
		if (planets != cachedFor) {
			List<Entry> list = new ArrayList<>();
			for (var ref : planets) {
				ref.planet().body().ifPresent(body -> list.add(new Entry(ref.key(), ref.planet(), body)));
			}
			cached = List.copyOf(list);
			cachedFor = planets;
		}
		return cached;
	}

	public static Optional<Entry> get(RegistryAccess access, ResourceKey<Planet> key) {
		return bodies(access).stream().filter(e -> e.key().equals(key)).findFirst();
	}

	public static Optional<Entry> forDimension(RegistryAccess access, ResourceKey<Level> dimension) {
		return bodies(access).stream().filter(e -> !e.body().star() && e.dimension().equals(dimension)).findFirst();
	}

	/** The star the planets are lit by, if the system has one. */
	public static Optional<Entry> star(RegistryAccess access) {
		return bodies(access).stream().filter(e -> e.body().star()).findFirst();
	}

	/** The enterable body whose atmosphere is closest to {@code pos}. */
	public static Optional<Entry> nearest(RegistryAccess access, Vec3 pos) {
		Entry best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Entry e : bodies(access)) {
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
		Vec3 centre = target.body().position();
		Vec3 back = from.subtract(centre).normalize();
		return centre.add(back.scale(target.body().entryRadius() + standoff));
	}

	/** The first body (other than {@code ignore}) whose sphere the segment passes through. */
	public static Optional<Entry> obstruction(RegistryAccess access, Vec3 from, Vec3 to, @Nullable ResourceKey<Planet> ignore) {
		Vec3 seg = to.subtract(from);
		double len2 = seg.lengthSqr();
		for (Entry e : bodies(access)) {
			if (e.key().equals(ignore)) continue;
			Vec3 c = e.body().position();
			double t = len2 == 0 ? 0 : Mth.clamp(c.subtract(from).dot(seg) / len2, 0, 1);
			double r = e.body().entryRadius() * (e.body().star() ? 1.5 : 1.0);
			if (from.add(seg.scale(t)).distanceToSqr(c) < r * r) return Optional.of(e);
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
