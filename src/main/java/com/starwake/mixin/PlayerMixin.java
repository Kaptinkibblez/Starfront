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
 *
 * <p>M9 shipyard: blocks aboard a ship really sit in its plot, far away in another dimension, so vanilla's
 * "is this block within reach" (menus closing, interaction checks) measures to where the block is on the
 * flying ship instead.
 */
@Mixin(Player.class)
abstract class PlayerMixin {
	@Inject(method = "isWithinBlockInteractionRange", at = @At("HEAD"), cancellable = true)
	private void starwake$shipyardReach(net.minecraft.core.BlockPos pos, double buffer, CallbackInfoReturnable<Boolean> cir) {
		if (!com.starwake.ship.yard.Shipyard.inYard(pos)) return;
		Player self = (Player) (Object) this;
		if (com.starwake.ship.yard.Shipyard.isShipyard(self.level())) return;
		if (self.level().isClientSide()) {
			cir.setReturnValue(true); // the server decides
			return;
		}
		ShipEntity ship = com.starwake.ship.yard.Shipyard.shipAt(pos);
		if (ship == null || ship.level() != self.level()) {
			cir.setReturnValue(false);
			return;
		}
		var centre = ship.blockCenter(com.starwake.ship.yard.Shipyard.toLocal(ship.plot(), pos));
		double range = self.blockInteractionRange() + buffer + 0.5;
		cir.setReturnValue(centre.distanceToSqr(self.getEyePosition()) < range * range);
	}

	@Inject(method = "wantsToStopRiding", at = @At("HEAD"), cancellable = true)
	private void starwake$noShiftDismountOnShips(CallbackInfoReturnable<Boolean> cir) {
		if (((Player) (Object) this).getVehicle() instanceof ShipEntity) {
			cir.setReturnValue(false);
		}
	}
}
