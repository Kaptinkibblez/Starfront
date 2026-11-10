package com.starwake.network;

import com.starwake.ship.ShipConsole;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * Registers every custom payload (both directions) and the server-side receivers.
 * Client receivers live in the client source set ({@code ModClientNetworking}).
 */
public final class ModNetworking {
	private ModNetworking() {}

	public static void init() {
		PayloadTypeRegistry.clientboundPlay().register(ShipConsolePayload.TYPE, ShipConsolePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ShipConsoleActionPayload.TYPE, ShipConsoleActionPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(ShipConsoleActionPayload.TYPE,
				(payload, context) -> ShipConsole.handleAction(context.player(), payload));
	}
}
