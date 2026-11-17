package com.starwake.ship.physics;

/**
 * M10: conversions between real units and the game's (1 block = 1 m, 20 ticks = 1 s). Ship tuning is in real units
 * (kN, t, m/s, m/s²); the simulation steps in blocks and ticks.
 */
public final class Units {
	/** Ticks per second. */
	public static final double TPS = 20;
	/** m/s² per block/tick² (a velocity change of 1 b/t in one tick). */
	public static final double ACCEL = TPS * TPS;
	/** Standard gravity, m/s². */
	public static final double G = 9.81;
	/** Sea-level air density on Earth, kg/m³ (a planet's {@code air_density} 1.0). */
	public static final double AIR = 1.225;

	private Units() {}

	/** m/s -> blocks/tick. */
	public static double perTick(double metresPerSecond) {
		return metresPerSecond / TPS;
	}

	/** blocks/tick -> m/s. */
	public static double perSecond(double blocksPerTick) {
		return blocksPerTick * TPS;
	}

	/** Acceleration from a force in kN on a mass in tonnes, in blocks/tick². */
	public static double accel(double kiloNewtons, double tonnes) {
		return kiloNewtons / tonnes / ACCEL;
	}

	/** Gravity in blocks/tick² for a planet gravity multiplier (1 = Earth). */
	public static double gravity(double g) {
		return g * G / ACCEL;
	}

	/** Force in kN that changes the velocity of {@code tonnes} by {@code blocksPerTick} in one tick. */
	public static double forceFor(double tonnes, double blocksPerTick) {
		return tonnes * blocksPerTick * ACCEL;
	}
}
