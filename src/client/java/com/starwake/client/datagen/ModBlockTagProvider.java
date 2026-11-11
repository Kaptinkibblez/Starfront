package com.starwake.client.datagen;

import com.starwake.registry.ModBlocks;
import com.starwake.registry.ModPlanetBlocks;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagsProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;

/**
 * Generates block tags. Mining rules are tag-driven: a block that
 * {@code requiresCorrectToolForDrops()} must be in a {@code mineable/*} tag, or it never drops.
 */
public final class ModBlockTagProvider extends FabricTagsProvider.BlockTagsProvider {
	public ModBlockTagProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
		super(output, registries);
	}

	@Override
	protected void addTags(HolderLookup.Provider registries) {
		builder(BlockTags.MINEABLE_WITH_PICKAXE).add(
				key(ModBlocks.HULL_PLATING), key(ModBlocks.REGOLITH), key(ModBlocks.FERRITE_ORE));
		builder(BlockTags.NEEDS_STONE_TOOL).add(key(ModBlocks.HULL_PLATING), key(ModBlocks.FERRITE_ORE));
		builder(BlockTags.MINEABLE_WITH_PICKAXE).add(
				key(ModBlocks.HELM), key(ModBlocks.THRUSTER), key(ModBlocks.MAIN_ENGINE), key(ModBlocks.MANEUVERING_THRUSTER), key(ModBlocks.WING), key(ModBlocks.LANDING_GEAR), key(ModBlocks.FUEL_TANK), key(ModBlocks.SEAT),
				key(ModBlocks.POWER_PLANT), key(ModBlocks.COOLER), key(ModBlocks.SHIELD_GENERATOR), key(ModBlocks.QUANTUM_DRIVE));

		// #starwake:ship_excluded (terrain that never counts as ship) is hand-written JSON in
		// src/main/resources so it can reference vanilla tags and be tweaked without datagen.
		builder(BlockTags.MINEABLE_WITH_SHOVEL).add(key(ModBlocks.RUST_SAND));

		// Planet content (M5)
		builder(BlockTags.MINEABLE_WITH_PICKAXE).add(key(ModPlanetBlocks.ANORTHOSITE), key(ModPlanetBlocks.HALITE),
				key(ModPlanetBlocks.SELENITE), key(ModPlanetBlocks.SELENITE_CLUSTER));
		builder(BlockTags.MINEABLE_WITH_SHOVEL).add(key(ModPlanetBlocks.FINE_REGOLITH), key(ModPlanetBlocks.VOLCANIC_ASH),
				key(ModPlanetBlocks.PERMAFROST), key(ModPlanetBlocks.MAT_TURF));
		builder(BlockTags.MINEABLE_WITH_AXE).add(key(ModPlanetBlocks.FERNWOOD_LOG), key(ModPlanetBlocks.FUNGAL_STEM),
				key(ModPlanetBlocks.FUNGAL_CAP));
		builder(BlockTags.MINEABLE_WITH_HOE).add(key(ModPlanetBlocks.FERNWOOD_FRONDS), key(ModPlanetBlocks.FUNGAL_CAP));
		builder(BlockTags.LEAVES).add(key(ModPlanetBlocks.FERNWOOD_FRONDS));
		builder(BlockTags.LOGS).add(key(ModPlanetBlocks.FERNWOOD_LOG), key(ModPlanetBlocks.FUNGAL_STEM));
		var ores = builder(BlockTags.MINEABLE_WITH_PICKAXE);
		var ironTier = builder(BlockTags.NEEDS_IRON_TOOL);
		var stoneTier = builder(BlockTags.NEEDS_STONE_TOOL);
		var oreTag = builder(BlockTags.ORES);
		for (var ore : ModPlanetBlocks.ores()) {
			ores.add(key(ore.block()));
			oreTag.add(key(ore.block()));
			// The tool tier comes from the mineral data (hard, refractory ores need iron).
			switch (ore.mineral().tool()) {
				case "iron" -> ironTier.add(key(ore.block()));
				case "stone" -> stoneTier.add(key(ore.block()));
				default -> { }
			}
		}

		// 26.3 gotcha: "solid" behaviour (heightmaps, ore placement, suffocation, fluid flow,
		// teleport targets) comes from this tag, not from the block's shape. Without it, worldgen
		// treats our terrain as hollow and ores never generate. Every Starwake block is currently
		// a full solid cube; non-solid blocks added later must opt out here.
		var motion = builder(BlockTags.BLOCKS_MOTION_NO_LEAVES);
		// Wings and landing gear aren't solid cubes, so they stay out.
		ModBlocks.all().stream().filter(block -> block != ModBlocks.WING && block != ModBlocks.LANDING_GEAR
				&& !ModPlanetBlocks.nonSolid().contains(block)).forEach(block -> motion.add(key(block)));
		builder(BlockTags.SAND).add(key(ModPlanetBlocks.FINE_REGOLITH), key(ModPlanetBlocks.VOLCANIC_ASH));

		// Vanilla category tags, so vanilla mechanics treat our blocks like their counterparts.
		builder(BlockTags.SAND).add(key(ModBlocks.RUST_SAND));     // e.g. cactus/dead bush placement
		builder(BlockTags.ORES).add(key(ModBlocks.FERRITE_ORE));
	}

	/** 26.3 tag appenders take registry keys rather than block instances. */
	private static ResourceKey<Block> key(Block block) {
		return block.builtInRegistryHolder().key();
	}
}
