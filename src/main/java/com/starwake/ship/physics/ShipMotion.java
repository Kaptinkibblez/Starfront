package com.starwake.ship.physics;

import com.starwake.ship.ShipEntity;
import com.starwake.ship.ShipStructure;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * Rigid-body motion with contacts (M9). Our own design, after the ideas VS2 and Sable use (rigid bodies, contact
 * points, sequential impulses), written for voxel ships: the ship moves and turns about its <b>centre of mass</b>;
 * every corner of its outer hull blocks is a contact probe. Each (sub)step:
 * <ol>
 *   <li>integrate the pose by the current linear and angular velocity;</li>
 *   <li>find probes that are inside terrain or another ship's blocks; the contact normal is the nearest
 *       <i>exposed</i> face of the block it's in (never pushing into a neighbouring solid block);</li>
 *   <li>solve velocities with sequential impulses (normal impulses never pull, Coulomb friction, a little
 *       restitution for hard hits), applied at the contact point so off-centre contacts make the ship tip;</li>
 *   <li>push the hull back out with a pseudo-velocity pass of the same solver (split impulse), so penetration
 *       never feeds back into speed and ships can't get stuck inside blocks.</li>
 * </ol>
 * Fast ships substep so they can't tunnel. Other ships take equal and opposite impulses (ships shove each other).
 */
public final class ShipMotion {
	private static final double FRICTION = 0.7, RESTITUTION = 0.15, RESTITUTION_THRESHOLD = 0.2;
	private static final double SLOP = 0.004, CORRECTION = 0.8, MAX_STEP = 0.4;
	private static final int VELOCITY_ITERATIONS = 8, POSITION_ITERATIONS = 4, MAX_SUBSTEPS = 10;
	/** Most speed (b/t), spin (rad/t) and push-out (blocks, radians per substep) contacts may add. */
	private static final double MAX_GAIN = 0.3, MAX_SPIN_GAIN = 0.03, MAX_PUSH = 0.25, MAX_TURN = 0.02;

	private ShipMotion() {}

	/** Logs every contact solve (very verbose; for tuning). */
	public static final boolean DEBUG = false;

	private static String fmt(Vector3d v) {
		return String.format("(%.3f %.3f %.3f)", v.x, v.y, v.z);
	}

	/** What happened this tick: the strongest normal impulse (as a velocity change), its direction, and ground contact. */
	public record Result(double impact, Vec3 blocked, boolean grounded, boolean touchdown, boolean moved) {}

	/** One body in a contact: this ship, or another ship it touches. */
	private static final class Body {
		final ShipEntity ship;
		final double invMass;
		final Matrix3d invInertiaLocal;
		final Vector3d comLocal;
		final Vector3d xc = new Vector3d();
		final Quaterniond q = new Quaterniond();
		final Vector3d v = new Vector3d(), w = new Vector3d();
		final Matrix3d invInertiaWorld = new Matrix3d();

		Body(ShipEntity ship, Vec3 velocity) {
			this.ship = ship;
			this.invMass = 1.0 / Math.max(1e-3, ship.mass());
			this.invInertiaLocal = new Matrix3d(ship.dynamics().inverseInertia());
			this.comLocal = new Vector3d(ship.dynamics().centre());
			Quaternionf o = new Quaternionf(ship.orientation());
			q.set(o.x, o.y, o.z, o.w);
			Vec3 pivot = ship.pivot();
			Vector3d offset = q.transform(new Vector3d(comLocal).sub(pivot.x, pivot.y, pivot.z));
			xc.set(ship.getX(), ship.getY(), ship.getZ()).add(offset);
			v.set(velocity.x, velocity.y, velocity.z);
			Vector3d omega = ship.angularVelocity();
			q.transform(new Vector3d(omega), w);
			refreshInertia();
		}

		void refreshInertia() {
			Matrix3d r = q.get(new Matrix3d());
			r.mul(invInertiaLocal, invInertiaWorld).mul(new Matrix3d(r).transpose());
		}

		Vector3d toWorld(double lx, double ly, double lz, Vector3d out) {
			return q.transform(out.set(lx, ly, lz).sub(comLocal)).add(xc);
		}

		Vector3d toLocal(Vector3d world, Vector3d out) {
			return q.transformInverse(out.set(world).sub(xc)).add(comLocal);
		}

		/** Velocity of a world point on this body. */
		Vector3d pointVelocity(Vector3d point, Vector3d out) {
			Vector3d r = new Vector3d(point).sub(xc);
			return w.cross(r, out).add(v);
		}

		void applyImpulse(Vector3d point, Vector3d impulse) {
			v.fma(invMass, impulse);
			Vector3d r = new Vector3d(point).sub(xc);
			w.add(invInertiaWorld.transform(r.cross(impulse, new Vector3d())));
		}

		/** Effective inverse mass along a direction at a point: 1/m + n . ((I^-1 (r x n)) x r). */
		double effective(Vector3d point, Vector3d n) {
			Vector3d r = new Vector3d(point).sub(xc);
			Vector3d rn = r.cross(n, new Vector3d());
			Vector3d t = invInertiaWorld.transform(rn, new Vector3d()).cross(r);
			return invMass + n.dot(t);
		}

		/** Moves the pose by a pseudo-velocity (position correction), not the real velocity. */
		void shift(Vector3d dx, Vector3d dtheta) {
			xc.add(dx);
			rotate(dtheta);
		}

		void rotate(Vector3d dtheta) {
			double angle = dtheta.length();
			if (angle < 1e-12) return;
			Quaterniond turn = new Quaterniond().fromAxisAngleRad(dtheta.x / angle, dtheta.y / angle, dtheta.z / angle, angle);
			turn.mul(q, q).normalize();
		}
	}

	private record Contact(Vector3d point, Vector3d normal, double depth, @Nullable Body other) {}

	/**
	 * Moves the ship by its velocity this tick with contacts against terrain and nearby ships. Writes the new pose,
	 * velocity and angular velocity back to the ship (and impulses into the ships it touched).
	 */
	public static Result step(ShipEntity ship, Vec3 velocity, List<ShipEntity> neighbours, boolean mayRest) {
		Level level = ship.level();
		double[] probes = ship.hullProbes();
		Body body = new Body(ship, velocity);
		List<Body> others = new ArrayList<>(neighbours.size());
		for (ShipEntity other : neighbours) {
			if (other.structure() != null && other.dynamics() != null) others.add(new Body(other, other.getDeltaMovement()));
		}
		double radius = ship.radius();
		double speedIn = body.v.length(), spinIn = body.w.length();
		double travel = body.v.length() + body.w.length() * radius;
		int substeps = Math.clamp((int) Math.ceil(travel / MAX_STEP), 1, MAX_SUBSTEPS);
		double fraction = 1.0 / substeps;

		Vector3d impulseSum = new Vector3d();
		double strongest = 0;
		boolean grounded = false;
		for (int s = 0; s < substeps; s++) {
			// Contacts at the current pose first: the velocity solve then stops motion into them before it happens.
			List<Contact> contacts = detect(level, body, others, probes);
			if (contacts.isEmpty()) {
				integrate(body, fraction);
				continue;
			}
			double[] normalImpulse = new double[contacts.size()];
			double[] frictionA = new double[contacts.size()], frictionB = new double[contacts.size()];
			Vector3d[] tangentA = new Vector3d[contacts.size()], tangentB = new Vector3d[contacts.size()];
			double[] restitution = new double[contacts.size()];
			for (int i = 0; i < contacts.size(); i++) {
				Contact c = contacts.get(i);
				double vn = relativeVelocity(body, c).dot(c.normal());
				// Bounce only off real impacts at the surface, never out of a block we're buried in.
				restitution[i] = vn < -RESTITUTION_THRESHOLD && c.depth() < 0.1 ? -RESTITUTION * vn : 0;
				Vector3d n = c.normal();
				Vector3d a = Math.abs(n.y) < 0.9 ? new Vector3d(0, 1, 0) : new Vector3d(1, 0, 0);
				tangentA[i] = a.cross(n, new Vector3d()).normalize();
				tangentB[i] = n.cross(tangentA[i], new Vector3d()).normalize();
				if (c.normal().y > 0.6 && c.other() == null) grounded = true;
			}
			// Velocity: no closing speed along any normal; friction up to mu x normal impulse.
			for (int it = 0; it < VELOCITY_ITERATIONS; it++) {
				for (int i = 0; i < contacts.size(); i++) {
					Contact c = contacts.get(i);
					Vector3d n = c.normal();
					Vector3d vrel = relativeVelocity(body, c);
					double k = body.effective(c.point(), n) + (c.other() == null ? 0 : c.other().effective(c.point(), n));
					double vn = vrel.dot(n);
					double lambda = (restitution[i] - vn) / k;
					double old = normalImpulse[i];
					normalImpulse[i] = Math.max(0, old + lambda);
					applyPair(body, c, new Vector3d(n).mul(normalImpulse[i] - old));
					// Friction: two fixed tangent axes per contact, the combined impulse clamped to mu x normal impulse.
					Vector3d t1 = tangentA[i], t2 = tangentB[i];
					vrel = relativeVelocity(body, c);
					double k1 = body.effective(c.point(), t1) + (c.other() == null ? 0 : c.other().effective(c.point(), t1));
					double k2 = body.effective(c.point(), t2) + (c.other() == null ? 0 : c.other().effective(c.point(), t2));
					double f1 = frictionA[i] - vrel.dot(t1) / k1;
					double f2 = frictionB[i] - vrel.dot(t2) / k2;
					double max = FRICTION * normalImpulse[i];
					double mag = Math.sqrt(f1 * f1 + f2 * f2);
					if (mag > max && mag > 0) {
						f1 *= max / mag;
						f2 *= max / mag;
					}
					Vector3d delta = new Vector3d(t1).mul(f1 - frictionA[i]).fma(f2 - frictionB[i], t2);
					frictionA[i] = f1;
					frictionB[i] = f2;
					applyPair(body, c, delta);
				}
			}
			for (int i = 0; i < contacts.size(); i++) {
				impulseSum.fma(normalImpulse[i], contacts.get(i).normal());
				strongest = Math.max(strongest, normalImpulse[i] * body.invMass);
			}
			integrate(body, fraction);
			// Position: push out of whatever we're inside now (pseudo-velocities, so no energy is added).
			List<Contact> after = detect(level, body, others, probes);
			if (!after.isEmpty()) correctPositions(body, after);
			if (DEBUG) {
				double maxDepth = 0;
				for (Contact c : contacts) maxDepth = Math.max(maxDepth, c.depth());
				double sumN = 0, sumT = 0;
				for (int i = 0; i < contacts.size(); i++) { sumN += normalImpulse[i]; sumT += Math.hypot(frictionA[i], frictionB[i]); }
				com.starwake.StarwakeMod.LOGGER.info("[motion] impulses N={} T={} invMass={} k0={}", String.format("%.4f", sumN), String.format("%.4f", sumT),
						body.invMass, String.format("%.6f", body.effective(contacts.get(0).point(), contacts.get(0).normal())));
				StringBuilder normals = new StringBuilder();
				for (Contact c : contacts) normals.append(String.format("[%.0f%.0f%.0f %.3f]", c.normal().x, c.normal().y, c.normal().z, c.depth()));
				com.starwake.StarwakeMod.LOGGER.info("[motion] t={} sub={}/{} vin={} contacts={} maxDepth={} v={} w={} xc={} n={}", ship.tickCount, s, substeps,
						fmt(new Vector3d(velocity.x, velocity.y, velocity.z)), contacts.size(), String.format("%.3f", maxDepth), fmt(body.v), fmt(body.w), fmt(body.xc), normals);
			}
		}

		// Contacts only ever take energy away (bar a small bounce): an overlap (a ship assembled against another,
		// a pose pushed into terrain) is pushed apart gently instead of launching anything.
		if (body.v.length() > speedIn + MAX_GAIN) body.v.normalize(speedIn + MAX_GAIN);
		if (body.w.length() > spinIn + MAX_SPIN_GAIN) body.w.normalize(spinIn + MAX_SPIN_GAIN);
		for (Body other : others) {
			double cap = other.ship.getDeltaMovement().length() + MAX_GAIN;
			if (other.v.length() > cap) other.v.normalize(cap);
		}

		// Write back.
		Vec3 pivot = ship.pivot();
		Vector3d offset = body.q.transform(new Vector3d(body.comLocal).sub(pivot.x, pivot.y, pivot.z));
		Vector3d position = new Vector3d(body.xc).sub(offset);
		boolean moved = position.distanceSquared(ship.getX(), ship.getY(), ship.getZ()) > 1e-12;
		ship.setPose(new Vec3(position.x, position.y, position.z), new Quaternionf((float) body.q.x, (float) body.q.y, (float) body.q.z, (float) body.q.w));
		Vector3d omegaLocal = body.q.transformInverse(new Vector3d(body.w));
		if (mayRest && grounded && body.v.lengthSquared() < 1e-4 && body.w.lengthSquared() < 1e-5) {
			// Resting (unpiloted only, so a weak lift-off is never cancelled): sleep, so a parked ship doesn't creep.
			body.v.zero();
			omegaLocal.zero();
		}
		ship.setDeltaMovement(new Vec3(body.v.x, body.v.y, body.v.z));
		ship.angularVelocity().set(omegaLocal);
		for (Body other : others) {
			other.ship.setDeltaMovement(new Vec3(other.v.x, other.v.y, other.v.z));
			other.ship.angularVelocity().set(other.q.transformInverse(new Vector3d(other.w)));
		}
		double impact = impulseSum.length() * body.invMass;
		Vec3 blocked = impact > 0 ? new Vec3(-impulseSum.x, -impulseSum.y, -impulseSum.z).normalize().scale(impact) : Vec3.ZERO;
		boolean touchdown = impact > 0 && impulseSum.y > 0.7 * impulseSum.length();
		return new Result(Math.max(impact, strongest), blocked, grounded, touchdown, moved);
	}

	private static void integrate(Body body, double fraction) {
		body.xc.fma(fraction, body.v);
		body.rotate(new Vector3d(body.w).mul(fraction));
		body.refreshInertia();
	}

	private static Vector3d relativeVelocity(Body body, Contact c) {
		Vector3d v = body.pointVelocity(c.point(), new Vector3d());
		if (c.other() != null) v.sub(c.other().pointVelocity(c.point(), new Vector3d()));
		return v;
	}

	private static void applyPair(Body body, Contact c, Vector3d impulse) {
		body.applyImpulse(c.point(), impulse);
		if (c.other() != null) c.other().applyImpulse(c.point(), new Vector3d(impulse).negate());
	}

	/** Split-impulse position correction: solves for pseudo-velocities that remove penetration beyond the slop. */
	private static void correctPositions(Body body, List<Contact> contacts) {
		Vector3d dx = new Vector3d(), dtheta = new Vector3d();
		double[] accumulated = new double[contacts.size()];
		for (int it = 0; it < POSITION_ITERATIONS; it++) {
			for (int i = 0; i < contacts.size(); i++) {
				Contact c = contacts.get(i);
				Vector3d n = c.normal();
				Vector3d r = new Vector3d(c.point()).sub(body.xc);
				// How far this contact has been pushed out already by the pseudo-velocity so far.
				double moved = new Vector3d(dtheta).cross(r).add(dx).dot(n);
				double wanted = Math.max(0, c.depth() - SLOP) * CORRECTION;
				double k = body.effective(c.point(), n);
				double lambda = (wanted - moved) / k;
				double old = accumulated[i];
				accumulated[i] = Math.max(0, old + lambda);
				double applied = accumulated[i] - old;
				dx.fma(body.invMass * applied, n);
				dtheta.add(body.invInertiaWorld.transform(r.cross(n, new Vector3d()).mul(applied)));
			}
		}
		if (dx.length() > MAX_PUSH) dx.normalize(MAX_PUSH);
		if (dtheta.length() > MAX_TURN) dtheta.normalize(MAX_TURN);
		body.shift(dx, dtheta);
		body.refreshInertia();
	}

	// --- Detection -------------------------------------------------------------------------------------

	private static List<Contact> detect(Level level, Body body, List<Body> others, double[] probes) {
		List<Contact> contacts = new ArrayList<>();
		Vector3d world = new Vector3d(), local = new Vector3d();
		BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
		for (int i = 0; i < probes.length; i += 3) {
			body.toWorld(probes[i], probes[i + 1], probes[i + 2], world);
			// Terrain.
			cell.set(Math.floor(world.x), Math.floor(world.y), Math.floor(world.z));
			BlockState state = level.getBlockState(cell);
			if (!state.isAir()) {
				VoxelShape shape = state.getCollisionShape(level, cell);
				if (!shape.isEmpty()) {
					Contact c = inside(world, shape, cell.getX(), cell.getY(), cell.getZ(), d -> solid(level, cell, d), null, null);
					if (c != null) contacts.add(c);
				}
			}
			// Other ships.
			for (Body other : others) {
				ShipStructure structure = other.ship.structure();
				if (structure == null) continue;
				other.toLocal(world, local);
				int lx = (int) Math.floor(local.x), ly = (int) Math.floor(local.y), lz = (int) Math.floor(local.z);
				BlockState there = structure.get(lx, ly, lz);
				if (there == null) continue;
				VoxelShape shape = there.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
				if (shape.isEmpty()) continue;
				BlockPos at = new BlockPos(lx, ly, lz);
				Contact c = inside(local, shape, lx, ly, lz, d -> structure.hasCollision(at.relative(d)), other, null);
				if (c != null) {
					// Normal and point back to world space.
					other.q.transform(c.normal());
					contacts.add(new Contact(new Vector3d(world), c.normal(), c.depth(), other));
				}
			}
		}
		return contacts;
	}

	private interface Solid {
		boolean test(Direction d);
	}

	private static boolean solid(Level level, BlockPos cell, Direction d) {
		BlockPos next = cell.relative(d);
		BlockState state = level.getBlockState(next);
		return !state.isAir() && state.isCollisionShapeFullBlock(level, next);
	}

	/**
	 * If {@code p} is inside one of the shape's boxes (shape at cell x, y, z), the contact: the nearest face of that box
	 * that isn't buried against a solid neighbour, as normal (pointing out) and depth.
	 */
	private static @Nullable Contact inside(Vector3d p, VoxelShape shape, int x, int y, int z, Solid neighbour, @Nullable Body other,
			@Nullable Object unused) {
		for (AABB box : shape.toAabbs()) {
			double minX = box.minX + x, minY = box.minY + y, minZ = box.minZ + z;
			double maxX = box.maxX + x, maxY = box.maxY + y, maxZ = box.maxZ + z;
			if (p.x <= minX || p.x >= maxX || p.y <= minY || p.y >= maxY || p.z <= minZ || p.z >= maxZ) continue;
			double best = Double.MAX_VALUE;
			Direction face = null;
			for (Direction d : Direction.values()) {
				double depth = switch (d) {
					case DOWN -> p.y - minY;
					case UP -> maxY - p.y;
					case NORTH -> p.z - minZ;
					case SOUTH -> maxZ - p.z;
					case WEST -> p.x - minX;
					case EAST -> maxX - p.x;
				};
				// A face flush with the cell boundary against a full neighbour is buried: don't push out through it.
				boolean flush = switch (d) {
					case DOWN -> box.minY <= 0;
					case UP -> box.maxY >= 1;
					case NORTH -> box.minZ <= 0;
					case SOUTH -> box.maxZ >= 1;
					case WEST -> box.minX <= 0;
					case EAST -> box.maxX >= 1;
				};
				if (flush && neighbour.test(d)) continue;
				if (depth < best) {
					best = depth;
					face = d;
				}
			}
			if (face == null) continue; // fully buried: let the next probe decide
			Vector3d normal = new Vector3d(face.getStepX(), face.getStepY(), face.getStepZ());
			return new Contact(new Vector3d(p), normal, best, other);
		}
		return null;
	}
}
