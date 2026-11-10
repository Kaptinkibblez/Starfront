package com.starwake.client.datagen;

import com.starwake.registry.ModBlocks;
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
	}
}
