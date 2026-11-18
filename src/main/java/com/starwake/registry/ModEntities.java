package com.starwake.registry;

import com.starwake.StarwakeMod;
import com.starwake.ship.ShipEntity;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/** Registry of Starwake entity types. */
public final class ModEntities {
	public static final ResourceKey<EntityType<?>> SHIP_KEY = ResourceKey.create(Registries.ENTITY_TYPE, StarwakeMod.id("ship"));

	/**
	 * Assembled ships. Tracked as far as the server's view distance allows (M11: combat; was 10 chunks) and synced every tick so
	 * riders see smooth motion. The hitbox comes from the structure, not these dimensions.
	 */
	public static final EntityType<ShipEntity> SHIP = Registry.register(BuiltInRegistries.ENTITY_TYPE, SHIP_KEY,
			EntityType.Builder.<ShipEntity>of(ShipEntity::new, MobCategory.MISC)
					.sized(1.0f, 1.0f)
					.clientTrackingRange(32)
					.updateInterval(1)
					.noSummon()
					.fireImmune()
					.build(SHIP_KEY));

	public static final ResourceKey<EntityType<?>> SHIP_PROJECTILE_KEY = ResourceKey.create(Registries.ENTITY_TYPE, StarwakeMod.id("ship_projectile"));

	/**
	 * Ship-gun shots (M11). They fly in straight lines, so clients move them from their synced velocity; position updates
	 * are rare. Tracked as far as ships are, so a fight is visible.
	 */
	public static final EntityType<com.starwake.ship.weapon.ShipProjectile> SHIP_PROJECTILE = Registry.register(BuiltInRegistries.ENTITY_TYPE,
			SHIP_PROJECTILE_KEY, EntityType.Builder.<com.starwake.ship.weapon.ShipProjectile>of(com.starwake.ship.weapon.ShipProjectile::new, MobCategory.MISC)
					.sized(0.25f, 0.25f)
					.clientTrackingRange(32)
					.updateInterval(20)
					.noSummon()
					.fireImmune()
					.build(SHIP_PROJECTILE_KEY));

	private ModEntities() {}

	public static void init() {}
}
