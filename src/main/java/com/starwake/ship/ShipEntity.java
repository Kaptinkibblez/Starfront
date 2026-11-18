package com.starwake.ship;

import com.starwake.planet.Planets;
import com.starwake.ship.block.HelmBlock;
import com.starwake.ship.block.SeatBlock;
import com.starwake.ship.block.ThrusterBlock;
import com.starwake.ship.data.ShipPhysics;
import com.starwake.ship.data.ShipStats;
import com.starwake.ship.data.HullTuning;
import com.starwake.ship.data.ShipMaterials;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import com.starwake.ship.systems.ShipDynamics;
import com.starwake.ship.systems.ShipSystems;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * An assembled, flying ship: a persistent entity that carries a {@link ShipStructure}, with a
 * full 6DOF transform.
 *
 * <h2>Frames</h2>
 * Blocks live in ship-local coordinates (block {@code p} fills {@code [p, p+1]}). The ship
 * rotates by {@link #orientation()} about {@link #pivot()} (the centre of the structure's
 * bounding box). The entity position is the pivot's world position, so
 * {@code world = position + R * (local - pivot)}.
 *
 * <h2>Sides</h2>
 * Server: flight physics from the pilot (mouse-aim steering, WASD/Space/Ctrl thrust, Z/C
 * roll, Shift boost), rotated terrain collision, carrying mobs, and seats.
 * Client: rendering (ShipRenderer), approximate collision shapes for prediction, and
 * interpolated orientation. Walking on a tilted deck is approximate until 3e
 * (ship-local physics); crew should sit in Seats while the ship maneuvers.
 */
public class ShipEntity extends Entity {
	private static final EntityDataAccessor<Integer> FUEL = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Quaternionfc> ORIENTATION = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.QUATERNION);
	/**
	 * Seat assignments, synced so clients place riders correctly. Encoded as
	 * {@code "uuid=packedPos;uuid=packedPos"} (26.3 has no compound-tag data serializer).
	 */
	private static final EntityDataAccessor<String> SEATS = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.STRING);
	/** M9 pass 3: hinge angles of the ship's sub-assemblies ({@link com.starwake.ship.articulation.Articulations#encode}). */
	private static final EntityDataAccessor<String> JOINTS = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.STRING);
	// Ship systems state for the client HUD (computed server-side, where ship stats data lives).
	private static final EntityDataAccessor<Integer> FUEL_CAPACITY = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
	/** M8f: fusion fuel (D + He-3) units for the quantum and jump drives; capacity is 10 x the propellant capacity. */
	private static final EntityDataAccessor<Integer> FUSION = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
	static final EntityDataAccessor<Float> SHIELD = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> SHIELD_MAX = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.FLOAT);
	/** Packed power split percents (0-100 each): engines * 1_000_000 + shields * 1000 + quantum. */
	private static final EntityDataAccessor<Integer> POWER_SPLIT = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Float> ENGINE_FACTOR = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.FLOAT);
	/** M10: heat as a fraction of the buffer (1 = overheating). */
	private static final EntityDataAccessor<Float> THERMAL = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.FLOAT);
	/** M10: most pips each subsystem can use (5 bits each), for the pip screen. */
	private static final EntityDataAccessor<Integer> PIP_MAX = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
	/** Quantum spool progress 0..1, or -1 when not spooling. */
	static final EntityDataAccessor<Float> QUANTUM = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.FLOAT);
	/** Orbited planet's dimension id ("" when not in space / unknown). */
	private static final EntityDataAccessor<String> ORBIT = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.STRING);
	/** Quantum drive state bits ({@code QT_*}). */
	static final EntityDataAccessor<Integer> QT_STATE = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
	/** Selected quantum target (planet registry id, "" for none). */
	static final EntityDataAccessor<String> QT_TARGET = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.STRING);
	/** Fuel cost of the selected jump (in quantum mode), or ticks left (while travelling). */
	static final EntityDataAccessor<Integer> QT_INFO = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
	/**
	 * During a jump: how far the ship has "really" travelled from where it sits. The hull
	 * stays put in the world until the jump ends; the crew see space as if from start + offset.
	 */
	/** The shipyard plot index, for clients (sign editing aboard maps plot positions back to the ship). */
	private static final EntityDataAccessor<Integer> PLOT = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
	static final EntityDataAccessor<org.joml.Vector3fc> QT_OFFSET = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.VECTOR3);

	/** Firing thrusters for exhaust effects (see {@link #publishThrottles}). */
	private static final EntityDataAccessor<String> THROTTLES = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.STRING);

	private static final EntityDataAccessor<String> ACCESS = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Boolean> DECOUPLED = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.BOOLEAN);
	/** Jump drive (M6): state bits ({@code JD_*}), target system id, info (fuel or ticks left), spool. */
	static final EntityDataAccessor<Integer> JD_STATE = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
	static final EntityDataAccessor<String> JD_TARGET = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.STRING);
	static final EntityDataAccessor<Integer> JD_INFO = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
	static final EntityDataAccessor<Float> JD_SPOOL = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.FLOAT);
	/** Jump range in light-years (ship stats are server data; the galaxy map needs it). */
	private static final EntityDataAccessor<Float> JD_REACH = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.FLOAT);

	/** {@link #jumpState()} bits. */
	public static final int JD_MODE = 1, JD_TRAVEL = 2, JD_ALIGNED = 4, JD_WELL = 8, JD_NO_FUEL = 16, JD_RANGE = 32,
			JD_NO_TARGET = 64, JD_SPOOLED = 128;

	/** {@link #quantumState()} bits. */
	public static final int QT_MODE = 1, QT_TRAVEL = 2, QT_ALIGNED = 4, QT_OBSTRUCTED = 8, QT_NO_FUEL = 16,
			QT_TOO_CLOSE = 32, QT_NO_TARGET = 64, QT_SPOOLED = 128, QT_NO_DRIVE = 256;

	/** Control bits from {@link com.starwake.network.ShipControlPayload}. */
	public static final int CONTROL_ROLL_LEFT = 1, CONTROL_ROLL_RIGHT = 2, CONTROL_FREE_LOOK = 4, CONTROL_JOYSTICK = 8, CONTROL_NAV = 16;

	/** Height of the pilot's seat above the Helm block's base (M9: raised so the eye clears the dash). */
	public static final double HELM_SEAT_HEIGHT = 1.4;

	/** Save-format version: 2 = position is the pivot (1/absent = position was the min corner). */
	private static final int FRAME_VERSION = 2;

	private @Nullable ShipStructure structure;
	private BlockPos helmLocal = BlockPos.ZERO;
	/** The ship's local "forward" (the Helm's facing at assembly). */
	private Direction forward = Direction.NORTH;
	private Vec3 pivot = Vec3.ZERO;
	private int fuelCapacity;
	/** Pips, heat, wear and the weapon capacitor (M10). */
	public final ShipPower power = new ShipPower(this);
	private ShipSystems systems = new ShipSystems();
	/** Quantum and jump drives (M10: one flow, split out). */
	public final ShipDrives drives = new ShipDrives(this);
	/** Client: travel offset at the previous tick, for interpolation. */
	private final Vector3f prevQtOffset = new Vector3f();
	private final Vector3f lastTickQtOffset = new Vector3f();
	private @Nullable ResourceKey<Level> orbiting;
	private boolean sealed;
	/** Counts structure changes so clients can tell whether a delta applies to what they hold (M9 pass 3). */
	private int structureVersion;
	/** The seal needs recomputing (server, lazily on first query: an air flood fill can be big on big ships). */
	private boolean sealDirty = true;

	// Derived from the structure (recomputed on load; never saved). Keyed by LOCAL direction.
	private double mass = 1;
	private double[] hullProbes = new double[0];
	private double radius = 1;
	/** Rigid-body model: centre of mass, inertia, thrusters (3h). */
	private ShipDynamics dynamics = new ShipDynamics().finish();
	/** Structure entry index of each thruster in {@code dynamics} (for the synced throttles). */
	private int[] thrusterEntries = new int[0];
	/** Flight computer output, one per thruster (warm start for the next tick). */
	double[] throttles = new double[0];
	/** Angular velocity in the ship's own frame, radians per tick. */
	final Vector3d angularVelocity = new Vector3d();
	private final java.util.Set<BlockPos> seats = new java.util.LinkedHashSet<>();
	/**
	 * Collision boxes (local) of blocks with any exposed face: the hull used for terrain
	 * collision. Each is the block's real shape bounds, so retracted landing gear doesn't
	 * touch the ground and deployed gear reaches below its block.
	 */
	private final Map<BlockPos, AABB> hull = new HashMap<>();
	/** Functional parts (anything with ship-system stats: thrusters, plants, coolers, drives, tanks...). M9 pass 3. */
	private final java.util.Set<BlockPos> components = new java.util.HashSet<>();
	/** Per block: mass factor, heat limit and armour from its material (M8e-1). */
	final Map<BlockPos, ShipMaterials.Rating> ratings = new HashMap<>();
	/** Landing gear blocks aboard (local positions). */
	private final java.util.Set<BlockPos> gear = new java.util.LinkedHashSet<>();

	/** Server: which seat (local pos) each passenger occupies. The Helm's seat makes you the pilot. */
	private final Map<UUID, BlockPos> seatOf = new HashMap<>();
	int controls;

	// Per-tick bookkeeping, both sides.
	private Vec3 lastMove = Vec3.ZERO;
	private @Nullable Vec3 lastTickPos;
	private Quaternionf lastTickOrientation = new Quaternionf();
	/** Client: orientation at the previous tick, for render interpolation. */
	private final Quaternionf prevOrientation = new Quaternionf();
	private boolean grounded;
	/** Admin/testing: constant velocity while unpiloted ({@code /starwake ship drift}). Not saved. */
	private Vec3 drift = Vec3.ZERO;
	private Vec3 spin = Vec3.ZERO;
	private double fuelDebt;

	public ShipEntity(EntityType<? extends ShipEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true; // we move ourselves; vanilla movement code never runs
	}

	// =========================================================================================
	// Setup and stats
	// =========================================================================================

	/** Server: fills a freshly created ship from an assembly (orientation = identity). */
	public void initialize(ShipStructure structure, BlockPos helmLocal, Direction forward, int fuel, int fuelCapacity) {
		this.helmLocal = helmLocal;
		this.forward = forward;
		this.fuelCapacity = fuelCapacity;
		this.entityData.set(FUEL, fuel);
		this.entityData.set(FUEL_CAPACITY, fuelCapacity);
		setStructure(structure);
		entityData.set(SHIELD, (float) systems.shieldCapacity()); // shields start charged
	}

	/** Sets the blocks (server on assembly/load; client when the structure payload arrives). */
	public void setStructure(ShipStructure structure) {
		this.structure = structure;
		this.pivot = structure.pivot(); // fixed at assembly, kept through edits
		long t0 = System.nanoTime();
		recomputeStats();
		sealDirty = true;
		if (structure.entries().size() >= 10_000) {
			com.starwake.StarwakeMod.LOGGER.info("Big ship model: {} blocks ({}), built in {} ms ({} probes)",
					structure.entries().size(), level().isClientSide() ? "client" : "server", (System.nanoTime() - t0) / 1_000_000,
					hullProbes.length / 3);
		}
		setBoundingBox(makeBoundingBox(position()));
	}

	public @Nullable ShipStructure structure() {
		return structure;
	}

	/** The structure's version (M9 pass 3; deltas apply only on top of the version they were made from). */
	public int structureVersion() {
		return structureVersion;
	}

	/** Client: a full structure from the server, at its version. */
	public void setStructure(ShipStructure structure, int version) {
		structureVersion = version;
		setStructure(structure);
		meshAll = true;
		meshCells.clear();
	}

	/**
	 * Client, for the renderer (M9 pass 3): cells changed since it last rebuilt this ship's mesh sections, or "all".
	 * The renderer reads and clears them with {@link #takeMeshChanges}.
	 */
	private final java.util.Set<BlockPos> meshCells = new java.util.HashSet<>();
	private boolean meshAll = true;

	/** Client renderer: the cells to re-tessellate (null = everything), then forgets them. */
	public java.util.@Nullable Set<BlockPos> takeMeshChanges() {
		if (meshAll) {
			meshAll = false;
			meshCells.clear();
			return null;
		}
		var out = java.util.Set.copyOf(meshCells);
		meshCells.clear();
		return out;
	}

	/**
	 * Client: a delta from the server (M9 pass 3). Applies when we hold the version it was made from; returns false
	 * otherwise (the caller asks for a full resync).
	 */
	public boolean applyDelta(int version, Map<BlockPos, ShipStructure.@Nullable Entry> changes) {
		if (structure == null || version != structureVersion + 1) return false;
		structureVersion = version;
		applyIncremental(structure.withChanges(changes), changes);
		meshCells.addAll(changes.keySet());
		return true;
	}

	public Vec3 pivot() {
		return pivot;
	}

	private void recomputeStats() {
		mass = 0;
		seats.clear();
		hull.clear();
		ratings.clear();
		gear.clear();
		components.clear();
		if (structure == null) return;
		ShipDynamics model = new ShipDynamics();
		for (ShipStructure.Entry entry : structure.entries()) addCell(model, entry);
		for (ShipStructure.Entry entry : structure.entries()) refreshHull(entry.pos());
		dynamics = model.finish();
		afterModelChange();
	}

	/**
	 * M9 pass 3: folds a set of changed cells into the derived model without rebuilding it (null entry = removed).
	 * Mass, inertia and cross-sections are running sums; the hull map is refreshed only around the changes; thrusters
	 * next to a change are re-checked for a clear exhaust; systems are recomputed over the functional parts only.
	 */
	private void applyIncremental(ShipStructure next, Map<BlockPos, ShipStructure.@Nullable Entry> changes) {
		ShipStructure prev = structure;
		java.util.Set<BlockPos> exhaustCheck = new java.util.HashSet<>();
		for (BlockPos pos : changes.keySet()) {
			BlockState old = prev.get(pos);
			if (old != null) removeCell(dynamics, pos, old);
			for (Direction d : Direction.values()) {
				BlockPos n = pos.relative(d);
				BlockState ns = prev.get(n);
				if (!changes.containsKey(n) && ns != null && ns.getBlock() instanceof ThrusterBlock
						&& n.relative(ns.getValue(ThrusterBlock.FACING)).equals(pos)) {
					exhaustCheck.add(n.immutable());
					dynamics.removeThruster(n);
				}
			}
		}
		structure = next;
		for (ShipStructure.Entry entry : changes.values()) if (entry != null) addCell(dynamics, entry);
		for (BlockPos n : exhaustCheck) {
			BlockState state = next.get(n);
			ShipMaterials.Rating rating = ratings.get(n);
			if (state != null && rating != null) dynamics.addThruster(n, state, exhaustClear(n, state), rating.stats());
		}
		for (BlockPos pos : changes.keySet()) {
			refreshHull(pos);
			for (Direction d : Direction.values()) refreshHull(pos.relative(d));
		}
		dynamics.finish();
		afterModelChange();
		sealDirty = true;
		setBoundingBox(makeBoundingBox(position()));
	}

	private boolean exhaustClear(BlockPos pos, BlockState state) {
		return !(state.getBlock() instanceof ThrusterBlock) || structure.get(pos.relative(state.getValue(ThrusterBlock.FACING))) == null;
	}

	private void addCell(ShipDynamics model, ShipStructure.Entry entry) {
		BlockState state = entry.state();
		BlockPos pos = entry.pos();
		ShipMaterials.Rating rating = ShipMaterials.rate(state, ShipMaterials.of(entry.blockEntity()), ShipMaterials.quality(entry.blockEntity()));
		ShipStats.BlockStats base = rating.stats();
		if (base.thrust() > 0 || base.powerOutput() > 0 || base.cooling() > 0 || base.shieldCapacity() > 0 || base.quantumSpool() > 0
				|| base.jumpRange() > 0) {
			// M10: a worn part works less well (and not at all below 25%).
			float health = power.load(pos, ShipMaterials.health(entry.blockEntity()));
			rating = new ShipMaterials.Rating(rating.massFactor(), rating.heatLimit(), rating.armor(), ShipMaterials.withHealth(base, health));
		}
		ratings.put(pos, rating);
		model.add(pos, state, exhaustClear(pos, state), rating.massFactor(), rating.stats());
		if (state.getBlock() instanceof com.starwake.ship.block.LandingGearBlock) gear.add(pos);
		if (state.getBlock() instanceof HelmBlock) {
			helmLocal = pos;
			forward = state.getValue(HelmBlock.FACING);
		} else if (state.getBlock() instanceof SeatBlock) {
			seats.add(pos);
		}
		ShipStats.BlockStats st = base; // what the part is, worn or not (a disabled part is still a part)
		if (state.getBlock() instanceof ThrusterBlock || st.powerOutput() > 0 || st.heat() > 0 || st.cooling() > 0 || st.powerDraw() > 0
				|| st.shieldCapacity() > 0 || st.quantumSpool() > 0 || st.jumpRange() > 0 || st.fuelCapacity() > 0) {
			components.add(pos);
		}
	}

	private void removeCell(ShipDynamics model, BlockPos pos, BlockState state) {
		ShipMaterials.Rating rating = ratings.remove(pos);
		model.remove(pos, state, rating == null ? 1.0 : rating.massFactor());
		gear.remove(pos);
		seats.remove(pos);
		components.remove(pos);
	}

	/** A cell is outer hull if it collides and any face neighbour doesn't. */
	private void refreshHull(BlockPos pos) {
		BlockState state = structure.get(pos);
		if (state == null || !structure.hasCollision(pos)) {
			hull.remove(pos);
			return;
		}
		for (Direction d : Direction.values()) {
			if (!structure.hasCollision(pos.relative(d))) {
				hull.put(pos.immutable(), state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).bounds().move(pos));
				return;
			}
		}
		hull.remove(pos);
	}

	/** Everything derived from the model: probes, trackers' copy of the model, throttle slots, systems. */
	private void afterModelChange() {
		mass = dynamics.mass();
		// Big ships (M9 pass 3): rebuilding thousands of probes for every block placed would hitch each edit, so an edit
		// marks them stale and they're rebuilt once edits pause (contacts use the previous set meanwhile).
		if (hullProbes.length > 0 && hull.size() > PROBE_BUDGET / 4 && tickCount > 0) probesStaleSince = tickCount;
		else computeProbes();
		if (level() instanceof ServerLevel && tickCount > 0) {
			// M9: trackers get the new model (pilots predict with it).
			var payload = com.starwake.network.ShipDynamicsPayload.of(getId(), dynamics);
			for (ServerPlayer player : net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(this)) {
				net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
			}
		}
		// M9 pass 3: hinges and rotors (sub-assemblies) and the thruster poses they give.
		var oldJoints = articulations;
		articulations = com.starwake.ship.articulation.Articulations.detect(structure, helmLocal, oldJoints);
		if (level().isClientSide() && !articulations.sameLayout(oldJoints)) meshAll = true;
		applyArticulation();
		var thrusters = dynamics.thrusters();
		thrusterEntries = new int[thrusters.size()];
		for (int k = 0; k < thrusterEntries.length; k++) thrusterEntries[k] = structure.indexOf(thrusters.get(k).pos());
		throttles = new double[thrusterEntries.length];

		systems = new ShipSystems();
		int capacity = 0;
		for (BlockPos pos : components) {
			BlockState state = structure.get(pos);
			if (state == null) continue;
			ShipMaterials.Rating rating = ratings.get(pos);
			ShipStats.BlockStats stats = rating == null ? ShipStats.get(state) : rating.stats();
			systems.add(state, exhaustClear(pos, state), stats);
			capacity += stats.fuelCapacity();
		}
		componentFuelCapacity = capacity;
		power.refit();
		publishSystems();
	}

	/** Sum of the fuel tanks' capacity (with quality), kept by {@link #afterModelChange()}. */
	private int componentFuelCapacity;

	/** Server: pushes systems numbers to the synced HUD fields. */
	/** Moving sub-assemblies (M9 pass 3). */
	private com.starwake.ship.articulation.Articulations articulations = com.starwake.ship.articulation.Articulations.NONE;

	public com.starwake.ship.articulation.Articulations articulations() {
		return articulations;
	}

	/** Poses articulated thrusters for the flight computer from the current joint angles. */
	private void applyArticulation() {
		if (!articulations.isEmpty()) dynamics.articulate(articulations.poses(level().getGameTime()));
	}

	/** Server, each tick: hinges follow the landing gear (gear-down pose when the ship has no gear). */
	private void tickJoints() {
		if (articulations.isEmpty()) return;
		boolean moved = articulations.tickServer(gear.isEmpty() || isGearDown());
		if (moved) entityData.set(JOINTS, articulations.encode());
		if (moved || articulations.hasRotors()) applyArticulation();
	}

	/** Tick of the last edit whose probes are still pending (-1 = up to date). */
	private int probesStaleSince = -1;

	/** Probe count above which big ships are thinned to one probe per lattice cell (M9 pass 3). */
	private static final int PROBE_BUDGET = 6000;

	/**
	 * Contact probes for {@link com.starwake.ship.physics.ShipMotion}: the unique corners of the hull boxes. Big ships
	 * keep one probe per cubic lattice cell (the one farthest from the centre of mass, so silhouettes and sharp edges
	 * survive), sized to stay near {@link #PROBE_BUDGET}; landing gear always keeps every corner, since that's what
	 * touches down.
	 */
	private void computeProbes() {
		probesStaleSince = -1;
		it.unimi.dsi.fastutil.longs.LongOpenHashSet seen = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		it.unimi.dsi.fastutil.doubles.DoubleArrayList out = new it.unimi.dsi.fastutil.doubles.DoubleArrayList();
		it.unimi.dsi.fastutil.booleans.BooleanArrayList keep = new it.unimi.dsi.fastutil.booleans.BooleanArrayList();
		Vector3d c = dynamics.centre();
		for (var e : hull.entrySet()) {
			AABB box = e.getValue();
			boolean isGear = gear.contains(e.getKey());
			for (int k = 0; k < 8; k++) {
				double x = (k & 1) == 0 ? box.minX : box.maxX, y = (k & 2) == 0 ? box.minY : box.maxY, z = (k & 4) == 0 ? box.minZ : box.maxZ;
				long key = (Math.round(x * 16) & 0x1FFFFF) | ((Math.round(y * 16) & 0x1FFFFF) << 21) | ((Math.round(z * 16) & 0x1FFFFF) << 42);
				if (!seen.add(key)) continue;
				// Probes sit a hair inside the box, so a hull resting exactly on a block face isn't "inside" it.
				out.add(x + ((k & 1) == 0 ? 0.001 : -0.001));
				out.add(y + ((k & 2) == 0 ? 0.001 : -0.001));
				out.add(z + ((k & 4) == 0 ? 0.001 : -0.001));
				keep.add(isGear);
			}
		}
		int count = out.size() / 3;
		double[] probes;
		if (count <= PROBE_BUDGET) {
			probes = out.toDoubleArray();
		} else {
			// Surface points thin out roughly with the square of the lattice spacing.
			int cell = (int) Math.ceil(Math.sqrt(count / (double) PROBE_BUDGET));
			it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap best = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
			best.defaultReturnValue(-1);
			it.unimi.dsi.fastutil.doubles.DoubleArrayList kept = new it.unimi.dsi.fastutil.doubles.DoubleArrayList();
			for (int i = 0; i < count; i++) {
				double x = out.getDouble(3 * i), y = out.getDouble(3 * i + 1), z = out.getDouble(3 * i + 2);
				if (keep.getBoolean(i)) {
					kept.add(x); kept.add(y); kept.add(z);
					continue;
				}
				long key = BlockPos.asLong(Math.floorDiv((int) Math.floor(x), cell), Math.floorDiv((int) Math.floor(y), cell),
						Math.floorDiv((int) Math.floor(z), cell));
				int prev = best.get(key);
				if (prev < 0 || distSq(out, i, c) > distSq(out, prev, c)) best.put(key, i);
			}
			for (int i : best.values()) {
				kept.add(out.getDouble(3 * i)); kept.add(out.getDouble(3 * i + 1)); kept.add(out.getDouble(3 * i + 2));
			}
			probes = kept.toDoubleArray();
		}
		double far = 1;
		for (int i = 0; i < probes.length; i += 3) {
			double dx = probes[i] - c.x, dy = probes[i + 1] - c.y, dz = probes[i + 2] - c.z;
			far = Math.max(far, Math.sqrt(dx * dx + dy * dy + dz * dz));
		}
		hullProbes = probes;
		radius = far;
	}

	private static double distSq(it.unimi.dsi.fastutil.doubles.DoubleArrayList p, int i, Vector3d c) {
		double dx = p.getDouble(3 * i) - c.x, dy = p.getDouble(3 * i + 1) - c.y, dz = p.getDouble(3 * i + 2) - c.z;
		return dx * dx + dy * dy + dz * dz;
	}

	/** M10: re-reads some parts (their health changed) without touching anything else. */
	void refreshParts(java.util.Set<BlockPos> parts) {
		if (structure == null) return;
		Map<BlockPos, ShipStructure.@Nullable Entry> changes = new java.util.LinkedHashMap<>();
		for (BlockPos pos : parts) {
			ShipStructure.Entry entry = structure.entry(pos);
			if (entry != null) changes.put(pos, entry);
		}
		if (!changes.isEmpty()) applyIncremental(structure, changes);
	}

	/** Functional parts (local positions). */
	public java.util.Set<BlockPos> components() {
		return java.util.Collections.unmodifiableSet(components);
	}

	void publishSystems() {
		if (level() == null || level().isClientSide()) return;
		entityData.set(SHIELD_MAX, (float) systems.shieldCapacity());
		entityData.set(SHIELD, (float) Math.min(entityData.get(SHIELD), systems.shieldCapacity()));
		entityData.set(POWER_SPLIT, power.pips().pack());
		int max = 0;
		for (var sub : ShipSystems.Subsystem.values()) max |= Math.min(31, systems.maxPips(sub)) << (5 * sub.ordinal());
		entityData.set(PIP_MAX, max);
		entityData.set(ENGINE_FACTOR, (float) power.factor(ShipSystems.Subsystem.ENGINES));
		entityData.set(THERMAL, (float) power.heatLevel());
		entityData.set(ORBIT, orbiting == null ? "" : orbiting.identifier().toString());
	}

	/**
	 * Same rule as the pre-flight check, on the stored blocks: flood-fill air from the pilot
	 * seat; full-collision blocks, doors and trapdoors seal; reaching the edge means a leak.
	 */
	private boolean computeSealed() {
		cabinAir = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		if (structure == null) return false;
		BlockPos seat = helmLocal.above();
		it.unimi.dsi.fastutil.longs.LongOpenHashSet visited = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue queue = new it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue();
		visited.add(seat.asLong());
		queue.enqueue(seat.asLong());
		BlockPos min = structure.min(), max = structure.max();
		BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
		while (!queue.isEmpty()) {
			long packed = queue.dequeueLong();
			int x = BlockPos.getX(packed), y = BlockPos.getY(packed), z = BlockPos.getZ(packed);
			if (x < min.getX() || y < min.getY() || z < min.getZ() || x > max.getX() || y > max.getY() || z > max.getZ()
					|| visited.size() > com.starwake.ship.scan.PreflightReport.MAX_CABIN) {
				return false;
			}
			for (Direction direction : Direction.values()) {
				next.set(x + direction.getStepX(), y + direction.getStepY(), z + direction.getStepZ());
				long key = next.asLong();
				if (visited.contains(key) || seals(next)) continue;
				visited.add(key);
				queue.enqueue(key);
			}
		}
		cabinAir = visited;
		return true;
	}

	/** Recomputes the seal if the structure changed since (both sides: M10, the client's environment card asks too). */
	private void ensureSealed() {
		if (!sealDirty) return;
		sealDirty = false;
		sealed = computeSealed();
	}

	/** Air cells inside the sealed cabin (empty when the hull leaks). */
	private it.unimi.dsi.fastutil.longs.LongOpenHashSet cabinAir = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

	/**
	 * True if a world position is inside this ship's sealed, pressurized cabin (M5 hazards:
	 * breathable air and a stable temperature whatever the planet outside).
	 */
	public boolean isInPressurizedCabin(Vec3 world) {
		ensureSealed();
		if (!sealed || cabinAir.isEmpty()) return false;
		return cabinAir.contains(BlockPos.containing(toLocal(world)).asLong());
	}

	private boolean seals(BlockPos local) {
		return sealsCabin(structure.get(local));
	}

	/**
	 * Airtight rule shared with the pre-flight check: full-collision blocks seal, and doors and
	 * trapdoors seal only while closed (they're the ship's airlocks).
	 */
	public static boolean sealsCabin(@Nullable BlockState state) {
		if (state == null) return false;
		if (state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS)) {
			return !state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)
					|| !state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN);
		}
		// Hull panels seal unless they're open frames or pillars (M9 pass 3); hull stairs and slabs are airtight pieces too.
		if (state.getBlock() instanceof com.starwake.ship.block.ShapedBlock shaped) return shaped.seals(state);
		if (state.is(com.starwake.registry.ModBlocks.HULL_PLATING_STAIRS) || state.is(com.starwake.registry.ModBlocks.HULL_PLATING_SLAB)) return true;
		return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
	}

	// =========================================================================================
	// Live interior: changing blocks and containers on an assembled ship
	// =========================================================================================

	// =========================================================================================
	// Shipyard plot (M9): the ship's blocks live as real blocks in a plot of starwake:shipyard
	// =========================================================================================

	/** This ship's plot index in the shipyard, or -1 before the first server tick of an old (pre-M9) ship. */
	private int plot = -1;
	/** Whether the snapshot has been checked against the plot since this entity loaded. */
	private boolean plotSynced;
	/** Plot changes heard this tick (local -> new state), folded into the snapshot once per tick. */
	private final Map<BlockPos, BlockState> plotChanges = new java.util.LinkedHashMap<>();

	public int plot() {
		return plot;
	}

	public void setPlot(int plot) {
		this.plot = plot;
	}

	/** A block in our plot changed (Shipyard listener). Changes that match the snapshot are ignored. */
	/**
	 * A plot block entity changed what clients see without a state change (sign text edited, ...): resync that cell
	 * so the new client tag reaches everyone (M9 pass 2).
	 */
	public void onPlotDataChange(BlockPos local) {
		if (structure == null) return;
		BlockState known = structure.get(local);
		if (known != null) plotChanges.put(local.immutable(), known);
	}

	/** Client: the synced plot index (-1 until known). */
	public int syncedPlot() {
		return entityData.get(PLOT);
	}

	public void onPlotChange(BlockPos local, BlockState state) {
		if (structure == null) return;
		BlockState known = structure.get(local);
		if (state.isAir() ? known == null : state.equals(known)) {
			plotChanges.remove(local);
			return;
		}
		plotChanges.put(local.immutable(), state);
	}

	/**
	 * Server, every tick: claims a plot for ships from before M9 (writing their stored blocks into it), keeps the
	 * plot's chunks loaded and ticking, and folds the tick's plot changes into the snapshot.
	 */
	private void tickPlot() {
		if (!(level() instanceof ServerLevel world) || structure == null) return;
		ServerLevel yard = com.starwake.ship.yard.Shipyard.level(world.getServer());
		if (yard == null) return;
		if (plot >= 0 && !com.starwake.ship.yard.Shipyard.claim(world.getServer(), plot, getUUID())) {
			// Another ship owns this plot (we're a stale copy): move into a plot of our own from our snapshot.
			com.starwake.StarwakeMod.LOGGER.warn("Ship {} found plot {} owned by another ship; moving to a new plot", getId(), plot);
			plot = -1;
		}
		if (plot < 0) {
			plot = com.starwake.ship.yard.Shipyard.allocate(world.getServer(), getUUID());
			com.starwake.ship.yard.Shipyard.writeFresh(yard, plot, structure.entries());
			com.starwake.StarwakeMod.LOGGER.info("Ship {} moved into shipyard plot {} ({} blocks)", getId(), plot, structure.entries().size());
		}
		com.starwake.ship.yard.Shipyard.track(this, plot);
		if (entityData.get(PLOT) != plot) entityData.set(PLOT, plot);
		if (tickCount % 40 == 1) com.starwake.ship.yard.Shipyard.ticket(yard, plot, structure);
		if (!plotSynced && yard.isLoaded(com.starwake.ship.yard.Shipyard.toPlot(plot, helmLocal))) {
			// First tick with the plot loaded: pick up anything that changed while nobody was listening.
			plotSynced = true;
			BlockPos lo = structure.min().offset(-1, -1, -1), hi = structure.max().offset(1, 1, 1);
			for (BlockPos local : BlockPos.betweenClosed(lo, hi)) {
				BlockState real = yard.getBlockState(com.starwake.ship.yard.Shipyard.toPlot(plot, local));
				if (!real.isAir() || structure.get(local) != null) onPlotChange(local, real);
			}
		}
		applyPlotChanges(yard);
	}

	private void applyPlotChanges(ServerLevel yard) {
		if (plotChanges.isEmpty() || structure == null) return;
		Map<BlockPos, ShipStructure.@Nullable Entry> changes = new java.util.LinkedHashMap<>();
		boolean removed = false;
		for (var change : plotChanges.entrySet()) {
			BlockPos local = change.getKey();
			BlockState state = change.getValue();
			if (state.isAir()) {
				changes.put(local, null);
				removed |= structure.get(local) != null;
			} else {
				var be = yard.getBlockEntity(com.starwake.ship.yard.Shipyard.toPlot(plot, local));
				changes.put(local, new ShipStructure.Entry(local, state,
						be == null ? java.util.Optional.empty() : java.util.Optional.of(be.saveWithFullMetadata(yard.registryAccess()))));
			}
		}
		plotChanges.clear();
		ShipStructure next = structure.withChanges(changes);
		if (removed) {
			// Something was taken out: whatever no longer connects to the Helm breaks away.
			List<BlockPos> gone = new ArrayList<>();
			for (var change : changes.entrySet()) if (change.getValue() == null) gone.add(change.getKey());
			java.util.Set<BlockPos> detached = com.starwake.ship.edit.ShipEditing.detached(next, helmLocal, gone);
			if (!detached.isEmpty() && level() instanceof ServerLevel world) {
				ShipStructure loose = new ShipStructure(com.starwake.ship.yard.Shipyard.read(yard, plot, detached), next.pivot());
				com.starwake.ship.yard.Shipyard.clear(yard, plot, detached);
				com.starwake.ship.edit.ShipEditing.dropDebris(this, world, loose, detached, null);
				next = next.without(detached);
				for (BlockPos pos : detached) changes.put(pos, null);
			}
		}
		if (next.entries().isEmpty()) {
			discard();
			return;
		}
		// Changes that put back what was already there aren't changes.
		changes.entrySet().removeIf(e -> e.getValue() == null && structure.get(e.getKey()) == null);
		applyEdit(next, changes);
	}

	@Override
	public void remove(RemovalReason reason) {
		if (!level().isClientSide() && plot >= 0 && level().getServer() != null) {
			com.starwake.ship.yard.Shipyard.untrack(this, plot);
			if (reason.shouldDestroy() && com.starwake.ship.yard.Shipyard.owns(level().getServer(), plot, getUUID())) {
				// The ship is gone for good (disassembled after its blocks were copied out, or destroyed): free the plot.
				ServerLevel yard = com.starwake.ship.yard.Shipyard.level(level().getServer());
				if (yard != null && structure != null) {
					com.starwake.ship.yard.Shipyard.clear(yard, plot, structure.entries().stream().map(ShipStructure.Entry::pos).toList());
				}
				com.starwake.ship.yard.Shipyard.free(level().getServer(), plot, getUUID());
			}
		}
		super.remove(reason);
	}

	/**
	 * Server: replaces the ship's blocks after an edit (Milestone 4). Anyone sitting on a seat
	 * that's gone stands up; container caches for removed blocks are dropped; fuel capacity
	 * follows the tanks aboard (fuel beyond it is lost); stats, seal and systems are
	 * recomputed and every tracking client gets the new structure.
	 */
	public void applyEdit(ShipStructure next) {
		applyEdit(next, null);
	}

	/** Above this many changed cells an edit resyncs the whole ship instead of sending a delta. */
	private static final int DELTA_LIMIT = 4096;

	/**
	 * Applies an edited structure. With {@code changes} (the cells that differ from the current structure; null =
	 * removed) small edits are folded in incrementally and trackers get only those cells (M9 pass 3); otherwise the
	 * model is rebuilt and the whole ship is resent.
	 */
	public void applyEdit(ShipStructure next, @Nullable Map<BlockPos, ShipStructure.@Nullable Entry> changes) {
		long t0 = System.nanoTime();
		asleep = false; // its shape (and maybe its support) changed
		for (Entity passenger : List.copyOf(getPassengers())) {
			BlockPos seat = seatOf(passenger);
			if (seat != null && next.get(seat) == null) passenger.stopRiding();
		}
		boolean delta = changes != null && structure != null && changes.size() <= DELTA_LIMIT;
		if (delta) applyIncremental(next, changes);
		else setStructure(next);
		int capacity = componentFuelCapacity;
		fuelCapacity = capacity;
		entityData.set(FUEL_CAPACITY, capacity);
		if (fuel() > capacity) entityData.set(FUEL, capacity);
		if (fusion() > fusionCapacity()) entityData.set(FUSION, fusionCapacity());
		long t1 = System.nanoTime();
		structureVersion++;
		net.minecraft.network.protocol.common.custom.CustomPacketPayload payload = delta
				? com.starwake.network.ShipDeltaPayload.of(getId(), structureVersion, next, changes, registryAccess())
				: new com.starwake.network.ShipStructurePayload(getId(), structureVersion, next);
		for (ServerPlayer player : net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(this)) {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
		}
		if (next.entries().size() >= 10_000) {
			com.starwake.StarwakeMod.LOGGER.debug("Big ship edit: {} blocks, {} changed ({}); recompute {} ms, send {} ms", next.entries().size(),
					changes == null ? "?" : changes.size(), delta ? "delta" : "full", (t1 - t0) / 1_000_000, (System.nanoTime() - t1) / 1_000_000);
		}
	}

	/** The block rotation matching the ship's heading (local north -> nearest world direction). */
	public net.minecraft.world.level.block.Rotation cardinalRotation() {
		Vec3 north = worldDirection(Direction.NORTH);
		Direction heading = Direction.getApproximateNearest(north.x, 0, north.z);
		for (var r : net.minecraft.world.level.block.Rotation.values()) {
			if (r.rotate(Direction.NORTH) == heading) return r;
		}
		return net.minecraft.world.level.block.Rotation.NONE;
	}

	/** World box around a local block cell at the current pose. */
	public AABB worldBoxOf(BlockPos local) {
		return worldBox(new AABB(local), 1.0);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(FUEL, 0);
		builder.define(ORIENTATION, new Quaternionf());
		builder.define(SEATS, "");
		builder.define(JOINTS, "");
		builder.define(FUEL_CAPACITY, 0);
		builder.define(FUSION, 0);
		builder.define(SHIELD, 0f);
		builder.define(SHIELD_MAX, 0f);
		builder.define(POWER_SPLIT, 0);
		builder.define(PIP_MAX, 0);
		builder.define(ENGINE_FACTOR, 1f);
		builder.define(THERMAL, 0f);
		builder.define(QUANTUM, -1f);
		builder.define(ORBIT, "");
		builder.define(QT_STATE, 0);
		builder.define(THROTTLES, "");
		builder.define(DECOUPLED, false);
		builder.define(ACCESS, "\n0\n");
		builder.define(QT_TARGET, "");
		builder.define(QT_INFO, 0);
		builder.define(QT_OFFSET, new Vector3f());
		builder.define(PLOT, -1);
		builder.define(JD_STATE, 0);
		builder.define(JD_TARGET, "");
		builder.define(JD_INFO, 0);
		builder.define(JD_SPOOL, -1f);
		builder.define(JD_REACH, 0f);
	}

	// =========================================================================================
	// Accessors
	// =========================================================================================

	public int fuel() { return entityData.get(FUEL); }
	public int fuelCapacity() { return level().isClientSide() ? entityData.get(FUEL_CAPACITY) : fuelCapacity; }
	public float shield() { return entityData.get(SHIELD); }
	public float shieldMax() { return entityData.get(SHIELD_MAX); }
	public float engineFactor() { return entityData.get(ENGINE_FACTOR); }
	/** Heat as a fraction of the buffer (M10; 1 = overheating). */
	public float heatLevel() { return entityData.get(THERMAL); }
	/** Quantum spool 0..1, or -1 when the drive isn't in quantum mode. */
	public float quantumProgress() { return entityData.get(QUANTUM); }
	public int quantumState() { return entityData.get(QT_STATE); }
	public String quantumTargetId() { return entityData.get(QT_TARGET); }
	public int quantumInfo() { return entityData.get(QT_INFO); }
	public boolean isQuantumTravelling() { return (quantumState() & QT_TRAVEL) != 0; }
	public int jumpState() { return entityData.get(JD_STATE); }
	public String jumpTargetId() { return entityData.get(JD_TARGET); }
	public int jumpInfo() { return entityData.get(JD_INFO); }
	public float jumpProgress() { return entityData.get(JD_SPOOL); }
	public boolean isJumping() { return (jumpState() & JD_TRAVEL) != 0; }
	public double jumpRange() { return entityData.get(JD_REACH); }
	public ShipSystems systemsView() { return systems; }

	/** Positions over the last 10 ticks, for a steady speed readout. */
	private final Vec3[] recentPositions = new Vec3[10];

	/** Average velocity over the last half second, in blocks per tick (HUDs). */
	public Vec3 recentVelocity() {
		Vec3 now = recentPositions[tickCount % recentPositions.length];
		Vec3 then = recentPositions[(tickCount + 1) % recentPositions.length];
		if (now == null || then == null) return lastMove;
		return now.subtract(then).scale(1.0 / (recentPositions.length - 1));
	}

	/** Average speed over the last half second, in blocks per tick (HUDs). */
	public double recentSpeed() {
		Vec3 now = recentPositions[tickCount % recentPositions.length];
		Vec3 then = recentPositions[(tickCount + 1) % recentPositions.length];
		if (now == null || then == null) return lastMove.length();
		return now.distanceTo(then) / (recentPositions.length - 1);
	}

	/** Client: how far the quantum offset moved in the last tick (travel speed, for effects). */
	public Vec3 quantumStep() {
		return new Vec3(lastTickQtOffset.x - prevQtOffset.x, lastTickQtOffset.y - prevQtOffset.y, lastTickQtOffset.z - prevQtOffset.z);
	}

	/** Client: the quantum travel offset, interpolated for rendering. */
	public Vec3 quantumOffset(float partialTick) {
		Vector3f v = new Vector3f(prevQtOffset).lerp(lastTickQtOffset, partialTick);
		return new Vec3(v.x, v.y, v.z);
	}
	public String orbitId() { return entityData.get(ORBIT); }
	/** Synced pips per subsystem (ShipSystems.Subsystem order). */
	public int[] pips() {
		return com.starwake.ship.systems.PowerPips.unpack(entityData.get(POWER_SPLIT));
	}

	/** Synced most pips each subsystem can use. */
	public int[] pipLimits() {
		return com.starwake.ship.systems.PowerPips.unpack(entityData.get(PIP_MAX));
	}
	public ShipSystems systems() { return systems; }
	public BlockPos helmLocal() { return helmLocal; }
	public Direction forward() { return forward; }
	public double mass() { return level().isClientSide() && serverDynamics != null ? serverDynamics.mass() : mass; }

	/** Angular velocity in the ship's own frame (rad/tick); {@link com.starwake.ship.physics.ShipMotion} updates it in place. */
	public Vector3d angularVelocity() { return angularVelocity; }

	/** Corners of every outer hull block's collision box, local coordinates, packed xyz (contact probes). */
	public double[] hullProbes() { return hullProbes; }

	/** Distance from the centre of mass to the farthest hull corner (blocks). */
	public double radius() { return radius; }

	/** Sets position and orientation together (the contact solver moves both). */
	public void setPose(Vec3 position, Quaternionfc rotation) {
		setOrientation(rotation);
		setPos(position.x, position.y, position.z);
	}
	public Vec3 lastMove() { return lastMove; }
	public boolean isSealed() {
		ensureSealed();
		return sealed;
	}
	public boolean isGrounded() { return grounded; }
	public @Nullable ResourceKey<Level> orbiting() { return orbiting; }
	public void setOrbiting(@Nullable ResourceKey<Level> orbiting) {
		this.orbiting = orbiting;
		publishSystems();
	}
	public void setDrift(Vec3 drift) { this.drift = drift; }
	/** Admin/testing: constant spin (yaw, pitch, roll in degrees/second about the ship's own axes). Not saved. */
	public void setSpin(Vec3 spin) { this.spin = spin; }
	public void setControls(int controls) { this.controls = controls; }

	/** Server: true while the pilot streams ShipInputPayloads (their controls ride along; the older control packet is ignored). */
	public boolean receivesInputs() { return tickCount - lastInputTick < INPUT_TIMEOUT; }

	/** Joystick steering (M9): the pilot's virtual stick, -1..1 (x = yaw right, y = pitch down). */
	float stickX, stickY;

	public void setStick(float x, float y) {
		stickX = Mth.clamp(x, -1, 1);
		stickY = Mth.clamp(y, -1, 1);
	}

	public int fusion() {
		return entityData.get(FUSION);
	}

	public int fusionCapacity() {
		return com.starwake.ship.fuel.Propellants.fusionCapacity(fuelCapacity());
	}

	public void useFusion(int amount) {
		entityData.set(FUSION, Math.max(0, fusion() - amount));
	}

	/** Adds fusion units up to capacity; returns the amount added. */
	public int addFusion(int amount) {
		int added = Math.max(0, Math.min(amount, fusionCapacity() - fusion()));
		entityData.set(FUSION, fusion() + added);
		return added;
	}

	/** Gases poured into the ship's tanks that are waiting for their partner (M8f), kg by species. Saved. */
	private final Map<String, Double> propellantBuffer = new HashMap<>();

	/** Pours kg of a propellant gas into the ship's tanks; matching pairs burn into fuel / fusion units. Returns kg accepted. */
	public double addPropellant(String species, double kg) {
		if (!com.starwake.ship.fuel.Propellants.SPECIES.contains(species)) return 0;
		propellantBuffer.merge(species, kg, Double::sum);
		int[] made = com.starwake.ship.fuel.Propellants.convert(propellantBuffer, fuelCapacity - fuel(), fusionCapacity() - fusion());
		addFuel(made[0]);
		addFusion(made[1]);
		return kg;
	}

	public Map<String, Double> propellantBuffer() {
		return propellantBuffer;
	}

	/** Removes up to {@code amount} fuel. */
	public void useFuel(int amount) {
		entityData.set(FUEL, Math.max(0, fuel() - amount));
	}

	/** Adds fuel up to capacity; returns the amount actually added. */
	public int addFuel(int amount) {
		int added = Math.max(0, Math.min(amount, fuelCapacity - fuel()));
		entityData.set(FUEL, fuel() + added);
		return added;
	}

	/** Movement so far in the current tick (before riders have been carried). */
	public Vec3 movementThisTick() {
		return lastTickPos == null ? Vec3.ZERO : position().subtract(lastTickPos);
	}

	// =========================================================================================
	// Transforms
	// =========================================================================================

	public Quaternionfc orientation() {
		if (predicting) return predictedOrientation;
		return shownReady ? shownOrientation : entityData.get(ORIENTATION);
	}

	public void setOrientation(Quaternionfc orientation) {
		if (predicting) predictedOrientation.set(orientation).normalize();
		else entityData.set(ORIENTATION, new Quaternionf(orientation).normalize());
		setBoundingBox(makeBoundingBox(position()));
	}

	/** Sets the orientation if the hull wouldn't overlap terrain there (lifting up to a block). */
	public boolean trySetOrientation(Quaternionfc rotation) {
		for (double lift : new double[] {0, 0.5, 1.0}) {
			Vec3 at = position().add(0, lift, 0);
			if (!hullCollides(at, rotation)) {
				if (lift > 0) setPos(at);
				setOrientation(rotation);
				return true;
			}
		}
		return false;
	}

		/** Client: orientation interpolated for rendering. */
	public Quaternionf orientation(float partialTick) {
		return new Quaternionf(prevOrientation).slerp(orientation(), partialTick);
	}

	/** World position of a point given in ship-local coordinates, using the current transform. */
	public Vec3 toWorld(Vec3 local) {
		return toWorld(local, position(), orientation());
	}

	/** World position of a local block's centre. */
	public Vec3 blockCenter(BlockPos local) {
		return toWorld(Vec3.atCenterOf(local));
	}

	/** Ship-local coordinates of a world point, using the current transform. */
	public Vec3 toLocal(Vec3 world) {
		return toLocal(world, position(), orientation());
	}

	/** World position of a local point if the ship were at {@code position} with {@code rotation}. */
	public Vec3 toWorldAt(Vec3 local, Vec3 position, Quaternionfc rotation) {
		return toWorld(local, position, rotation);
	}

	private Vec3 toWorld(Vec3 local, Vec3 position, Quaternionfc rotation) {
		Vector3f v = new Vector3f((float) (local.x - pivot.x), (float) (local.y - pivot.y), (float) (local.z - pivot.z));
		rotation.transform(v);
		return position.add(v.x, v.y, v.z);
	}

	private Vec3 toLocal(Vec3 world, Vec3 position, Quaternionfc rotation) {
		Vector3f v = new Vector3f((float) (world.x - position.x), (float) (world.y - position.y), (float) (world.z - position.z));
		rotation.transformInverse(v);
		return new Vec3(v.x + pivot.x, v.y + pivot.y, v.z + pivot.z);
	}

	/** A local direction rotated into world space. */
	/** M9 pass 3: a local point on cell {@code cell}'s sub-assembly, turned by its joint (unchanged on the rigid hull). */
	public Vec3 posed(BlockPos cell, Vec3 local) {
		int g = articulations.groupOf(cell);
		if (g < 0) return local;
		var p = articulations.joint(g).pivot();
		var v = articulations.rotation(g, level().getGameTime(), 1).transform(new org.joml.Vector3d(local.x - p.x, local.y - p.y, local.z - p.z));
		return new Vec3(v.x + p.x, v.y + p.y, v.z + p.z);
	}

	/** A local direction on cell {@code cell}'s sub-assembly, turned by its joint. */
	public Vec3 posedDirection(BlockPos cell, Vec3 local) {
		int g = articulations.groupOf(cell);
		if (g < 0) return local;
		var v = articulations.rotation(g, level().getGameTime(), 1).transform(new org.joml.Vector3d(local.x, local.y, local.z));
		return new Vec3(v.x, v.y, v.z);
	}

	/** A local direction vector in world space. */
	public Vec3 worldDirection(Vec3 local) {
		Vector3f v = new Vector3f((float) local.x, (float) local.y, (float) local.z);
		orientation().transform(v);
		return new Vec3(v.x, v.y, v.z);
	}

	public Vec3 worldDirection(Direction local) {
		Vector3f v = new Vector3f(local.getStepX(), local.getStepY(), local.getStepZ());
		orientation().transform(v);
		return new Vec3(v.x, v.y, v.z);
	}

	/** True when the ship is within ~2 degrees of an unrotated or quarter-turn (yaw-only) pose. */
	public boolean isAxisAligned() {
		return isAxisAligned(orientation());
	}

	private static boolean isAxisAligned(Quaternionfc rotation) {
		Vector3f up = rotation.transform(new Vector3f(0, 1, 0));
		Vector3f north = rotation.transform(new Vector3f(0, 0, -1));
		double c = Math.cos(Math.toRadians(2));
		return up.y > c && Math.max(Math.abs(north.x), Math.abs(north.z)) > c;
	}

	// =========================================================================================
	// Bounding box and collision
	// =========================================================================================

	@Override
	protected AABB makeBoundingBox(Vec3 pos) {
		if (structure == null) {
			return new AABB(pos, pos.add(1, 1, 1));
		}
		BlockPos lo = structure.min(), hi = structure.max().offset(1, 1, 1);
		// The world AABB of the rotated local box: transform all 8 corners.
		Quaternionfc rotation = this.entityData == null ? new Quaternionf() : orientation();
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (int i = 0; i < 8; i++) {
			Vec3 corner = toWorld(new Vec3((i & 1) == 0 ? lo.getX() : hi.getX(), ((i >> 1) & 1) == 0 ? lo.getY() : hi.getY(),
					((i >> 2) & 1) == 0 ? lo.getZ() : hi.getZ()), pos, rotation);
			minX = Math.min(minX, corner.x); minY = Math.min(minY, corner.y); minZ = Math.min(minZ, corner.z);
			maxX = Math.max(maxX, corner.x); maxY = Math.max(maxY, corner.y); maxZ = Math.max(maxZ, corner.z);
		}
		return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
	}

	/**
	 * Adds world-space collision shapes for ship blocks near {@code area}. Exact when the ship
	 * is axis-aligned; otherwise each block becomes a slightly shrunk world AABB around its
	 * rotated shape (good enough to stand on and bump into until ship-local physics in 3e).
	 */
	public void collectShapes(AABB area, List<VoxelShape> out) {
		if (structure == null) return;
		AABB local = localBounds(area.inflate(1.0));
		BlockPos lo = structure.min(), hi = structure.max();
		int minX = Math.max(lo.getX(), Mth.floor(local.minX)), maxX = Math.min(hi.getX(), Mth.floor(local.maxX));
		int minY = Math.max(lo.getY(), Mth.floor(local.minY)), maxY = Math.min(hi.getY(), Mth.floor(local.maxY));
		int minZ = Math.max(lo.getZ(), Mth.floor(local.minZ)), maxZ = Math.min(hi.getZ(), Mth.floor(local.maxZ));
		boolean aligned = isAxisAligned();
		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					BlockState state = structure.get(x, y, z);
					if (state == null) continue;
					VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
					if (shape.isEmpty()) continue;
					for (AABB part : shape.toAabbs()) {
						AABB world = worldBox(part.move(x, y, z), aligned ? 1.0 : 0.85);
						if (world.intersects(area)) {
							out.add(net.minecraft.world.phys.shapes.Shapes.create(world));
						}
					}
				}
			}
		}
	}

	/**
	 * Collision shapes of ship blocks in ship-local space (exact, axis-aligned) that intersect
	 * {@code localArea}. Used by ship-local player physics (walking aboard at any attitude).
	 */
	public void collectLocalShapes(AABB localArea, List<VoxelShape> out) {
		if (structure == null) return;
		BlockPos lo = structure.min(), hi = structure.max();
		int minX = Math.max(lo.getX(), Mth.floor(localArea.minX)), maxX = Math.min(hi.getX(), Mth.floor(localArea.maxX));
		int minY = Math.max(lo.getY(), Mth.floor(localArea.minY)), maxY = Math.min(hi.getY(), Mth.floor(localArea.maxY));
		int minZ = Math.max(lo.getZ(), Mth.floor(localArea.minZ)), maxZ = Math.min(hi.getZ(), Mth.floor(localArea.maxZ));
		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					BlockState state = structure.get(x, y, z);
					if (state == null) continue;
					VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
					if (!shape.isEmpty()) out.add(shape.move(x, y, z));
				}
			}
		}
	}

	/** True if a local point is within the structure's box grown by {@code margin}. */
	public boolean isNearLocal(Vec3 local, double margin) {
		if (structure == null) return false;
		return structure.inBounds(local.x, local.y, local.z, margin);
	}

	/** World velocity of a ship-local point due to the ship's last-tick motion (translation + rotation). */
	public Vec3 pointVelocity(Vec3 local) {
		if (lastTickPos == null) return Vec3.ZERO;
		return toWorld(local).subtract(toWorld(local, lastTickPos, prevOrientation));
	}

		/** World AABB enclosing a local box after rotation (current pose), scaled about its centre. */
	private AABB worldBox(AABB local, double scale) {
		return worldBox(local, scale, position(), orientation());
	}

	private AABB worldBox(AABB local, double scale, Vec3 position, Quaternionfc rotation) {
		Vec3 center = toWorld(local.getCenter(), position, rotation);
		Vec3 half = new Vec3(local.getXsize() / 2, local.getYsize() / 2, local.getZsize() / 2);
		// Extent of a rotated box along each world axis = sum |R_ij| * half_j.
		Vector3f ax = new Vector3f(1, 0, 0), ay = new Vector3f(0, 1, 0), az = new Vector3f(0, 0, 1);
		rotation.transform(ax);
		rotation.transform(ay);
		rotation.transform(az);
		double ex = (Math.abs(ax.x) * half.x + Math.abs(ay.x) * half.y + Math.abs(az.x) * half.z) * scale;
		double ey = (Math.abs(ax.y) * half.x + Math.abs(ay.y) * half.y + Math.abs(az.y) * half.z) * scale;
		double ez = (Math.abs(ax.z) * half.x + Math.abs(ay.z) * half.y + Math.abs(az.z) * half.z) * scale;
		return new AABB(center.x - ex, center.y - ey, center.z - ez, center.x + ex, center.y + ey, center.z + ez);
	}

	/** Local-space AABB enclosing a world AABB. */
	private AABB localBounds(AABB world) {
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (int i = 0; i < 8; i++) {
			Vec3 c = toLocal(new Vec3((i & 1) == 0 ? world.minX : world.maxX, (i & 2) == 0 ? world.minY : world.maxY,
					(i & 4) == 0 ? world.minZ : world.maxZ));
			minX = Math.min(minX, c.x); minY = Math.min(minY, c.y); minZ = Math.min(minZ, c.z);
			maxX = Math.max(maxX, c.x); maxY = Math.max(maxY, c.y); maxZ = Math.max(maxZ, c.z);
		}
		return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
	}

	// =========================================================================================
	// Ticking
	// =========================================================================================

	/**
	 * Ships ignore vanilla's per-tick fluid and inside-block checks (M9 pass 3): both scan every cell of the bounding
	 * box, which for a 155 m hull is most of a million blocks a tick. Ships don't swim or trip pressure plates.
	 */
	@Override
	protected boolean updateFluidInteraction() {
		return false;
	}

	@Override
	protected void applyEffectsFromBlocks() {
	}

	@Override
	public void tick() {
		super.tick();
		if (probesStaleSince >= 0 && tickCount - probesStaleSince >= 10 && structure != null) computeProbes();
		if (level().isClientSide()) clientTick();
		if (!level().isClientSide() && structure != null) {
			tickJoints();
			if (serverTick()) return; // replaced by a copy in another dimension
			sendPilotState();
			if (lastTickPos != null && (movementThisTick().lengthSqr() > 0 || !orientation().equals(lastTickOrientation, 1.0e-7f))) {
				carryEntities();
			}
			ShipFooting.carry(this); // walkers aboard (M9): placed from their position in our frame
		}
		// Measure against our own end-of-last-tick state: on the client, updates can be applied
		// between ticks (packet handling), so in-tick snapshots would miss them.
		lastMove = lastTickPos == null ? Vec3.ZERO : position().subtract(lastTickPos);
		lastTickPos = position();
		prevOrientation.set(lastTickOrientation);
		lastTickOrientation.set(orientation());
		recentPositions[tickCount % recentPositions.length] = position();
		prevQtOffset.set(lastTickQtOffset);
		lastTickQtOffset.set(entityData.get(QT_OFFSET));
		if (lastTickQtOffset.lengthSquared() == 0) prevQtOffset.zero(); // arrival: no lerp back
	}

	/** @return true if the ship was transferred to another dimension (stop ticking it) */
	private boolean serverTick() {
		tickPlot();
		if (isRemoved()) return true;
		ShipPhysics physics = ShipPhysics.get();
		shipRiders = findShipRiders();
		Player pilot = pilot();
		Vec3 velocity = getDeltaMovement();
		if (drives.jumpTicks >= 0) {
			// In a jump tunnel (M6): the hull holds still until it arrives in the other system.
			setDeltaMovement(Vec3.ZERO);
			angularVelocity.zero();
			java.util.Arrays.fill(throttles, 0);
			publishThrottles();
			return drives.tickJumpTravel();
		}
		if (drives.jump != null) {
			// In quantum travel: the hull holds still while the jump plays out.
			setDeltaMovement(Vec3.ZERO);
			angularVelocity.zero();
			java.util.Arrays.fill(throttles, 0);
			publishThrottles();
			tickSystems();
			drives.tickJump(physics);
			return false;
		}

		if (pilot == null && spin.lengthSqr() > 0) {
			Quaternionf next = new Quaternionf(orientation())
					.rotateY((float) Math.toRadians(-spin.x / 20)).rotateX((float) Math.toRadians(spin.y / 20))
					.rotateZ((float) Math.toRadians(spin.z / 20));
			setOrientation(next.normalize());
		}
		if (pilot != null) asleep = false;
		if (pilot == null) {
			// Parked (M9 pass 2): a ship at rest on the ground stops simulating until something disturbs it. Stepping
			// a resting body still pushes its contacts apart a hair every tick, which made parked ships creep.
			if (getDeltaMovement().lengthSqr() > 1.0e-4 || drift.lengthSqr() > 0 || spin.lengthSqr() > 0) asleep = false;
			else if (!asleep && (grounded || flight.clearance() < 0.05) && angularVelocity.lengthSquared() < 1.0e-5) asleep = true;
			if (asleep) {
				setDeltaMovement(Vec3.ZERO);
				angularVelocity.zero();
				java.util.Arrays.fill(throttles, 0);
				publishThrottles();
				return false;
			}
			if (drift.lengthSqr() > 0) {
				velocity = drift;
			} else {
				velocity = flight.unpiloted(velocity, physics);
				angularVelocity.mul(0.8);
				java.util.Arrays.fill(throttles, 0);
			}
			publishThrottles();
			if (moveStep(velocity, physics, null)) return true;
		} else {
			// M9: one flight step per pilot input, in order. Inputs arrive in bursts (a client's slow frame runs
			// several ticks at once); stepping per input keeps the server exactly in step with the pilot's prediction,
			// as vanilla does for walking players. With no input for a while the ship flies on with the last one.
			int steps = 0;
			while (!inputQueue.isEmpty() && steps < MAX_STEPS_PER_TICK) {
				applyInput(inputQueue.poll());
				steps++;
				velocity = flight.fly(pilot, getDeltaMovement(), physics, pilotInput);
				publishThrottles();
				if (moveStep(velocity, physics, pilot)) return true;
			}
			if (steps == 0 && tickCount - lastInputTick > INPUT_TIMEOUT) {
				velocity = flight.fly(pilot, getDeltaMovement(), physics, serverInput(pilot));
				publishThrottles();
				if (moveStep(velocity, physics, pilot)) return true;
			}
		}
		tickSystems();
		return false;
	}

	/** Inputs waiting to be flown (server), at most {@link #MAX_STEPS_PER_TICK} per tick. */
	private final java.util.ArrayDeque<com.starwake.network.ShipInputPayload> inputQueue = new java.util.ArrayDeque<>();
	private static final int MAX_STEPS_PER_TICK = 5, INPUT_TIMEOUT = 10, MAX_QUEUE = 40;

	/**
	 * One physics step after the flight computer (or the unpiloted rules) chose the velocity: orbit-insertion assist,
	 * speed cap, terrain streaming, contacts, impacts and the atmosphere boundary.
	 *
	 * @return true if the ship left for another dimension (stop ticking this copy)
	 */
	private boolean moveStep(Vec3 velocity, ShipPhysics physics, @Nullable Player pilot) {
		if (exitAssist > 0) {
			// Just out of the atmosphere: the flight computer settles the ship into orbit, with a
			// little free help so ships with weak downward thrusters don't drift away for ages.
			exitAssist--;
			if (!decoupled) {
				velocity = velocity.scale(0.92);
				angularVelocity.mul(0.9);
			}
		}
		flight.measureClearance(); // every step on both sides, so prediction matches
		if (tickCount % 10 == 0 && velocity.lengthSqr() > 1.0 && level() instanceof ServerLevel ahead) {
			// Fast ships: start loading the terrain ~3 s ahead so it's there when they arrive.
			ahead.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PORTAL,
					net.minecraft.world.level.ChunkPos.containing(BlockPos.containing(position().add(velocity.scale(60)))), 2);
		}
		setDeltaMovement(velocity); // M10: no speed caps (the flight computer holds the pilot's limit)
		// M9: rigid-body motion with contacts (slides, tips, settles, shoves other ships).
		neighbours = nearbyShips(getBoundingBox().expandTowards(velocity).inflate(1.5));
		long perfStart = System.nanoTime();
		var result = com.starwake.ship.physics.ShipMotion.step(this, velocity, neighbours, pilot == null);
		if (structure != null && structure.entries().size() >= 10_000) {
			perfNanos += System.nanoTime() - perfStart;
			if (++perfSteps == 100) {
				com.starwake.StarwakeMod.LOGGER.debug("Big ship physics: {} blocks, {} probes: {} ms/step avg", structure.entries().size(),
						hullProbes.length / 3, String.format("%.2f", perfNanos / 1e8));
				perfNanos = 0;
				perfSteps = 0;
			}
		}
		grounded = result.grounded();
		damage.afterStep(result);
		return result.moved() && level() instanceof ServerLevel serverLevel && SpaceTravel.checkAltitude(this, serverLevel);
	}

	/** The local direction closest to a world direction. */
	public Direction localDirection(Vec3 world) {
		Vector3f v = new Vector3f((float) world.x, (float) world.y, (float) world.z);
		orientation().transformInverse(v);
		return Direction.getApproximateNearest(v.x, v.y, v.z);
	}

	/** An action-bar message for the pilot and everyone aboard. */
	public void messageAboard(Component message) {
		if (!(level() instanceof ServerLevel server)) return;
		for (net.minecraft.server.level.ServerPlayer player : server.getPlayers(p -> p.getVehicle() == this || isSupporting(p)
				|| p.distanceToSqr(this) < 64 * 64 && getBoundingBox().inflate(2).contains(p.position()))) {
			player.sendOverlayMessage(message);
		}
	}

	/** Shield regeneration and quantum spool, scaled by the power routed to each. */
	private void tickSystems() {
		float shield = entityData.get(SHIELD);
		if (shield < systems.shieldCapacity()) {
			double regen = systems.shieldRegen() * power.factor(ShipSystems.Subsystem.SHIELDS);
			entityData.set(SHIELD, (float) Math.min(systems.shieldCapacity(), shield + regen));
		}
		drives.tick();
		// M10: heat in and out, wear from overheating.
		double load = 0;
		for (double t : throttles) load += t;
		load = throttles.length == 0 ? 0 : load / throttles.length;
		boolean spooling = drives.spooling();
		power.tick(load, entityData.get(SHIELD) < systems.shieldCapacity(), spooling);
		if (tickCount % 10 == 0) entityData.set(THERMAL, (float) power.heatLevel());
		if (tickCount % 20 == 0) entityData.set(JD_REACH, (float) systems.jumpRange());
		if (SpaceTravel.isSpace(level()) && tickCount % 20 == 0) {
			// "Orbiting" = the nearest planet, for the HUD and as the fallback home.
			com.starwake.galaxy.SolarSystem.nearest(level(), position())
					.ifPresent(body -> setOrbiting(body.dimension()));
		}
	}

	/** The passenger sitting at the Helm, if any. */
	public @Nullable Player pilot() {
		for (Entity passenger : getPassengers()) {
			// seatOf(...) reads the synced copy on clients: the client's own map can be stale when
			// passengers arrive before the structure (e.g. after loading a save).
			if (passenger instanceof Player player && helmLocal.equals(seatOf(player))) {
				return player;
			}
		}
		return null;
	}

	/** The flight computer (M10: split out, real units). */
	public final ShipFlight flight = new ShipFlight(this);

	// =========================================================================================
	// Prediction (M9): the pilot's client flies its own copy of the ship with the same flight
	// computer and contact physics, and the server stays in charge. The client sends its input
	// for each tick (with a sequence number); the server flies with exactly that input and sends
	// back the state it reached; the client compares with what it predicted for that tick and
	// shifts its state (and its remembered predictions) by the difference.
	// =========================================================================================

	/** Client hooks (registered by the client entrypoint; common code can't see client classes). */
	public interface PilotHook {
		boolean isLocalPilot(ShipEntity ship);
		Input input();
		int controls();
		float stickX();
		float stickY();
		Vec3 look();
		/** The pilot's speed limiter, m/s (M10). */
		float speedLimit();
		void send(com.starwake.network.ShipInputPayload payload);
	}

	public static @Nullable PilotHook pilotHook;

	/** A predicted tick: the input sequence and the state the client reached with it. */
	private static final class Predicted {
		final int seq;
		Vec3 pos;
		final Quaternionf rotation;
		Vec3 velocity;
		final Vector3d spin;

		Predicted(int seq, Vec3 pos, Quaternionfc rotation, Vec3 velocity, org.joml.Vector3dc spin) {
			this.seq = seq;
			this.pos = pos;
			this.rotation = new Quaternionf(rotation);
			this.velocity = velocity;
			this.spin = new Vector3d(spin);
		}
	}

	/** Client: true while the local player pilots this ship and the client predicts it. */
	private boolean predicting;
	private final Quaternionf predictedOrientation = new Quaternionf();
	private int predictSeq;
	private final java.util.ArrayDeque<Predicted> history = new java.util.ArrayDeque<>();
	/** Client: the server's rigid-body model of this ship (ShipDynamicsPayload). */
	private @Nullable ShipDynamics serverDynamics;

	/** Server: the pilot's latest input from ShipInputPayload, its sequence and when it arrived. */
	private Input pilotInput = Input.EMPTY;
	@Nullable Vec3 pilotLook;
	int lastInputTick = -100;
	private int lastInputSeq = -1;

	public void setServerDynamics(ShipDynamics dynamics) {
		this.serverDynamics = dynamics;
		if (!articulations.isEmpty()) dynamics.articulate(articulations.poses(level().getGameTime()));
	}

	public boolean isPredicting() {
		return predicting;
	}

	/** Server: the pilot's input for this tick (from ShipInputPayload if fresh, else vanilla's last input). */
	private Input serverInput(Player pilot) {
		if (tickCount - lastInputTick < 10) return pilotInput;
		return pilot instanceof ServerPlayer sp ? sp.getLastClientInput() : Input.EMPTY;
	}

	/** Server: a pilot input arrived; it's flown in order, one step each (see serverTick). */
	public void acceptInput(com.starwake.network.ShipInputPayload payload) {
		inputQueue.addLast(payload);
		while (inputQueue.size() > MAX_QUEUE) inputQueue.removeFirst(); // far behind: skip ahead (the client will snap)
		lastInputTick = tickCount;
	}

	private void applyInput(com.starwake.network.ShipInputPayload payload) {
		pilotInput = payload.input();
		controls = payload.controls();
		setStick(payload.stickX(), payload.stickY());
		pilotLook = new Vec3(payload.lookX(), payload.lookY(), payload.lookZ());
		flight.setLimiter(payload.speedLimit(), (controls & CONTROL_NAV) != 0);
		lastInputSeq = payload.seq();
	}

	/** Server: tells the pilot where the ship really is after their latest input. */
	private void sendPilotState() {
		if (!(pilot() instanceof ServerPlayer player) || lastInputSeq < 0 || isRemoved()) return;
		Quaternionfc q = orientation();
		Vec3 v = getDeltaMovement();
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, new com.starwake.network.ShipStatePayload(getId(), lastInputSeq,
				getX(), getY(), getZ(), q.x(), q.y(), q.z(), q.w(), v.x, v.y, v.z,
				(float) angularVelocity.x, (float) angularVelocity.y, (float) angularVelocity.z));
	}

	/** Client tick: predict the ship we pilot; otherwise follow the server. */
	private void clientTick() {
		if (!articulations.isEmpty()) {
			articulations.tickClient();
			if (articulations.moving() || articulations.hasRotors()) {
				applyArticulation();
				if (serverDynamics != null) serverDynamics.articulate(articulations.poses(level().getGameTime()));
			}
		}
		PilotHook hook = pilotHook;
		boolean local = hook != null && structure != null && serverDynamics != null && hook.isLocalPilot(this)
				&& drives.jumpTicks < 0 && entityData.get(QT_STATE) != QT_TRAVEL;
		if (!local) {
			if (predicting) {
				predicting = false;
				history.clear();
				// Observers resume from where the prediction left the ship.
				shownOrientation.set(predictedOrientation);
				shownReady = true;
				shownPositions.clear();
				shownOrientations.clear();
			}
			return;
		}
		if (!predicting) {
			predictedOrientation.set(entityData.get(ORIENTATION));
			predicting = true;
			history.clear();
		}
		Player pilot = pilot();
		if (pilot == null) return;
		Input input = hook.input();
		controls = hook.controls();
		setStick(hook.stickX(), hook.stickY());
		int seq = ++predictSeq;
		Vec3 look = hook.look();
		hook.send(new com.starwake.network.ShipInputPayload(getId(), seq, input, controls, stickX, stickY,
				(float) look.x, (float) look.y, (float) look.z, hook.speedLimit()));
		flight.setLimiter(hook.speedLimit(), (controls & CONTROL_NAV) != 0);
		ShipPhysics physics = ShipPhysics.get();
		flight.measureClearance();
		Vec3 velocity = flight.fly(pilot, getDeltaMovement(), physics, input);
		neighbours = nearbyShips(getBoundingBox().expandTowards(velocity).inflate(1.5));
		var result = com.starwake.ship.physics.ShipMotion.step(this, velocity, neighbours, false);
		grounded = result.grounded();
		history.addLast(new Predicted(seq, position(), orientation(), getDeltaMovement(), angularVelocity));
		while (history.size() > 100) history.removeFirst();
	}

	/**
	 * Client: the server's state after input {@code seq}. The difference from what we predicted for that tick is
	 * added to our current state (and to the predictions after it), so the ship stays where the server has it
	 * without replaying. A large error (a teleport, a collision we didn't see) snaps.
	 */
	public void reconcile(com.starwake.network.ShipStatePayload state) {
		if (!predicting) return;
		Predicted base = null;
		while (!history.isEmpty() && history.peekFirst().seq <= state.seq()) {
			Predicted p = history.removeFirst();
			if (p.seq == state.seq()) base = p;
		}
		if (com.starwake.ship.physics.ShipMotion.DEBUG && state.seq() % 40 == 0) com.starwake.StarwakeMod.LOGGER.info("[predict-sample] seq={} matched={} pending={}",
				state.seq(), base != null, history.size());
		if (base == null) return;
		Vec3 serverPos = new Vec3(state.x(), state.y(), state.z());
		Quaternionf serverRot = new Quaternionf(state.qx(), state.qy(), state.qz(), state.qw());
		Vec3 dPos = serverPos.subtract(base.pos);
		Quaternionf dRot = new Quaternionf(serverRot).mul(new Quaternionf(base.rotation).conjugate()).normalize();
		Vec3 dVel = new Vec3(state.vx(), state.vy(), state.vz()).subtract(base.velocity);
		Vector3d dSpin = new Vector3d(state.wx(), state.wy(), state.wz()).sub(base.spin);
		double angle = 2 * Math.acos(Math.min(1, Math.abs(dRot.w)));
		if (dPos.lengthSqr() < 1e-8 && angle < 1e-5 && dVel.lengthSqr() < 1e-10) return;
		if (com.starwake.ship.physics.ShipMotion.DEBUG || dPos.length() > 0.05) com.starwake.StarwakeMod.LOGGER.info("[predict] seq={} pending={} dPos={} angle={} dVel={}",
				state.seq(), history.size(), String.format("%.4f", dPos.length()), String.format("%.5f", angle), String.format("%.4f", dVel.length()));
		if (dPos.length() > 4 || angle > 0.6) {
			// Way off: take the server's state as it is and start predicting afresh.
			setPose(serverPos, serverRot);
			setDeltaMovement(new Vec3(state.vx(), state.vy(), state.vz()));
			angularVelocity.set(state.wx(), state.wy(), state.wz());
			history.clear();
			return;
		}
		setPose(position().add(dPos), new Quaternionf(dRot).mul(orientation()).normalize());
		setDeltaMovement(getDeltaMovement().add(dVel));
		angularVelocity.add(dSpin);
		for (Predicted p : history) {
			p.pos = p.pos.add(dPos);
			new Quaternionf(dRot).mul(p.rotation, p.rotation).normalize();
			p.velocity = p.velocity.add(dVel);
			p.spin.add(dSpin);
		}
	}

	// Observers (M9 pass 2): server updates are played out one per tick from a small queue instead of snapping as they
	// arrive. Packets come in bursts (two in one tick, none the next); a queue evens that out at about a tick of
	// latency. Position and orientation travel separately (move packets vs entity data), so each has its own queue,
	// popped in step. The pilot's own client predicts instead and ignores both.
	/** Queued server positions / orientations, oldest first (client, observers only). */
	private final java.util.ArrayDeque<Vec3> shownPositions = new java.util.ArrayDeque<>();
	private final java.util.ArrayDeque<Quaternionf> shownOrientations = new java.util.ArrayDeque<>();
	/** The orientation observers see (what {@link #orientation()} returns on their client once ready). */
	private final Quaternionf shownOrientation = new Quaternionf();
	private boolean shownReady;
	/** Queue lengths past which the playout catches up (drops the oldest), and the jump that snaps instead. */
	private static final int SHOWN_MAX = 3;
	private static final double SHOWN_SNAP = 16;

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
		super.onSyncedDataUpdated(accessor);
		if (shownOrientations == null) return; // called from the superclass constructor
		if (accessor.equals(JOINTS) && level().isClientSide() && articulations != null) articulations.decode(entityData.get(JOINTS));
		if (accessor.equals(ORIENTATION) && level().isClientSide() && !predicting) {
			Quaternionf q = new Quaternionf(entityData.get(ORIENTATION));
			if (!shownReady) {
				shownOrientation.set(q);
				shownReady = true;
				setBoundingBox(makeBoundingBox(position()));
			} else {
				shownOrientations.addLast(q);
			}
		}
	}

	/** The server's velocity packets don't overwrite a predicted ship's velocity either. */
	@Override
	public void lerpMotion(Vec3 movement) {
		if (!predicting) super.lerpMotion(movement);
	}

	/** Server packets don't move a ship the client is predicting (it reconciles instead); observers queue them. */
	@Override
	protected net.minecraft.world.entity.InterpolationHandler createInterpolationHandler() {
		return new net.minecraft.world.entity.InterpolationHandler() {
			@Override
			public net.minecraft.core.@Nullable PositionAndRotation target() {
				Vec3 last = shownPositions.peekLast();
				return last == null ? null : net.minecraft.core.PositionAndRotation.of(last, getYRot(), getXRot());
			}

			@Override
			public boolean interpolateTo(net.minecraft.world.entity.@Nullable PositionPath position, float yRot, float xRot, boolean hasRotation) {
				if (predicting) return true;
				if (position == null) return true;
				Vec3 end = position.endPosition();
				Vec3 from = shownPositions.isEmpty() ? position() : shownPositions.peekLast();
				if (end.distanceToSqr(from) > SHOWN_SNAP * SHOWN_SNAP) {
					// A teleport or a long lag: snap there and drop the backlog.
					shownPositions.clear();
					flushOrientations();
					setPos(end);
					return true;
				}
				shownPositions.addLast(end);
				return true;
			}

			@Override
			public void interpolate() {
				if (predicting) return;
				// Orientation first, so the bounding box built by setPos uses it.
				Quaternionf q = null;
				while (shownOrientations.size() > SHOWN_MAX) shownOrientations.pollFirst();
				if (!shownOrientations.isEmpty()) q = shownOrientations.pollFirst();
				if (q != null) shownOrientation.set(q);
				while (shownPositions.size() > SHOWN_MAX) shownPositions.pollFirst();
				Vec3 next = shownPositions.pollFirst();
				if (next != null) setPos(next);
				else if (q != null) setBoundingBox(makeBoundingBox(position()));
			}

			@Override
			public void applyPredictedMovement(Vec3 delta) {
			}

			@Override
			public boolean hasActiveInterpolation() {
				return !shownPositions.isEmpty();
			}

			@Override
			public void cancel() {
				shownPositions.clear();
				flushOrientations();
			}
		};
	}

	/** Jumps the shown orientation to the newest one received. */
	private void flushOrientations() {
		Quaternionf last = shownOrientations.peekLast();
		if (last != null) shownOrientation.set(last);
		shownOrientations.clear();
	}

	// =========================================================================================
	// Ownership (3j): owner + crew may pilot and disassemble; a locked ship keeps strangers
	// out of doors, seats and storage. Ships with no owner are claimed by their first pilot.
	// =========================================================================================

	private @Nullable UUID owner;
	private String ownerName = "";
	private final Map<UUID, String> crew = new java.util.LinkedHashMap<>();
	private boolean locked;

	public @Nullable UUID owner() { return owner; }

	/** Sets the owner (assembly, admin command, or the first pilot of an ownerless ship). */
	public void setOwner(@Nullable Player player) {
		owner = player == null ? null : player.getUUID();
		ownerName = player == null ? "" : player.getGameProfile().name();
		crew.remove(owner);
		publishAccess();
	}

	public boolean isOwner(Player player) {
		return owner != null && owner.equals(player.getUUID());
	}

	/** Owner, crew, or anyone at all on an ownerless ship. */
	public boolean canPilot(Player player) {
		return owner == null || owner.equals(player.getUUID()) || crew.containsKey(player.getUUID());
	}

	/** Doors, seats and storage: everyone, unless the ship is locked. */
	public boolean canUse(Player player) {
		return !locked || !com.starwake.config.StarwakeConfig.on(com.starwake.config.StarwakeConfig.LOCKING) || canPilot(player);
	}

	public void addCrew(Player player) {
		if (!player.getUUID().equals(owner)) crew.put(player.getUUID(), player.getGameProfile().name());
		publishAccess();
	}

	/** Removes a crew member by name (case-insensitive). Returns false if they weren't crew. */
	public boolean removeCrew(String name) {
		boolean removed = crew.values().removeIf(n -> n.equalsIgnoreCase(name));
		publishAccess();
		// Someone no longer allowed to fly loses the Helm (they can still ride unless locked).
		if (pilot() instanceof Player pilot && !canPilot(pilot)) {
			pilot.stopRiding();
			if (pilot instanceof ServerPlayer player) {
				player.sendOverlayMessage(Component.translatable("message.starwake.ship.not_yours", ownerName));
			}
		}
		return removed;
	}

	public void setLocked(boolean locked) {
		this.locked = locked;
		publishAccess();
		// Locking out strangers also clears them from seats.
		if (locked) {
			for (Entity passenger : List.copyOf(getPassengers())) {
				if (passenger instanceof Player player && !canPilot(player)) player.stopRiding();
			}
		}
	}

	/** Synced access summary: owner name, locked flag, crew names (for the Wristcom Crew app). */
	private void publishAccess() {
		if (level() == null || level().isClientSide()) return;
		entityData.set(ACCESS, ownerName + "\n" + (locked ? "1" : "0") + "\n" + String.join(",", crew.values()));
	}

	/** Client: owner name ("" if none). */
	public String ownerName() {
		return entityData.get(ACCESS).split("\n", -1)[0];
	}

	public boolean isLocked() {
		String[] parts = entityData.get(ACCESS).split("\n", -1);
		return parts.length > 1 && parts[1].equals("1");
	}

	public List<String> crewNames() {
		String[] parts = entityData.get(ACCESS).split("\n", -1);
		if (parts.length < 3 || parts[2].isEmpty()) return List.of();
		return List.of(parts[2].split(","));
	}

	/** Number of landing gear blocks aboard (both sides: computed from the structure). */
	public int gearCount() {
		return gear.size();
	}

	/** True if the ship has landing gear and it's deployed. */
	public boolean isGearDown() {
		if (structure == null || gear.isEmpty()) return false;
		BlockState state = structure.get(gear.iterator().next());
		return state != null && state.hasProperty(com.starwake.ship.block.LandingGearBlock.DEPLOYED)
				&& state.getValue(com.starwake.ship.block.LandingGearBlock.DEPLOYED);
	}

	/** Pilot pressed N: deploy or retract every landing gear block. */
	public void toggleGear() {
		if (structure == null || gear.isEmpty() || !(level() instanceof ServerLevel serverLevel)) return;
		boolean deploy = !isGearDown();
		ShipStructure before = structure;
		// One batch for every strut (M9 pass 3: was one structure rebuild per strut, then a full resend).
		Map<BlockPos, ShipStructure.@Nullable Entry> changes = new java.util.LinkedHashMap<>();
		Map<BlockPos, ShipStructure.@Nullable Entry> undo = new java.util.LinkedHashMap<>();
		for (BlockPos pos : List.copyOf(gear)) {
			ShipStructure.Entry entry = structure.entry(pos);
			if (entry == null) continue;
			changes.put(pos, new ShipStructure.Entry(pos, entry.state().setValue(com.starwake.ship.block.LandingGearBlock.DEPLOYED, deploy), entry.blockEntity()));
			undo.put(pos, entry);
		}
		ShipStructure next = structure.withChanges(changes);
		applyIncremental(next, changes);
		if (deploy) {
			// Extending gear into the ground lifts the ship onto it (or fails if there's no room).
			boolean placed = false;
			for (double lift : new double[] {0, 0.25, 0.5, 0.75}) {
				if (!hullCollides(position().add(0, lift, 0), orientation())) {
					if (lift > 0) setPos(position().add(0, lift, 0));
					placed = true;
					break;
				}
			}
			if (!placed) {
				applyIncremental(before, undo);
				if (pilot() instanceof ServerPlayer player) player.sendOverlayMessage(Component.translatable("message.starwake.gear.blocked"));
				return;
			}
		}
		for (BlockPos pos : gear) {
			BlockState state = structure.get(pos);
			if (state != null) com.starwake.ship.yard.Shipyard.set(this, pos, state); // the plot follows (M9)
		}
		structureVersion++;
		var payload = com.starwake.network.ShipDeltaPayload.of(getId(), structureVersion, structure, changes, registryAccess());
		for (ServerPlayer player : net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(this)) {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
		}
		serverLevel.playSound(null, BlockPos.containing(position()), com.starwake.registry.ModSounds.GEAR,
				net.minecraft.sounds.SoundSource.BLOCKS, 1.0f, deploy ? 1.0f : 0.85f);
		if (pilot() instanceof ServerPlayer player) {
			player.sendOverlayMessage(Component.translatable(deploy ? "message.starwake.gear.down" : "message.starwake.gear.up"));
		}
	}

	/** Ticks of orbit-insertion assist left after leaving an atmosphere (not saved). */
	private int exitAssist;

	/** Called on the ship's new copy in space after it leaves a planet. */
	public void startExitAssist() {
		exitAssist = 100;
	}

	/** Decoupled flight (Star Citizen's "decoupled mode"): no automatic braking. Saved. */
	private boolean decoupled;

	public boolean isDecoupled() {
		return level().isClientSide() ? entityData.get(DECOUPLED) : decoupled;
	}

	/** Pilot pressed V: coupled <-> decoupled. */
	public void toggleDecoupled() {
		decoupled = !decoupled;
		entityData.set(DECOUPLED, decoupled);
		if (pilot() instanceof ServerPlayer player) {
			player.sendOverlayMessage(Component.translatable(decoupled ? "message.starwake.decoupled" : "message.starwake.coupled"));
		}
	}

	private long perfNanos;
	private int perfSteps;

	/** Parked and not simulating (server, unpiloted only; see the unpiloted branch of the server tick). */
	private boolean asleep;

	/** Syncs thruster throttles for exhaust effects: "entryIndex:0-9;" for each firing thruster. */
	private void publishThrottles() {
		StringBuilder out = new StringBuilder();
		for (int k = 0; k < throttles.length; k++) {
			int level = (int) Math.round(throttles[k] * 9);
			if (level > 0) out.append(thrusterEntries[k]).append(':').append(level).append(';');
		}
		entityData.set(THROTTLES, out.toString());
	}

	/** Client: firing thrusters as (structure entry index, throttle 0..1). */
	public Map<Integer, Float> firingThrusters() {
		Map<Integer, Float> result = new HashMap<>();
		for (String part : entityData.get(THROTTLES).split(";")) {
			int colon = part.indexOf(':');
			if (colon > 0) result.put(Integer.parseInt(part.substring(0, colon)), Integer.parseInt(part.substring(colon + 1)) / 9f);
		}
		return result;
	}

	/** The rigid-body model (centre of mass, inertia, thrusters). */
	public ShipDynamics dynamics() {
		return level().isClientSide() && serverDynamics != null ? serverDynamics : dynamics;
	}

	/** Fractional fuel accumulates so small burns still drain the tank over time. */
	void burnFuel(double amount) {
		fuelDebt += amount * com.starwake.config.StarwakeConfig.get(com.starwake.config.StarwakeConfig.FUEL_USE);
		int whole = (int) fuelDebt;
		if (whole > 0) {
			fuelDebt -= whole;
			entityData.set(FUEL, Math.max(0, fuel() - whole));
		}
	}

	// --- Terrain collision -------------------------------------------------------------------

	/** Re-entry heating and impact damage (M10: split out; combat damage joins it in M11). */
	public final ShipDamage damage = new ShipDamage(this);

	private double clip(Direction.Axis axis, double distance) {
		if (distance == 0 || structure == null) return distance;
		boolean aligned = isAxisAligned();
		for (AABB local : hull.values()) {
			AABB box = worldBox(local, aligned ? 1.0 : 0.9).deflate(1.0e-7);
			AABB swept = box.expandTowards(axis == Direction.Axis.X ? distance : 0,
					axis == Direction.Axis.Y ? distance : 0, axis == Direction.Axis.Z ? distance : 0);
			for (VoxelShape shape : level().getBlockCollisions(null, swept)) {
				distance = shape.collide(axis, box, distance);
				if (distance == 0) return 0;
			}
			if (!neighbours.isEmpty()) {
				List<VoxelShape> shapes = new ArrayList<>();
				for (ShipEntity other : neighbours) other.collectShapes(swept, shapes);
				for (VoxelShape shape : shapes) {
					distance = shape.collide(axis, box, distance);
					if (distance == 0) return 0;
				}
			}
		}
		return distance;
	}

	/** A ship resting on this one: its pivot in our local frame, and its rotation relative to ours. */
	private record ShipRider(ShipEntity ship, Vec3 local, Quaternionf relative) {}

	private List<ShipRider> shipRiders = List.of();

	/** Ships whose hull rests on ours right now (checked before we move). */
	private List<ShipRider> findShipRiders() {
		List<ShipEntity> near = nearbyShips(getBoundingBox().inflate(0.5));
		if (near.isEmpty()) return List.of();
		List<ShipRider> riders = new ArrayList<>();
		for (ShipEntity other : near) {
			if (other.restsOn(this)) {
				Quaternionf relative = new Quaternionf(orientation()).conjugate().mul(other.orientation());
				riders.add(new ShipRider(other, toLocal(other.position()), relative));
			}
		}
		return riders;
	}

	/** True if some of this ship's hull sits on {@code carrier}'s blocks (within a few cm). */
	private boolean restsOn(ShipEntity carrier) {
		boolean aligned = isAxisAligned();
		List<VoxelShape> shapes = new ArrayList<>();
		for (AABB local : hull.values()) {
			AABB box = worldBox(local, aligned ? 1.0 : 0.9);
			AABB below = new AABB(box.minX, box.minY - 0.1, box.minZ, box.maxX, box.minY + 0.05, box.maxZ);
			shapes.clear();
			carrier.collectShapes(below, shapes);
			for (VoxelShape shape : shapes) if (shape.bounds().intersects(below)) return true;
		}
		return false;
	}

	/** Ships near this one during the current move (empty most of the time). */
	private List<ShipEntity> neighbours = List.of();

	private List<ShipEntity> nearbyShips(AABB area) {
		List<ShipEntity> found = null;
		for (ShipEntity other : ShipCollisions.shipsIn(level())) {
			if (other == this || other.structure() == null || !other.getBoundingBox().intersects(area)) continue;
			if (found == null) found = new ArrayList<>();
			found.add(other);
		}
		return found == null ? List.of() : found;
	}

	/** Would the hull overlap terrain at this pose? (Used to refuse rotations into the ground.) */
	private boolean hullCollides(Vec3 position, Quaternionfc rotation) {
		boolean aligned = isAxisAligned(rotation);
		List<ShipEntity> others = nearbyShips(getBoundingBox().move(position.subtract(position())).inflate(2.0));
		List<VoxelShape> shipShapes = new ArrayList<>();
		for (AABB local : hull.values()) {
			AABB box = worldBox(local, aligned ? 1.0 : 0.9, position, rotation).deflate(0.05);
			for (VoxelShape shape : level().getBlockCollisions(null, box)) {
				if (!shape.isEmpty() && shape.bounds().intersects(box)) return true;
			}
			if (!others.isEmpty()) {
				shipShapes.clear();
				for (ShipEntity other : others) other.collectShapes(box, shipShapes);
				for (VoxelShape shape : shipShapes) {
					if (shape.bounds().intersects(box)) return true;
				}
			}
		}
		return false;
	}

	// --- Carrying ------------------------------------------------------------------------------

	/**
	 * Server: moves non-player entities on or inside the ship rigidly with it (translation and
	 * rotation). Players move themselves; the client moves the local player in the ship frame (ShipLocalMotion).
	 */
	private void carryEntities() {
		Vec3 oldPos = lastTickPos;
		Quaternionfc oldRot = lastTickOrientation;
		// Ships parked on this one (a fighter on a carrier) ride along, turning with it.
		for (ShipRider rider : shipRiders) {
			if (rider.ship().isRemoved() || rider.ship().getDeltaMovement().y > 0.05) continue; // taking off
			rider.ship().setPos(toWorld(rider.local(), position(), orientation()));
			rider.ship().setOrientation(new Quaternionf(orientation()).mul(rider.relative()).normalize());
		}
		AABB zone = getBoundingBox().expandTowards(movementThisTick().reverse()).inflate(1.0);
		for (Entity entity : level().getEntities(this, zone, e -> !(e instanceof Player) && !e.isPassenger() && !(e instanceof ShipEntity))) {
			Vec3 local = toLocal(entity.position(), oldPos, oldRot);
			if (isAboard(entity, local)) {
				entity.setPos(toWorld(local, position(), orientation()));
			}
		}
	}

	/**
	 * True if an entity whose feet are at ship-local {@code local} stands on a ship block or
	 * is inside the hull's bounds.
	 */
	public boolean isAboard(Entity entity, Vec3 local) {
		if (structure == null) return false;
		double r = entity.getBbWidth() / 2;
		for (double ox : new double[] {-r, r}) {
			for (double oz : new double[] {-r, r}) {
				BlockPos below = BlockPos.containing(local.x + ox, local.y - 0.2, local.z + oz);
				if (structure.hasCollision(below)) return true;
			}
		}
		return structure.inBounds(local.x, local.y, local.z, 0) && !entity.onGround();
	}

	/** True if the entity is aboard at the current transform. */
	public boolean isSupporting(Entity entity) {
		return isAboard(entity, toLocal(entity.position()));
	}

	/** Transform of the previous tick (for order-independent carrying on the client). */
	public Vec3 toLocalAtLastTick(Vec3 world) {
		return lastTickPos == null ? toLocal(world) : toLocal(world, lastTickPos, lastTickOrientation);
	}

	// =========================================================================================
	// Seats and riding
	// =========================================================================================

	public List<BlockPos> seats() {
		return List.copyOf(seats);
	}

	/** The seat a passenger occupies (server map, or the synced copy on clients). */
	private @Nullable BlockPos seatOf(Entity passenger) {
		if (!level().isClientSide()) return seatOf.get(passenger.getUUID());
		String key = passenger.getUUID() + "=";
		for (String part : entityData.get(SEATS).split(";")) {
			if (part.startsWith(key)) return BlockPos.of(Long.parseLong(part.substring(key.length())));
		}
		return null;
	}

	private void publishSeats() {
		StringBuilder encoded = new StringBuilder();
		seatOf.forEach((uuid, pos) -> encoded.append(uuid).append('=').append(pos.asLong()).append(';'));
		entityData.set(SEATS, encoded.toString());
	}

	/** Seats a player at a local seat (the Helm or a Seat block). Returns false if taken. */
	public boolean seat(Player player, BlockPos seatLocal) {
		if (seatOf.containsValue(seatLocal) && getPassengers().stream().anyMatch(p -> seatLocal.equals(seatOf.get(p.getUUID())))) {
			return false;
		}
		seatOf.put(player.getUUID(), seatLocal);
		if (player.getVehicle() != this && !player.startRiding(this)) {
			seatOf.remove(player.getUUID());
			return false;
		}
		if (seatLocal.equals(helmLocal) && player instanceof ServerPlayer serverPlayer) {
			// A new pilot faces along the nose: mouse-aim steers toward your view, so keeping the
			// look you sat down with (usually down at the Helm) would pitch the ship at once.
			Vec3 nose = worldDirection(forward);
			float yaw = (float) Math.toDegrees(Math.atan2(-nose.x, nose.z));
			float pitch = (float) Math.toDegrees(-Math.asin(Mth.clamp(nose.y, -1, 1)));
			serverPlayer.forceSetRotation(yaw, false, pitch, false);
		}
		publishSeats();
		return true;
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return passenger instanceof Player && getPassengers().size() < 1 + seats.size();
	}

	@Override
	protected void addPassenger(Entity passenger) {
		super.addPassenger(passenger);
		// Riders restored from saves (or via vanilla teleport) get the helm, then any free seat.
		if (!seatOf.containsKey(passenger.getUUID())) {
			List<BlockPos> free = new ArrayList<>();
			free.add(helmLocal);
			free.addAll(seats);
			for (BlockPos seat : free) {
				if (getPassengers().stream().noneMatch(p -> p != passenger && seat.equals(seatOf.get(p.getUUID())))) {
					seatOf.put(passenger.getUUID(), seat);
					break;
				}
			}
		}
		if (!level().isClientSide()) publishSeats();
	}

	@Override
	protected void removePassenger(Entity passenger) {
		super.removePassenger(passenger);
		seatOf.remove(passenger.getUUID());
		if (!level().isClientSide()) publishSeats();
	}

	/** Seat position: on top of the Helm, or sitting in a Seat block. */
	private Vec3 seatPoint(BlockPos seatLocal) {
		boolean helm = seatLocal.equals(helmLocal);
		// M9: the pilot sits high on the Helm so their eye clears the dash.
		return new Vec3(seatLocal.getX() + 0.5, seatLocal.getY() + (helm ? HELM_SEAT_HEIGHT : 0.5), seatLocal.getZ() + 0.5);
	}

	@Override
	protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
		BlockPos seat = seatOf(passenger);
		Vec3 point = toWorld(seatPoint(seat == null ? helmLocal : seat)).subtract(position());
		// Vanilla then subtracts the rider's own attachment offset along *world* Y. Swap that for
		// the same offset along the ship's up, or a rider in an inverted ship sits in the ceiling.
		Vec3 own = passenger.getVehicleAttachmentPoint(this);
		Vector3f rotated = orientation().transform(new Vector3f((float) own.x, (float) own.y, (float) own.z));
		return point.add(own).subtract(rotated.x, rotated.y, rotated.z);
	}

	@Override
	public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
		BlockPos seat = seatOf(passenger);
		if (seat == null) seat = helmLocal;
		return toWorld(new Vec3(seat.getX() + 0.5, seat.getY() + 1.0, seat.getZ() + 0.5));
	}

	@Override
	public @Nullable LivingEntity getControllingPassenger() {
		return null; // the server flies the ship from the pilot's input; no client-side steering
	}

	// =========================================================================================
	// Misc entity behaviour
	// =========================================================================================

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		return false;
	}

	@Override
	public boolean isPickable() {
		return false; // interaction goes through ship-block raycasts instead (see MinecraftMixin)
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public PushReaction getPistonPushReaction() {
		return PushReaction.IGNORE_ENTITY;
	}

	@Override
	public boolean shouldBeSaved() {
		return structure != null && super.shouldBeSaved();
	}

	// =========================================================================================
	// Persistence
	// =========================================================================================

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		fuelCapacity = input.getIntOr("fuel_capacity", 0);
		entityData.set(FUEL_CAPACITY, fuelCapacity);
		power.read(input);
		entityData.set(SHIELD, (float) input.getDoubleOr("shield", 0));
		entityData.set(FUEL, input.getIntOr("fuel", 0));
		entityData.set(FUSION, input.getIntOr("fusion", 0));
		propellantBuffer.clear();
		input.read("propellants", com.mojang.serialization.Codec.unboundedMap(com.mojang.serialization.Codec.STRING,
				com.mojang.serialization.Codec.DOUBLE)).ifPresent(propellantBuffer::putAll);
		orbiting = input.read("orbiting", Level.RESOURCE_KEY_CODEC).orElse(null);
		decoupled = input.getBooleanOr("decoupled", false);
		owner = input.read("owner", net.minecraft.core.UUIDUtil.CODEC).orElse(null);
		ownerName = input.getStringOr("owner_name", "");
		locked = input.getBooleanOr("locked", false);
		crew.clear();
		for (var member : input.childrenListOrEmpty("crew")) {
			member.read("uuid", net.minecraft.core.UUIDUtil.CODEC).ifPresent(uuid -> crew.put(uuid, member.getStringOr("name", "?")));
		}
		publishAccess();
		entityData.set(DECOUPLED, decoupled);
		input.read("orientation", ExtraCodecs.QUATERNIONF).ifPresent(q -> entityData.set(ORIENTATION, new Quaternionf(q)));
		int frame = input.getIntOr("frame", 1);
		input.read("structure", ShipStructure.CODEC).ifPresent(this::setStructure);
		plot = input.getIntOr("plot", -1);
		if (frame < FRAME_VERSION && structure != null) {
			// Ships saved before 3d stored the min corner as their position.
			setPos(getX() + pivot.x, getY() + pivot.y, getZ() + pivot.z);
		}
		lastTickOrientation.set(orientation());
		prevOrientation.set(orientation());
		// A jump-drive tunnel in progress resumes (M6).
		input.getString("jump_target").ifPresent(target -> {
			drives.jumpTarget = target;
			drives.jumpTicks = input.getIntOr("jump_ticks", 1);
			drives.jumpTotal = input.getIntOr("jump_total", drives.jumpTicks);
			drives.jumpDirection = input.read("jump_direction", Vec3.CODEC).orElse(new Vec3(1, 0, 0));
			entityData.set(JD_STATE, JD_TRAVEL);
			entityData.set(JD_TARGET, target);
		});
		// A jump in progress resumes where it was (target, start, drop-out point, ticks so far).
		input.read("quantum_target", ResourceKey.codec(com.starwake.planet.Planets.REGISTRY_KEY)).ifPresent(target -> {
			var from = input.read("quantum_from", Vec3.CODEC);
			var to = input.read("quantum_to", Vec3.CODEC);
			if (from.isPresent() && to.isPresent()) {
				// Saves from before moving orbits have no separate drop-out point: the end was physical then.
				Vec3 arrival = input.read("quantum_arrival", Vec3.CODEC).orElse(to.get());
				drives.jump = new ShipDrives.QuantumJump(target, from.get(), to.get(), arrival, input.getIntOr("quantum_elapsed", 0));
				entityData.set(QT_STATE, QT_TRAVEL);
				entityData.set(QT_TARGET, target.identifier().toString());
			}
		});
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.putInt("frame", FRAME_VERSION);
		output.putInt("fuel", fuel());
		output.putInt("fusion", fusion());
		if (!propellantBuffer.isEmpty()) output.store("propellants", com.mojang.serialization.Codec.unboundedMap(com.mojang.serialization.Codec.STRING,
				com.mojang.serialization.Codec.DOUBLE), propellantBuffer);
		output.putInt("fuel_capacity", fuelCapacity);
		power.write(output);
		output.putDouble("shield", entityData.get(SHIELD));
		output.store("orientation", ExtraCodecs.QUATERNIONF, orientation());
		output.putBoolean("decoupled", decoupled);
		if (owner != null) {
			output.store("owner", net.minecraft.core.UUIDUtil.CODEC, owner);
			output.putString("owner_name", ownerName);
		}
		output.putBoolean("locked", locked);
		var crewList = output.childrenList("crew");
		crew.forEach((uuid, name) -> {
			var member = crewList.addChild();
			member.store("uuid", net.minecraft.core.UUIDUtil.CODEC, uuid);
			member.putString("name", name);
		});
		if (orbiting != null) {
			output.store("orbiting", Level.RESOURCE_KEY_CODEC, orbiting);
		}
		if (drives.jumpTicks >= 0 && drives.jumpTarget != null) {
			output.putString("jump_target", drives.jumpTarget);
			output.putInt("jump_ticks", drives.jumpTicks);
			output.putInt("jump_total", drives.jumpTotal);
			output.store("jump_direction", Vec3.CODEC, drives.jumpDirection);
		}
		if (drives.jump != null) {
			output.store("quantum_target", ResourceKey.codec(com.starwake.planet.Planets.REGISTRY_KEY), drives.jump.target());
			output.store("quantum_from", Vec3.CODEC, drives.jump.from());
			output.store("quantum_to", Vec3.CODEC, drives.jump.to());
			output.store("quantum_arrival", Vec3.CODEC, drives.jump.arrival());
			output.putInt("quantum_elapsed", drives.jump.elapsed());
		}
		if (structure != null) {
			output.store("structure", ShipStructure.CODEC, structure);
		}
		output.putInt("plot", plot);
	}
}
