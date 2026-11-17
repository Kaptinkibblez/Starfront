package com.starwake.ship;

import com.starwake.ship.data.ShipPhysics;
import com.starwake.ship.systems.ShipSystems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * A ship's quantum drive (in-system, 3g) and jump drive (between stars, M6), split out of {@link ShipEntity} in M10 and
 * run as <b>one flow</b>:
 * <ol>
 * <li>Pick a destination on a map: a planet, moon or field (Starmap app, console routes) or a star (galaxy map). That
 *     arms the drive it needs. With nothing picked, the quantum drive targets whatever destination the nose is on.</li>
 * <li>B (tap) arms or disarms the drive for the destination; the drive spools on the quantum pips.</li>
 * <li>Fly the nose onto the target marker; B (hold) engages once spooled, aligned and fuelled (and, for a jump, in
 *     range and clear of gravity wells).</li>
 * </ol>
 * Both drives burn fusion units and hold the hull still while the trip plays out; the HUD shows one drive panel.
 */
public final class ShipDrives {
	private final ShipEntity ship;

	ShipDrives(ShipEntity ship) {
		this.ship = ship;
	}

	// --- One flow (M10) ----------------------------------------------------------------------

	/** True if the last destination picked is another star system (the jump drive's job). */
	private boolean destinationIsStar;

	/** True while either drive is armed or travelling. */
	public boolean busy() {
		return quantumMode || jumpMode || jump != null || jumpTicks >= 0;
	}

	/** In a jump tunnel or quantum travel: the hull holds still. */
	public boolean travelling() {
		return jump != null || jumpTicks >= 0;
	}

	/** B tapped: arm the drive the destination needs, or stand down whichever is armed. */
	public void toggle() {
		if (travelling()) return;
		if (quantumMode) {
			exitQuantumMode(Component.translatable("message.starwake.quantum.off"));
		} else if (jumpMode) {
			toggleJumpMode();
		} else if (destinationIsStar && jumpTarget != null) {
			toggleJumpMode();
		} else {
			Component error = enterQuantumMode(null);
			if (ship.pilot() instanceof ServerPlayer player) {
				player.sendOverlayMessage(error != null ? error : Component.translatable("message.starwake.quantum.on"));
			}
		}
	}

	/** B held: engage whichever drive is armed. */
	public void engage() {
		if (jumpMode) engageJump();
		else if (quantumMode) engageQuantum();
		else if (ship.pilot() instanceof ServerPlayer player) player.sendOverlayMessage(Component.translatable("message.starwake.drive.not_armed"));
	}

	/** Server tick while not travelling: spool and evaluate whichever drive is armed. */
	void tick() {
		tickQuantum();
		tickJumpMode();
	}

	/** Spooling right now (for heat). */
	boolean spooling() {
		return quantumMode && quantumSpool < 1 || jumpMode && jumpSpool < 1;
	}

	/** Quantum drive (3g). Mode, target and spool aren't saved; an unfinished jump is resumed. */
	private boolean quantumMode;
	private @Nullable ResourceKey<com.starwake.planet.Planet> quantumTarget;
	double quantumSpool;
	@Nullable QuantumJump jump;
	/** Jump drive (M6): armed, selected system, spool, and a jump in progress (ticks left / total). */
	boolean jumpMode;
	@Nullable String jumpTarget;
	double jumpSpool;
	int jumpTicks = -1, jumpTotal;
	Vec3 jumpDirection = Vec3.ZERO;

	// =========================================================================================
	// Quantum drive (3g, Star Citizen style)
	// =========================================================================================
	//
	// 1. Quantum mode (B, or a Starmap/console pick) arms the drive and starts the spool.
	// 2. The target is the destination nearest the ship's nose (or the course set on the
	//    console). Its marker shows on the HUD; the pilot flies the nose onto it.
	// 3. Engage (hold B) once spooled, aligned, unobstructed and fuelled: the ship accelerates
	//    to quantum speed along the line to the target, cruises, and drops out just outside its
	//    atmosphere. Long trips take minutes. Nothing can steer or stop it once committed.

	/**
	 * A jump in progress: {@code from}/{@code to} in system coordinates (the moving orbits), {@code arrival} the physical
	 * drop-out point in the target's neighbourhood.
	 */
	record QuantumJump(ResourceKey<com.starwake.planet.Planet> target, Vec3 from, Vec3 to, Vec3 arrival, int elapsed) {
		double distance() { return from.distanceTo(to); }
	}

	/** Arms the quantum drive (and optionally selects a target). Returns why not, or null. */
	public @Nullable Component enterQuantumMode(@Nullable ResourceKey<com.starwake.planet.Planet> target) {
		if (!SpaceTravel.isSpace(ship.level())) return Component.translatable("message.starwake.course.not_in_space");
		if (!ship.systems().hasQuantumDrive()) return Component.translatable("message.starwake.quantum.no_drive");
		if (jumpTicks >= 0) return Component.translatable("message.starwake.jump.in_tunnel");
		if (jumpMode) {
			jumpMode = false;
			ship.getEntityData().set(ShipEntity.JD_STATE, 0);
			ship.getEntityData().set(ShipEntity.JD_SPOOL, -1f);
		}
		if (jump != null) return null;
		if (!quantumMode) {
			quantumSpool = 0;
			if (ship.level() instanceof ServerLevel serverLevel) {
				serverLevel.playSound(null, BlockPos.containing(ship.position()), com.starwake.registry.ModSounds.QUANTUM_SPOOL,
						net.minecraft.sounds.SoundSource.NEUTRAL, 1.2f, 1.0f);
			}
		}
		quantumMode = true;
		if (target != null) {
			quantumTarget = target;
			destinationIsStar = false; // a map pick in this system
		}
		tickQuantum();
		return null;
	}

	public void exitQuantumMode(@Nullable Component reason) {
		if (jump != null) return; // committed
		quantumMode = false;
		quantumTarget = null;
		quantumSpool = 0;
		ship.getEntityData().set(ShipEntity.QUANTUM, -1f);
		ship.getEntityData().set(ShipEntity.QT_STATE, 0);
		ship.getEntityData().set(ShipEntity.QT_TARGET, "");
		if (reason != null && ship.pilot() instanceof ServerPlayer player) player.sendOverlayMessage(reason);
	}

	/** Pilot pressed B: toggle quantum mode. */
	public void toggleQuantumMode() {
		if (jump != null) return;
		if (quantumMode) {
			exitQuantumMode(Component.translatable("message.starwake.quantum.off"));
		} else {
			Component error = enterQuantumMode(null);
			if (ship.pilot() instanceof ServerPlayer player) {
				player.sendOverlayMessage(error != null ? error : Component.translatable("message.starwake.quantum.on"));
			}
		}
	}

	/** Spools, auto-targets and evaluates the route every tick while in quantum mode. */
	void tickQuantum() {
		if (jump != null || !quantumMode) return;
		if (!SpaceTravel.isSpace(ship.level()) || ship.pilot() == null || !ship.systems().hasQuantumDrive()) {
			exitQuantumMode(Component.translatable("message.starwake.quantum.aborted"));
			return;
		}
		ShipPhysics physics = ShipPhysics.get();
		var access = ship.level().registryAccess();
		Vec3 nose = ship.worldDirection(ship.forward());

		// Auto-target whatever destination marker the nose is on (within 10 degrees).
		double bestAngle = Math.toRadians(10);
		for (var body : com.starwake.galaxy.SolarSystem.bodies(ship.level())) {
			if (!body.enterable()) continue;
			Vec3 to = body.at(com.starwake.galaxy.SolarSystem.time(ship.level()))
					.subtract(com.starwake.galaxy.SolarSystem.toSystem(ship.level(), ship.position()));
			double angle = Math.acos(Mth.clamp(nose.dot(to.normalize()), -1, 1));
			if (angle < bestAngle) {
				bestAngle = angle;
				quantumTarget = body.key();
			}
		}

		double rate = ship.power.factor(ShipSystems.Subsystem.QUANTUM) / Math.max(1.0, ship.systems().quantumSpool() * 20.0);
		boolean wasSpooled = quantumSpool >= 1;
		quantumSpool = Math.min(1.0, quantumSpool + rate);
		if (!wasSpooled && quantumSpool >= 1 && ship.level() instanceof ServerLevel serverLevel) {
			serverLevel.playSound(null, BlockPos.containing(ship.position()), net.minecraft.sounds.SoundEvents.BEACON_POWER_SELECT,
					net.minecraft.sounds.SoundSource.NEUTRAL, 1.0f, 1.4f);
		}
		ship.getEntityData().set(ShipEntity.QUANTUM, (float) quantumSpool);

		int state = ShipEntity.QT_MODE | (quantumSpool >= 1 ? ShipEntity.QT_SPOOLED : 0);
		var target = quantumTarget == null ? null : com.starwake.galaxy.SolarSystem.get(access, quantumTarget).orElse(null);
		if (target == null) {
			state |= ShipEntity.QT_NO_TARGET;
			ship.getEntityData().set(ShipEntity.QT_TARGET, "");
			ship.getEntityData().set(ShipEntity.QT_INFO, 0);
		} else {
			SpaceTravel.Route route = SpaceTravel.route(ship, target);
			Vec3 dir = route.direction();
			if (Math.acos(Mth.clamp(nose.dot(dir), -1, 1)) < Math.toRadians(physics.quantumAlignDegrees())) state |= ShipEntity.QT_ALIGNED;
			if (route.obstruction() != null) state |= ShipEntity.QT_OBSTRUCTED;
			if (route.tooClose()) state |= ShipEntity.QT_TOO_CLOSE;
			if (ship.fusion() < route.fuel()) state |= ShipEntity.QT_NO_FUEL;
			ship.getEntityData().set(ShipEntity.QT_TARGET, target.key().identifier().toString());
			ship.getEntityData().set(ShipEntity.QT_INFO, route.fuel());
		}
		ship.getEntityData().set(ShipEntity.QT_STATE, state);
	}

	/** Pilot held B: jump if everything checks out, else say what's missing. */
	public void engageQuantum() {
		if (jump != null || !quantumMode || !(ship.pilot() instanceof ServerPlayer player)) return;
		tickQuantum();
		int state = ship.quantumState();
		String problem = (state & ShipEntity.QT_NO_TARGET) != 0 ? "no_target"
				: (state & ShipEntity.QT_SPOOLED) == 0 ? "not_spooled"
				: (state & ShipEntity.QT_TOO_CLOSE) != 0 ? "too_close"
				: (state & ShipEntity.QT_OBSTRUCTED) != 0 ? "obstructed"
				: (state & ShipEntity.QT_NO_FUEL) != 0 ? "no_fuel"
				: (state & ShipEntity.QT_ALIGNED) == 0 ? "not_aligned" : null;
		if (problem != null) {
			player.sendOverlayMessage(Component.translatable("message.starwake.quantum." + problem));
			return;
		}
		var target = com.starwake.galaxy.SolarSystem.get(ship.level().registryAccess(), quantumTarget).orElseThrow();
		SpaceTravel.Route route = SpaceTravel.route(ship, target);
		ship.useFusion(route.fuel());
		jump = new QuantumJump(target.key(), route.from(), route.arrivalSystem(), route.arrival(), 0);
		quantumMode = false;
		ship.getEntityData().set(ShipEntity.QUANTUM, -1f);
		ship.getEntityData().set(ShipEntity.QT_STATE, ShipEntity.QT_TRAVEL);
		ship.getEntityData().set(ShipEntity.QT_INFO, (int) Math.ceil(route.ticks()));
		ship.setDeltaMovement(Vec3.ZERO);
		if (ship.level() instanceof ServerLevel serverLevel) {
			serverLevel.playSound(null, BlockPos.containing(ship.position()), com.starwake.registry.ModSounds.QUANTUM_ENGAGE,
					net.minecraft.sounds.SoundSource.NEUTRAL, 2.0f, 0.5f);
		}
		player.sendOverlayMessage(Component.translatable("message.starwake.quantum.engaged", target.displayName(),
				com.starwake.galaxy.SolarSystem.formatTime(route.ticks())));
	}

	/** Advances a jump in progress; at the end, moves the ship to the drop-out point. */
	void tickJump(ShipPhysics physics) {
		QuantumJump j = jump;
		jump = new QuantumJump(j.target(), j.from(), j.to(), j.arrival(), j.elapsed() + 1);
		double distance = j.distance();
		double total = com.starwake.galaxy.SolarSystem.travelTicks(distance, physics.quantumSpeed() * com.starwake.config.StarwakeConfig.get(com.starwake.config.StarwakeConfig.QUANTUM_SPEED), physics.quantumRamp());
		double travelled = com.starwake.galaxy.SolarSystem.travelled(distance, physics.quantumSpeed() * com.starwake.config.StarwakeConfig.get(com.starwake.config.StarwakeConfig.QUANTUM_SPEED), physics.quantumRamp(), jump.elapsed());
		// The hull holds still; the offset says where it really is now, relative to its own system position.
		Vec3 offset = j.to().subtract(j.from()).normalize().scale(travelled).add(j.from())
				.subtract(com.starwake.galaxy.SolarSystem.toSystem(ship.level(), ship.position()));
		ship.getEntityData().set(ShipEntity.QT_OFFSET, new Vector3f((float) offset.x, (float) offset.y, (float) offset.z));
		ship.getEntityData().set(ShipEntity.QT_INFO, (int) Math.ceil(total - jump.elapsed()));
		if (ship.level() instanceof ServerLevel serverLevel && (int) Math.ceil(total) - jump.elapsed() == 100) {
			// 5 s out: start loading the drop-out point so the ship arrives into ready chunks.
			serverLevel.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PORTAL,
					net.minecraft.world.level.ChunkPos.containing(BlockPos.containing(j.arrival())), 3);
		}
		if (jump.elapsed() < total) return;

		// Drop out.
		jump = null;
		ship.getEntityData().set(ShipEntity.QT_STATE, 0);
		ship.getEntityData().set(ShipEntity.QT_TARGET, "");
		ship.getEntityData().set(ShipEntity.QT_OFFSET, new Vector3f());
		Vec3 arrival = j.arrival();
		com.starwake.galaxy.SolarSystem.get(ship.level().registryAccess(), j.target()).ifPresent(t -> ship.setOrbiting(t.dimension()));
		SpaceTravel.jump(ship, arrival);
		ship.setDeltaMovement(j.to().subtract(j.from()).normalize().scale(0.6)); // a little momentum out of the jump
		if (ship.level() instanceof ServerLevel serverLevel) {
			serverLevel.playSound(null, BlockPos.containing(ship.position()), com.starwake.registry.ModSounds.QUANTUM_EXIT,
					net.minecraft.sounds.SoundSource.NEUTRAL, 2.0f, 0.7f);
		}
		if (ship.pilot() instanceof ServerPlayer player) {
			player.sendOverlayMessage(Component.translatable("message.starwake.quantum.arrived",
					SpaceTravel.planetName(ship.level(), ship.orbiting())));
		}
	}

	// =========================================================================================
	// Jump drive (M6): between star systems
	// =========================================================================================
	//
	// 1. Pick a star on the galaxy map ({@link #setJumpTarget}); that arms the drive (jump mode); B taps arm/disarm.
	// 2. The drive spools on the quantum share of ship.power. The HUD shows the target star (it is
	//    really in the sky, in its true direction) and what's missing: range, fuel, distance
	//    from gravity wells, alignment.
	// 3. Hold B to engage: fuel is burned, the hull holds still in the tunnel for a time that
	//    grows with distance, then the ship and everyone aboard arrive in the target system's
	//    space, on the side facing where they came from, pointed at the star.

	/** Selects the jump target (from the galaxy map; pilot only). Returns why not, or null. */
	public @Nullable Component setJumpTarget(String system) {
		if (!ship.systems().hasJumpDrive()) return Component.translatable("message.starwake.jump.no_drive");
		if (com.starwake.galaxy.Galaxy.star(system).isEmpty()) return Component.translatable("message.starwake.jump.unknown");
		if (system.equals(com.starwake.galaxy.SolarSystem.systemOf(ship.level()))) return Component.translatable("message.starwake.jump.here");
		jumpTarget = system;
		destinationIsStar = true;
		if (!jumpMode) toggleJumpMode();
		return null;
	}

	/** Pilot tapped J: arm or disarm the jump drive. */
	public void toggleJumpMode() {
		if (jumpTicks >= 0) return;
		Player pilot = ship.pilot();
		if (jumpMode) {
			jumpMode = false;
			jumpSpool = 0;
			ship.getEntityData().set(ShipEntity.JD_STATE, 0);
			ship.getEntityData().set(ShipEntity.JD_SPOOL, -1f);
			if (pilot instanceof ServerPlayer p) p.sendOverlayMessage(Component.translatable("message.starwake.jump.off"));
			return;
		}
		Component error = !SpaceTravel.isSpace(ship.level()) ? Component.translatable("message.starwake.course.not_in_space")
				: !ship.systems().hasJumpDrive() ? Component.translatable("message.starwake.jump.no_drive") : null;
		if (error == null && quantumMode) exitQuantumMode(null);
		if (error == null) {
			jumpMode = true;
			jumpSpool = 0;
		}
		if (pilot instanceof ServerPlayer p) p.sendOverlayMessage(error != null ? error : Component.translatable("message.starwake.jump.on"));
	}

	/** Spools and evaluates the jump every tick while armed. */
	void tickJumpMode() {
		if (!jumpMode || jumpTicks >= 0) return;
		if (!SpaceTravel.isSpace(ship.level()) || !ship.systems().hasJumpDrive()) {
			jumpMode = false;
			ship.getEntityData().set(ShipEntity.JD_STATE, 0);
			ship.getEntityData().set(ShipEntity.JD_SPOOL, -1f);
			return;
		}
		var tuning = com.starwake.ship.data.JumpTuning.get();
		double rate = ship.power.factor(ShipSystems.Subsystem.QUANTUM) / Math.max(1.0, ship.systems().jumpSpool() * 20.0);
		boolean wasSpooled = jumpSpool >= 1;
		jumpSpool = Math.min(1.0, jumpSpool + rate);
		if (!wasSpooled && jumpSpool >= 1 && ship.level() instanceof ServerLevel serverLevel) {
			serverLevel.playSound(null, BlockPos.containing(ship.position()), com.starwake.registry.ModSounds.QUANTUM_SPOOL,
					net.minecraft.sounds.SoundSource.NEUTRAL, 1.5f, 0.7f);
		}
		ship.getEntityData().set(ShipEntity.JD_SPOOL, (float) jumpSpool);
		int state = ShipEntity.JD_MODE | (jumpSpool >= 1 ? ShipEntity.JD_SPOOLED : 0);
		var here = com.starwake.galaxy.Galaxy.star(com.starwake.galaxy.SolarSystem.systemOf(ship.level())).orElse(null);
		var there = jumpTarget == null ? null : com.starwake.galaxy.Galaxy.star(jumpTarget).orElse(null);
		if (here == null || there == null) {
			state |= ShipEntity.JD_NO_TARGET;
			ship.getEntityData().set(ShipEntity.JD_TARGET, "");
			ship.getEntityData().set(ShipEntity.JD_INFO, 0);
		} else {
			double ly = here.distanceTo(there);
			Vec3 dir = there.position().subtract(here.position()).normalize();
			Vec3 nose = ship.worldDirection(ship.forward());
			if (Math.acos(Mth.clamp(nose.dot(dir), -1, 1)) < Math.toRadians(tuning.alignDegrees())) state |= ShipEntity.JD_ALIGNED;
			if (ly > ship.systems().jumpRange()) state |= ShipEntity.JD_RANGE;
			int fuel = tuning.fuel(ly);
			if (ship.fusion() < fuel) state |= ShipEntity.JD_NO_FUEL;
			for (var body : com.starwake.galaxy.SolarSystem.bodies(ship.level())) {
				double clearance = body.body().star() ? body.body().radius() * tuning.starWell() : body.body().entryRadius() * tuning.planetWell();
				if (ship.position().distanceTo(body.body().position()) < clearance) state |= ShipEntity.JD_WELL;
			}
			ship.getEntityData().set(ShipEntity.JD_TARGET, there.id());
			ship.getEntityData().set(ShipEntity.JD_INFO, fuel);
		}
		ship.getEntityData().set(ShipEntity.JD_STATE, state);
	}

	/** Pilot held J: jump if everything is ready, else say what's missing. */
	public void engageJump() {
		if (!jumpMode || jumpTicks >= 0 || !(ship.pilot() instanceof ServerPlayer player)) return;
		int state = ship.jumpState();
		String problem = (state & ShipEntity.JD_NO_TARGET) != 0 ? "no_target"
				: (state & ShipEntity.JD_RANGE) != 0 ? "out_of_range"
				: (state & ShipEntity.JD_SPOOLED) == 0 ? "not_spooled"
				: (state & ShipEntity.JD_WELL) != 0 ? "gravity_well"
				: (state & ShipEntity.JD_NO_FUEL) != 0 ? "no_fuel"
				: (state & ShipEntity.JD_ALIGNED) == 0 ? "not_aligned" : null;
		if (problem != null) {
			player.sendOverlayMessage(Component.translatable("message.starwake.jump." + problem));
			return;
		}
		var here = com.starwake.galaxy.Galaxy.star(com.starwake.galaxy.SolarSystem.systemOf(ship.level())).orElseThrow();
		var there = com.starwake.galaxy.Galaxy.star(jumpTarget).orElseThrow();
		double ly = here.distanceTo(there);
		var tuning = com.starwake.ship.data.JumpTuning.get();
		ship.useFusion(tuning.fuel(ly));
		jumpDirection = there.position().subtract(here.position()).normalize();
		jumpTotal = tuning.ticks(ly);
		jumpTicks = jumpTotal;
		jumpMode = false;
		ship.getEntityData().set(ShipEntity.JD_SPOOL, -1f);
		ship.getEntityData().set(ShipEntity.JD_STATE, ShipEntity.JD_TRAVEL);
		ship.getEntityData().set(ShipEntity.JD_INFO, jumpTicks);
		if (ship.level() instanceof ServerLevel serverLevel) {
			serverLevel.playSound(null, BlockPos.containing(ship.position()), com.starwake.registry.ModSounds.QUANTUM_ENGAGE,
					net.minecraft.sounds.SoundSource.NEUTRAL, 2.5f, 0.35f);
		}
		player.sendOverlayMessage(Component.translatable("message.starwake.jump.engaged", there.name(),
				String.format("%.1f", ly), com.starwake.galaxy.SolarSystem.formatTime(jumpTotal)));
	}

	/** One tick in the jump tunnel; at the end, arrive in the target system. True if transferred. */
	boolean tickJumpTravel() {
		jumpTicks--;
		ship.getEntityData().set(ShipEntity.JD_INFO, Math.max(0, jumpTicks));
		if (!(ship.level() instanceof ServerLevel serverLevel)) return false;
		var server = serverLevel.getServer();
		if (jumpTicks == 100 || jumpTicks <= 0) {
			// Make the target system real (opening it is idempotent) and load the arrival point.
			com.starwake.galaxy.StarSystems.open(server, jumpTarget);
		}
		var space = server.getLevel(com.starwake.galaxy.SolarSystem.spaceOf(jumpTarget));
		if (jumpTicks == 100 && space != null) {
			space.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PORTAL,
					net.minecraft.world.level.ChunkPos.containing(BlockPos.containing(jumpArrival(server))), 3);
		}
		if (jumpTicks > 0) return false;
		jumpTicks = -1;
		ship.getEntityData().set(ShipEntity.JD_STATE, 0);
		ship.getEntityData().set(ShipEntity.JD_TARGET, "");
		if (space == null) return false;
		Vec3 arrival = jumpArrival(server);
		String name = com.starwake.galaxy.Galaxy.star(jumpTarget).map(com.starwake.galaxy.Galaxy.Star::name).orElse(jumpTarget);
		Player pilot = ship.pilot();
		jumpTarget = null;
		boolean moved = SpaceTravel.transfer(ship, space, arrival, new org.joml.Quaternionf());
		if (pilot instanceof ServerPlayer player) {
			player.sendOverlayMessage(Component.translatable("message.starwake.jump.arrived", name));
		}
		space.playSound(null, BlockPos.containing(arrival), com.starwake.registry.ModSounds.QUANTUM_EXIT,
				net.minecraft.sounds.SoundSource.NEUTRAL, 2.5f, 0.5f);
		return moved;
	}

	/** Where a jump drops out: on the near side of the target star, at its habitable-zone distance. */
	private Vec3 jumpArrival(net.minecraft.server.MinecraftServer server) {
		var star = com.starwake.galaxy.Galaxy.star(jumpTarget).orElse(null);
		double luminosity = star == null ? 1 : star.luminosity() + star.companion().map(com.starwake.galaxy.Galaxy.Companion::luminosity).orElse(0.0);
		double starRadius = star == null ? 40_000 : star.radius() * com.starwake.galaxy.StarSystems.SOLAR_RADIUS;
		double distance = Math.clamp(com.starwake.ship.data.JumpTuning.get().arrival() * Math.sqrt(luminosity), starRadius * 40, 20_000_000);
		Vec3 back = new Vec3(-jumpDirection.x, 0, -jumpDirection.z);
		if (back.lengthSqr() < 1.0e-6) back = new Vec3(1, 0, 0);
		return back.normalize().scale(distance).add(0, Math.clamp(-jumpDirection.y * 200, -300, 300), 0);
	}
}
