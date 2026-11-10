package com.starwake.mixin;

import com.starwake.ship.ShipEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * On ships, Shift is the boost key, not "dismount": crew leave their seat with the dedicated
 * "Leave seat" key (ShipControlPayload) instead.
 */
@Mixin(Player.class)
abstract class PlayerMixin {
	@Inject(method = "wantsToStopRiding", at = @At("HEAD"), cancellable = true)
	private void starwake$noShiftDismountOnShips(CallbackInfoReturnable<Boolean> cir) {
		if (((Player) (Object) this).getVehicle() instanceof ShipEntity) {
			cir.setReturnValue(false);
		}
	}
}
