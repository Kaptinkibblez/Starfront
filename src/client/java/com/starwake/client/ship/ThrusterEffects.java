package com.starwake.client.ship;

import com.starwake.registry.ModBlocks;
import com.starwake.ship.ShipEntity;
import com.starwake.ship.ShipCollisions;
import com.starwake.ship.ShipStructure;
import com.starwake.ship.block.ThrusterBlock;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Exhaust from firing thrusters (3h), so you can see the flight computer at work: main engines
 * roar flame and smoke, thrusters burn, maneuvering thrusters puff like RCS. Throttles come
 * from the ship's synced data; everything here is client-side particles.
 */
public final class ThrusterEffects {
	private static final double RANGE_SQ = 128 * 128;

	private ThrusterEffects() {}

	/** Called every client tick. */
	public static void tick(Minecraft client) {
		if (client.level == null || client.player == null || client.isPaused()) return;
		RandomSource random = client.level.getRandom();
		for (ShipEntity ship : ShipCollisions.shipsIn(client.level)) {
			ShipStructure structure = ship.structure();
			if (structure == null || ship.distanceToSqr(client.player) > RANGE_SQ) continue;
			Map<Integer, Float> firing = ship.firingThrusters();
			if (firing.isEmpty()) continue;
			List<ShipStructure.Entry> entries = structure.entries();
			Vec3 shipVelocity = ship.lastMove();
			for (var fire : firing.entrySet()) {
				if (fire.getKey() >= entries.size()) continue;
				ShipStructure.Entry entry = entries.get(fire.getKey());
				BlockState state = entry.state();
				if (!(state.getBlock() instanceof ThrusterBlock)) continue;
				float throttle = fire.getValue();
				Direction nozzle = state.getValue(ThrusterBlock.FACING);
				Vec3 out = ship.worldDirection(nozzle);
				Vec3 at = ship.toWorld(Vec3.atCenterOf(entry.pos()).add(nozzle.getStepX() * 0.6, nozzle.getStepY() * 0.6,
						nozzle.getStepZ() * 0.6));

				boolean main = state.is(ModBlocks.MAIN_ENGINE), rcs = state.is(ModBlocks.MANEUVERING_THRUSTER);
				int puffs = main ? 3 : 1;
				for (int i = 0; i < puffs; i++) {
					if (random.nextFloat() > throttle) continue;
					ParticleOptions type = rcs ? ParticleTypes.CLOUD : ParticleTypes.FLAME;
					double speed = (rcs ? 0.12 : 0.2) + 0.35 * throttle;
					spawn(client, type, at, out.scale(speed).add(shipVelocity), random, main ? 0.25 : 0.1);
				}
				if (main && random.nextFloat() < throttle * 0.5) {
					spawn(client, ParticleTypes.LARGE_SMOKE, at.add(out.scale(0.4)), out.scale(0.25).add(shipVelocity), random, 0.2);
				}
			}
		}
	}

	private static void spawn(Minecraft client, ParticleOptions type, Vec3 at, Vec3 velocity, RandomSource random, double jitter) {
		client.level.addParticle(type,
				at.x + (random.nextDouble() - 0.5) * jitter, at.y + (random.nextDouble() - 0.5) * jitter,
				at.z + (random.nextDouble() - 0.5) * jitter,
				velocity.x + (random.nextDouble() - 0.5) * 0.04, velocity.y + (random.nextDouble() - 0.5) * 0.04,
				velocity.z + (random.nextDouble() - 0.5) * 0.04);
	}
}
