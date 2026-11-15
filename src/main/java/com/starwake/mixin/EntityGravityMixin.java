package com.starwake.mixin;

import com.starwake.planet.Planets;
import com.starwake.ship.ShipEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Planet gravity for non-living entities (M9): items, arrows, falling blocks, minecarts... Living entities already
 * get it from the vanilla {@code gravity} attribute ({@link com.starwake.planet.PlanetGravity}); everything else
 * reads a hard-coded default, so {@code Entity.getGravity()} (final, so no override hook) is scaled at its return.
 * Space is weightless for them. Ships run their own physics.
 */
@Mixin(Entity.class)
public abstract class EntityGravityMixin {
	@Inject(method = "getGravity", at = @At("RETURN"), cancellable = true)
	private void starwake$planetGravity(CallbackInfoReturnable<Double> cir) {
		Entity self = (Entity) (Object) this;
		if (self instanceof LivingEntity || self instanceof ShipEntity) return;
		double base = cir.getReturnValueD();
		if (base == 0.0) return;
		Level level = self.level();
		double g = Planets.gravityFactor(level);
		if (g != 1.0) cir.setReturnValue(base * g);
	}
}
