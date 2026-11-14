package com.starwake.client.mixin;

import com.starwake.client.ship.ShipRain;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Rain splashes and the "rain above" sound also see ship roofs (see {@link ShipRain}). */
@Mixin(ClientLevel.class)
abstract class ClientLevelWeatherMixin {
	@Redirect(method = "tickWeatherEffects", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/ClientLevel;getHeightmapPos(Lnet/minecraft/world/level/levelgen/Heightmap$Types;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;"))
	private BlockPos starwake$shipRoof(ClientLevel level, Heightmap.Types type, BlockPos pos) {
		BlockPos ground = level.getHeightmapPos(type, pos);
		int y = ShipRain.height(level, ground.getX(), ground.getZ(), ground.getY());
		return y == ground.getY() ? ground : new BlockPos(ground.getX(), y, ground.getZ());
	}
}
