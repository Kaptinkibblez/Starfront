package com.starwake.client.mixin;

import com.starwake.client.ship.ShipLocalMotion;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Wraps the local player's tick so ShipLocalMotion can enter, follow and leave ship frames. */
@Mixin(LocalPlayer.class)
abstract class LocalPlayerMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void starwake$beforeTick(CallbackInfo ci) {
		ShipLocalMotion.beforeTick((LocalPlayer) (Object) this);
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void starwake$afterTick(CallbackInfo ci) {
		ShipLocalMotion.afterTick((LocalPlayer) (Object) this);
	}
}
