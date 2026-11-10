package com.starwake.client.mixin;

import com.starwake.client.ship.ShipClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Right-click on ship blocks. Ships aren't pickable entities, so vanilla's crosshair never
 * targets them; we raycast ships first and, if one is closer than whatever vanilla targeted,
 * handle the click ourselves (works with an empty hand and when vanilla sees nothing).
 */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void starwake$useShipBlock(CallbackInfo ci) {
		if (ShipClient.tryInteract()) {
			ci.cancel();
		}
	}
}
