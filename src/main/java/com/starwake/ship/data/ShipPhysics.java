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
 * @param maxSpeed       hard cap in blocks/tick (protects chunk loading and the network)
 */
public record ShipPhysics(double thrustToAccel, double gravityToAccel, double dragAtmosphere,
		double dragSpace, double maxSpeed) {
	public static final ShipPhysics DEFAULT = new ShipPhysics(0.05, 0.05, 0.9, 0.985, 2.0);

	public static final Codec<ShipPhysics> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.DOUBLE.optionalFieldOf("thrust_to_accel", DEFAULT.thrustToAccel).forGetter(ShipPhysics::thrustToAccel),
			Codec.DOUBLE.optionalFieldOf("gravity_to_accel", DEFAULT.gravityToAccel).forGetter(ShipPhysics::gravityToAccel),
			Codec.doubleRange(0, 1).optionalFieldOf("drag_atmosphere", DEFAULT.dragAtmosphere).forGetter(ShipPhysics::dragAtmosphere),
			Codec.doubleRange(0, 1).optionalFieldOf("drag_space", DEFAULT.dragSpace).forGetter(ShipPhysics::dragSpace),
			Codec.doubleRange(0.1, 8).optionalFieldOf("max_speed", DEFAULT.maxSpeed).forGetter(ShipPhysics::maxSpeed)
	).apply(i, ShipPhysics::new));

	private static ShipPhysics current = DEFAULT;

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
