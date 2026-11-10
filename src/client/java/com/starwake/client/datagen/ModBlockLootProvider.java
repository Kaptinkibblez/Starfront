package com.starwake.client.datagen;

import com.starwake.registry.ModBlocks;
import com.starwake.registry.ModItems;
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
		dropSelf(ModBlocks.HULL_PLATING);
		dropSelf(ModBlocks.REGOLITH);
		dropSelf(ModBlocks.RUST_SAND);
		dropSelf(ModBlocks.HELM);
		dropSelf(ModBlocks.THRUSTER);
		dropSelf(ModBlocks.FUEL_TANK);
		// Vanilla ore behaviour: drops raw ore, affected by Fortune; Silk Touch drops the block.
		add(ModBlocks.FERRITE_ORE, createOreDrop(ModBlocks.FERRITE_ORE, ModItems.RAW_FERRITE));
	}
}
