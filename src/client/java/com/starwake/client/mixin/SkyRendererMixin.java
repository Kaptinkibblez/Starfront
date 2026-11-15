package com.starwake.client.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.vertex.PoseStack;
import com.starwake.client.sky.PlanetSky;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the vanilla sun and moon on Starwake planets (M9): {@link PlanetSky} draws the system's real star where the
 * sun would be, and the real moons and planets. Sky colour, sunrise glow and vanilla stars stay.
 */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {
	@Inject(method = "renderSun", at = @At("HEAD"), cancellable = true)
	private void starwake$hideSun(RenderPass renderPass, float rainBrightness, PoseStack poseStack, CallbackInfo ci) {
		if (PlanetSky.replacesVanilla(Minecraft.getInstance().level)) ci.cancel();
	}

	@Inject(method = "renderMoon", at = @At("HEAD"), cancellable = true)
	private void starwake$hideMoon(RenderPass renderPass, MoonPhase moonPhase, float rainBrightness, PoseStack poseStack, CallbackInfo ci) {
		if (PlanetSky.replacesVanilla(Minecraft.getInstance().level)) ci.cancel();
	}
}
