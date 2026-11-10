package com.starwake.planet;

import com.starwake.StarwakeMod;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Applies a planet's gravity to living entities using vanilla attributes. There are no mixins
 * and no per-tick work.
 *
 * Modifiers are re-evaluated only when an entity enters a level (loaded from disk, spawned,
 * changed dimension, or respawned). Since gravity is a synced attribute, the client predicts
 * player movement with the right gravity automatically.
 *
 * At gravity multiplier g:
 * - gravity x g: slower falls and higher jumps
 * - safe fall distance x 1/g: a fall that is safe on the Overworld stays safe
 * - fall damage x g: falls hurt less where gravity is weak
 *
 * Limitation: non-living entities (items, arrows, falling blocks) still use normal gravity.
 */
public final class PlanetGravity {
	private static final Identifier GRAVITY_ID = StarwakeMod.id("planet_gravity");
	private static final Identifier SAFE_FALL_ID = StarwakeMod.id("planet_safe_fall");
	private static final Identifier FALL_DAMAGE_ID = StarwakeMod.id("planet_fall_damage");

	private PlanetGravity() {}

	public static void init() {
		// Covers chunk/entity loading, natural spawns, summons, and players joining.
		ServerEntityEvents.ENTITY_LOAD.register(PlanetGravity::onEnterLevel);
		// Players keep the same entity across dimensions; other entities are recreated (and also
		// fire ENTITY_LOAD), but handle both for safety.
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register(
				(player, origin, destination) -> onEnterLevel(player, destination));
		ServerEntityLevelChangeEvents.AFTER_ENTITY_CHANGE_LEVEL.register(
				(original, copy, origin, destination) -> onEnterLevel(copy, destination));
		// Respawning creates a fresh player entity with fresh attributes.
		ServerPlayerEvents.AFTER_RESPAWN.register(
				(oldPlayer, newPlayer, alive) -> onEnterLevel(newPlayer, newPlayer.level()));
	}

	private static void onEnterLevel(Entity entity, ServerLevel level) {
		if (entity instanceof LivingEntity living) {
			apply(living, Planets.gravityOf(level.dimension()));
		}
	}

	/** Sets (or clears, at g = 1) this mod's gravity modifiers on an entity. */
	public static void apply(LivingEntity entity, double g) {
		setMultiplier(entity, Attributes.GRAVITY, GRAVITY_ID, g);
		setMultiplier(entity, Attributes.SAFE_FALL_DISTANCE, SAFE_FALL_ID, 1.0 / g);
		setMultiplier(entity, Attributes.FALL_DAMAGE_MULTIPLIER, FALL_DAMAGE_ID, g);
	}

	/**
	 * Replaces our modifier on one attribute so the final value is multiplied by {@code factor}.
	 * Transient modifiers are never saved, so leaving a planet or reloading can't leave stale
	 * gravity behind.
	 */
	private static void setMultiplier(LivingEntity entity, Holder<Attribute> attribute, Identifier id, double factor) {
		AttributeInstance instance = entity.getAttribute(attribute);
		if (instance == null) {
			return;
		}
		instance.removeModifier(id);
		if (Math.abs(factor - 1.0) > 1.0e-6) {
			instance.addTransientModifier(
					new AttributeModifier(id, factor - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
	}
}
