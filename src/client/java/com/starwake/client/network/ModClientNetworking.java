package com.starwake.client.network;

import com.starwake.client.wristcom.ShipReports;
import com.starwake.client.wristcom.WristcomScreen;
import com.starwake.client.wristcom.apps.ShipApp;
import com.starwake.network.ShipConsolePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Client-side payload receivers. Handlers run on the client (render) thread. */
public final class ModClientNetworking {
	private ModClientNetworking() {}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(com.starwake.network.ConfigSyncPayload.TYPE,
				(payload, context) -> com.starwake.config.StarwakeConfig.applySync(payload.orbitSpeed()));
		ClientPlayNetworking.registerGlobalReceiver(com.starwake.network.TradeIntelPayload.TYPE,
				(payload, context) -> com.starwake.client.wristcom.apps.TradeApp.receive(payload));
		ClientPlayNetworking.registerGlobalReceiver(com.starwake.network.KioskPayload.TYPE,
				(payload, context) -> com.starwake.client.trade.KioskScreen.receive(payload));
		ClientPlayNetworking.registerGlobalReceiver(com.starwake.network.CargoPayload.TYPE,
				(payload, context) -> com.starwake.client.wristcom.apps.CargoApp.receive(payload));
		ClientPlayNetworking.registerGlobalReceiver(com.starwake.network.CommsMessagePayload.TYPE,
				(payload, context) -> com.starwake.client.wristcom.apps.CommsApp.receive(payload));
		ClientPlayNetworking.registerGlobalReceiver(com.starwake.network.PlanetListPayload.TYPE,
				(payload, context) -> com.starwake.planet.proc.ProceduralPlanets.setFromServer(payload.planets()));
		ClientPlayNetworking.registerGlobalReceiver(ShipConsolePayload.TYPE, (payload, context) -> {
			// Ship reports (Helm pre-flight, ship status) show in the Wristcom's Ship app: refresh
			// it in place if it's open, otherwise open the Wristcom on that app.
			ShipReports.set(payload);
			if (context.client().gui.screen() instanceof WristcomScreen open && open.currentApp() instanceof ShipApp) {
				open.refresh();
			} else {
				context.client().gui.setScreen(WristcomScreen.openOn(ShipApp.class));
			}
		});
	}
}
