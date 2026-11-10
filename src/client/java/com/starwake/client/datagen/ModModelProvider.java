package com.starwake.client.datagen;

import com.starwake.registry.ModBlocks;
import com.starwake.registry.ModItems;
import net.fabricmc.fabric.api.client.datagen.v1.provider.FabricModelProvider;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.model.ModelTemplates;

/** Generates blockstates, block models and item models. */
public final class ModModelProvider extends FabricModelProvider {
	public ModModelProvider(FabricPackOutput output) {
		super(output);
	}

	@Override
	public void generateBlockStateModels(BlockModelGenerators generators) {
		// Plain full cube: textures/block/<name>.png on all six faces.
		generators.createTrivialCube(ModBlocks.HULL_PLATING);
		generators.createTrivialCube(ModBlocks.REGOLITH);
		generators.createTrivialCube(ModBlocks.RUST_SAND);
		generators.createTrivialCube(ModBlocks.FERRITE_ORE);
	}

	@Override
	public void generateItemModels(ItemModelGenerators generators) {
		// Flat 2D item sprite: textures/item/<name>.png.
		generators.generateFlatItem(ModItems.RAW_FERRITE, ModelTemplates.FLAT_ITEM);
	}
}
