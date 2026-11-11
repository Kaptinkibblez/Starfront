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
	@org.spongepowered.asm.mixin.Shadow public net.minecraft.server.level.ServerPlayer player;

	/**
	 * Players aboard a moving ship are carried by it on their own client, so their moves can be
	 * far bigger than their own velocity predicts: skip the "moved too quickly" check near ships
	 * (as singleplayer hosts already are). The ship's own speed cap still bounds it.
	 */
	@org.spongepowered.asm.mixin.injection.Inject(method = "shouldCheckPlayerMovement", at = @At("HEAD"), cancellable = true)
	private void starwake$noSpeedCheckAboard(boolean isFallFlying,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
		if (ShipCollisions.isNearShip(player.level(), player.getBoundingBox().inflate(4.0))) cir.setReturnValue(false);
	}

	/**
	 * Dedicated servers kick players who "float" (no blocks around them) for too long. Standing
	 * on or walking inside a ship is standing on blocks the world doesn't know about, so near a
	 * ship nobody counts as floating (3j multiplayer fix; singleplayer never runs this check).
	 */
	@org.spongepowered.asm.mixin.injection.Inject(method = "noBlocksAround", at = @At("HEAD"), cancellable = true)
	private void starwake$shipsAreGround(net.minecraft.world.entity.Entity entity,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
		if (ShipCollisions.isNearShip(entity.level(), entity.getBoundingBox().inflate(2.0))) cir.setReturnValue(false);
	}

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
