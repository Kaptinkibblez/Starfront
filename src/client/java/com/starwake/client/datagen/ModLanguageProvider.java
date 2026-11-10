package com.starwake.client.datagen;

import com.starwake.registry.ModBlocks;
import com.starwake.registry.ModCreativeTabs;
import com.starwake.registry.ModItems;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricLanguageProvider;
import net.minecraft.core.HolderLookup;

/** Generates the English (en_us) translations. */
public final class ModLanguageProvider extends FabricLanguageProvider {
	public ModLanguageProvider(FabricPackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
		super(output, "en_us", registries);
	}

	@Override
	public void generateTranslations(HolderLookup.Provider registries, TranslationBuilder builder) {
		builder.add(ModCreativeTabs.MAIN_TITLE_KEY, "Starwake");
		builder.add(ModBlocks.HULL_PLATING, "Hull Plating");
		builder.add(ModBlocks.REGOLITH, "Regolith");
		builder.add(ModBlocks.RUST_SAND, "Rust Sand");
		builder.add(ModBlocks.FERRITE_ORE, "Ferrite Ore");
		builder.add(ModItems.RAW_FERRITE, "Raw Ferrite");

		// Planets (names referenced from data/starwake/starwake/planet/*.json)
		builder.add("planet.starwake.veyra", "Veyra");

		// Commands
		builder.add("commands.starwake.planet.unknown", "Unknown planet: %s");
		builder.add("commands.starwake.planet.missing_dimension", "Planet %s has no loaded dimension");
		builder.add("commands.starwake.planet.list.header", "%s planet(s) charted:");
		builder.add("commands.starwake.planet.list.entry", " - %s (%s), gravity %sg");
		builder.add("commands.starwake.planet.tp.success", "Sent %s player(s) to %s");
		builder.add("commands.starwake.planet.leave.success", "Returned %s player(s) home");
	}
}
