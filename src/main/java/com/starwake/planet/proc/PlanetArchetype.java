package com.starwake.planet.proc;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;

/**
 * A hand-authored planet template (decision B, "curated randomness"), loaded from
 * {@code data/<ns>/starwake/planet_archetype/<id>.json}. The world seed picks an archetype for
 * each orbit and then picks every value inside the ranges designed here, so every planet of a
 * kind is different but none of them is noise soup.
 *
 * <p>Everything is grounded in real planetary science (user, 2026-11-11: keep it realistic for
 * the later metallurgy system): real minerals and rock types, hot worlds near the star, frozen
 * ones far out, thin or no air on small worlds.
 *
 * @param name          shown as the planet's type ("Frozen world")
 * @param weight        how often the archetype is picked (Crystal is rare)
 * @param orbit         distance from the star in millions of blocks (Mm)
 * @param radius        sphere radius in space (blocks)
 * @param environment   gravity, air and temperature ranges
 * @param dimensionTypes sky styles (dimension types with their own clock/timeline); one is picked
 * @param terrain       terrain styles (noise settings: density functions, aquifers); one is picked
 * @param stone         the rock the planet is made of; one is picked
 * @param fluid         the ocean/aquifer fluid (air for none); one is picked
 * @param seaLevel      ocean level
 * @param biomeCount    how many of the optional biomes a planet gets
 * @param biomes        the biome menu: each with a climate point (vanilla multi-noise) and its
 *                      surface material rule
 * @param colors        orbit colours: surface, secondary, atmosphere glow (one of each picked)
 */
public record PlanetArchetype(Component name, double weight, Range orbit, Range radius, Environment environment,
		List<ResourceKey<DimensionType>> dimensionTypes,
		List<ResourceKey<NoiseGeneratorSettings>> terrain, List<BlockState> stone, List<BlockState> fluid, Range seaLevel,
		Range biomeCount, List<BiomeOption> biomes, Colors colors) {

	public static final ResourceKey<Registry<PlanetArchetype>> REGISTRY_KEY =
			ResourceKey.createRegistryKey(StarwakeMod.id("planet_archetype"));

	/** One biome an archetype can use. {@code required} biomes appear on every planet of the kind. */
	public record BiomeOption(ResourceKey<Biome> biome, boolean required, Climate.ParameterPoint parameters, MaterialRule surface) {
		public static final Codec<BiomeOption> CODEC = RecordCodecBuilder.create(i -> i.group(
				ResourceKey.codec(Registries.BIOME).fieldOf("biome").forGetter(BiomeOption::biome),
				Codec.BOOL.optionalFieldOf("required", false).forGetter(BiomeOption::required),
				Climate.ParameterPoint.CODEC.fieldOf("parameters").forGetter(BiomeOption::parameters),
				MaterialRule.CODEC.fieldOf("surface").forGetter(BiomeOption::surface)
		).apply(i, BiomeOption::new));
	}

	/**
	 * Physical environment ranges.
	 *
	 * @param gravity     gravity multiplier
	 * @param airDensity  0 = no atmosphere (no wing lift, no atmosphere glow)
	 * @param breathable  chance the air is breathable (0..1)
	 * @param toxicity    atmospheric toxicity (0..1)
	 * @param temperature surface temperature, degrees Celsius
	 */
	public record Environment(Range gravity, Range airDensity, double breathable, Range toxicity, Range temperature) {
		public static final Codec<Environment> CODEC = RecordCodecBuilder.create(i -> i.group(
				Range.CODEC.fieldOf("gravity").forGetter(Environment::gravity),
				Range.CODEC.fieldOf("air_density").forGetter(Environment::airDensity),
				Codec.doubleRange(0, 1).optionalFieldOf("breathable", 0.0).forGetter(Environment::breathable),
				Range.CODEC.optionalFieldOf("toxicity", new Range(0, 0)).forGetter(Environment::toxicity),
				Range.CODEC.fieldOf("temperature").forGetter(Environment::temperature)
		).apply(i, Environment::new));
	}

	/** Orbit colour choices (the seed picks one of each). */
	public record Colors(List<Integer> surface, List<Integer> secondary, List<Integer> atmosphere) {
		public static final Codec<Colors> CODEC = RecordCodecBuilder.create(i -> i.group(
				ExtraCodecs.STRING_RGB_COLOR.listOf(1, 32).fieldOf("surface").forGetter(Colors::surface),
				ExtraCodecs.STRING_RGB_COLOR.listOf(1, 32).fieldOf("secondary").forGetter(Colors::secondary),
				ExtraCodecs.STRING_RGB_COLOR.listOf(1, 32).fieldOf("atmosphere").forGetter(Colors::atmosphere)
		).apply(i, Colors::new));
	}

	public static final Codec<PlanetArchetype> CODEC = RecordCodecBuilder.create(i -> i.group(
			ComponentSerialization.CODEC.fieldOf("name").forGetter(PlanetArchetype::name),
			Codec.doubleRange(0, 100).optionalFieldOf("weight", 1.0).forGetter(PlanetArchetype::weight),
			Range.CODEC.fieldOf("orbit").forGetter(PlanetArchetype::orbit),
			Range.CODEC.fieldOf("radius").forGetter(PlanetArchetype::radius),
			Environment.CODEC.fieldOf("environment").forGetter(PlanetArchetype::environment),
			ResourceKey.codec(Registries.DIMENSION_TYPE).listOf(1, 16).fieldOf("dimension_types").forGetter(PlanetArchetype::dimensionTypes),
			ResourceKey.codec(Registries.NOISE_SETTINGS).listOf(1, 16).fieldOf("terrain").forGetter(PlanetArchetype::terrain),
			BlockState.CODEC.listOf(1, 16).fieldOf("stone").forGetter(PlanetArchetype::stone),
			BlockState.CODEC.listOf(1, 16).fieldOf("fluid").forGetter(PlanetArchetype::fluid),
			Range.CODEC.fieldOf("sea_level").forGetter(PlanetArchetype::seaLevel),
			Range.CODEC.optionalFieldOf("biome_count", new Range(1, 3)).forGetter(PlanetArchetype::biomeCount),
			BiomeOption.CODEC.listOf(1, 32).fieldOf("biomes").forGetter(PlanetArchetype::biomes),
			Colors.CODEC.fieldOf("colors").forGetter(PlanetArchetype::colors)
	).apply(i, PlanetArchetype::new));
}
