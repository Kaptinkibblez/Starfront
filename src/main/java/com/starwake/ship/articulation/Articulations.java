package com.starwake.ship.articulation;

import com.starwake.ship.ShipStructure;
import com.starwake.ship.block.JointBlock;
import com.starwake.ship.systems.ShipDynamics;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * A ship's moving sub-assemblies (M9 pass 3). Every Hinge or Rotor whose facing side leads to blocks that don't connect
 * back to the Helm except through it owns those blocks as a group, turned about the joint's facing axis through the centre
 * of its face. The blocks stay in the structure (and the shipyard plot) in their as-built pose; only rendering and
 * thrusters use the turned pose, and collision keeps the as-built one.
 *
 * Hinge angles follow the landing gear (down / up angle from the block state) at {@link #HINGE_RATE}, are run by the
 * server and synced as a string; rotors turn with game time, so both sides agree without syncing. Nested joints are not
 * articulated on their own (their part turns with the outer group).
 */
public final class Articulations {
	public static final Articulations NONE = new Articulations(List.of(), new Long2IntOpenHashMap());
	/** Hinge speed, degrees per tick (45 degrees a second). */
	public static final double HINGE_RATE = 2.25;
	private static final int MAX_GROUP = 65_536;

	/** One joint: the joint block, its facing (the turning axis) and the pivot (centre of its facing face). */
	public record Joint(BlockPos hinge, Direction facing, boolean rotor, double rpm, double downAngle, double upAngle, Vector3d pivot, int size) {}

	private final List<Joint> joints;
	private final Long2IntOpenHashMap group;
	/** Current and previous-tick hinge angles in degrees, per joint index. */
	private double[] angle, prev;
	/** Client: the last synced hinge angles, adopted at the next client tick. */
	private double[] synced;

	private Articulations(List<Joint> joints, Long2IntOpenHashMap group) {
		this.joints = joints;
		this.group = group;
		group.defaultReturnValue(-1);
		this.angle = new double[joints.size()];
		this.prev = new double[joints.size()];
		this.synced = new double[joints.size()];
	}

	/** Finds the joints of a structure; hinge angles carry over from {@code old} by joint position. */
	public static Articulations detect(ShipStructure structure, BlockPos helm, Articulations old) {
		List<Joint> joints = new ArrayList<>();
		Long2IntOpenHashMap group = new Long2IntOpenHashMap();
		group.defaultReturnValue(-1);
		for (ShipStructure.Entry entry : structure.entries()) {
			BlockState state = entry.state();
			if (!(state.getBlock() instanceof JointBlock joint)) continue;
			Direction facing = state.getValue(JointBlock.FACING);
			BlockPos start = entry.pos().relative(facing);
			if (structure.get(start) == null || group.containsKey(start.asLong())) continue;
			it.unimi.dsi.fastutil.longs.LongOpenHashSet cells = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
			LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
			cells.add(start.asLong());
			queue.enqueue(start.asLong());
			long hingeKey = entry.pos().asLong(), helmKey = helm.asLong();
			boolean valid = true;
			BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
			while (!queue.isEmpty() && valid) {
				long cell = queue.dequeueLong();
				for (Direction d : Direction.values()) {
					next.set(BlockPos.getX(cell) + d.getStepX(), BlockPos.getY(cell) + d.getStepY(), BlockPos.getZ(cell) + d.getStepZ());
					long key = next.asLong();
					if (key == hingeKey || cells.contains(key) || structure.get(next) == null) continue;
					if (key == helmKey || cells.size() >= MAX_GROUP || group.containsKey(key)) {
						valid = false; // loops back to the hull (or overlaps another joint): rigid
						break;
					}
					cells.add(key);
					queue.enqueue(key);
				}
			}
			if (!valid) continue;
			int index = joints.size();
			for (long cell : cells) group.put(cell, index);
			Vector3d pivot = new Vector3d(entry.pos().getX() + 0.5 + facing.getStepX() * 0.5, entry.pos().getY() + 0.5 + facing.getStepY() * 0.5,
					entry.pos().getZ() + 0.5 + facing.getStepZ() * 0.5);
			boolean rotor = joint.isRotor();
			joints.add(new Joint(entry.pos().immutable(), facing, rotor, rotor ? JointBlock.RPM[state.getValue(JointBlock.SPEED)] : 0,
					state.getValue(JointBlock.DOWN_ANGLE) * 15.0, state.getValue(JointBlock.UP_ANGLE) * 15.0, pivot, cells.size()));
		}
		if (joints.isEmpty()) return NONE;
		Articulations out = new Articulations(List.copyOf(joints), group);
		for (int i = 0; i < joints.size(); i++) {
			int o = old.indexOf(joints.get(i).hinge());
			double a = o >= 0 ? old.angle[o] : joints.get(i).downAngle();
			out.angle[i] = a;
			out.prev[i] = a;
			out.synced[i] = o >= 0 ? old.synced[o] : a;
		}
		return out;
	}

	/**
	 * Pre-flight (world blocks, before assembly): every hinge's part posed at its gear-up angle, so the report can check
	 * the cruise pose too. Same grouping rule as {@link #detect}; null for blocks that don't move.
	 */
	public static java.util.function.Function<BlockPos, ShipDynamics.@Nullable Pose> gearUpPoses(java.util.Set<BlockPos> ship,
			net.minecraft.world.level.Level level, BlockPos helm) {
		Map<BlockPos, ShipDynamics.Pose> poses = new HashMap<>();
		for (BlockPos pos : ship) {
			BlockState state = level.getBlockState(pos);
			if (!(state.getBlock() instanceof JointBlock joint) || joint.isRotor()) continue;
			Direction facing = state.getValue(JointBlock.FACING);
			double angle = state.getValue(JointBlock.UP_ANGLE) * 15.0;
			BlockPos start = pos.relative(facing);
			if (angle == 0 || !ship.contains(start) || poses.containsKey(start)) continue;
			java.util.Set<BlockPos> cells = new java.util.HashSet<>(List.of(start));
			java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>(cells);
			boolean valid = true;
			while (!queue.isEmpty() && valid) {
				BlockPos cell = queue.poll();
				for (Direction d : Direction.values()) {
					BlockPos next = cell.relative(d);
					if (next.equals(pos) || cells.contains(next) || !ship.contains(next)) continue;
					if (next.equals(helm) || cells.size() >= MAX_GROUP || poses.containsKey(next)) {
						valid = false;
						break;
					}
					cells.add(next);
					queue.add(next);
				}
			}
			if (!valid) continue;
			Vector3d pivot = new Vector3d(pos.getX() + 0.5 + facing.getStepX() * 0.5, pos.getY() + 0.5 + facing.getStepY() * 0.5,
					pos.getZ() + 0.5 + facing.getStepZ() * 0.5);
			// Same as the flying ship: the hinge angle turns the part from its as-built pose.
			double rad = Math.toRadians(angle);
			ShipDynamics.Pose pose = new ShipDynamics.Pose(pivot,
					new Quaterniond().fromAxisAngleRad(facing.getStepX(), facing.getStepY(), facing.getStepZ(), rad));
			for (BlockPos cell : cells) poses.put(cell, pose);
		}
		return poses.isEmpty() ? null : poses::get;
	}

	private int indexOf(BlockPos hinge) {
		for (int i = 0; i < joints.size(); i++) if (joints.get(i).hinge().equals(hinge)) return i;
		return -1;
	}

	public boolean isEmpty() {
		return joints.isEmpty();
	}

	public int count() {
		return joints.size();
	}

	public Joint joint(int i) {
		return joints.get(i);
	}

	/** The group (joint index) a ship-local cell turns with, or -1. */
	public int groupOf(BlockPos local) {
		return group.get(local.asLong());
	}

	public int groupOf(long local) {
		return group.get(local);
	}

	/** Same joints and membership (so meshes can stay). */
	public boolean sameLayout(Articulations other) {
		if (joints.size() != other.joints.size()) return false;
		for (int i = 0; i < joints.size(); i++) {
			Joint a = joints.get(i), b = other.joints.get(i);
			if (!a.hinge().equals(b.hinge()) || a.facing() != b.facing() || a.size() != b.size()) return false;
		}
		return true;
	}

	public boolean hasRotors() {
		for (Joint j : joints) if (j.rotor() && j.rpm() > 0) return true;
		return false;
	}

	/** Server: steps hinges toward their gear pose; true if any moved. */
	public boolean tickServer(boolean gearDown) {
		boolean moved = false;
		for (int i = 0; i < joints.size(); i++) {
			prev[i] = angle[i];
			Joint j = joints.get(i);
			if (j.rotor()) continue;
			double target = gearDown ? j.downAngle() : j.upAngle();
			double delta = Mth.wrapDegrees(target - angle[i]);
			if (Math.abs(delta) < 1e-6) continue;
			angle[i] = Mth.wrapDegrees(angle[i] + Math.max(-HINGE_RATE, Math.min(HINGE_RATE, delta)));
			moved = true;
		}
		return moved;
	}

	/** Client: last tick's angles become the interpolation start, the synced ones the end. */
	public void tickClient() {
		System.arraycopy(angle, 0, prev, 0, angle.length);
		System.arraycopy(synced, 0, angle, 0, angle.length);
	}

	/** Whether any hinge is between poses. */
	public boolean moving() {
		for (int i = 0; i < joints.size(); i++) if (angle[i] != prev[i]) return true;
		return false;
	}

	/** Angle of joint i in degrees (rotors from game time, hinges interpolated). */
	public double angle(int i, long gameTime, float partial) {
		Joint j = joints.get(i);
		if (j.rotor()) return ((gameTime + partial) * j.rpm() * 0.3) % 360.0;
		return prev[i] + Mth.wrapDegrees(angle[i] - prev[i]) * partial;
	}

	/** The rotation of group i about its pivot (axis = the joint's facing). */
	public Quaterniond rotation(int i, long gameTime, float partial) {
		Joint j = joints.get(i);
		double rad = Math.toRadians(angle(i, gameTime, partial));
		return new Quaterniond().fromAxisAngleRad(j.facing().getStepX(), j.facing().getStepY(), j.facing().getStepZ(), rad);
	}

	/** Thruster poses for {@link ShipDynamics#articulate}. */
	public java.util.function.Function<BlockPos, ShipDynamics.@Nullable Pose> poses(long gameTime) {
		if (joints.isEmpty()) return pos -> null;
		ShipDynamics.Pose[] poses = new ShipDynamics.Pose[joints.size()];
		for (int i = 0; i < poses.length; i++) poses[i] = new ShipDynamics.Pose(joints.get(i).pivot(), rotation(i, gameTime, 1));
		return pos -> {
			int g = group.get(pos.asLong());
			return g < 0 ? null : poses[g];
		};
	}

	/** Hinge angles for syncing ("x,y,z=deg;..."). */
	public String encode() {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < joints.size(); i++) {
			if (joints.get(i).rotor()) continue;
			BlockPos p = joints.get(i).hinge();
			b.append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()).append('=')
					.append(String.format(java.util.Locale.ROOT, "%.2f", angle[i])).append(';');
		}
		return b.toString();
	}

	/** Client: adopt synced hinge angles. */
	public void decode(String encoded) {
		Map<BlockPos, Double> values = new HashMap<>();
		for (String part : encoded.split(";")) {
			int eq = part.indexOf('=');
			if (eq < 0) continue;
			String[] xyz = part.substring(0, eq).split(",");
			try {
				values.put(new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])), Double.parseDouble(part.substring(eq + 1)));
			} catch (RuntimeException ignored) {
				// malformed entry: skip
			}
		}
		for (int i = 0; i < joints.size(); i++) {
			Double v = values.get(joints.get(i).hinge());
			if (v != null) synced[i] = v;
		}
	}

	private static final class Mth {
		static double wrapDegrees(double d) {
			return net.minecraft.util.Mth.wrapDegrees(d);
		}
	}
}
