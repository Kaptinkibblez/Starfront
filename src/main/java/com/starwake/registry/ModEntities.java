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
	 * Assembled ships. Tracked from 10 chunks away (ships are big) and synced every tick so
	 * riders see smooth motion. The hitbox comes from the structure, not these dimensions.
	 */
	public static final EntityType<ShipEntity> SHIP = Registry.register(BuiltInRegistries.ENTITY_TYPE, SHIP_KEY,
			EntityType.Builder.<ShipEntity>of(ShipEntity::new, MobCategory.MISC)
					.sized(1.0f, 1.0f)
					.clientTrackingRange(10)
					.updateInterval(1)
					.noSummon()
					.fireImmune()
					.build(SHIP_KEY));

	private ModEntities() {}

	public static void init() {}
}
