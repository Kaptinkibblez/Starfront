package com.starwake;

import com.starwake.registry.ModBlocks;
import com.starwake.registry.ModCreativeTabs;
import com.starwake.registry.ModItems;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint for Starwake. It runs on both the dedicated server and the client.
 *
 * This class only wires systems together: every system exposes a static {@code init()}
 * that registers its content. Keep gameplay logic out of here.
 */
public final class StarwakeMod implements ModInitializer {
	/** The mod id. Permanent: changing it would orphan every saved block and item. */
	public static final String MOD_ID = "starwake";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Order matters: items reference blocks, and creative tabs reference both.
		ModBlocks.init();
		ModItems.init();
		ModCreativeTabs.init();

		LOGGER.info("Starwake initialized: {} blocks, {} items registered",
				ModBlocks.count(), ModItems.count());
	}

	/** Builds a namespaced id in the {@code starwake} namespace, e.g. {@code starwake:hull_plating}. */
	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
