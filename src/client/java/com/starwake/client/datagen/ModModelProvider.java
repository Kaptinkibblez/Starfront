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
import net.minecraft.client.data.models.model.ModelTemplate;
import net.minecraft.client.data.models.model.ModelTemplates;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.data.models.model.TextureSlot;
import net.minecraft.client.data.models.model.TexturedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
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
		createNozzleBlock(generators, ModBlocks.MAIN_ENGINE);
		createWing(generators, ModBlocks.WING);
		createLandingGear(generators, ModBlocks.LANDING_GEAR);
		createNozzleBlock(generators, ModBlocks.MANEUVERING_THRUSTER);
		createSeat(generators, ModBlocks.SEAT);
		generators.createTrivialBlock(ModBlocks.POWER_PLANT, TexturedModel.COLUMN);
		generators.createTrivialBlock(ModBlocks.COOLER, TexturedModel.COLUMN);
		generators.createTrivialBlock(ModBlocks.SHIELD_GENERATOR, TexturedModel.COLUMN);
		generators.createTrivialBlock(ModBlocks.QUANTUM_DRIVE, TexturedModel.COLUMN);
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
		Identifier modelId = ModelTemplates.CUBE_ORIENTABLE_VERTICAL.create(block, textures, generators.modelOutput);
		MultiVariant model = BlockModelGenerators.plainVariant(modelId);
		generators.registerSimpleItemModel(block, modelId); // the item shows the nozzle-up model
		generators.blockStateOutput.accept(MultiVariantGenerator.dispatch(block).with(
				PropertyDispatch.initial(BlockStateProperties.FACING)
						.select(Direction.UP, model)
						.select(Direction.DOWN, model.with(BlockModelGenerators.X_ROT_180))
						.select(Direction.NORTH, model.with(BlockModelGenerators.X_ROT_90))
						.select(Direction.SOUTH, model.with(BlockModelGenerators.X_ROT_90).with(BlockModelGenerators.Y_ROT_180))
						.select(Direction.WEST, model.with(BlockModelGenerators.X_ROT_90).with(BlockModelGenerators.Y_ROT_270))
						.select(Direction.EAST, model.with(BlockModelGenerators.X_ROT_90).with(BlockModelGenerators.Y_ROT_90))));
	}

	/**
	 * A thin plate (hand-written parent {@code block/template_wing}), turned so its normal follows
	 * AXIS: Y = horizontal wing, X/Z = vertical fin. Textures {@code <name>_top} (faces), {@code _side} (edges).
	 */
	private static void createWing(BlockModelGenerators generators, Block block) {
		TextureMapping textures = new TextureMapping()
				.put(TextureSlot.TOP, TextureMapping.getBlockTexture(block, "_top"))
				.put(TextureSlot.SIDE, TextureMapping.getBlockTexture(block, "_side"));
		ModelTemplate template = new ModelTemplate(java.util.Optional.of(com.starwake.StarwakeMod.id("block/template_wing")),
				java.util.Optional.empty(), TextureSlot.TOP, TextureSlot.SIDE);
		Identifier modelId = template.create(block, textures, generators.modelOutput);
		MultiVariant model = BlockModelGenerators.plainVariant(modelId);
		generators.registerSimpleItemModel(block, modelId);
		generators.blockStateOutput.accept(MultiVariantGenerator.dispatch(block).with(
				PropertyDispatch.initial(BlockStateProperties.AXIS)
						.select(Direction.Axis.Y, model)
						.select(Direction.Axis.Z, model.with(BlockModelGenerators.X_ROT_90))
						.select(Direction.Axis.X, model.with(BlockModelGenerators.X_ROT_90).with(BlockModelGenerators.Y_ROT_90))));
	}

	/**
	 * Landing gear: hand-written parents {@code block/template_gear_down} (strut + pad, reaching
	 * below the block) and {@code block/template_gear_up} (flush hatch). Textures {@code _side} and {@code _top}.
	 */
	private static void createLandingGear(BlockModelGenerators generators, Block block) {
		TextureMapping textures = new TextureMapping()
				.put(TextureSlot.SIDE, TextureMapping.getBlockTexture(block, "_side"))
				.put(TextureSlot.TOP, TextureMapping.getBlockTexture(block, "_top"));
		Identifier down = new ModelTemplate(java.util.Optional.of(com.starwake.StarwakeMod.id("block/template_gear_down")),
				java.util.Optional.of("_down"), TextureSlot.SIDE, TextureSlot.TOP).create(block, textures, generators.modelOutput);
		Identifier up = new ModelTemplate(java.util.Optional.of(com.starwake.StarwakeMod.id("block/template_gear_up")),
				java.util.Optional.of("_up"), TextureSlot.SIDE, TextureSlot.TOP).create(block, textures, generators.modelOutput);
		generators.registerSimpleItemModel(block, down);
		generators.blockStateOutput.accept(MultiVariantGenerator.dispatch(block).with(
				PropertyDispatch.initial(com.starwake.ship.block.LandingGearBlock.DEPLOYED)
						.select(true, BlockModelGenerators.plainVariant(down))
						.select(false, BlockModelGenerators.plainVariant(up))));
	}

	/** A low cushioned seat: bottom-slab shape with {@code <name>_top} and {@code <name>_side}. */
	private static void createSeat(BlockModelGenerators generators, Block block) {
		TextureMapping textures = new TextureMapping()
				.put(TextureSlot.TOP, TextureMapping.getBlockTexture(block, "_top"))
				.put(TextureSlot.BOTTOM, TextureMapping.getBlockTexture(block, "_side"))
				.put(TextureSlot.SIDE, TextureMapping.getBlockTexture(block, "_side"));
		Identifier modelId = ModelTemplates.SLAB_BOTTOM.create(block, textures, generators.modelOutput);
		generators.blockStateOutput.accept(MultiVariantGenerator.dispatch(block, BlockModelGenerators.plainVariant(modelId)));
		generators.registerSimpleItemModel(block, modelId);
	}

	@Override
	public void generateItemModels(ItemModelGenerators generators) {
		// Flat 2D item sprite: textures/item/<name>.png.
		generators.generateFlatItem(ModItems.RAW_FERRITE, ModelTemplates.FLAT_ITEM);
	}
}
