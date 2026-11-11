package com.starwake.galaxy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.phys.Vec3;

/**
 * Where a planet (or the system's star) physically sits in the {@code starwake:space}
 * dimension, and how it looks from orbit. Part of the planet JSON ({@code "body": {...}}).
 *
 * Space uses real block coordinates (1 block = 1 m), so planets are millions of blocks apart:
 * crossing that takes quantum travel, as in Star Citizen. Hand-placed for now; the Milestone 6
 * star map will generate systems from the galaxy seed instead.
 *
 * @param name         display name override (the star uses this; planets use the planet name)
 * @param position     centre in space coordinates
 * @param radius       visible surface radius (blocks)
 * @param atmosphere   radius at which a ship enters the planet's dimension (>= radius)
 * @param worldRadius  how the sphere maps to the flat planet dimension: planet X = longitude
 *                     x worldRadius, planet Z = latitude x worldRadius
 * @param color        main surface colour
 * @param secondaryColor second surface colour (continents, bands)
 * @param atmosphereColor glow around the limb
 * @param star         emissive light source (the planets are lit from it); can't be entered
 */
public record Body(Optional<Component> name, Vec3 position, double radius, double atmosphere, double worldRadius,
		int color, int secondaryColor, int atmosphereColor, boolean star) {
	public static final Codec<Body> CODEC = RecordCodecBuilder.create(i -> i.group(
			ComponentSerialization.CODEC.optionalFieldOf("name").forGetter(Body::name),
			Vec3.CODEC.fieldOf("position").forGetter(Body::position),
			Codec.doubleRange(16, 1.0e7).fieldOf("radius").forGetter(Body::radius),
			Codec.doubleRange(16, 1.0e7).optionalFieldOf("atmosphere", 0.0).forGetter(Body::atmosphere),
			Codec.doubleRange(1000, 1.0e7).optionalFieldOf("world_radius", 20000.0).forGetter(Body::worldRadius),
			ExtraCodecs.STRING_RGB_COLOR.optionalFieldOf("color", 0xFF808080).forGetter(Body::color),
			ExtraCodecs.STRING_RGB_COLOR.optionalFieldOf("secondary_color", 0xFF606060).forGetter(Body::secondaryColor),
			ExtraCodecs.STRING_RGB_COLOR.optionalFieldOf("atmosphere_color", 0xFF9FC8FF).forGetter(Body::atmosphereColor),
			Codec.BOOL.optionalFieldOf("star", false).forGetter(Body::star)
	).apply(i, Body::new));

	/** Radius at which the planet takes over: the atmosphere, or the surface if it has none. */
	public double entryRadius() {
		return Math.max(radius, atmosphere);
	}
}
