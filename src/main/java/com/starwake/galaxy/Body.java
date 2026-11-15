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
 * @param mass         a star's mass in solar masses (sets its planets' orbital periods); ignored for planets
 * @param moonOrbit    for a moon: its distance from the parent planet and starting angle (its {@code position} is
 *                     then only its neighbourhood, placed well away from the parent's)
 * @param wrap         M9: the surface is finite and wraps around ({@link #circumference()} blocks each way); the
 *                     Overworld stays endless
 * @param field        M9: an asteroid field in a belt (a quantum destination you fly around in, not a world to enter;
 *                     {@code radius} is the field's extent)
 *
 * <p>M9: {@code position} is the body's fixed <em>neighbourhood</em> in the space dimension (where you fly
 * around it, where stations stand) and its place at game time 0; the body itself orbits (see {@link SolarSystem}).
 */
public record Body(Optional<Component> name, Vec3 position, double radius, double atmosphere, double worldRadius,
		int color, int secondaryColor, int atmosphereColor, boolean star, double mass, Optional<MoonOrbit> moonOrbit, boolean wrap, boolean field) {
	/** A moon's orbit around its planet: distance (blocks) and the angle at time 0 (radians). */
	public record MoonOrbit(double distance, double phase) {
		public static final Codec<MoonOrbit> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.doubleRange(100, 1.0e7).fieldOf("distance").forGetter(MoonOrbit::distance),
				Codec.DOUBLE.optionalFieldOf("phase", 0.0).forGetter(MoonOrbit::phase)
		).apply(i, MoonOrbit::new));
	}

	public Body(Optional<Component> name, Vec3 position, double radius, double atmosphere, double worldRadius,
			int color, int secondaryColor, int atmosphereColor, boolean star) {
		this(name, position, radius, atmosphere, worldRadius, color, secondaryColor, atmosphereColor, star, 1.0, Optional.empty(), false, false);
	}

	public Body(Optional<Component> name, Vec3 position, double radius, double atmosphere, double worldRadius,
			int color, int secondaryColor, int atmosphereColor, boolean star, double mass) {
		this(name, position, radius, atmosphere, worldRadius, color, secondaryColor, atmosphereColor, star, mass, Optional.empty(), false, false);
	}

	public static final Codec<Body> CODEC = RecordCodecBuilder.create(i -> i.group(
			ComponentSerialization.CODEC.optionalFieldOf("name").forGetter(Body::name),
			Vec3.CODEC.fieldOf("position").forGetter(Body::position),
			Codec.doubleRange(16, 1.0e7).fieldOf("radius").forGetter(Body::radius),
			Codec.doubleRange(16, 1.0e7).optionalFieldOf("atmosphere", 0.0).forGetter(Body::atmosphere),
			Codec.doubleRange(1000, 1.0e7).optionalFieldOf("world_radius", 20000.0).forGetter(Body::worldRadius),
			ExtraCodecs.STRING_RGB_COLOR.optionalFieldOf("color", 0xFF808080).forGetter(Body::color),
			ExtraCodecs.STRING_RGB_COLOR.optionalFieldOf("secondary_color", 0xFF606060).forGetter(Body::secondaryColor),
			ExtraCodecs.STRING_RGB_COLOR.optionalFieldOf("atmosphere_color", 0xFF9FC8FF).forGetter(Body::atmosphereColor),
			Codec.BOOL.optionalFieldOf("star", false).forGetter(Body::star),
			Codec.doubleRange(0.01, 1000).optionalFieldOf("mass", 1.0).forGetter(Body::mass),
			MoonOrbit.CODEC.optionalFieldOf("moon_orbit").forGetter(Body::moonOrbit),
			Codec.BOOL.optionalFieldOf("wrap", false).forGetter(Body::wrap),
			Codec.BOOL.optionalFieldOf("field", false).forGetter(Body::field)
	).apply(i, Body::new));

	/** Surface size of a wrapping planet, each way (blocks, a multiple of 16): longitude x world radius around. */
	public int circumference() {
		return Math.max(256, (int) Math.round(2 * Math.PI * worldRadius / 16.0) * 16);
	}

	/**
	 * M9: surface size for a body of this radius: 8 km around for a small moon up to 32 km for an Earth-size world.
	 * Returns the world radius that gives it (circumference / 2 pi).
	 */
	public static double worldRadiusFor(double radius) {
		double around = Math.clamp(8000 + (radius - 500) * (24000.0 / 3500.0), 8000, 32000);
		return Math.round(around / 512.0) * 512 / (2 * Math.PI);
	}

	/** Radius at which the planet takes over: the atmosphere, or the surface if it has none. */
	public double entryRadius() {
		return Math.max(radius, atmosphere);
	}
}
