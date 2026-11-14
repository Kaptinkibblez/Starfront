package com.starwake.client.mixin;

import com.starwake.client.ship.ShipRain;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Precipitation columns stop at ship roofs as well as terrain (see {@link ShipRain}). */
@Mixin(WeatherEffectRenderer.class)
abstract class WeatherMixin {
	@Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/ClientLevel;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I"))
	private int starwake$shipRoof(ClientLevel level, Heightmap.Types type, int x, int z) {
		return ShipRain.height(level, x, z, level.getHeight(type, x, z));
	}
}
