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
		com.starwake.client.trade.TradeClient.init();
		com.starwake.client.galaxy.GalaxyClient.init();
		com.starwake.client.chem.ChemistryTooltips.init();
		com.starwake.client.metal.MetalTooltips.init();
		com.starwake.client.machine.MachineScreen.register();
		com.starwake.client.ship.ShipEditingClient.init();
		com.starwake.client.hud.FlightHud.init();
		com.starwake.client.wristcom.WristcomApps.init();
		com.starwake.client.hud.SpaceHud.init();
		com.starwake.client.hud.EnvironmentHud.init();
		com.starwake.client.hud.HubHud.init();
		com.starwake.client.space.SpaceRenderer.init();
		StarwakeMod.LOGGER.info("Starwake client initialized");
	}
}
