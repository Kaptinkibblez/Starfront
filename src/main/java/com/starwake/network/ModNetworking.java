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
		PayloadTypeRegistry.clientboundPlay().register(ShipStatePayload.TYPE, ShipStatePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ShipDynamicsPayload.TYPE, ShipDynamicsPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ShipPhysicsPayload.TYPE, ShipPhysicsPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PlanetListPayload.TYPE, PlanetListPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(GalaxyInfoPayload.TYPE, GalaxyInfoPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SystemInfoPayload.TYPE, SystemInfoPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SystemInfoRequestPayload.TYPE, SystemInfoRequestPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(JumpTargetPayload.TYPE, JumpTargetPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(EconomyPayload.TYPE, EconomyPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MeltStatePayload.TYPE, MeltStatePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(HubListPayload.TYPE, HubListPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MarketPayload.TYPE, MarketPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ShopPayload.TYPE, ShopPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ContractBoardPayload.TYPE, ContractBoardPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(TradeActionPayload.TYPE, TradeActionPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ShipConsoleActionPayload.TYPE, ShipConsoleActionPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ShipInteractPayload.TYPE, ShipInteractPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ShipControlPayload.TYPE, ShipControlPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ShipInputPayload.TYPE, ShipInputPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ShipPowerPayload.TYPE, ShipPowerPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ShipQuantumPayload.TYPE, ShipQuantumPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ShipCrewPayload.TYPE, ShipCrewPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ShipEditPayload.TYPE, ShipEditPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(ShipConsoleActionPayload.TYPE,
				(payload, context) -> ShipConsole.handleAction(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(ShipControlPayload.TYPE, (payload, context) -> {
			if (context.player().getVehicle() instanceof com.starwake.ship.ShipEntity ship) {
				if (payload.leave()) {
					context.player().stopRiding();
				} else if (ship.pilot() == context.player() && !ship.receivesInputs()) {
					ship.setControls(payload.controls());
					ship.setStick(payload.stickX(), payload.stickY());
				}
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(ShipInputPayload.TYPE, (payload, context) -> {
			if (context.player().getVehicle() instanceof com.starwake.ship.ShipEntity ship && ship.getId() == payload.shipId()
					&& ship.pilot() == context.player()) {
				ship.acceptInput(payload);
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
				switch (payload.action()) {
					case ShipQuantumPayload.ACTION_QUANTUM_ENGAGE -> ship.engageQuantum();
					case ShipQuantumPayload.ACTION_QUANTUM_TOGGLE -> ship.toggleQuantumMode();
					case ShipQuantumPayload.ACTION_DECOUPLE -> ship.toggleDecoupled();
					case ShipQuantumPayload.ACTION_GEAR -> ship.toggleGear();
					case ShipQuantumPayload.ACTION_JUMP_TOGGLE -> ship.toggleJumpMode();
					case ShipQuantumPayload.ACTION_JUMP_ENGAGE -> ship.engageJump();
					default -> { }
				}
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(TradeActionPayload.TYPE, (payload, context) -> {
			if (payload.action().equals("abandon")) {
				context.player().sendOverlayMessage(com.starwake.trade.Contracts.abandon(context.player(), payload.arg()));
				return;
			}
			com.starwake.trade.Terminals.action(context.player(), payload);
		});
		ServerPlayNetworking.registerGlobalReceiver(JumpTargetPayload.TYPE, (payload, context) -> {
			var player = context.player();
			if (player.getVehicle() instanceof com.starwake.ship.ShipEntity ship && ship.pilot() == player) {
				var error = ship.setJumpTarget(payload.system());
				player.sendOverlayMessage(error != null ? error : net.minecraft.network.chat.Component.translatable(
						"message.starwake.jump.target", com.starwake.galaxy.Galaxy.star(payload.system())
								.map(com.starwake.galaxy.Galaxy.Star::name).orElse(payload.system())));
			} else {
				player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("message.starwake.jump.not_pilot"));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(SystemInfoRequestPayload.TYPE, (payload, context) -> {
			var info = com.starwake.galaxy.StarSystems.info(context.player().level().registryAccess(), payload.system());
			if (info != null) context.responseSender().sendPacket(info);
		});
		ServerPlayNetworking.registerGlobalReceiver(ShipEditPayload.TYPE, (payload, context) -> {
			var player = context.player();
			if (payload.action() == ShipEditPayload.ABORT) {
				com.starwake.ship.edit.ShipEditing.abortBreak(player);
				return;
			}
			if (!(player.level().getEntity(payload.shipId()) instanceof com.starwake.ship.ShipEntity ship)) return;
			if (payload.action() == ShipEditPayload.START) com.starwake.ship.edit.ShipEditing.startBreak(player, ship, payload.local());
			else if (payload.action() == ShipEditPayload.FINISH) com.starwake.ship.edit.ShipEditing.finishBreak(player, ship, payload.local());
		});
		ServerPlayNetworking.registerGlobalReceiver(ShipCrewPayload.TYPE, (payload, context) -> {
			var player = context.player();
			if (!(player.level().getEntity(payload.shipId()) instanceof com.starwake.ship.ShipEntity ship)) return;
			if (!ship.isOwner(player) || player.distanceToSqr(ship) > 48 * 48) {
				player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("message.starwake.ship.owner_only"));
				return;
			}
			switch (payload.action()) {
				case ShipCrewPayload.ADD -> {
					var target = player.level().getServer().getPlayerList().getPlayerByName(payload.name());
					if (target == null) {
						player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("message.starwake.ship.crew_unknown", payload.name()));
					} else {
						ship.addCrew(target);
						player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("message.starwake.ship.crew_added", target.getGameProfile().name()));
					}
				}
				case ShipCrewPayload.REMOVE -> ship.removeCrew(payload.name());
				case ShipCrewPayload.LOCK -> ship.setLocked(true);
				case ShipCrewPayload.UNLOCK -> ship.setLocked(false);
				default -> { }
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(ShipInteractPayload.TYPE,
				(payload, context) -> ShipInteraction.handle(context.player(), payload));
	}
}
