package com.starwake.client.mixin;

import com.starwake.client.ship.ShipClient;
import com.starwake.client.ship.ShipEditingClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Clicks on ship blocks. Ships aren't pickable entities, so vanilla's crosshair never targets
 * them; we raycast ships first and, if one is closer than whatever vanilla targeted, handle
 * the click ourselves:
 * <ul>
 *   <li>right-click (use / place, Milestone 4), keeping vanilla's 4-tick repeat when held;</li>
 *   <li>left-click (start mining) and held left-click (continue mining), Milestone 4.</li>
 * </ul>
 */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Shadow
	private int rightClickDelay;

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void starwake$useShipBlock(CallbackInfo ci) {
		if (ShipClient.tryInteract()) {
			rightClickDelay = 4;
			ci.cancel();
		}
	}

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void starwake$startMiningShipBlock(CallbackInfoReturnable<Boolean> cir) {
		if (ShipEditingClient.startAttack((Minecraft) (Object) this)) cir.setReturnValue(false);
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void starwake$continueMiningShipBlock(boolean down, CallbackInfo ci) {
		if (ShipEditingClient.continueAttack((Minecraft) (Object) this, down)) ci.cancel();
	}
}
