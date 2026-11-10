package com.starwake.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.starwake.ship.ShipCollisions;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Makes ship blocks solid: adds ship collision shapes wherever an entity collects
 * "entity collisions" (the same channel boats and shulkers use) during movement.
 */
@Mixin(Entity.class)
abstract class EntityCollideMixin {
	@WrapOperation(method = "collide", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/Level;getEntityCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
	private List<VoxelShape> starwake$addShipShapes(Level level, Entity source, AABB area, Operation<List<VoxelShape>> original) {
		return ShipCollisions.append(level, source, area, original.call(level, source, area));
	}
}
