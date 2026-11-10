package com.starwake.client.mixin;

import com.starwake.client.ship.ShipCarry;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Wraps the local player's tick so ShipCarry can move them with the ship they stand on. */
@Mixin(LocalPlayer.class)
abstract class LocalPlayerMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void starwake$carryBeforeTick(CallbackInfo ci) {
		ShipCarry.beforePlayerTick((LocalPlayer) (Object) this);
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void starwake$trackAfterTick(CallbackInfo ci) {
		ShipCarry.afterPlayerTick((LocalPlayer) (Object) this);
	}
}
