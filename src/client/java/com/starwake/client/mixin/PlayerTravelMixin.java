package com.starwake.client.mixin;

import com.starwake.client.ship.ShipLocalMotion;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces the local player's movement with ship-local physics aboard ships, or EVA in space. */
@Mixin(Player.class)
abstract class PlayerTravelMixin {
	@Inject(method = "travel", at = @At("HEAD"), cancellable = true)
	private void starwake$shipLocalTravel(Vec3 input, CallbackInfo ci) {
		if ((Object) this instanceof LocalPlayer player && ShipLocalMotion.travel(player)) {
			ci.cancel();
		}
	}
}
