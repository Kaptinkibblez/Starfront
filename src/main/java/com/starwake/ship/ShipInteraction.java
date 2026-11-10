package com.starwake.ship;

import com.starwake.network.ShipInteractPayload;
import com.starwake.network.ShipStructurePayload;
import com.starwake.ship.block.FuelTankBlock;
import com.starwake.ship.block.HelmBlock;
import com.starwake.ship.data.ShipFuels;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Server side of interacting with an assembled ship: syncing its blocks to clients, and
 * handling right-clicks on ship blocks (found client-side by a ship-space raycast).
 */
public final class ShipInteraction {
	private ShipInteraction() {}

	public static void init() {
		EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
			if (entity instanceof ShipEntity ship && ship.structure() != null) {
				ServerPlayNetworking.send(player, new ShipStructurePayload(ship.getId(), ship.structure()));
			}
		});
	}

	public static void handle(ServerPlayer player, ShipInteractPayload payload) {
		if (!(player.level().getEntity(payload.shipId()) instanceof ShipEntity ship) || ship.structure() == null) {
			return;
		}
		BlockState state = ship.structure().get(payload.local());
		if (state == null) return;
		// Reach check against the block's current world position (plus slack for latency).
		double reach = player.blockInteractionRange() + 3;
		if (player.getEyePosition().distanceToSqr(ship.blockCenter(payload.local())) > reach * reach) {
			return;
		}

		if (state.getBlock() instanceof HelmBlock || state.getBlock() instanceof com.starwake.ship.block.SeatBlock) {
			if (!ship.seat(player, payload.local())) {
				player.sendOverlayMessage(Component.translatable("message.starwake.helm_taken"));
			}
		} else if (state.getBlock() instanceof FuelTankBlock) {
			ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
			int units = ShipFuels.unitsFor(stack);
			if (units > 0) {
				int room = (ship.fuelCapacity() - ship.fuel()) / units;
				int items = Math.min(player.isShiftKeyDown() ? stack.getCount() : 1, room);
				if (items > 0) {
					stack.shrink(items);
					ship.addFuel(items * units);
				}
			}
			player.sendOverlayMessage(Component.translatable("message.starwake.fuel_tank.gauge", ship.fuel(), ship.fuelCapacity()));
		} else {
			player.sendOverlayMessage(Component.translatable("message.starwake.ship_block_locked"));
		}
	}
}
