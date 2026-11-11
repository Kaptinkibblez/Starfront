package com.starwake.planet;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

/**
 * A planet and its id, whether it came from the {@code starwake:planet} datapack registry or was
 * generated from the world seed ({@code planet/proc/ProceduralPlanets}). Procedural planets have
 * keys in the same registry namespace but aren't registry entries.
 */
public record PlanetRef(ResourceKey<Planet> key, Planet planet) {
	public static final Codec<PlanetRef> CODEC = RecordCodecBuilder.create(i -> i.group(
			Identifier.CODEC.fieldOf("id").forGetter(r -> r.key().identifier()),
			Planet.CODEC.fieldOf("planet").forGetter(PlanetRef::planet)
	).apply(i, (id, planet) -> new PlanetRef(ResourceKey.create(Planets.REGISTRY_KEY, id), planet)));
}
