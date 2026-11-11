package com.starwake.client.datagen;

import com.starwake.registry.ModBlocks;
import com.starwake.registry.ModItems;
import com.starwake.registry.ModPlanetBlocks;
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

		// Planet content (M5)
		for (Block block : new Block[] {ModPlanetBlocks.ANORTHOSITE, ModPlanetBlocks.FINE_REGOLITH, ModPlanetBlocks.VOLCANIC_ASH,
				ModPlanetBlocks.PERMAFROST, ModPlanetBlocks.HALITE, ModPlanetBlocks.SELENITE, ModPlanetBlocks.FUNGAL_CAP}) {
			generators.createTrivialCube(block);
		}
		ModPlanetBlocks.ores().forEach(ore -> generators.createTrivialCube(ore.block()));

		// Trade hubs (M7)
		for (Block block : new Block[] {com.starwake.registry.ModHubBlocks.STATION_PANEL, com.starwake.registry.ModHubBlocks.STATION_FLOOR,
				com.starwake.registry.ModHubBlocks.LIGHT_PANEL, com.starwake.registry.ModHubBlocks.LANDING_PAD}) {
			generators.createTrivialCube(block);
		}
		for (Block block : new Block[] {com.starwake.registry.ModHubBlocks.COMMODITY_TERMINAL, com.starwake.registry.ModHubBlocks.OUTFITTER_TERMINAL,
				com.starwake.registry.ModHubBlocks.CONTRACT_BOARD}) {
			generators.createHorizontallyRotatedBlock(block, TexturedModel.ORIENTABLE_ONLY_TOP.updateTexture(t -> t
					.put(TextureSlot.FRONT, TextureMapping.getBlockTexture(block, "_front"))
					.put(TextureSlot.SIDE, TextureMapping.getBlockTexture(block, "_side"))
					.put(TextureSlot.TOP, TextureMapping.getBlockTexture(com.starwake.registry.ModHubBlocks.STATION_PANEL))));
		}
		generators.createTrivialBlock(ModPlanetBlocks.MAT_TURF, TexturedModel.CUBE_BOTTOM_TOP.updateTexture(t -> t
				.put(TextureSlot.TOP, TextureMapping.getBlockTexture(ModPlanetBlocks.MAT_TURF, "_top"))
				.put(TextureSlot.SIDE, TextureMapping.getBlockTexture(ModPlanetBlocks.MAT_TURF, "_side"))
				.put(TextureSlot.BOTTOM, TextureMapping.getBlockTexture(net.minecraft.world.level.block.Blocks.DIRT))));
		generators.woodProvider(ModPlanetBlocks.FERNWOOD_LOG).log(ModPlanetBlocks.FERNWOOD_LOG);
		generators.woodProvider(ModPlanetBlocks.FUNGAL_STEM).log(ModPlanetBlocks.FUNGAL_STEM);
		generators.createTrivialBlock(ModPlanetBlocks.FERNWOOD_FRONDS, TexturedModel.LEAVES);
		generators.createCrossBlockWithDefaultItem(ModPlanetBlocks.TUFT_GRASS, BlockModelGenerators.PlantType.NOT_TINTED);
		generators.createCrossBlockWithDefaultItem(ModPlanetBlocks.PUFFBALL, BlockModelGenerators.PlantType.NOT_TINTED);
		generators.createAmethystCluster(ModPlanetBlocks.SELENITE_CLUSTER);
		generators.registerSimpleFlatItemModel(ModPlanetBlocks.SELENITE_CLUSTER);

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
		generators.createTrivialBlock(ModBlocks.JUMP_DRIVE, TexturedModel.COLUMN);
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
		ModPlanetBlocks.ores().forEach(ore -> generators.generateFlatItem(ore.raw(), ModelTemplates.FLAT_ITEM));
		generators.generateFlatItem(ModItems.SURVEY_SUIT, ModelTemplates.FLAT_ITEM);
		generators.generateFlatItem(com.starwake.registry.ModHubBlocks.SEALED_CARGO, ModelTemplates.FLAT_ITEM);
		// Element items: textures/item/element/<name>_<form>.png (tools/periodic_table.py).
		for (var element : com.starwake.chem.Chemistry.elements()) {
			if (!element.item().getNamespace().equals("starwake")) continue;
			var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(element.item());
			var texture = net.minecraft.resources.Identifier.fromNamespaceAndPath("starwake", "item/element/" + element.item().getPath());
			generators.itemModelOutput.accept(item, net.minecraft.client.data.models.model.ItemModelUtils.plainModel(
					ModelTemplates.FLAT_ITEM.create(item, net.minecraft.client.data.models.model.TextureMapping.layer0(new net.minecraft.client.resources.model.sprite.Material(texture)), generators.modelOutput)));
		}
	}
}
