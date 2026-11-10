package com.starwake.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.starwake.ship.ShipCollisions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets players walk on moving ships without the server's "moved wrongly" rubber-banding.
 *
 * The server validates each player move by re-simulating it with collisions. Near a ship, its
 * copy of the ship is a few ticks ahead of what the client collided with, so the simulation
 * disagrees and the player gets snapped back. We still run the simulation (it computes ground
 * contact for fall damage and fly-kick logic) but then accept the client's position when the
 * player is near a ship.
 *
 * Trade-off: slightly looser movement validation within ~2 blocks of a ship. Tighten in
 * Milestone 9 (e.g. validate in the ship's frame).
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerImplMixin {
	@WrapOperation(method = "handlePlayerPositionChange", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/level/ServerPlayer;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"))
	private void starwake$trustMovesNearShips(ServerPlayer player, MoverType type, Vec3 delta, Operation<Void> original) {
		Vec3 target = player.position().add(delta);
		original.call(player, type, delta);
		if (ShipCollisions.isNearShip(player.level(), player.getBoundingBox().inflate(2.0))) {
			player.setPos(target);
		}
	}
}
