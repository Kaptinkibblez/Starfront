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
 * Damage to a ship's hull (M10: split out of {@link ShipEntity}; combat damage joins it in M11): re-entry heating of
 * the leading face (M8e-1), crash and landing impacts soaked by shields, then by the hull's armour, then by the crew.
 */
public final class ShipDamage {
	private final ShipEntity ship;
	/** Re-entry heat soaked by leading-face blocks (a block burns off at 1). */
	private final Map<BlockPos, Double> heatDose = new HashMap<>();

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

	/**
	 * Every half second: the leading face heats to 25 C + the stagnation rise ({@link Reentry#heating}); blocks
	 * whose heat limit (alloy service temperature, or ship_stats {@code heat_limit}) is lower soak heat in
	 * proportion to the excess and burn off at a dose of 1. Cooler blocks shed it again.
	 */
	void tick() {
		if (ship.tickCount % 10 != 0 || ship.structure() == null) return;
		HullTuning tuning = HullTuning.get();
		// Mid-tick on the server the half-second ring isn't updated yet: use last tick's movement.
		Vec3 v = ship.lastMove();
		double rise = Reentry.heating(ship, v);
		float skin = rise < 20 ? 0 : (float) (25 + rise);
		if (skin <= 0) {
			ship.getEntityData().set(ShipEntity.SKIN_TEMP, 0f);
			heatDose.replaceAll((k, d) -> d * 0.8);
			heatDose.values().removeIf(d -> d < 0.01);
			return;
		}
		Direction lead = ship.localDirection(v.lengthSqr() > 1e-6 ? v : ship.worldDirection(Direction.DOWN));
		double weakest = Double.MAX_VALUE;
		java.util.Set<BlockPos> burned = new java.util.HashSet<>();
		for (BlockPos pos : ship.structure().outerFace(lead)) {
			ShipMaterials.Rating r = ship.ratings.get(pos);
			double limit = r == null ? ShipStats.get(ship.structure().get(pos)).heatLimit() : r.heatLimit();
			weakest = Math.min(weakest, limit);
			double over = skin - limit;
			double dose = heatDose.getOrDefault(pos, 0.0);
			dose = over > 0 ? dose + over * tuning.burnRate() * 0.5 : dose * 0.8;
			if (dose >= 1 && com.starwake.config.StarwakeConfig.on(com.starwake.config.StarwakeConfig.REENTRY)) burned.add(pos);
			else if (dose > 0.01) heatDose.put(pos, dose);
			else heatDose.remove(pos);
		}
		ship.getEntityData().set(ShipEntity.SKIN_TEMP, skin);
		ship.getEntityData().set(ShipEntity.HULL_LIMIT, weakest == Double.MAX_VALUE ? 0f : (float) weakest);
		if (!burned.isEmpty() && ship.level() instanceof ServerLevel server) {
			burned.forEach(heatDose::remove);
			for (BlockPos pos : burned) {
				Vec3 at = ship.blockCenter(pos);
				server.sendParticles(net.minecraft.core.particles.ParticleTypes.FLAME, at.x, at.y, at.z, 12, 0.4, 0.4, 0.4, 0.05);
			}
			ship.messageAboard(Component.translatable("message.starwake.hull.burned", burned.size()));
			com.starwake.StarwakeMod.LOGGER.info("Ship {}: re-entry at {} C burned off {} blocks", ship.getId(), Math.round(skin), burned.size());
			com.starwake.ship.edit.ShipEditing.destroy(ship, burned, false);
		}
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
		}
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
