package com.starwake.ship;

import com.starwake.ship.block.HelmBlock;
import com.starwake.ship.data.HullTuning;
import com.starwake.ship.data.ShipMaterials;
import com.starwake.ship.data.ShipStats;
import com.starwake.ship.physics.ShipMotion;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Damage to a ship's hull (M10: split out of {@link ShipEntity}): crash and landing impacts (re-entry heating was removed in
 * M10 at the user's request) soaked by shields, then by the hull's armour, then by the crew.
 *
 * <h2>Weapon hits (M11, hybrid damage)</h2> Shields take their share of a hit first (ballistic rounds partly pass them) and
 * pause regenerating for {@link #SHIELD_PAUSE} ticks. The rest comes off the hull point pool (the sum of block armour) and
 * wears the parts next to the impact. Blocks only break when a single hit is heavy (at least the weapon's break damage:
 * torpedoes, later) or once the pool is empty; then the hit carves out the cells nearest the impact.
 */
public final class ShipDamage {
	private final ShipEntity ship;

	ShipDamage(ShipEntity ship) {
		this.ship = ship;
	}

	/** Ticks shields wait after a hit before regenerating (Star Citizen style). */
	static final int SHIELD_PAUSE = 50;
	/** Ticks left before shields regenerate again. */
	int shieldPause;
	private boolean warnedBreach;

	/**
	 * Server: a weapon hit on cell {@code local} at world point {@code at}. {@code shieldFactor} is the share shields can
	 * take; a hit of at least {@code breakDamage} after shields breaks blocks even with hull points left.
	 */
	public void takeHit(BlockPos local, Vec3 at, double damage, double shieldFactor, double breakDamage) {
		if (!(ship.level() instanceof ServerLevel level) || ship.structure() == null || damage <= 0) return;
		float shield = ship.getEntityData().get(ShipEntity.SHIELD);
		double absorbed = Math.min(shield, damage * shieldFactor);
		if (absorbed > 0) {
			ship.getEntityData().set(ShipEntity.SHIELD, (float) (shield - absorbed));
			level.sendParticles(net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK, true, true, at.x, at.y, at.z, 8, 0.4, 0.4, 0.4, 0.2);
			level.playSound(null, BlockPos.containing(at), com.starwake.registry.ModSounds.SHIELD_HIT, net.minecraft.sounds.SoundSource.NEUTRAL,
					1.0f, 0.9f + level.getRandom().nextFloat() * 0.2f);
		}
		shieldPause = SHIELD_PAUSE;
		double rest = damage - absorbed;
		if (rest <= 1e-6) return;
		level.sendParticles(net.minecraft.core.particles.ParticleTypes.LAVA, true, true, at.x, at.y, at.z, 2, 0.1, 0.1, 0.1, 0);
		level.sendParticles(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE, true, true, at.x, at.y, at.z, 3, 0.2, 0.2, 0.2, 0.02);
		level.playSound(null, BlockPos.containing(at), com.starwake.registry.ModSounds.WEAPON_IMPACT, net.minecraft.sounds.SoundSource.NEUTRAL,
				1.2f, 0.85f + level.getRandom().nextFloat() * 0.3f);
		float hull = Math.max(0, ship.getEntityData().get(ShipEntity.HULL) - (float) rest);
		ship.getEntityData().set(ShipEntity.HULL, hull);
		// Parts next to the impact take wear (a 40-point hit wears an adjacent part 10%).
		for (BlockPos p : BlockPos.betweenClosed(local.offset(-1, -1, -1), local.offset(1, 1, 1))) {
			if (ship.components().contains(p)) ship.power.wear(p.immutable(), (float) Math.min(0.5, rest / 400.0));
		}
		ship.power.flush();
		boolean heavy = rest >= breakDamage;
		if (hull <= 0 && !warnedBreach) {
			ship.messageAboard(Component.translatable("message.starwake.hull.breached"));
			warnedBreach = true;
		} else if (hull > 0) {
			warnedBreach = false;
		}
		if (!(heavy || hull <= 0) || !com.starwake.config.StarwakeConfig.on(com.starwake.config.StarwakeConfig.WEAPON_BLOCK_DAMAGE)) return;
		int count = heavy ? 1 + (int) (rest / breakDamage) * 3 : 1 + (int) (rest / 30);
		java.util.Set<BlockPos> cells = nearestCells(local, Math.min(27, count));
		if (!cells.isEmpty()) com.starwake.ship.edit.ShipEditing.destroy(ship, cells, true);
		if (ship.structure() != null && ship.structure().entries().size() <= 1) wreck(level);
	}

	/** Shot down to the Helm alone: the ship is destroyed. The Helm drops as an item and the entity (and its plot) goes. */
	private void wreck(ServerLevel level) {
		ship.messageAboard(Component.translatable("message.starwake.hull.destroyed"));
		Vec3 at = ship.position();
		level.sendParticles(net.minecraft.core.particles.ParticleTypes.EXPLOSION_EMITTER, true, true, at.x, at.y, at.z, 1, 0, 0, 0, 0);
		level.playSound(null, ship.blockPosition(), net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(), net.minecraft.sounds.SoundSource.NEUTRAL, 3f, 0.8f);
		for (var entry : ship.structure().entries()) {
			level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level, at.x, at.y, at.z,
					new net.minecraft.world.item.ItemStack(entry.state().getBlock())));
		}
		ship.ejectPassengers();
		ship.discard();
	}

	/** Up to {@code count} ship cells nearest to {@code local} (within 2 blocks), never the Helm. */
	private java.util.Set<BlockPos> nearestCells(BlockPos local, int count) {
		List<BlockPos> near = new ArrayList<>();
		for (BlockPos p : BlockPos.betweenClosed(local.offset(-2, -2, -2), local.offset(2, 2, 2))) {
			var state = ship.structure().get(p);
			if (state != null && !(state.getBlock() instanceof HelmBlock)) near.add(p.immutable());
		}
		near.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(local)));
		return new java.util.LinkedHashSet<>(near.subList(0, Math.min(count, near.size())));
	}

	/** After every physics step: touchdowns and crashes. */
	void afterStep(ShipMotion.Result result) {
		if (impactCooldown > 0) {
			impactCooldown--;
			return;
		}
		boolean upright = ship.worldDirection(Direction.UP).y > 0.8;
		double safe = !result.touchdown() ? SAFE_IMPACT : ship.isGearDown() && upright ? SAFE_GEAR_LANDING : SAFE_BELLY_LANDING;
		if (result.impact() > safe) onImpact(result.impact(), safe, result.blocked());
	}

	/** Ticks before another impact can register: a crash counts once, not once per tick of scraping. */
	private int impactCooldown;

	/** Touchdown limits (blocks/tick): ~6 m/s on deployed gear, ~1.6 m/s on the belly. */
	private static final double SAFE_GEAR_LANDING = 0.3, SAFE_BELLY_LANDING = 0.08;

	/** Impact speed (blocks/tick) a ship absorbs without consequence: a firm landing. */
	private static final double SAFE_IMPACT = 0.35;

	/**
	 * A crash: shields soak the impact energy first; anything left hurts everyone aboard
	 * (crew in seats and people walking inside).
	 */
	void onImpact(double impact, double safe, Vec3 blocked) {
		if (!(ship.level() instanceof ServerLevel serverLevel)) return;
		impactCooldown = 10;
		// 1 b/t over the limit = 40 shield HP; scraping in on the belly is five times worse.
		double energy = (impact - safe) * (safe == SAFE_BELLY_LANDING ? 200.0 : 40.0); // 1 b/t over the limit = 40 shield HP
		float shield = ship.getEntityData().get(ShipEntity.SHIELD);
		double absorbed = Math.min(shield, energy);
		ship.getEntityData().set(ShipEntity.SHIELD, (float) (shield - absorbed));
		double excess = energy - absorbed;
		serverLevel.playSound(null, BlockPos.containing(ship.position()), net.minecraft.sounds.SoundEvents.ANVIL_LAND,
				net.minecraft.sounds.SoundSource.BLOCKS, (float) Math.min(1.5, impact), absorbed > 0 ? 1.6f : 0.6f);
		if (excess <= 0 || !com.starwake.config.StarwakeConfig.on(com.starwake.config.StarwakeConfig.IMPACT)) return;
		excess -= hullImpact(excess, blocked);
		if (excess <= 1e-6) return;
		float damage = (float) Math.min(30.0, excess / 4.0);
		AABB inside = ship.getBoundingBox().inflate(1.0);
		for (Entity entity : serverLevel.getEntities(ship, inside, e -> e instanceof LivingEntity)) {
			if (entity.getVehicle() == ship || ship.isSupporting(entity)) {
				entity.hurtServer(serverLevel, serverLevel.damageSources().flyIntoWall(), damage);
			}
		}
	}

	/**
	 * Impact armour (M8e-1): the blocks on the face that hit share the energy left after shields. Each
	 * soaks up to its armour (alloy toughness or ship_stats {@code armor}); a block whose share is more
	 * than its armour breaks off (dropped). The hull soaks at most {@code crumple} of the energy; the
	 * rest reaches the crew. Returns the energy absorbed.
	 */
	private double hullImpact(double energy, Vec3 blocked) {
		if (ship.structure() == null || blocked.lengthSqr() < 1e-9) return 0;
		Direction face = ship.localDirection(blocked);
		List<BlockPos> skin = ship.structure().outerFace(face);
		if (skin.isEmpty()) return 0;
		// Only the layer that sticks out furthest toward the impact touched it.
		int extreme = Integer.MIN_VALUE;
		for (BlockPos p : skin) extreme = Math.max(extreme, p.get(face.getAxis()) * face.getAxisDirection().getStep());
		List<BlockPos> contact = new ArrayList<>();
		for (BlockPos p : skin) if (p.get(face.getAxis()) * face.getAxisDirection().getStep() >= extreme) contact.add(p);
		double share = energy / contact.size();
		double absorbed = 0;
		java.util.Set<BlockPos> broken = new java.util.HashSet<>();
		for (BlockPos p : contact) {
			ShipMaterials.Rating r = ship.ratings.get(p);
			double armor = r == null ? ShipStats.get(ship.structure().get(p)).armor() : r.armor();
			absorbed += Math.min(share, armor);
			if (share > armor && !(ship.structure().get(p).getBlock() instanceof HelmBlock)) broken.add(p);
			// M10: parts that take a hit and hold together are worn by it.
			else if (ship.components().contains(p)) ship.power.wear(p, (float) Math.min(0.5, 0.5 * share / Math.max(1, armor)));
		}
		ship.power.flush();
		absorbed = Math.min(absorbed, energy * HullTuning.get().crumple());
		com.starwake.StarwakeMod.LOGGER.info("Ship {}: impact energy {} on {} contact blocks ({} face): hull soaked {}, tore off {}", ship.getId(),
				Math.round(energy), contact.size(), face, Math.round(absorbed), broken.size());
		if (!broken.isEmpty()) {
			ship.messageAboard(Component.translatable("message.starwake.hull.smashed", broken.size()));
			com.starwake.ship.edit.ShipEditing.destroy(ship, broken, true);
		}
		return absorbed;
	}
}
