package com.starwake.planet.proc;

import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.material.MaterialRules;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;

/**
 * Turns a procedural planet's picks ({@link ProceduralPlanets.Spec}) into a vanilla
 * {@link NoiseBasedChunkGenerator}: the archetype's terrain style (an authored noise settings
 * entry) with this planet's rock, fluid, sea level and surface rules swapped in, and a
 * multi-noise biome source over the biomes the seed picked. Vanilla does the rest (caves,
 * aquifers, features, ores), so planets generate as fast as the Overworld.
 */
public final class PlanetGenerators {
	private static final ResourceKey<MaterialRule> BEDROCK_FLOOR =
			ResourceKey.create(Registries.MATERIAL_RULE, Identifier.withDefaultNamespace("bedrock_floor"));

	/** Continentalness below this is ocean (the vanilla coast line). */
	private static final float COAST = -0.19f, OCEAN_MIN = -1.2f;

	private PlanetGenerators() {}

	private static boolean isOcean(PlanetArchetype.BiomeOption option) {
		return option.parameters().continentalness().max() <= Climate.quantizeCoord(COAST);
	}

	/** A climate box: the given temperature and continentalness, everything else open. */
	private static Climate.ParameterPoint span(Climate.Parameter temperature, Climate.Parameter continentalness) {
		Climate.Parameter open = Climate.Parameter.span(-2, 2);
		return Climate.parameters(temperature, open, continentalness, open, open, open, 0);
	}

	public static ChunkGenerator create(RegistryAccess access, ProceduralPlanets.Spec spec) {
		HolderGetter<Biome> biomes = access.lookupOrThrow(Registries.BIOME);
		NoiseGeneratorSettings base = access.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(spec.terrain()).value();

		// Surface: bedrock floor, then each biome's own surface rule above the preliminary surface.
		List<MaterialRule> perBiome = new ArrayList<>();
		List<Pair<Climate.ParameterPoint, Holder<Biome>>> climate = new ArrayList<>();
		List<PlanetArchetype.BiomeOption> land = new ArrayList<>(), ocean = new ArrayList<>();
		for (PlanetArchetype.BiomeOption option : spec.biomes()) {
			(isOcean(option) ? ocean : land).add(option);
			perBiome.add(MaterialRules.ifTrue(MaterialRules.isBiome(biomes, option.biome()), option.surface()));
		}
		// Partition climate space among the biomes this planet got, so there are no gaps: oceans
		// share the ocean continentalness band (deep to shallow, in authored order), land biomes
		// share the temperature range (cold to hot by their authored temperature). Every other
		// axis is open, so a land sample can never be "closer" to an ocean and vice versa.
		land.sort(java.util.Comparator.comparingLong(o -> o.parameters().temperature().min()));
		boolean hasLand = !land.isEmpty();
		for (int i = 0; i < ocean.size(); i++) {
			float lo = OCEAN_MIN + (COAST - OCEAN_MIN) * i / ocean.size(), hi = OCEAN_MIN + (COAST - OCEAN_MIN) * (i + 1) / ocean.size();
			climate.add(Pair.of(span(Climate.Parameter.span(-2, 2), Climate.Parameter.span(lo, hasLand ? hi : 2)), biomes.getOrThrow(ocean.get(i).biome())));
		}
		for (int i = 0; i < land.size(); i++) {
			// Slabs across the band where the temperature noise lives (-1..1), open at the ends.
			float lo = i == 0 ? -2 : -1 + 2f * i / land.size();
			float hi = i == land.size() - 1 ? 2 : -1 + 2f * (i + 1) / land.size();
			climate.add(Pair.of(span(Climate.Parameter.span(lo, hi), Climate.Parameter.span(ocean.isEmpty() ? -2 : COAST, 2)),
					biomes.getOrThrow(land.get(i).biome())));
		}
		MaterialRule surface = MaterialRules.sequence(
				MaterialRules.getRule(access.lookupOrThrow(Registries.MATERIAL_RULE), BEDROCK_FLOOR),
				MaterialRules.ifTrue(MaterialRules.abovePreliminarySurface(), MaterialRules.sequence(perBiome)));

		boolean dry = spec.fluid().isAir();
		NoiseGeneratorSettings settings = new NoiseGeneratorSettings(
				base.noiseSettings(),
				spec.stone(),
				dry ? Blocks.AIR.defaultBlockState() : spec.fluid(),
				base.noiseRouter(),
				Holder.direct(surface),
				base.spawnTarget(),
				spec.seaLevel(),
				true, // no vanilla mob spawning at generation
				dry ? java.util.Optional.empty() : base.aquifers(),
				base.useLegacyRandomSource(),
				base.debugFunctions());
		return new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(climate)),
				Holder.direct(settings));
	}
}
