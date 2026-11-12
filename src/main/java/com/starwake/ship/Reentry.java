package com.starwake.ship;

import com.starwake.galaxy.SolarSystem;
import com.starwake.planet.Planets;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Re-entry intensity (3j, shared by client effects and, since M8e-1, server-side hull heating): 0..1 from
 * speed (2.5 -> 5.5 blocks/tick since M9; only while descending in an atmosphere), scaled by how close the ship is to an atmosphere boundary (the top of a
 * planet's sky, or within 250 blocks of a planet's atmosphere sphere in space while diving toward it).
 */
public final class Reentry {
	private Reentry() {}

	/** Client: from the half-second average velocity (smooth for effects). */
	public static float intensity(ShipEntity ship) {
		return intensity(ship, ship.recentVelocity());
	}

	/** With an explicit velocity (blocks/tick); the server passes last tick's movement. */
	public static float intensity(ShipEntity ship, Vec3 v) {
		double speed = v.length();
		// M9: from 50 m/s to 110 m/s (the speed caps went up; climbing at full speed must not cook a hull).
		float fromSpeed = (float) Mth.clamp((speed - 2.5) / 3.0, 0, 1);
		if (fromSpeed <= 0) return 0;
		Level level = ship.level();
		if (SpaceTravel.isSpace(level)) {
			var nearest = SolarSystem.nearest(level, ship.position());
			if (nearest.isEmpty()) return 0;
			var body = nearest.get().body();
			double gap = ship.position().distanceTo(body.position()) - body.entryRadius();
			Vec3 inward = body.position().subtract(ship.position()).normalize();
			if (v.dot(inward) <= 0) return 0; // only when diving toward it
			return fromSpeed * (float) Mth.clamp(1 - gap / 250.0, 0, 1);
		}
		if (v.y >= 0) return 0; // climbing out doesn't heat the hull; coming down from space does
		double fromTop = (level.getMaxY() + SpaceTravel.EXIT_MARGIN) - ship.getY();
		return fromSpeed * (float) Mth.clamp(1 - fromTop / 120.0, 0, 1);
	}

	/** Density of the air being entered, relative to the Overworld (server side). */
	public static double airDensity(ShipEntity ship) {
		Level level = ship.level();
		if (SpaceTravel.isSpace(level)) {
			return SolarSystem.nearest(level, ship.position()).map(e -> e.planet().airDensity()).orElse(0.0);
		}
		return Planets.forDimension(level.dimension()).map(r -> r.planet().airDensity()).orElse(1.0);
	}
}
