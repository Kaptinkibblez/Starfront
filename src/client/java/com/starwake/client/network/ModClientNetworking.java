package com.starwake.client.network;

import com.starwake.client.screen.ShipConsoleScreen;
import com.starwake.network.ShipConsolePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Client-side payload receivers. Handlers run on the client (render) thread. */
public final class ModClientNetworking {
	private ModClientNetworking() {}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(ShipConsolePayload.TYPE, (payload, context) -> {
			// Refresh the open console in place after a re-scan; otherwise open a new one.
			if (context.client().gui.screen() instanceof ShipConsoleScreen open && open.isFor(payload)) {
				open.update(payload);
			} else {
				context.client().gui.setScreen(new ShipConsoleScreen(payload));
			}
		});
	}
}
