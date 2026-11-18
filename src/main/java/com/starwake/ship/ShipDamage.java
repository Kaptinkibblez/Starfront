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
 * Damage to a ship's hull (M10: split out of {@link ShipEntity}; combat damage joins it in M11): crash and landing
 * impacts (re-entry heating was removed in M10 at the user's request) soaked by shields, then by the hull's armour, then by the crew.
 */
public final class ShipDamage {
	private final ShipEntity ship;

	ShipDamage(ShipEntity ship) {
		this.ship = ship;
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
