package com.starwake.client.datagen;

import com.starwake.registry.ModBlocks;
import com.starwake.registry.ModItems;
import net.fabricmc.fabric.api.client.datagen.v1.provider.FabricModelProvider;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.MultiVariant;
import net.minecraft.client.data.models.blockstates.MultiVariantGenerator;
import net.minecraft.client.data.models.blockstates.PropertyDispatch;
import net.minecraft.client.data.models.model.ModelTemplates;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.data.models.model.TextureSlot;
import net.minecraft.client.data.models.model.TexturedModel;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

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

		// Ship parts
		generators.createHorizontallyRotatedBlock(ModBlocks.HELM, TexturedModel.ORIENTABLE_ONLY_TOP);
		generators.createTrivialBlock(ModBlocks.FUEL_TANK, TexturedModel.COLUMN);
		createNozzleBlock(generators, ModBlocks.THRUSTER);
	}

	/**
	 * A six-way block whose model's "front" (the nozzle) points up by default, rotated to face
	 * any direction, like vanilla's end rods.
	 * Textures: {@code <name>_front} (nozzle) and {@code <name>_side}.
	 */
	private static void createNozzleBlock(BlockModelGenerators generators, Block block) {
		TextureMapping textures = new TextureMapping()
				.put(TextureSlot.FRONT, TextureMapping.getBlockTexture(block, "_front"))
				.put(TextureSlot.SIDE, TextureMapping.getBlockTexture(block, "_side"));
		MultiVariant model = BlockModelGenerators.plainVariant(
				ModelTemplates.CUBE_ORIENTABLE_VERTICAL.create(block, textures, generators.modelOutput));
		generators.blockStateOutput.accept(MultiVariantGenerator.dispatch(block).with(
				PropertyDispatch.initial(BlockStateProperties.FACING)
						.select(Direction.UP, model)
						.select(Direction.DOWN, model.with(BlockModelGenerators.X_ROT_180))
						.select(Direction.NORTH, model.with(BlockModelGenerators.X_ROT_90))
						.select(Direction.SOUTH, model.with(BlockModelGenerators.X_ROT_90).with(BlockModelGenerators.Y_ROT_180))
						.select(Direction.WEST, model.with(BlockModelGenerators.X_ROT_90).with(BlockModelGenerators.Y_ROT_270))
						.select(Direction.EAST, model.with(BlockModelGenerators.X_ROT_90).with(BlockModelGenerators.Y_ROT_90))));
	}

	@Override
	public void generateItemModels(ItemModelGenerators generators) {
		// Flat 2D item sprite: textures/item/<name>.png.
		generators.generateFlatItem(ModItems.RAW_FERRITE, ModelTemplates.FLAT_ITEM);
	}
}
