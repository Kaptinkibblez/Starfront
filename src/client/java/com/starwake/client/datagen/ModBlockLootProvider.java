package com.starwake.client.datagen;

import com.starwake.registry.ModBlocks;
import com.starwake.registry.ModItems;
import com.starwake.registry.ModPlanetBlocks;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricBlockLootSubProvider;
import net.minecraft.core.HolderLookup;

/** Generates block loot tables (what each block drops when mined). */
public final class ModBlockLootProvider extends FabricBlockLootSubProvider {
	public ModBlockLootProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
		super(output, registries);
	}

	@Override
	public void generate() {
		// Hull plating keeps its alloy (M8e-1); every Mk tier of every ship part keeps its material and quality (M8e-2).
		materialDrop(ModBlocks.HULL_PLATING);
		for (var part : com.starwake.registry.ModShipParts.Part.values()) {
			for (int mk = 1; mk <= 3; mk++) materialDrop(com.starwake.registry.ModShipParts.get(part, mk));
		}
		com.starwake.registry.ModFabrication.blocks().forEach(this::dropSelf);
		com.starwake.registry.ModNuclear.blocks().forEach(this::dropSelf);
		com.starwake.registry.ModMachines.all().forEach(this::dropSelf);
		com.starwake.registry.ModProcess.blocks().forEach(this::dropSelf);
		com.starwake.registry.ModMetalworks.blocks().forEach(this::dropSelf);
		dropSelf(ModBlocks.REGOLITH);
		dropSelf(ModBlocks.RUST_SAND);
		dropSelf(ModBlocks.HELM);
		dropSelf(ModBlocks.WING);
		dropSelf(ModBlocks.SEAT);
		for (var block : new net.minecraft.world.level.block.Block[] {com.starwake.registry.ModHubBlocks.STATION_PANEL,
				com.starwake.registry.ModHubBlocks.STATION_FLOOR, com.starwake.registry.ModHubBlocks.LIGHT_PANEL,
				com.starwake.registry.ModHubBlocks.LANDING_PAD, com.starwake.registry.ModHubBlocks.COMMODITY_TERMINAL,
				com.starwake.registry.ModHubBlocks.OUTFITTER_TERMINAL, com.starwake.registry.ModHubBlocks.CONTRACT_BOARD}) {
			dropSelf(block);
		}
		// Vanilla ore behaviour: drops raw ore, affected by Fortune; Silk Touch drops the block.
		add(ModBlocks.FERRITE_ORE, createOreDrop(ModBlocks.FERRITE_ORE, ModItems.RAW_FERRITE));

		// Planet content (M5)
		for (var block : new net.minecraft.world.level.block.Block[] {ModPlanetBlocks.ANORTHOSITE, ModPlanetBlocks.FINE_REGOLITH,
				ModPlanetBlocks.VOLCANIC_ASH, ModPlanetBlocks.PERMAFROST, ModPlanetBlocks.HALITE, ModPlanetBlocks.SELENITE,
				ModPlanetBlocks.MAT_TURF, ModPlanetBlocks.FERNWOOD_LOG, ModPlanetBlocks.FUNGAL_STEM, ModPlanetBlocks.FUNGAL_CAP}) {
			dropSelf(block);
		}
		add(ModPlanetBlocks.SELENITE_CLUSTER, createSilkTouchDispatchTable(ModPlanetBlocks.SELENITE_CLUSTER,
				applyExplosionDecay(ModPlanetBlocks.SELENITE_CLUSTER, net.minecraft.world.level.storage.loot.entries.LootItem.lootTableItem(ModPlanetBlocks.SELENITE))));
		add(ModPlanetBlocks.FERNWOOD_FRONDS, createShearsOrSilkTouchOnlyDrop(ModPlanetBlocks.FERNWOOD_FRONDS));
		add(ModPlanetBlocks.TUFT_GRASS, createShearsOrSilkTouchOnlyDrop(ModPlanetBlocks.TUFT_GRASS));
		dropSelf(ModPlanetBlocks.PUFFBALL);
		ModPlanetBlocks.ores().forEach(ore -> add(ore.block(), createOreDrop(ore.block(), ore.raw())));
	}

	/** Drops itself with the block entity's material components (composition, temper, quality). */
	private void materialDrop(net.minecraft.world.level.block.Block block) {
		add(block, net.minecraft.world.level.storage.loot.LootTable.lootTable().withPool(applyExplosionCondition(block,
				net.minecraft.world.level.storage.loot.LootPool.lootPool().setRolls(net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProviders.exactly(1))
						.add(net.minecraft.world.level.storage.loot.entries.LootItem.lootTableItem(block)
								.apply(net.minecraft.world.level.storage.loot.functions.CopyComponentsFunction
										.copyComponentsFromBlockEntity(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY)
										.include(com.starwake.registry.ModComponents.COMPOSITION)
										.include(com.starwake.registry.ModComponents.TEMPER)
										.include(com.starwake.registry.ModComponents.QUALITY))))));
	}
}
