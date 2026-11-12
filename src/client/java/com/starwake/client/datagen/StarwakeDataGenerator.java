package com.starwake.client.datagen;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;

/**
 * Data generation entrypoint, run with {@code ./gradlew runDatagen}.
 *
 * Generates the boilerplate JSON (models, blockstates, lang, loot tables, tags) into
 * {@code src/main/generated}. It lives in the client source set because model generation
 * needs client classes. Commit the generated output.
 */
public final class StarwakeDataGenerator implements DataGeneratorEntrypoint {
	@Override
	public void onInitializeDataGenerator(FabricDataGenerator generator) {
		FabricDataGenerator.Pack pack = generator.createPack();
		pack.addProvider(ModModelProvider::new);
		pack.addProvider(ModLanguageProvider::new);
		pack.addProvider(ModBlockLootProvider::new);
		pack.addProvider(ModBlockTagProvider::new);
		pack.addProvider(ModRecipeProvider::new);
	}
}
