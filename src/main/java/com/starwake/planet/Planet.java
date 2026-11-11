package com.starwake.planet;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.galaxy.Body;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * A planet definition, loaded from datapacks at {@code data/<namespace>/starwake/planet/<id>.json}.
 *
 * The planet's look (sky, fog, day length) lives in its dimension type and timeline JSON, and its
 * terrain lives in its noise settings and biome JSON. This record only holds gameplay facts that
 * vanilla has no place for.
 *
 * @param name      display name shown in commands and, later, on the star map
 * @param dimension the dimension the planet's surface lives in
 * @param gravity     gravity relative to the Overworld (1.0 = normal, 0.5 = half)
 * @param destination whether ships can set a course here (false for space itself)
 * @param body        where the planet sits in space and how it looks from orbit (3g). Space's
 *                    own entry uses this for the system's star.
 * @param airDensity  atmosphere density relative to the Overworld: scales wing lift and drag (3i)
 * @param hazards     air, toxicity and temperature for unprotected players (M5)
 * @param archetype   for procedural planets: the archetype's display name ("Frozen world")
 * @param system      the star system it belongs to (M6): {@code solace} or a galaxy system id
 */
public record Planet(Component name, ResourceKey<Level> dimension, double gravity, boolean destination, Optional<Body> body,
		double airDensity, Hazards hazards, Optional<Component> archetype, String system) {
	public static final Codec<Planet> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			ComponentSerialization.CODEC.fieldOf("name").forGetter(Planet::name),
			Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Planet::dimension),
			Codec.doubleRange(0.05, 4.0).optionalFieldOf("gravity", 1.0).forGetter(Planet::gravity),
			Codec.BOOL.optionalFieldOf("destination", true).forGetter(Planet::destination),
			Body.CODEC.optionalFieldOf("body").forGetter(Planet::body),
			Codec.doubleRange(0.0, 10.0).optionalFieldOf("air_density", 1.0).forGetter(Planet::airDensity),
			Hazards.CODEC.optionalFieldOf("hazards", Hazards.EARTHLIKE).forGetter(Planet::hazards),
			ComponentSerialization.CODEC.optionalFieldOf("archetype").forGetter(Planet::archetype),
			Codec.STRING.optionalFieldOf("system", "solace").forGetter(Planet::system)
	).apply(instance, Planet::new));
}
