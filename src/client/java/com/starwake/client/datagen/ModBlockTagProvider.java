package com.starwake.client.datagen;

import com.starwake.registry.ModBlocks;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagsProvider;
import net.minecraft.core.HolderLookup;
import net.minecraft.tags.BlockTags;

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
		// 26.3 tag appenders take registry keys rather than block instances.
		var hullPlating = ModBlocks.HULL_PLATING.builtInRegistryHolder().key();
		builder(BlockTags.MINEABLE_WITH_PICKAXE).add(hullPlating);
		builder(BlockTags.NEEDS_STONE_TOOL).add(hullPlating);
	}
}
