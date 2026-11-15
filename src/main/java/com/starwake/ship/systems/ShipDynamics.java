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
	/**
	 * Running sums over every block (M9 pass 3: blocks can be added and removed one at a time, so editing a
	 * 262k-block ship doesn't rebuild the model): raw mass, first moments and second moments about the local
	 * origin, plus each unit cube's own inertia ({@code m/6} per axis).
	 */
	private double rawMass, sxx, syy, szz, sxy, sxz, syz, cubeSum;
	private final Vector3d weightedPos = new Vector3d();
	private final List<Thruster> thrusters = new ArrayList<>();
	/**
	 * Hull silhouette for air drag (M9 pass 2): the occupied cells projected onto each local plane, so {@code area}
	 * x/y/z is the cross-section facing air along that axis (blocks²). Collected in add(), counted in finish().
	 */
	private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap shadowX = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(),
			shadowY = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(), shadowZ = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
	private final Vector3d area = new Vector3d();

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
		accumulate(pos, ShipStats.get(state).mass() * massFactor, 1);
		addThruster(pos, state, clearExhaust, stats);
	}

	/** Undoes {@link #add} for a block (same mass factor), then call {@link #finish()} again (M9 pass 3). */
	public void remove(BlockPos pos, BlockState state, double massFactor) {
		accumulate(pos, ShipStats.get(state).mass() * massFactor, -1);
		removeThruster(pos);
	}

	private void accumulate(BlockPos pos, double m, int sign) {
		double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
		double sm = sign * m;
		rawMass += sm;
		weightedPos.add(x * sm, y * sm, z * sm);
		sxx += sm * x * x; syy += sm * y * y; szz += sm * z * z;
		sxy += sm * x * y; sxz += sm * x * z; syz += sm * y * z;
		cubeSum += sm / 6.0;
		shadow(shadowX, BlockPos.asLong(0, pos.getY(), pos.getZ()), sign);
		shadow(shadowY, BlockPos.asLong(pos.getX(), 0, pos.getZ()), sign);
		shadow(shadowZ, BlockPos.asLong(pos.getX(), pos.getY(), 0), sign);
	}

	private static void shadow(it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap counts, long key, int sign) {
		int n = counts.addTo(key, sign) + sign;
		if (n <= 0) counts.remove(key);
	}

	/** Removes the thruster at a block position, if there is one (exhaust or stats changed: re-add it after). */
	public void removeThruster(BlockPos pos) {
		thrusters.removeIf(t -> t.pos().equals(pos));
	}

	/** Adds a block's thruster (if it is one, with a clear exhaust and thrust). */
	public void addThruster(BlockPos pos, BlockState state, boolean clearExhaust, ShipStats.BlockStats stats) {
		double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
		if (state.getBlock() instanceof ThrusterBlock && clearExhaust && stats.thrust() > 0) {
			Direction nozzle = state.getValue(ThrusterBlock.FACING);
			Direction push = nozzle.getOpposite();
			thrusters.add(new Thruster(new Vector3d(x, y, z), new Vector3d(push.getStepX(), push.getStepY(), push.getStepZ()),
					stats.thrust(), stats.fuelBurn(), nozzle, pos));
		}
	}

	/** Computes centre of mass, inertia and the thruster matrix. Call once after adding blocks. */
	public ShipDynamics finish() {
		mass = Math.max(rawMass, 1);
		centre.set(weightedPos).div(mass);
		// Second moments about the centre of mass from the running sums (parallel axis theorem).
		double cx = centre.x, cy = centre.y, cz = centre.z, wx = weightedPos.x, wy = weightedPos.y, wz = weightedPos.z;
		double qxx = sxx - 2 * cx * wx + rawMass * cx * cx;
		double qyy = syy - 2 * cy * wy + rawMass * cy * cy;
		double qzz = szz - 2 * cz * wz + rawMass * cz * cz;
		double xx = qyy + qzz + cubeSum, yy = qxx + qzz + cubeSum, zz = qxx + qyy + cubeSum;
		double xy = -(sxy - cx * wy - cy * wx + rawMass * cx * cy);
		double xz = -(sxz - cx * wz - cz * wx + rawMass * cx * cz);
		double yz = -(syz - cy * wz - cz * wy + rawMass * cy * cz);
		// JOML Matrix3d(m00, m01, m02, m10, ...) is column-major; the tensor is symmetric anyway.
		inertia.set(xx, xy, xz, xy, yy, yz, xz, yz, zz);
		inertia.invert(inverseInertia);
		gyration = Math.max(1.0, Math.sqrt((xx + yy + zz) / (2 * mass)));
		area.set(Math.max(1, shadowX.size()), Math.max(1, shadowY.size()), Math.max(1, shadowZ.size()));

		buildColumns();
		return this;
	}

	/**
	 * M9 pass 3: a rigid motion for articulated thrusters (a hinge or rotor turning part of the ship about a pivot).
	 * Positions and pushes are in ship-local block coordinates.
	 */
	public record Pose(Vector3d pivot, org.joml.Quaterniond rotation) {
		public Vector3d point(Vector3d p) {
			return rotation.transform(new Vector3d(p).sub(pivot)).add(pivot);
		}

		public Vector3d direction(Vector3d d) {
			return rotation.transform(new Vector3d(d));
		}
	}

	private java.util.function.Function<BlockPos, @org.jspecify.annotations.Nullable Pose> poses = pos -> null;
	private Vector3d[] posedPosition = new Vector3d[0], posedPush = new Vector3d[0];

	/** Re-poses articulated thrusters (null = not articulated) and rebuilds the thruster matrix. */
	public void articulate(java.util.function.Function<BlockPos, @org.jspecify.annotations.Nullable Pose> poses) {
		this.poses = poses;
		buildColumns();
	}

	/** A thruster's current position and push direction (articulation applied). */
	public Vector3d position(int k) {
		return posedPosition[k];
	}

	public Vector3d push(int k) {
		return posedPush[k];
	}

	private void buildColumns() {
		int n = thrusters.size();
		columns = new double[n][];
		posedPosition = new Vector3d[n];
		posedPush = new Vector3d[n];
		for (int k = 0; k < n; k++) {
			Thruster t = thrusters.get(k);
			Pose pose = poses.apply(t.pos());
			Vector3d position = pose == null ? new Vector3d(t.position()) : pose.point(t.position());
			Vector3d push = pose == null ? new Vector3d(t.push()) : pose.direction(t.push());
			posedPosition[k] = position;
			posedPush[k] = push;
			Vector3d r = new Vector3d(position).sub(centre);
			Vector3d torque = r.cross(push, new Vector3d());
			columns[k] = new double[] {push.x * t.thrust(), push.y * t.thrust(), push.z * t.thrust(),
					torque.x * t.thrust(), torque.y * t.thrust(), torque.z * t.thrust()};
		}
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
		buf.writeDouble(area.x).writeDouble(area.y).writeDouble(area.z);
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
		d.area.set(buf.readDouble(), buf.readDouble(), buf.readDouble());
		d.buildColumns();
		return d;
	}

	public double mass() { return mass; }
	public Vector3d centre() { return centre; }
	public Matrix3d inertia() { return inertia; }
	public Matrix3d inverseInertia() { return inverseInertia; }
	public List<Thruster> thrusters() { return thrusters; }
	public int thrusterCount() { return thrusters.size(); }

	/** Cross-section facing the air along each local axis (blocks²). */
	public Vector3d area() { return area; }

	/**
	 * Air drag (M9 pass 2; replaces 3i's wings: starships don't fly on wings). Quadratic drag on each local axis,
	 * {@code coefficient x density x area x |v| x v}, divided by mass: a long thin ship slips through the air nose
	 * first and slows hard broadside; thin air is slippery, thick air sluggish. Acts at the centre of mass (no
	 * torque), so it never fights the pilot's attitude.
	 *
	 * @param velocity local velocity (blocks/tick)
	 * @return local acceleration (blocks/tick²)
	 */
	public Vector3d drag(Vector3d velocity, double density, double coefficient) {
		Vector3d out = new Vector3d();
		if (density <= 0 || coefficient <= 0) return out;
		double speed = velocity.length();
		double k = coefficient * density * speed / mass;
		out.set(-k * area.x * velocity.x, -k * area.y * velocity.y, -k * area.z * velocity.z);
		// Never more than stops the ship this tick (stiff at low mass / huge areas).
		for (int axis = 0; axis < 3; axis++) {
			double v = velocity.get(axis), a = out.get(axis);
			if (Math.abs(a) > Math.abs(v)) out.setComponent(axis, -v);
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
		for (int k = 0; k < thrusters.size(); k++) {
			if (pushesAlong(k, push)) sum += thrusters.get(k).thrust();
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
	 * @param weight    vertical force needed to hover (thrust units); 
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
		for (int k = 0; k < thrusters.size(); k++) raw += Math.max(0, posedPush[k].dot(unit)) * thrusters.get(k).thrust();
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
		for (int k = 0; k < thrusters.size(); k++) {
			if (pushesAlong(k, push)) sum += thrusters.get(k).burn();
		}
		return sum;
	}

	/** Whether thruster k (as posed) pushes mostly along a local direction. */
	private boolean pushesAlong(int k, Direction push) {
		Vector3d p = posedPush[k];
		return p.x * push.getStepX() + p.y * push.getStepY() + p.z * push.getStepZ() > 0.7;
	}

	/** Fuel burned per tick at these throttles (before boost/power scaling). */
	public double burn(double[] throttles) {
		double sum = 0;
		for (int k = 0; k < thrusters.size(); k++) sum += thrusters.get(k).burn() * throttles[k];
		return sum;
	}
}
