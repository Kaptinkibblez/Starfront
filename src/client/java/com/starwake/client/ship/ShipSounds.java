package com.starwake.client.ship;

import com.starwake.client.hud.FlightHud;
import com.starwake.registry.ModBlocks;
import com.starwake.registry.ModSounds;
import com.starwake.ship.ShipCollisions;
import com.starwake.ship.ShipEntity;
import com.starwake.ship.ShipStructure;
import com.starwake.ship.SpaceTravel;
import com.starwake.ship.block.ThrusterBlock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Ship audio (3j), all client-side from synced throttles:
 * <ul>
 *   <li>an engine rumble and a thruster hiss per nearby ship, whose volume and pitch follow
 *       how hard its main engines and thrusters are firing;</li>
 *   <li>a puff whenever a maneuvering thruster starts firing (so you hear the flight computer);</li>
 *   <li>the quantum travel hum while your ship is in a jump;</li>
 *   <li>cockpit alarms for the pilot: low fuel, overheat, shields down, gear up on approach.</li>
 * </ul>
 * One-shot events (quantum spool/engage/exit, gear, impacts) are played by the server.
 */
public final class ShipSounds {
	private static final double RANGE_SQ = 96 * 96;
	private static final Map<Integer, Loop> ENGINES = new HashMap<>();
	private static final Map<Integer, Loop> THRUSTERS = new HashMap<>();
	private static final Map<Integer, Set<Integer>> FIRING_RCS = new HashMap<>();
	private static Loop travel;
	private static int alarmCooldown;

	private ShipSounds() {}

	/** Throttle totals for one ship: [main engines, thrusters], plus the firing RCS entries. */
	private record Firing(float mains, float thrusters, Set<Integer> rcs) {}

	static Firing firing(ShipEntity ship) {
		ShipStructure structure = ship.structure();
		float mains = 0, thrusters = 0;
		Set<Integer> rcs = new HashSet<>();
		if (structure == null) return new Firing(0, 0, rcs);
		List<ShipStructure.Entry> entries = structure.entries();
		for (var fire : ship.firingThrusters().entrySet()) {
			if (fire.getKey() >= entries.size()) continue;
			BlockState state = entries.get(fire.getKey()).state();
			if (state.is(ModBlocks.MAIN_ENGINE)) mains += fire.getValue();
			else if (state.is(ModBlocks.MANEUVERING_THRUSTER)) rcs.add(fire.getKey());
			else if (state.getBlock() instanceof ThrusterBlock) thrusters += fire.getValue();
		}
		return new Firing(mains, thrusters, rcs);
	}

	public static void tick(Minecraft client) {
		if (client.level == null || client.player == null || client.isPaused()) return;
		for (ShipEntity ship : ShipCollisions.shipsIn(client.level)) {
			if (ship.distanceToSqr(client.player) > RANGE_SQ) continue;
			Firing firing = firing(ship);
			if (firing.mains() > 0.01f) ensure(client, ENGINES, ship, ModSounds.ENGINE_LOOP, true);
			if (firing.thrusters() > 0.01f) ensure(client, THRUSTERS, ship, ModSounds.THRUSTER_LOOP, false);
			puffs(client, ship, firing.rcs());
		}
		ENGINES.values().removeIf(Loop::isStopped);
		THRUSTERS.values().removeIf(Loop::isStopped);
		FIRING_RCS.keySet().removeIf(id -> client.level.getEntity(id) == null);

		ShipEntity mine = client.player.getVehicle() instanceof ShipEntity s ? s : ShipLocalMotion.activeShip();
		if (mine != null && mine.isQuantumTravelling() && (travel == null || travel.isStopped())) {
			travel = new Loop(mine, ModSounds.QUANTUM_TRAVEL_LOOP, Loop.Kind.QUANTUM);
			client.getSoundManager().play(travel);
		}
		alarms(client);
	}

	private static void ensure(Minecraft client, Map<Integer, Loop> loops, ShipEntity ship, SoundEvent event, boolean mains) {
		Loop loop = loops.get(ship.getId());
		if (loop == null || loop.isStopped()) {
			loop = new Loop(ship, event, mains ? Loop.Kind.MAINS : Loop.Kind.THRUSTERS);
			loops.put(ship.getId(), loop);
			client.getSoundManager().play(loop);
		}
	}

	/** A puff for each maneuvering thruster that just started firing (a few per tick at most). */
	private static void puffs(Minecraft client, ShipEntity ship, Set<Integer> rcs) {
		Set<Integer> before = FIRING_RCS.getOrDefault(ship.getId(), Set.of());
		int played = 0;
		ShipStructure structure = ship.structure();
		for (int index : rcs) {
			if (before.contains(index) || structure == null || played >= 3) continue;
			var entry = structure.entries().get(index);
			Direction nozzle = entry.state().getValue(ThrusterBlock.FACING);
			Vec3 at = ship.toWorld(Vec3.atCenterOf(entry.pos()).add(nozzle.getStepX() * 0.6, nozzle.getStepY() * 0.6, nozzle.getStepZ() * 0.6));
			client.getSoundManager().play(new SimpleSoundInstance(ModSounds.RCS_PUFF, SoundSource.NEUTRAL, 0.5f,
					0.9f + client.level.getRandom().nextFloat() * 0.25f, RandomSource.create(), at.x, at.y, at.z));
			played++;
		}
		FIRING_RCS.put(ship.getId(), rcs);
	}

	/** Pilot-only warning tones, one beep a second while a warning holds. */
	private static void alarms(Minecraft client) {
		if (alarmCooldown > 0) {
			alarmCooldown--;
			return;
		}
		ShipEntity ship = FlightHud.pilotedShip();
		if (ship == null) return;
		boolean lowFuel = ship.fuelCapacity() > 0 && ship.fuel() < ship.fuelCapacity() * 0.15;
		boolean overheat = ship.thermalFactor() < 0.999f;
		boolean shieldsDown = ship.shieldMax() > 0 && ship.shield() < 1;
		boolean gearWarning = false;
		if (ship.gearCount() > 0 && !ship.isGearDown() && !SpaceTravel.isSpace(ship.level())) {
			double agl = FlightHud.heightAboveGround(ship);
			gearWarning = agl >= 0 && agl < 15 && ship.recentVelocity().y < -0.02;
		}
		if (lowFuel || overheat || shieldsDown || gearWarning) {
			client.getSoundManager().play(SimpleSoundInstance.forUI(ModSounds.ALARM, gearWarning ? 1.25f : 1.0f, 0.45f));
			alarmCooldown = 20;
		}
	}

	/** A looping sound that follows a ship and tracks its throttle (or its quantum jump). */
	static final class Loop extends AbstractTickableSoundInstance {
		enum Kind { MAINS, THRUSTERS, QUANTUM }

		private final ShipEntity ship;
		private final Kind kind;
		private int silentTicks;

		Loop(ShipEntity ship, SoundEvent event, Kind kind) {
			super(event, SoundSource.NEUTRAL, RandomSource.create());
			this.ship = ship;
			this.kind = kind;
			this.looping = true;
			this.delay = 0;
			this.volume = 0.01f;
			if (kind == Kind.QUANTUM) {
				this.relative = true;
				this.attenuation = SoundInstance.Attenuation.NONE;
			} else {
				this.x = ship.getX();
				this.y = ship.getY();
				this.z = ship.getZ();
			}
		}

		@Override
		public void tick() {
			Minecraft client = Minecraft.getInstance();
			if (ship.isRemoved() || client.player == null || ship.distanceToSqr(client.player) > RANGE_SQ) {
				stop();
				return;
			}
			float target;
			if (kind == Kind.QUANTUM) {
				target = ship.isQuantumTravelling() ? 0.8f : 0f;
				pitch = 1.0f;
			} else {
				Firing firing = firing(ship);
				float level = kind == Kind.MAINS ? firing.mains() : firing.thrusters();
				// Volume above 1 also stretches the hearing range (16 blocks per unit).
				target = level <= 0.01f ? 0 : Math.min(kind == Kind.MAINS ? 2.5f : 1.2f, 0.25f + level * 0.5f);
				pitch = 0.75f + 0.35f * Mth.clamp(level / (kind == Kind.MAINS ? 2f : 4f), 0, 1);
				x = ship.getX();
				y = ship.getY();
				z = ship.getZ();
			}
			volume += (target - volume) * 0.15f; // smooth spool up/down
			silentTicks = target <= 0 && volume < 0.02f ? silentTicks + 1 : 0;
			if (silentTicks > 20) stop();
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}
	}
}
