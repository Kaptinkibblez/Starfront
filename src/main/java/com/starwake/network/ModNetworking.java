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
		PayloadTypeRegistry.serverboundPlay().register(ShipControlPayload.TYPE, ShipControlPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ShipPowerPayload.TYPE, ShipPowerPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ShipQuantumPayload.TYPE, ShipQuantumPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(ShipConsoleActionPayload.TYPE,
				(payload, context) -> ShipConsole.handleAction(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(ShipControlPayload.TYPE, (payload, context) -> {
			if (context.player().getVehicle() instanceof com.starwake.ship.ShipEntity ship) {
				if (payload.leave()) {
					context.player().stopRiding();
				} else if (ship.pilot() == context.player()) {
					ship.setControls(payload.controls());
				}
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(ShipPowerPayload.TYPE, (payload, context) -> {
			// Only the pilot routes power; the server normalizes whatever it receives.
			if (context.player().getVehicle() instanceof com.starwake.ship.ShipEntity ship && ship.pilot() == context.player()) {
				ship.setAllocation(com.starwake.ship.systems.PowerAllocation.fromPercents(
						payload.engines(), payload.shields(), payload.quantum()));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(ShipQuantumPayload.TYPE, (payload, context) -> {
			if (context.player().getVehicle() instanceof com.starwake.ship.ShipEntity ship && ship.pilot() == context.player()) {
				if (payload.engage()) ship.engageQuantum();
				else ship.toggleQuantumMode();
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(ShipInteractPayload.TYPE,
				(payload, context) -> ShipInteraction.handle(context.player(), payload));
	}
}
