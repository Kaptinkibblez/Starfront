package com.starwake.ship;

import com.starwake.galaxy.SolarSystem;
import com.starwake.planet.Planets;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Aerodynamic heating (3j effects, M8e-1 hull heating; M10: real physics). With no speed caps any more the old
 * "fast and near the top of the sky" rule cooked hulls in level flight, so the skin now heats to the stagnation
 * temperature rise of the air it rams: dT = r v² / (2 cp), recovery factor r 0.9, cp of air 1005 J/(kg K), scaled by
 * the square root of the air's density (thin air can't deliver the heat). Level flight at 150 m/s warms a hull ~10 C;
 * 600 m/s ~160 C; diving in from space at 1,500 m/s ~1,000 C, past what a steel hull takes.
 * In space, a ship diving toward a planet heats over the last 250 blocks before its atmosphere.
 */
public final class Reentry {
	/** cp of air, J/(kg K), and the recovery factor of a blunt leading face. */
	private static final double CP = 1005, RECOVERY = 0.9;
	/** Temperature rise (C) that counts as full intensity for effects (plasma, haze, shake). */
	private static final double FULL_EFFECTS = 900, EFFECTS_FROM = 120;

	private Reentry() {}

	/** Client: from the half-second average velocity (smooth for effects). */
	public static float intensity(ShipEntity ship) {
		return intensity(ship, ship.recentVelocity());
	}

	/** 0..1 for effects, from the heating at this velocity (blocks/tick). */
	public static float intensity(ShipEntity ship, Vec3 v) {
		return (float) Mth.clamp((heating(ship, v) - EFFECTS_FROM) / (FULL_EFFECTS - EFFECTS_FROM), 0, 1);
	}

	/** Skin temperature rise (C) of the leading face at this velocity (blocks/tick). */
	public static double heating(ShipEntity ship, Vec3 v) {
		double speed = com.starwake.ship.physics.Units.perSecond(v.length());
		double rise = RECOVERY * speed * speed / (2 * CP) * Math.sqrt(airDensity(ship));
		if (rise <= 0) return 0;
		Level level = ship.level();
		if (SpaceTravel.isSpace(level)) {
			var nearest = SolarSystem.nearest(level, ship.position());
			if (nearest.isEmpty()) return 0;
			var body = nearest.get().body();
			double gap = ship.position().distanceTo(body.position()) - body.entryRadius();
			Vec3 inward = body.position().subtract(ship.position()).normalize();
			if (v.dot(inward) <= 0) return 0; // only when diving toward it
			return rise * Mth.clamp(1 - gap / 250.0, 0, 1);
		}
		return rise;
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
