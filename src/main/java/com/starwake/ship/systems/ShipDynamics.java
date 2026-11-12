package com.starwake.ship.systems;

import com.starwake.ship.block.ThrusterBlock;
import com.starwake.ship.data.ShipStats;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix3d;
import org.joml.Vector3d;

/**
 * Rigid-body model of a ship for physicalized thrust (3h, Star Citizen style).
 *
 * Every block adds its mass at its centre, giving a centre of mass and an inertia tensor.
 * Every thruster pushes from its own position, opposite its nozzle, so it both moves the ship
 * and twists it about the centre of mass. Nothing is assumed about layout: a thruster's role
 * (main engine, retro, VTOL, maneuvering) is simply what its position and direction can do.
 *
 * <h2>Flight computer</h2>
 * {@link #allocate} plays the part of Star Citizen's IFCS: given what the pilot wants, it
 * picks a throttle (0..1) for every thruster, in priority order (stay level and aloft, then
 * turn, then translate), so lopsided layouts lose thrust rather than tumble. The core is a
 * cascaded generalized inverse over the six force/torque axes: O(thrusters) per solve.
 *
 * All vectors are in ship-local block coordinates. Forces are in thrust units (ship_stats
 * "thrust"); {@code ShipPhysics.thrustToAccel} turns force / mass into blocks/tick².
 */
public final class ShipDynamics {
	/** A thruster: where it is, which way it pushes (unit), how hard and how thirsty at full throttle. */
	public record Thruster(Vector3d position, Vector3d push, double thrust, double burn, Direction nozzle, BlockPos pos) {}

	private double mass;
	private final Vector3d weightedPos = new Vector3d();
	private final List<double[]> blocks = new ArrayList<>(); // x, y, z, mass
	private final List<Thruster> thrusters = new ArrayList<>();
	/** Wing panels: position and plate normal (local); horizontal ones make lift (3i). */
	public record Wing(Vector3d position, Vector3d normal, boolean horizontal) {}
	private final List<Wing> wings = new ArrayList<>();

	// Derived by finish().
	private final Vector3d centre = new Vector3d();
	private final Matrix3d inertia = new Matrix3d();
	private final Matrix3d inverseInertia = new Matrix3d();
	/** Radius of gyration: converts torque to force-like units for the solver's weights. */
	private double gyration = 1;
	/** Per thruster: [force x,y,z, torque x,y,z] at full throttle. */
	private double[][] columns = new double[0][];

	/**
	 * Adds one block (at its local position). Thrusters count only with a clear exhaust
	 * ({@code clearExhaust}).
	 */
	public void add(BlockPos pos, BlockState state, boolean clearExhaust) {
		add(pos, state, clearExhaust, 1.0);
	}

	/** Adds one block whose mass is scaled by its material ({@code massFactor}, M8e-1). */
	public void add(BlockPos pos, BlockState state, boolean clearExhaust, double massFactor) {
		add(pos, state, clearExhaust, massFactor, ShipStats.get(state));
	}

	/** Adds one block with explicit stats (a part's quality bonus, M8e-2); mass = JSON mass x massFactor. */
	public void add(BlockPos pos, BlockState state, boolean clearExhaust, double massFactor, ShipStats.BlockStats stats) {
		double m = ShipStats.get(state).mass() * massFactor;
		double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
		mass += m;
		weightedPos.add(x * m, y * m, z * m);
		blocks.add(new double[] {x, y, z, m});
		if (state.getBlock() instanceof com.starwake.ship.block.WingBlock) {
			Direction.Axis axis = com.starwake.ship.block.WingBlock.normal(state);
			Vector3d normal = new Vector3d(axis == Direction.Axis.X ? 1 : 0, axis == Direction.Axis.Y ? 1 : 0, axis == Direction.Axis.Z ? 1 : 0);
			wings.add(new Wing(new Vector3d(x, y, z), normal, axis == Direction.Axis.Y));
		}
		if (state.getBlock() instanceof ThrusterBlock && clearExhaust && stats.thrust() > 0) {
			Direction nozzle = state.getValue(ThrusterBlock.FACING);
			Direction push = nozzle.getOpposite();
			thrusters.add(new Thruster(new Vector3d(x, y, z), new Vector3d(push.getStepX(), push.getStepY(), push.getStepZ()),
					stats.thrust(), stats.fuelBurn(), nozzle, pos));
		}
	}

	/** Computes centre of mass, inertia and the thruster matrix. Call once after adding blocks. */
	public ShipDynamics finish() {
		mass = Math.max(mass, 1);
		centre.set(weightedPos).div(mass);
		double xx = 0, yy = 0, zz = 0, xy = 0, xz = 0, yz = 0;
		for (double[] b : blocks) {
			double rx = b[0] - centre.x, ry = b[1] - centre.y, rz = b[2] - centre.z, m = b[3];
			double cube = m / 6.0; // a unit cube's own inertia about its centre
			xx += m * (ry * ry + rz * rz) + cube;
			yy += m * (rx * rx + rz * rz) + cube;
			zz += m * (rx * rx + ry * ry) + cube;
			xy -= m * rx * ry;
			xz -= m * rx * rz;
			yz -= m * ry * rz;
		}
		// JOML Matrix3d(m00, m01, m02, m10, ...) is column-major; the tensor is symmetric anyway.
		inertia.set(xx, xy, xz, xy, yy, yz, xz, yz, zz);
		inertia.invert(inverseInertia);
		gyration = Math.max(1.0, Math.sqrt((xx + yy + zz) / (2 * mass)));
		blocks.clear(); // only needed for the tensor

		columns = new double[thrusters.size()][];
		for (int k = 0; k < thrusters.size(); k++) {
			Thruster t = thrusters.get(k);
			Vector3d r = new Vector3d(t.position()).sub(centre);
			Vector3d torque = r.cross(t.push(), new Vector3d());
			columns[k] = new double[] {t.push().x * t.thrust(), t.push().y * t.thrust(), t.push().z * t.thrust(),
					torque.x * t.thrust(), torque.y * t.thrust(), torque.z * t.thrust()};
		}
		return this;
	}

	/**
	 * M9: the finished model as bytes, so a pilot's client can run the same flight computer and contact
	 * physics for prediction (ship stats and part materials are server-side data).
	 */
	public void write(net.minecraft.network.FriendlyByteBuf buf) {
		buf.writeDouble(mass);
		buf.writeDouble(centre.x).writeDouble(centre.y).writeDouble(centre.z);
		double[] m = new double[9];
		inertia.get(m);
		for (double v : m) buf.writeDouble(v);
		buf.writeDouble(gyration);
		buf.writeVarInt(thrusters.size());
		for (Thruster t : thrusters) {
			buf.writeDouble(t.position().x).writeDouble(t.position().y).writeDouble(t.position().z);
			buf.writeDouble(t.push().x).writeDouble(t.push().y).writeDouble(t.push().z);
			buf.writeDouble(t.thrust()).writeDouble(t.burn());
			buf.writeByte(t.nozzle().get3DDataValue());
			buf.writeBlockPos(t.pos());
		}
		buf.writeVarInt(wings.size());
		for (Wing w : wings) {
			buf.writeDouble(w.position().x).writeDouble(w.position().y).writeDouble(w.position().z);
			buf.writeDouble(w.normal().x).writeDouble(w.normal().y).writeDouble(w.normal().z);
			buf.writeBoolean(w.horizontal());
		}
	}

	/** Reads a model written by {@link #write} (derived values are rebuilt). */
	public static ShipDynamics read(net.minecraft.network.FriendlyByteBuf buf) {
		ShipDynamics d = new ShipDynamics();
		d.mass = buf.readDouble();
		d.centre.set(buf.readDouble(), buf.readDouble(), buf.readDouble());
		double[] m = new double[9];
		for (int i = 0; i < 9; i++) m[i] = buf.readDouble();
		d.inertia.set(m);
		d.inertia.invert(d.inverseInertia);
		d.gyration = buf.readDouble();
		int n = buf.readVarInt();
		for (int i = 0; i < n; i++) {
			Vector3d pos = new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
			Vector3d push = new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
			double thrust = buf.readDouble(), burn = buf.readDouble();
			Direction nozzle = Direction.from3DDataValue(buf.readByte());
			d.thrusters.add(new Thruster(pos, push, thrust, burn, nozzle, buf.readBlockPos()));
		}
		int w = buf.readVarInt();
		for (int i = 0; i < w; i++) {
			Vector3d pos = new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
			Vector3d normal = new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
			d.wings.add(new Wing(pos, normal, buf.readBoolean()));
		}
		d.columns = new double[d.thrusters.size()][];
		for (int k = 0; k < d.thrusters.size(); k++) {
			Thruster t = d.thrusters.get(k);
			Vector3d r = new Vector3d(t.position()).sub(d.centre);
			Vector3d torque = r.cross(t.push(), new Vector3d());
			d.columns[k] = new double[] {t.push().x * t.thrust(), t.push().y * t.thrust(), t.push().z * t.thrust(),
					torque.x * t.thrust(), torque.y * t.thrust(), torque.z * t.thrust()};
		}
		return d;
	}

	public double mass() { return mass; }
	public Vector3d centre() { return centre; }
	public Matrix3d inertia() { return inertia; }
	public Matrix3d inverseInertia() { return inverseInertia; }
	public List<Thruster> thrusters() { return thrusters; }
	public int thrusterCount() { return thrusters.size(); }

	public List<Wing> wings() { return wings; }

	/** Number of horizontal (lifting) wing panels. */
	public int liftingWings() {
		int n = 0;
		for (Wing w : wings) if (w.horizontal()) n++;
		return n;
	}

	/**
	 * Aerodynamic force and torque (local, thrust units) on the wings (3i). Each panel is a flat
	 * plate: air pushing through it is resisted ({@code drag x |v| x v·n}, along -n), which also
	 * damps spin and weathervanes a ship with fins at the back. Horizontal panels are cambered:
	 * forward airspeed squared times {@code lift}, along the panel's up. Every panel acts at
	 * its own position, so wings ahead of or behind the centre of mass pitch the ship.
	 *
	 * @param velocity local velocity (blocks/tick); {@code omega} local angular velocity (rad/tick)
	 * @param forward  the ship's local forward (unit)
	 */
	public double[] aero(Vector3d velocity, Vector3d omega, Vector3d forward, double density, double lift, double drag) {
		double[] out = new double[6];
		if (density <= 0 || wings.isEmpty()) return out;
		for (Wing w : wings) {
			Vector3d r = new Vector3d(w.position()).sub(centre);
			Vector3d v = omega.cross(r, new Vector3d()).add(velocity); // the panel's own velocity
			double speed = v.length();
			double through = v.dot(w.normal());
			Vector3d force = new Vector3d(w.normal()).mul(-drag * density * speed * through);
			if (w.horizontal()) {
				double airspeed = Math.max(0, v.dot(forward));
				force.add(0, lift * density * airspeed * airspeed, 0);
			}
			Vector3d torque = r.cross(force, new Vector3d());
			out[0] += force.x; out[1] += force.y; out[2] += force.z;
			out[3] += torque.x; out[4] += torque.y; out[5] += torque.z;
		}
		return out;
	}

	/** Sum of every thruster's full thrust (an upper bound on any request). */
	public double totalThrust() {
		double sum = 0;
		for (Thruster t : thrusters) sum += t.thrust();
		return sum;
	}

	/** Raw thrust pushing along a local direction, ignoring balance. */
	public double rawThrust(Direction push) {
		double sum = 0;
		for (Thruster t : thrusters) {
			if (t.nozzle().getOpposite() == push) sum += t.thrust();
		}
		return sum;
	}

	/**
	 * The flight computer. Requests are met in priority order, each as far as the thrusters
	 * allow without breaking the ones before:
	 * <ol>
	 *   <li>hover: push {@code weight} along world-up ({@code up}, in the ship's frame) with no spin;</li>
	 *   <li>rotation: the torque the pilot's turn needs;</li>
	 *   <li>translation: as much {@code push} as is left.</li>
	 * </ol>
	 * Only what matters is pinned down: vertical lift, the three torques and thrust along the
	 * commanded direction. Everything else is left free, so a tilted VTOL ship holds altitude
	 * and drifts forward like a helicopter, and a lopsided design loses lift or thrust rather
	 * than tumbling. An axis no thruster can twist simply doesn't turn.
	 *
	 * @param up        world-up in the ship's frame (ignored when {@code weight} is 0, e.g. space)
	 * @param hover     whether to hold altitude at all (false in space, or climbing/diving on purpose)
	 * @param weight    vertical force needed to hover (thrust units); negative if wings over-lift
	 * @param torque    wanted torque (local)
	 * @param push      wanted translation force (local); over-ask for "full thrust"
	 * @param scale     thrust multiplier for every thruster (power routing, boost)
	 * @param throttles out: one throttle per thruster in [0, 1]
	 * @return achieved [force xyz, torque xyz] (local, already scaled)
	 */
	public double[] allocate(Vector3d up, boolean hover, double weight, Vector3d torque, Vector3d push, double scale, double[] throttles) {
		java.util.Arrays.fill(throttles, 0);
		if (columns.length == 0 || scale <= 0) return new double[6];
		Vector3d u = new Vector3d(up).normalize();

		// Constraint rows (functionals of [force, torque]) and their targets.
		List<double[]> rows = new ArrayList<>();
		if (hover) rows.add(new double[] {u.x, u.y, u.z, 0, 0, 0});
		rows.add(new double[] {0, 0, 0, 1, 0, 0});
		rows.add(new double[] {0, 0, 0, 0, 1, 0});
		rows.add(new double[] {0, 0, 0, 0, 0, 1});
		int torqueRow = hover ? 1 : 0;

		// 1. Hover with no spin.
		double[] base = new double[rows.size()];
		double h = 0;
		if (hover) {
			double[] full = base.clone();
			full[0] = weight;
			h = largestFeasible(rows, base, full, scale, throttles);
			base[0] = h * weight;
		}
		// 2. Rotation.
		double[] spin = base.clone();
		spin[torqueRow] += torque.x;
		spin[torqueRow + 1] += torque.y;
		spin[torqueRow + 2] += torque.z;
		double t = largestFeasible(rows, base, spin, scale, throttles);
		double[] withSpin = lerp(base, spin, t);
		// 3. Translation along the commanded direction (vertical part stacks on the hover).
		double strength = push.length();
		if (strength > 1.0e-9) {
			Vector3d d = new Vector3d(push).div(strength);
			boolean alongUp = hover && Math.abs(d.dot(u)) > 0.99;
			List<double[]> moveRows = new ArrayList<>(rows);
			double[] start = withSpin, end = withSpin.clone();
			if (hover) end[0] += strength * d.dot(u);
			if (!alongUp) {
				moveRows.add(new double[] {d.x, d.y, d.z, 0, 0, 0});
				start = java.util.Arrays.copyOf(withSpin, withSpin.length + 1);
				start[start.length - 1] = hover ? h * weight * u.dot(d) : 0;
				end = java.util.Arrays.copyOf(end, end.length + 1);
				end[end.length - 1] = start[start.length - 1] + strength;
			}
			double m = largestFeasible(moveRows, start, end, scale, throttles);
			return cascade(moveRows, lerp(start, end, m), scale, throttles);
		}
		return cascade(rows, withSpin, scale, throttles);
	}

	/**
	 * Largest k in [0, 1] such that targets {@code from + k * (to - from)} are achievable on the
	 * given constraint rows. Leaves the matching throttles.
	 */
	private double largestFeasible(List<double[]> rows, double[] from, double[] to, double scale, double[] throttles) {
		if (java.util.Arrays.equals(from, to)) return 0;
		if (feasible(rows, to, scale, throttles)) return 1;
		double lo = 0, hi = 1;
		for (int i = 0; i < 7; i++) {
			double mid = (lo + hi) / 2;
			if (feasible(rows, lerp(from, to, mid), scale, throttles)) lo = mid;
			else hi = mid;
		}
		return lo;
	}

	private boolean feasible(List<double[]> rows, double[] want, double scale, double[] throttles) {
		double[] got = cascade(rows, want, scale, throttles);
		double error = 0, size = 1.0e-6;
		for (int r = 0; r < rows.size(); r++) {
			double w = rowWeight(rows.get(r));
			double value = 0;
			for (int j = 0; j < 6; j++) value += rows.get(r)[j] * got[j];
			error += Math.abs(value - want[r]) * w;
			size += Math.abs(want[r]) * w;
		}
		return error <= size * 1.0e-3;
	}

	/**
	 * Cascaded generalized inverse (a standard control-allocation method): the minimum-throttle
	 * solution that meets every constraint row exactly, via the pseudo-inverse; thrusters that
	 * come out below 0 or above 1 are pinned there and the rest re-solved, a few rounds.
	 * Returns the [force, torque] the resulting throttles achieve.
	 */
	private double[] cascade(List<double[]> rows, double[] want, double scale, double[] throttles) {
		int n = columns.length, m = rows.size();
		// Each thruster's effect on each (weighted) constraint row.
		double[][] effect = new double[n][m];
		double[] weights = new double[m];
		for (int r = 0; r < m; r++) weights[r] = rowWeight(rows.get(r));
		for (int k = 0; k < n; k++) {
			for (int r = 0; r < m; r++) {
				double v = 0;
				for (int j = 0; j < 6; j++) v += rows.get(r)[j] * columns[k][j];
				effect[k][r] = v * scale * weights[r];
			}
		}
		boolean[] pinned = new boolean[n];
		java.util.Arrays.fill(throttles, 0);
		for (int round = 0; round < 24; round++) {
			double[] target = new double[m];
			for (int r = 0; r < m; r++) target[r] = want[r] * weights[r];
			double[][] gram = new double[m][m];
			for (int k = 0; k < n; k++) {
				if (pinned[k]) {
					for (int r = 0; r < m; r++) target[r] -= effect[k][r] * throttles[k];
				} else {
					for (int r = 0; r < m; r++) {
						for (int c = 0; c < m; c++) gram[r][c] += effect[k][r] * effect[k][c];
					}
				}
			}
			double[] y = solve(gram, target);
			boolean violated = false;
			for (int k = 0; k < n; k++) {
				if (pinned[k]) continue;
				double value = 0;
				for (int r = 0; r < m; r++) value += effect[k][r] * y[r];
				throttles[k] = value;
				if (value < 0 || value > 1) {
					throttles[k] = Math.clamp(value, 0.0, 1.0);
					pinned[k] = true;
					violated = true;
				}
			}
			if (violated) continue;
			// Active-set release: plain CGI can pin a thruster that's actually needed (e.g. a
			// pitch thruster trimming off-centre retros). If the request still isn't met, free
			// the pinned thruster whose nudge would help most and solve again.
			double[] residual = new double[m];
			for (int r = 0; r < m; r++) {
				residual[r] = -want[r] * weights[r];
				for (int k = 0; k < n; k++) residual[r] += effect[k][r] * throttles[k];
			}
			double size = 1.0e-9, error = 0;
			for (int r = 0; r < m; r++) {
				error += Math.abs(residual[r]);
				size += Math.abs(want[r] * weights[r]);
			}
			if (error <= size * 1.0e-4) break;
			int best = -1;
			double bestGain = 1.0e-9;
			for (int k = 0; k < n; k++) {
				if (!pinned[k]) continue;
				double slope = 0; // d(error²)/d(throttle)
				for (int r = 0; r < m; r++) slope += effect[k][r] * residual[r];
				double gain = throttles[k] <= 0 ? -slope : throttles[k] >= 1 ? slope : 0;
				if (gain > bestGain) {
					bestGain = gain;
					best = k;
				}
			}
			if (best < 0) break;
			pinned[best] = false;
		}
		for (int k = 0; k < n; k++) throttles[k] = Math.clamp(throttles[k], 0.0, 1.0);
		double[] achieved = new double[6];
		for (int k = 0; k < n; k++) {
			for (int j = 0; j < 6; j++) achieved[j] += columns[k][j] * scale * throttles[k];
		}
		return achieved;
	}

	/** Torque rows are divided by the radius of gyration, so all rows are in force-like units. */
	private double rowWeight(double[] row) {
		return row[3] != 0 || row[4] != 0 || row[5] != 0 ? 1.0 / gyration : 1.0;
	}

	/** Solves a small symmetric system (Gaussian elimination, partial pivoting, light ridge). */
	private static double[] solve(double[][] a, double[] b) {
		int m = b.length;
		double trace = 0;
		for (int i = 0; i < m; i++) trace += a[i][i];
		double ridge = Math.max(1.0e-12, trace * 1.0e-9);
		double[][] x = new double[m][m + 1];
		for (int i = 0; i < m; i++) {
			System.arraycopy(a[i], 0, x[i], 0, m);
			x[i][i] += ridge;
			x[i][m] = b[i];
		}
		for (int col = 0; col < m; col++) {
			int pivot = col;
			for (int r = col + 1; r < m; r++) if (Math.abs(x[r][col]) > Math.abs(x[pivot][col])) pivot = r;
			double[] tmp = x[col];
			x[col] = x[pivot];
			x[pivot] = tmp;
			double p = x[col][col];
			if (Math.abs(p) < 1.0e-300) continue;
			for (int r = 0; r < m; r++) {
				if (r == col) continue;
				double f = x[r][col] / p;
				if (f == 0) continue;
				for (int c = col; c <= m; c++) x[r][c] -= f * x[col][c];
			}
		}
		double[] out = new double[m];
		for (int i = 0; i < m; i++) out[i] = Math.abs(x[i][i]) < 1.0e-300 ? 0 : x[i][m] / x[i][i];
		return out;
	}

	private static double[] lerp(double[] a, double[] b, double k) {
		double[] out = new double[a.length];
		for (int j = 0; j < a.length; j++) out[j] = a[j] + (b[j] - a[j]) * k;
		return out;
	}

	/**
	 * Balanced capability: the most force along {@code direction} the thrusters can make with
	 * no spin (push along other axes is allowed: e.g. lift thrusters trimming an off-centre
	 * main engine). Used by pre-flight.
	 */
	public double balancedThrust(Vector3d direction) {
		Vector3d unit = new Vector3d(direction).normalize();
		double raw = 0;
		for (Thruster t : thrusters) raw += Math.max(0, t.push().dot(unit)) * t.thrust();
		if (raw <= 0) return 0;
		List<double[]> rows = List.of(new double[] {unit.x, unit.y, unit.z, 0, 0, 0},
				new double[] {0, 0, 0, 1, 0, 0}, new double[] {0, 0, 0, 0, 1, 0}, new double[] {0, 0, 0, 0, 0, 1});
		double[] throttles = new double[columns.length];
		return raw * largestFeasible(rows, new double[4], new double[] {raw, 0, 0, 0}, 1.0, throttles);
	}

	/**
	 * Turning capability about a local axis: angular acceleration (rad/tick² per unit
	 * thrustToAccel) from every thruster that twists the ship that way (in flight the flight
	 * computer balances the rest). Used by pre-flight.
	 */
	public double angularAuthority(Vector3d axis) {
		Vector3d unit = new Vector3d(axis).normalize();
		double best = 0;
		for (double[] column : columns) {
			best += Math.max(0, column[3] * unit.x + column[4] * unit.y + column[5] * unit.z);
		}
		Vector3d alpha = inverseInertia.transform(new Vector3d(unit).mul(best), new Vector3d());
		return Math.max(0, alpha.dot(unit));
	}

	/** Fuel burned per tick by the thrusters pushing along a local direction, all at full throttle. */
	public double burnPushing(Direction push) {
		double sum = 0;
		for (Thruster t : thrusters) {
			if (t.nozzle().getOpposite() == push) sum += t.burn();
		}
		return sum;
	}

	/** Fuel burned per tick at these throttles (before boost/power scaling). */
	public double burn(double[] throttles) {
		double sum = 0;
		for (int k = 0; k < thrusters.size(); k++) sum += thrusters.get(k).burn() * throttles[k];
		return sum;
	}
}
