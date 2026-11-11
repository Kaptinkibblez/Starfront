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
