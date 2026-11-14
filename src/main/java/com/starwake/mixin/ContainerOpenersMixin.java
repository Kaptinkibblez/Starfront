package com.starwake.mixin;

import com.starwake.ship.ShipEntity;
import com.starwake.ship.yard.Shipyard;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Containers aboard ships (M9 pass 2): vanilla re-counts a chest's viewers every 5 ticks by looking for players
 * around the chest in its own level. A ship's chest lives in the shipyard, where no player ever is, so the count
 * dropped to zero while the menu was open and the lid got stuck. Look around where the chest really is instead.
 */
@Mixin(ContainerOpenersCounter.class)
abstract class ContainerOpenersMixin {
	@Inject(method = "getEntitiesWithContainerOpen", at = @At("HEAD"), cancellable = true)
	private void starwake$viewersAboard(Level level, BlockPos pos, CallbackInfoReturnable<List<ContainerUser>> cir) {
		if (level.isClientSide() || !Shipyard.isShipyard(level)) return;
		ShipEntity ship = Shipyard.shipAt(pos);
		if (ship == null) {
			cir.setReturnValue(List.of());
			return;
		}
		ContainerOpenersCounter self = (ContainerOpenersCounter) (Object) this;
		Vec3 centre = ship.blockCenter(Shipyard.toLocal(ship.plot(), pos));
		AABB box = new AABB(centre, centre).inflate(12);
		cir.setReturnValue(ship.level().getEntities((Entity) null, box, e -> e instanceof ContainerUser user
						&& !user.getLivingEntity().isSpectator() && user.hasContainerOpen(self, pos))
				.stream().map(e -> (ContainerUser) e).toList());
	}
}
