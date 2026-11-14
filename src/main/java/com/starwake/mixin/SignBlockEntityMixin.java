package com.starwake.mixin;

import com.starwake.ship.yard.Shipyard;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Signs aboard ships (M9 pass 2): a sign in the shipyard looks for its editor in the shipyard dimension, where no
 * player ever is, so it would cancel every edit at once. Look the player up server-wide instead; the reach check
 * ({@code PlayerMixin}) measures to where the sign really is on the ship.
 */
@Mixin(SignBlockEntity.class)
abstract class SignBlockEntityMixin {
	@Inject(method = "playerIsTooFarAwayToEdit", at = @At("HEAD"), cancellable = true)
	private void starwake$editorAboard(UUID uuid, CallbackInfoReturnable<Boolean> cir) {
		SignBlockEntity self = (SignBlockEntity) (Object) this;
		if (!(self.getLevel() instanceof ServerLevel level) || !Shipyard.isShipyard(level)) return;
		Player player = level.getServer().getPlayerList().getPlayer(uuid);
		cir.setReturnValue(player == null || !player.isWithinBlockInteractionRange(self.getBlockPos(), 4.0));
	}
}
