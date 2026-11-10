package com.starwake.network;

import com.starwake.ship.ShipConsole;
import com.starwake.ship.ShipInteraction;
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
		PayloadTypeRegistry.clientboundPlay().register(ShipStructurePayload.TYPE, ShipStructurePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ShipConsoleActionPayload.TYPE, ShipConsoleActionPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ShipInteractPayload.TYPE, ShipInteractPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(ShipConsoleActionPayload.TYPE,
				(payload, context) -> ShipConsole.handleAction(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(ShipInteractPayload.TYPE,
				(payload, context) -> ShipInteraction.handle(context.player(), payload));
	}
}
