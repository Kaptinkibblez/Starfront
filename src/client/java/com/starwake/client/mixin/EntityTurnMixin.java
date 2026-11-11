package com.starwake.client.mixin;

import com.starwake.client.ship.ShipLocalMotion;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mouse look while walking aboard a ship turns you relative to the ship, not the world. */
@Mixin(Entity.class)
abstract class EntityTurnMixin {
	@Inject(method = "turn", at = @At("HEAD"), cancellable = true)
	private void starwake$shipLocalLook(double xo, double yo, CallbackInfo ci) {
		if ((Object) this instanceof LocalPlayer player && ShipLocalMotion.turn(player, xo, yo)) {
			ci.cancel();
		}
	}
}
