package com.starwake.ship;

import com.starwake.planet.Planets;
import com.starwake.ship.data.ShipPhysics;
import com.starwake.ship.physics.Units;
import com.starwake.ship.systems.ShipDynamics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * The flight computer of one ship (3h, rebuilt in M10 in real units), split out of {@link ShipEntity}.
 *
 * <p>The pilot asks for motion (WASD/Space/Ctrl, hover) and rotation (mouse-aim or stick, Z/C roll). That becomes a
 * wanted force (kN) and torque (kN m) in the ship's frame; {@link ShipDynamics#allocate} picks a throttle for every
 * thruster; the force and torque they actually produce, from where they actually are, accelerate the ship
 * (F / m, in m/s²). Nothing caps speed. In coupled flight the computer holds the pilot's <b>speed limit</b>:
 * SCM (combat, up to {@link ShipPhysics#scmSpeed}) or NAV (cruise, up to {@link ShipPhysics#navSpeed}); it brakes
 * uncommanded axes, cancels gravity and eases a controlled descent. Decoupled flight is Newtonian: no braking, no
 * hover, no limit (gravity and air drag still act).
 *
 * <p>Runs identically on the server and on the pilot's predicting client; every input arrives through the
 * {@link Input}, the control bits and the speed limit of {@link com.starwake.network.ShipInputPayload}.
 */
public final class ShipFlight {
	/** Coupled descent (M9): up to 25 m/s high up, easing to 3 m/s in the last 15 m (blocks/tick). */
	private static final double DESCENT_FAST = 1.25, DESCENT_SLOW = 0.15;
	/** SCM boost lifts the speed limit by this factor. */
	private static final double BOOST_LIMIT = 1.25;
	/** Seconds of idle hover kept in reserve: below it an unpiloted ship lands itself if the ground is near. */
	private static final int HOVER_RESERVE_TICKS = 1200;
	/** The default speed limit, m/s, until the pilot sets one. */
	public static final float DEFAULT_LIMIT = 150;

	/** Logs requests and results twice a second on the server ({@code -Dstarwake.flightDebug=true}). */
	private static final boolean DEBUG = Boolean.getBoolean("starwake.flightDebug");

	private static String fmt(double v) {
		return String.format("%.0f", v);
	}

	private final ShipEntity ship;
	/** Height of the hull's bottom above the ground below the ship's centre (blocks; 64 = nothing in range). */
	private double clearance = 64;
	/** Whether the pilot holds boost this step. */
	private boolean boosting;
	/** The pilot's speed limit (m/s) and mode from the latest input. */
	private float speedLimit = DEFAULT_LIMIT;
	private boolean nav;

	ShipFlight(ShipEntity ship) {
		this.ship = ship;
	}

	public double clearance() {
		return clearance;
	}

	public boolean boosting() {
		return boosting;
	}

	public boolean nav() {
		return nav;
	}

	/** The pilot's requested limit, m/s (before the mode's ceiling). */
	public float requestedLimit() {
		return speedLimit;
	}

	void setLimiter(float limit, boolean nav) {
		this.speedLimit = Float.isFinite(limit) ? Math.max(1, limit) : DEFAULT_LIMIT;
		this.nav = nav;
	}

	/** The ceiling of the current mode where the ship is (m/s). */
	public double ceiling(ShipPhysics physics, boolean space) {
		return nav ? physics.navSpeed(space) : physics.scmSpeed();
	}

	/** The speed the flight computer holds right now (m/s): the pilot's limit within the mode's ceiling, boosted in SCM. */
	public double limit(ShipPhysics physics, boolean space) {
		double limit = Math.min(speedLimit, ceiling(physics, space));
		return !nav && boosting ? limit * BOOST_LIMIT : limit;
	}

	/** Planet gravity on the ship (blocks/tick²); ships are weightless in space. */
	private double gravity(boolean space) {
		return space ? 0 : Units.gravity(Planets.gravityOf(ship.level()));
	}

	/** Air drag (blocks/tick², world frame) at this velocity: 0.5 rho Cd A v² / m over the hull's silhouette. */
	Vec3 drag(Vec3 velocity, ShipPhysics physics, boolean space) {
		double density = space ? 0 : Planets.airDensityOf(ship.level());
		if (density <= 0) return Vec3.ZERO;
		Quaternionfc q = ship.orientation();
		Vector3f v = q.transformInverse(new Vector3f((float) velocity.x, (float) velocity.y, (float) velocity.z));
		Vector3d d = ship.dynamics().drag(new Vector3d(v.x, v.y, v.z), density, 0.5 * Units.AIR * physics.dragCoefficient() / 1000);
		Vector3f w = q.transform(new Vector3f((float) d.x, (float) d.y, (float) d.z));
		return new Vec3(w.x, w.y, w.z);
	}

	/** Pilot input -> thruster throttles -> new velocity (blocks/tick). Updates the ship's angular velocity too. */
	Vec3 fly(Player pilot, Vec3 velocity, ShipPhysics physics, Input input) {
		ShipDynamics dynamics = ship.dynamics();
		double mass = ship.mass();
		boolean decoupled = ship.isDecoupled();
		boolean space = SpaceTravel.isSpace(ship.level());
		double gravity = gravity(space);
		boosting = input.shift();
		// Power routing and boost scale every thruster; no fuel, no thrust.
		double scale = ship.fuel() > 0 ? ship.engineFactor() * (boosting ? physics.boostMultiplier() : 1.0) : 0;
		Quaternionfc q = ship.orientation();
		Direction forward = ship.forward();
		Vector3f vf = q.transformInverse(new Vector3f((float) velocity.x, (float) velocity.y, (float) velocity.z));
		Vector3d local = new Vector3d(vf.x, vf.y, vf.z);
		double perVelocity = Units.forceFor(mass, physics.coupledBraking()); // kN per block/tick of drift
		double limit = Units.perTick(limit(physics, space));

		// Wanted push along each commanded axis: everything it has, except that in coupled flight the push eases off
		// as that axis approaches the speed limit and holds it there.
		Vector3d force = new Vector3d();
		Direction left = forward.getCounterClockWise();
		ask(dynamics, force, local, forward, input.forward(), scale, decoupled, limit, mass);
		ask(dynamics, force, local, forward.getOpposite(), input.backward(), scale, decoupled, limit, mass);
		ask(dynamics, force, local, left, input.left(), scale, decoupled, limit, mass);
		ask(dynamics, force, local, left.getOpposite(), input.right(), scale, decoupled, limit, mass);
		ask(dynamics, force, local, Direction.UP, input.jump(), scale, decoupled, limit, mass);
		// Ctrl: coupled = controlled descent (below); decoupled (or weightless) = push down.
		ask(dynamics, force, local, Direction.DOWN, input.sprint() && (decoupled || gravity <= 0), scale, decoupled, limit, mass);

		boolean hovering = !decoupled && !input.jump() && gravity > 0;
		double targetClimb = input.sprint() && !decoupled ? -descentRate() : 0;
		if (!decoupled) {
			// Coupled (Star Citizen's IFCS): on every axis the pilot isn't commanding, fire the opposite thrusters
			// to null the drift. No retros, no braking.
			Direction right = forward.getClockWise();
			Vector3d fwdAxis = new Vector3d(forward.getStepX(), 0, forward.getStepZ());
			Vector3d rightAxis = new Vector3d(right.getStepX(), 0, right.getStepZ());
			if (!input.forward() && !input.backward()) force.fma(-local.dot(fwdAxis) * perVelocity, fwdAxis);
			if (!input.left() && !input.right()) force.fma(-local.dot(rightAxis) * perVelocity, rightAxis);
			if (!input.sprint() && !input.jump() && !hovering) force.add(0, -local.y * perVelocity, 0);
			// Over the limit overall (a diagonal, a dive, a slower mode just picked): shed the excess along the motion.
			double speed = local.length();
			if (speed > limit && speed > 1.0e-6) force.fma(-(speed - limit) / speed * perVelocity, local);
		}

		// Hover (coupled only): hold the ship's weight whatever its attitude (top priority) and null vertical drift so
		// it holds altitude, or sinks at the descent rate.
		Vector3f upLocal = q.transformInverse(new Vector3f(0, 1, 0));
		double weight = hovering
				? Math.max(0, mass * Units.ACCEL * gravity - (velocity.y - targetClimb) * perVelocity) : 0;

		// Wanted torque: steer the angular velocity toward what the pilot asks for. Landed, the mouse doesn't twist
		// the ship on its gear; steering starts once it lifts off.
		Vector3d wanted = ship.isGrounded() && !input.jump() ? new Vector3d() : wantedRotation(pilot, physics, space);
		Vector3d alpha = wanted.sub(ship.angularVelocity).mul(0.35);
		Vector3d torque = dynamics.inertia().transform(alpha, new Vector3d()).mul(Units.ACCEL);

		boolean client = ship.level().isClientSide();
		double[] th = client || ship.throttles.length != dynamics.thrusterCount() ? new double[dynamics.thrusterCount()] : ship.throttles;
		double[] achieved = dynamics.allocate(new Vector3d(upLocal.x, upLocal.y, upLocal.z), hovering, weight, torque, force, scale, th);
		if (scale > 0 && !client) ship.burnFuel(dynamics.burn(th) * scale);

		if (DEBUG && !client && ship.tickCount % 10 == 0) {
			com.starwake.StarwakeMod.LOGGER.info("[flight] v={} m/s local=({},{},{}) ask=({},{},{}) got=({},{},{}) weight={} mass={}",
					String.format("%.1f", Units.perSecond(velocity.length())), fmt(local.x), fmt(local.y), fmt(local.z),
					fmt(force.x), fmt(force.y), fmt(force.z), fmt(achieved[0]), fmt(achieved[1]), fmt(achieved[2]), fmt(weight), fmt(mass));
		}
		Vector3f f = q.transform(new Vector3f((float) achieved[0], (float) achieved[1], (float) achieved[2]));
		Vec3 accel = new Vec3(f.x, f.y, f.z).scale(1 / (mass * Units.ACCEL)).add(drag(velocity, physics, space)).add(0, -gravity, 0);
		Vector3d spin = dynamics.inverseInertia().transform(new Vector3d(achieved[3], achieved[4], achieved[5])).div(Units.ACCEL);
		ship.angularVelocity.add(spin).mul(space ? 0.998 : 0.985); // slight passive damping; the computer does the braking
		double cap = Math.toRadians(turnRate(physics)) / Units.TPS * 1.25;
		if (ship.angularVelocity.length() > cap) ship.angularVelocity.normalize(cap);
		return velocity.add(accel);
	}

	/** Turn rate in degrees/second for the current mode. */
	private double turnRate(ShipPhysics physics) {
		return physics.turnRateDegrees() * (nav ? physics.navAgility() : 1);
	}

	/**
	 * Adds an "everything along this local direction" request when the key is held. Coupled, the request is trimmed
	 * to what reaches the speed limit this tick along that axis, and becomes a brake once past it.
	 */
	private static void ask(ShipDynamics dynamics, Vector3d force, Vector3d local, Direction direction, boolean held,
			double scale, boolean decoupled, double limit, double mass) {
		if (!held) return;
		double amount = dynamics.rawThrust(direction) * scale;
		if (!decoupled) {
			double along = local.x * direction.getStepX() + local.y * direction.getStepY() + local.z * direction.getStepZ();
			amount = Math.min(amount, Units.forceFor(mass, limit - along));
		}
		force.add(direction.getStepX() * amount, direction.getStepY() * amount, direction.getStepZ() * amount);
	}

	/**
	 * Mouse-aim: the angular velocity (ship frame, rad/tick) that turns the nose toward where the pilot looks, eased
	 * near the target and capped at the turn rate; joystick mode sets rates directly; Z/C roll; in an atmosphere the
	 * ship eases level when not rolling. Free-look holds attitude.
	 */
	private Vector3d wantedRotation(Player pilot, ShipPhysics physics, boolean space) {
		double max = Math.toRadians(turnRate(physics)) / Units.TPS;
		int controls = ship.controls;
		boolean rolling = (controls & (ShipEntity.CONTROL_ROLL_LEFT | ShipEntity.CONTROL_ROLL_RIGHT)) != 0;
		boolean freeLook = (controls & ShipEntity.CONTROL_FREE_LOOK) != 0;
		Quaternionfc q = ship.orientation();
		Direction forward = ship.forward();
		Vector3d nose = new Vector3d(forward.getStepX(), forward.getStepY(), forward.getStepZ());
		Vector3d want = new Vector3d();
		if ((controls & ShipEntity.CONTROL_JOYSTICK) != 0) {
			// Joystick mode: the stick sets yaw/pitch rates directly (yaw about the deck's up, pitch about its right).
			Direction rightDir = forward.getClockWise();
			want.add(0, -ship.stickX * max, 0);
			want.add(new Vector3d(rightDir.getStepX(), 0, rightDir.getStepZ()).mul(-ship.stickY * max));
		} else if (!freeLook) {
			Vec3 lookWorld = !ship.level().isClientSide() && ship.pilotLook != null && ship.tickCount - ship.lastInputTick < 10
					? ship.pilotLook : pilot.getViewVector(1.0f);
			Vector3f l = q.transformInverse(new Vector3f((float) lookWorld.x, (float) lookWorld.y, (float) lookWorld.z));
			Vector3d look = new Vector3d(l.x, l.y, l.z).normalize();
			double angle = Math.acos(Mth.clamp(nose.dot(look), -1, 1));
			if (angle > 1.0e-3) {
				Vector3d axis = nose.cross(look, new Vector3d());
				if (axis.lengthSquared() < 1.0e-8) axis.set(0, 1, 0); // looking straight back
				want.add(axis.normalize().mul(Math.min(angle * 0.2, max)));
			}
		}
		if (rolling) {
			want.add(new Vector3d(nose).mul((controls & ShipEntity.CONTROL_ROLL_RIGHT) != 0 ? max : -max));
		} else if (!space) {
			// Atmospheric auto-level: ease the ship level unless the pilot is rolling on purpose.
			Vector3f up = q.transform(new Vector3f(0, 1, 0));
			Direction right = forward.getClockWise();
			Vector3f r = q.transform(new Vector3f(right.getStepX(), 0, right.getStepZ()));
			double bank = Math.atan2(-r.y, up.y);
			if (Math.abs(bank) > 1.0e-3 && Math.abs(bank) < Math.PI * 0.9) {
				want.add(new Vector3d(nose).mul(Mth.clamp(-bank * 0.1, -max, max)));
			}
		}
		return want;
	}

	/**
	 * Unpiloted ships (M9): in space they brake to a stop and hold. On a planet they stay put if resting on something;
	 * otherwise they hover at an idle burn (a tenth of what holding their weight on thrusters costs); with the tank
	 * nearly dry they let themselves down gently if the ground is within 64 blocks, and with it empty they fall.
	 */
	Vec3 unpiloted(Vec3 velocity, ShipPhysics physics) {
		boolean space = SpaceTravel.isSpace(ship.level());
		double gravity = gravity(space);
		if (gravity <= 0) {
			velocity = velocity.scale(0.8);
			return velocity.lengthSqr() < 1.0e-6 ? Vec3.ZERO : velocity;
		}
		if (ship.isGrounded() || clearance < 0.05) {
			// Parked on something (or just assembled standing on it): gravity and the contacts keep it there.
			return velocity.add(0, -gravity, 0).scale(0.9);
		}
		ShipDynamics dynamics = ship.dynamics();
		double weight = ship.mass() * Units.ACCEL * gravity;
		double lift = dynamics.rawThrust(Direction.UP);
		double idle = lift <= 0 ? 0 : dynamics.burnPushing(Direction.UP) * Math.min(1, weight / lift) * 0.1;
		if (ship.fuel() > 0 && lift > 0) {
			ship.burnFuel(idle * com.starwake.config.StarwakeConfig.get(com.starwake.config.StarwakeConfig.IDLE_BURN));
			double sink = ship.fuel() < idle * HOVER_RESERVE_TICKS && clearance < 64 ? -descentRate() : 0;
			Vec3 held = velocity.scale(0.8);
			return new Vec3(held.x, held.y * 0.5 + sink * 0.5, held.z);
		}
		// Out of fuel: it falls, through the air.
		return velocity.add(0, -gravity, 0).add(drag(velocity, physics, space));
	}

	double descentRate() {
		if (clearance <= 15) return DESCENT_SLOW;
		double t = Math.min(1, (clearance - 15) / 45.0);
		return DESCENT_SLOW + (DESCENT_FAST - DESCENT_SLOW) * t;
	}

	/** Measures {@link #clearance} straight down from the bottom of the bounding box (cheap: one column). */
	void measureClearance() {
		AABB box = ship.getBoundingBox();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int x = Mth.floor(box.getCenter().x), z = Mth.floor(box.getCenter().z);
		int top = Mth.floor(box.minY);
		for (int dy = 0; dy < 64; dy++) {
			p.set(x, top - dy, z);
			if (!ship.level().getBlockState(p).getCollisionShape(ship.level(), p).isEmpty()) {
				clearance = Math.max(0, box.minY - (top - dy + 1));
				return;
			}
		}
		clearance = 64;
	}
}
