package com.starwake.client;

import com.starwake.StarwakeMod;
import com.starwake.client.network.ModClientNetworking;
import com.starwake.client.ship.ShipClient;
import net.fabricmc.api.ClientModInitializer;

/**
 * Client-only entrypoint. Rendering, screens, input and sky effects get registered here.
 * Nothing in this source set is ever loaded on a dedicated server.
 */
public final class StarwakeClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ModClientNetworking.init();
		ShipClient.init();
		StarwakeMod.LOGGER.info("Starwake client initialized");
	}
}
