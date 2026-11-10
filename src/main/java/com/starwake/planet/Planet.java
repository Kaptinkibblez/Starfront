package com.starwake.planet;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
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
 * @param gravity   gravity relative to the Overworld (1.0 = normal, 0.5 = half)
 */
public record Planet(Component name, ResourceKey<Level> dimension, double gravity) {
	public static final Codec<Planet> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			ComponentSerialization.CODEC.fieldOf("name").forGetter(Planet::name),
			Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Planet::dimension),
			Codec.doubleRange(0.05, 4.0).optionalFieldOf("gravity", 1.0).forGetter(Planet::gravity)
	).apply(instance, Planet::new));
}
