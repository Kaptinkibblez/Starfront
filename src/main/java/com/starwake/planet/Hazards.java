package com.starwake.planet;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A planet's environment, as it affects an unprotected player (M5). Part of the planet JSON
 * ({@code "hazards": {...}}) and generated for procedural planets from their archetype.
 * {@code planet/PlanetHazards} turns these into damage; a Survey Suit or a sealed ship cabin
 * protects.
 *
 * @param breathable  whether the air can be breathed (false = vacuum or unbreathable gas)
 * @param toxicity    0..1: how corrosive or poisonous the atmosphere and its liquids are
 * @param temperature surface temperature in degrees Celsius
 */
public record Hazards(boolean breathable, float toxicity, float temperature) {
	/** Earth-like: breathable, clean, 15 degrees C. */
	public static final Hazards EARTHLIKE = new Hazards(true, 0, 15);

	/** Unprotected players start freezing below this (degrees C). */
	public static final float COLD_LIMIT = -25;
	/** Unprotected players start burning above this (degrees C). */
	public static final float HEAT_LIMIT = 55;

	public static final Codec<Hazards> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.BOOL.optionalFieldOf("breathable", true).forGetter(Hazards::breathable),
			Codec.floatRange(0, 1).optionalFieldOf("toxicity", 0f).forGetter(Hazards::toxicity),
			Codec.floatRange(-273, 2000).optionalFieldOf("temperature", 15f).forGetter(Hazards::temperature)
	).apply(i, Hazards::new));

	public boolean cold() {
		return temperature < COLD_LIMIT;
	}

	public boolean hot() {
		return temperature > HEAT_LIMIT;
	}

	public boolean toxic() {
		return toxicity > 0.05f;
	}

	/** Anything that hurts an unprotected player. */
	public boolean any() {
		return !breathable || toxic() || cold() || hot();
	}
}
