package com.starwake.ship.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import java.util.Map;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Global flight tuning, from {@code data/starwake/starwake/ship_physics/default.json}.
 *
 * <p>M10: real units. Thrust is in kN, mass in tonnes, so a thruster's push accelerates the ship by thrust / mass m/s²;
 * gravity is 9.81 m/s² x the planet's gravity; air drag is 0.5 rho Cd A v². Nothing caps speed: the flight computer
 * holds the pilot's speed limit in coupled flight (SCM up to {@code scmSpeed}, NAV up to {@code navSpeedSpace} in space
 * or {@code navSpeedAtmosphere} in an atmosphere, where terrain has to stream in ahead of the ship); decoupled flight
 * is plain Newtonian motion with drag. Conversions live in {@link com.starwake.ship.physics.Units}.
 *
 * @param scmSpeed           SCM (combat) speed limit ceiling, m/s
 * @param navSpeedAtmosphere NAV speed limit ceiling in an atmosphere, m/s
 * @param navSpeedSpace      NAV speed limit ceiling in space, m/s
 * @param coupledBraking     share of uncommanded velocity the flight computer cancels per tick
 * @param navAgility         turn-rate factor in NAV mode (cruise flight turns lazily)
 * @param turnRateDegrees    base turn rate in degrees/second
 * @param boostMultiplier    thrust (and fuel burn) multiplier while boosting; SCM boost also lifts the limit 25%
 * @param quantumSpeed       quantum cruise speed in blocks/tick (1 block = 1 m)
 * @param quantumRamp        ticks to reach (and to shed) quantum cruise speed
 * @param quantumFuelPerMm   fuel units per million blocks (1,000 km) of quantum travel
 * @param quantumMinDistance shortest jump the drive can calibrate for (blocks)
 * @param quantumStandoff    how far outside the target's atmosphere a jump drops out (blocks)
 * @param quantumAlignDegrees how closely the nose must point at the target to engage
 * @param dragCoefficient    Cd of a ship's projected silhouette (blocky hulls are draggy)
 */
public record ShipPhysics(double scmSpeed, double navSpeedAtmosphere, double navSpeedSpace, double coupledBraking,
		double navAgility, double turnRateDegrees, double boostMultiplier,
		double quantumSpeed, double quantumRamp, double quantumFuelPerMm, double quantumMinDistance,
		double quantumStandoff, double quantumAlignDegrees, double dragCoefficient) {
	public static final ShipPhysics DEFAULT = new ShipPhysics(220, 400, 1500, 0.12, 0.6, 60.0, 1.6,
			1500, 100, 50, 20_000, 600, 3.0, 1.0);

	public static final Codec<ShipPhysics> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.doubleRange(5, 5000).optionalFieldOf("scm_speed", DEFAULT.scmSpeed).forGetter(ShipPhysics::scmSpeed),
			Codec.doubleRange(5, 5000).optionalFieldOf("nav_speed_atmosphere", DEFAULT.navSpeedAtmosphere).forGetter(ShipPhysics::navSpeedAtmosphere),
			Codec.doubleRange(5, 100_000).optionalFieldOf("nav_speed_space", DEFAULT.navSpeedSpace).forGetter(ShipPhysics::navSpeedSpace),
			Codec.doubleRange(0, 1).optionalFieldOf("coupled_braking", DEFAULT.coupledBraking).forGetter(ShipPhysics::coupledBraking),
			Codec.doubleRange(0.05, 2).optionalFieldOf("nav_agility", DEFAULT.navAgility).forGetter(ShipPhysics::navAgility),
			Codec.doubleRange(1, 720).optionalFieldOf("turn_rate", DEFAULT.turnRateDegrees).forGetter(ShipPhysics::turnRateDegrees),
			Codec.doubleRange(1, 5).optionalFieldOf("boost_multiplier", DEFAULT.boostMultiplier).forGetter(ShipPhysics::boostMultiplier),
			Codec.doubleRange(10, 1.0e6).optionalFieldOf("quantum_speed", DEFAULT.quantumSpeed).forGetter(ShipPhysics::quantumSpeed),
			Codec.doubleRange(1, 2000).optionalFieldOf("quantum_ramp", DEFAULT.quantumRamp).forGetter(ShipPhysics::quantumRamp),
			Codec.doubleRange(0, 1.0e6).optionalFieldOf("quantum_fuel_per_mm", DEFAULT.quantumFuelPerMm).forGetter(ShipPhysics::quantumFuelPerMm),
			Codec.doubleRange(0, 1.0e7).optionalFieldOf("quantum_min_distance", DEFAULT.quantumMinDistance).forGetter(ShipPhysics::quantumMinDistance),
			Codec.doubleRange(0, 1.0e6).optionalFieldOf("quantum_standoff", DEFAULT.quantumStandoff).forGetter(ShipPhysics::quantumStandoff),
			Codec.doubleRange(0.1, 45).optionalFieldOf("quantum_align", DEFAULT.quantumAlignDegrees).forGetter(ShipPhysics::quantumAlignDegrees),
			Codec.doubleRange(0, 10).optionalFieldOf("drag_coefficient", DEFAULT.dragCoefficient).forGetter(ShipPhysics::dragCoefficient)
	).apply(i, ShipPhysics::new));

	/** The NAV ceiling where the ship is (m/s). */
	public double navSpeed(boolean space) {
		return space ? navSpeedSpace : navSpeedAtmosphere;
	}

	private static ShipPhysics current = DEFAULT;

	/** Fuel units for a quantum jump of {@code distance} blocks. */
	public int quantumFuel(double distance) {
		return (int) Math.ceil(distance / 1.0e6 * quantumFuelPerMm);
	}

	public static ShipPhysics get() {
		return current;
	}

	/** Client (M9): the server's tuning, from {@code ShipPhysicsPayload}. */
	public static void setFromServer(ShipPhysics physics) {
		current = physics;
	}

	public static void init() {
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(StarwakeMod.id("ship_physics"),
				new SimpleJsonResourceReloadListener<>(CODEC, FileToIdConverter.json("starwake/ship_physics")) {
					@Override
					protected void apply(Map<Identifier, ShipPhysics> loaded, ResourceManager manager, ProfilerFiller profiler) {
						current = loaded.getOrDefault(StarwakeMod.id("default"), DEFAULT);
					}
				});
	}
}
