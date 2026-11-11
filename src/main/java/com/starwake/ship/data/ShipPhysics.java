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
 * @param thrustToAccel  blocks/tick² of acceleration per unit of thrust per tonne
 * @param gravityToAccel blocks/tick² of downward pull per 1.0g (equal to thrustToAccel, so
 *                       thrust == weight hovers exactly)
 * @param dragAtmosphere velocity multiplier per tick in an atmosphere
 * @param dragSpace      velocity multiplier per tick in space
 * @param maxSpeed       hard cap in blocks/tick in an atmosphere (protects chunk loading and the network)
 * @param maxSpeedSpace  hard cap in blocks/tick in space (sublight flight between quantum jumps)
 * @param turnRateDegrees base turn rate in degrees/second (scaled by the ship's agility)
 * @param boostMultiplier thrust (and fuel burn) multiplier while boosting
 * @param quantumSpeed   quantum cruise speed in blocks/tick (1 block = 1 m)
 * @param quantumRamp    ticks to reach (and to shed) quantum cruise speed
 * @param quantumFuelPerMm fuel units per million blocks (1,000 km) of quantum travel
 * @param quantumMinDistance shortest jump the drive can calibrate for (blocks)
 * @param quantumStandoff  how far outside the target's atmosphere a jump drops out (blocks)
 * @param quantumAlignDegrees how closely the nose must point at the target to engage
 */
public record ShipPhysics(double thrustToAccel, double gravityToAccel, double dragAtmosphere,
		double dragSpace, double maxSpeed, double maxSpeedSpace, double turnRateDegrees, double boostMultiplier,
		double quantumSpeed, double quantumRamp, double quantumFuelPerMm, double quantumMinDistance,
		double quantumStandoff, double quantumAlignDegrees) {
	public static final ShipPhysics DEFAULT = new ShipPhysics(0.05, 0.05, 0.9, 0.985, 2.0, 5.0, 60.0, 1.6,
			1500, 100, 50, 20_000, 600, 3.0);

	public static final Codec<ShipPhysics> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.DOUBLE.optionalFieldOf("thrust_to_accel", DEFAULT.thrustToAccel).forGetter(ShipPhysics::thrustToAccel),
			Codec.DOUBLE.optionalFieldOf("gravity_to_accel", DEFAULT.gravityToAccel).forGetter(ShipPhysics::gravityToAccel),
			Codec.doubleRange(0, 1).optionalFieldOf("drag_atmosphere", DEFAULT.dragAtmosphere).forGetter(ShipPhysics::dragAtmosphere),
			Codec.doubleRange(0, 1).optionalFieldOf("drag_space", DEFAULT.dragSpace).forGetter(ShipPhysics::dragSpace),
			Codec.doubleRange(0.1, 8).optionalFieldOf("max_speed", DEFAULT.maxSpeed).forGetter(ShipPhysics::maxSpeed),
			Codec.doubleRange(0.1, 16).optionalFieldOf("max_speed_space", DEFAULT.maxSpeedSpace).forGetter(ShipPhysics::maxSpeedSpace),
			Codec.doubleRange(1, 720).optionalFieldOf("turn_rate", DEFAULT.turnRateDegrees).forGetter(ShipPhysics::turnRateDegrees),
			Codec.doubleRange(1, 5).optionalFieldOf("boost_multiplier", DEFAULT.boostMultiplier).forGetter(ShipPhysics::boostMultiplier),
			Codec.doubleRange(10, 1.0e6).optionalFieldOf("quantum_speed", DEFAULT.quantumSpeed).forGetter(ShipPhysics::quantumSpeed),
			Codec.doubleRange(1, 2000).optionalFieldOf("quantum_ramp", DEFAULT.quantumRamp).forGetter(ShipPhysics::quantumRamp),
			Codec.doubleRange(0, 1.0e6).optionalFieldOf("quantum_fuel_per_mm", DEFAULT.quantumFuelPerMm).forGetter(ShipPhysics::quantumFuelPerMm),
			Codec.doubleRange(0, 1.0e7).optionalFieldOf("quantum_min_distance", DEFAULT.quantumMinDistance).forGetter(ShipPhysics::quantumMinDistance),
			Codec.doubleRange(0, 1.0e6).optionalFieldOf("quantum_standoff", DEFAULT.quantumStandoff).forGetter(ShipPhysics::quantumStandoff),
			Codec.doubleRange(0.1, 45).optionalFieldOf("quantum_align", DEFAULT.quantumAlignDegrees).forGetter(ShipPhysics::quantumAlignDegrees)
	).apply(i, ShipPhysics::new));

	private static ShipPhysics current = DEFAULT;

	/** Fuel units for a quantum jump of {@code distance} blocks. */
	public int quantumFuel(double distance) {
		return (int) Math.ceil(distance / 1.0e6 * quantumFuelPerMm);
	}

	public static ShipPhysics get() {
		return current;
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
