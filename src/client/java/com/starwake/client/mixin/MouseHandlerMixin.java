package com.starwake.client.mixin;

import com.starwake.client.ship.FlightControls;
import com.starwake.client.ship.ShipClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * M10: while piloting (no screen open), the mouse wheel sets the speed limiter instead of scrolling the hotbar (which
 * is hidden in the cockpit anyway), like Star Citizen's throttle wheel.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Shadow @Final private Minecraft minecraft;

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void starwake$speedLimiter(long handle, double xoffset, double yoffset, CallbackInfo ci) {
		if (handle != minecraft.getWindow().handle() || minecraft.gui.screen() != null || !ShipClient.piloting(minecraft)) return;
		if (yoffset != 0) FlightControls.scrollLimit(minecraft, (int) Math.signum(yoffset));
		ci.cancel();
	}
}
