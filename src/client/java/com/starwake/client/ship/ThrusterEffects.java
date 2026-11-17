package com.starwake.client.ship;

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
				// Thrusters on hinged parts (M9 pass 3) fire from where the part has turned to.
				Vec3 dir = new Vec3(nozzle.getStepX(), nozzle.getStepY(), nozzle.getStepZ());
				Vec3 out = ship.worldDirection(ship.posedDirection(entry.pos(), dir));
				// M10: spawn where the nozzle was at the start of this tick. A new particle has no previous position to
				// interpolate from, so for its first tick it's drawn exactly where it spawned, while the ship is drawn
				// between last tick and this one: spawning at the end-of-tick position put fast ships' exhaust up to a
				// tick of travel ahead of the hull (flames in front of the canopy at speed).
				Vec3 at = ship.toWorld(ship.posed(entry.pos(), Vec3.atCenterOf(entry.pos()).add(dir.scale(0.6)))).subtract(shipVelocity);

				String part = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
				boolean main = part.startsWith("main_engine"), rcs = part.startsWith("maneuvering_thruster"); // any Mk
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
