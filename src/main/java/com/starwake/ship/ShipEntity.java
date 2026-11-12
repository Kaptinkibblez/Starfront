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
import com.starwake.ship.systems.PowerAllocation;
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
	// Ship systems state for the client HUD (computed server-side, where ship stats data lives).
	private static final EntityDataAccessor<Integer> FUEL_CAPACITY = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
	/** M8f: fusion fuel (D + He-3) units for the quantum and jump drives; capacity is 10 x the propellant capacity. */
	private static final EntityDataAccessor<Integer> FUSION = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Float> SHIELD = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> SHIELD_MAX = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.FLOAT);
	/** M8e-1: skin temperature of the leading face (C, 0 = not heating) and the weakest heat limit on it. */
	private static final EntityDataAccessor<Float> SKIN_TEMP = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> HULL_LIMIT = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.FLOAT);
	/** Packed power split percents (0-100 each): engines * 1_000_000 + shields * 1000 + quantum. */
	private static final EntityDataAccessor<Integer> POWER_SPLIT = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Float> ENGINE_FACTOR = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> THERMAL = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.FLOAT);
	/** Quantum spool progress 0..1, or -1 when not spooling. */
	private static final EntityDataAccessor<Float> QUANTUM = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.FLOAT);
	/** Orbited planet's dimension id ("" when not in space / unknown). */
	private static final EntityDataAccessor<String> ORBIT = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.STRING);
	/** Quantum drive state bits ({@code QT_*}). */
	private static final EntityDataAccessor<Integer> QT_STATE = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
	/** Selected quantum target (planet registry id, "" for none). */
	private static final EntityDataAccessor<String> QT_TARGET = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.STRING);
	/** Fuel cost of the selected jump (in quantum mode), or ticks left (while travelling). */
	private static final EntityDataAccessor<Integer> QT_INFO = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
	/**
	 * During a jump: how far the ship has "really" travelled from where it sits. The hull
	 * stays put in the world until the jump ends; the crew see space as if from start + offset.
	 */
	private static final EntityDataAccessor<org.joml.Vector3fc> QT_OFFSET = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.VECTOR3);

	/** Firing thrusters for exhaust effects (see {@link #publishThrottles}). */
	private static final EntityDataAccessor<String> THROTTLES = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.STRING);

	private static final EntityDataAccessor<String> ACCESS = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Boolean> DECOUPLED = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Float> WING_LIFT = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.FLOAT);
	/** Jump drive (M6): state bits ({@code JD_*}), target system id, info (fuel or ticks left), spool. */
	private static final EntityDataAccessor<Integer> JD_STATE = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<String> JD_TARGET = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Integer> JD_INFO = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Float> JD_SPOOL = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.FLOAT);
	/** Jump range in light-years (ship stats are server data; the galaxy map needs it). */
	private static final EntityDataAccessor<Float> JD_REACH = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.FLOAT);

	/** {@link #jumpState()} bits. */
	public static final int JD_MODE = 1, JD_TRAVEL = 2, JD_ALIGNED = 4, JD_WELL = 8, JD_NO_FUEL = 16, JD_RANGE = 32,
			JD_NO_TARGET = 64, JD_SPOOLED = 128;

	/** {@link #quantumState()} bits. */
	public static final int QT_MODE = 1, QT_TRAVEL = 2, QT_ALIGNED = 4, QT_OBSTRUCTED = 8, QT_NO_FUEL = 16,
			QT_TOO_CLOSE = 32, QT_NO_TARGET = 64, QT_SPOOLED = 128, QT_NO_DRIVE = 256;

	/** Control bits from {@link com.starwake.network.ShipControlPayload}. */
	public static final int CONTROL_ROLL_LEFT = 1, CONTROL_ROLL_RIGHT = 2, CONTROL_FREE_LOOK = 4, CONTROL_JOYSTICK = 8;

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
	private PowerAllocation allocation = PowerAllocation.DEFAULT;
	private ShipSystems systems = new ShipSystems();
	/** Quantum drive (3g). Mode, target and spool aren't saved; an unfinished jump is resumed. */
	private boolean quantumMode;
	private @Nullable ResourceKey<com.starwake.planet.Planet> quantumTarget;
	private double quantumSpool;
	private @Nullable QuantumJump jump;
	/** Jump drive (M6): armed, selected system, spool, and a jump in progress (ticks left / total). */
	private boolean jumpMode;
	private @Nullable String jumpTarget;
	private double jumpSpool;
	private int jumpTicks = -1, jumpTotal;
	private Vec3 jumpDirection = Vec3.ZERO;
	/** Client: travel offset at the previous tick, for interpolation. */
	private final Vector3f prevQtOffset = new Vector3f();
	private final Vector3f lastTickQtOffset = new Vector3f();
	private @Nullable ResourceKey<Level> orbiting;
	private boolean sealed;

	// Derived from the structure (recomputed on load; never saved). Keyed by LOCAL direction.
	private double mass = 1;
	private double[] hullProbes = new double[0];
	private double radius = 1;
	/** Rigid-body model: centre of mass, inertia, thrusters (3h). */
	private ShipDynamics dynamics = new ShipDynamics().finish();
	/** Structure entry index of each thruster in {@code dynamics} (for the synced throttles). */
	private int[] thrusterEntries = new int[0];
	/** Flight computer output, one per thruster (warm start for the next tick). */
	private double[] throttles = new double[0];
	/** Angular velocity in the ship's own frame, radians per tick. */
	private final Vector3d angularVelocity = new Vector3d();
	private final List<BlockPos> seats = new ArrayList<>();
	/**
	 * Collision boxes (local) of blocks with any exposed face: the hull used for terrain
	 * collision. Each is the block's real shape bounds, so retracted landing gear doesn't
	 * touch the ground and deployed gear reaches below its block.
	 */
	private final List<AABB> hull = new ArrayList<>();
	/** Per block: mass factor, heat limit and armour from its material (M8e-1). */
	private final Map<BlockPos, ShipMaterials.Rating> ratings = new HashMap<>();
	/** Re-entry heat soaked by leading-face blocks (a block burns off at 1). */
	private final Map<BlockPos, Double> heatDose = new HashMap<>();
	/** Landing gear blocks aboard (local positions). */
	private final List<BlockPos> gear = new ArrayList<>();

	/** Server: which seat (local pos) each passenger occupies. The Helm's seat makes you the pilot. */
	private final Map<UUID, BlockPos> seatOf = new HashMap<>();
	private int controls;

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
		recomputeStats();
		sealed = computeSealed();
		setBoundingBox(makeBoundingBox(position()));
	}

	public @Nullable ShipStructure structure() {
		return structure;
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
		if (structure == null) return;
		ShipDynamics model = new ShipDynamics();
		List<Integer> thrusterIndex = new ArrayList<>();
		List<ShipStructure.Entry> entries = structure.entries();
		for (int index = 0; index < entries.size(); index++) {
			ShipStructure.Entry entry = entries.get(index);
			BlockState state = entry.state();
			int before = model.thrusterCount();
			boolean clear = !(state.getBlock() instanceof ThrusterBlock)
					|| structure.get(entry.pos().relative(state.getValue(ThrusterBlock.FACING))) == null;
			ShipMaterials.Rating rating = ShipMaterials.rate(state, ShipMaterials.of(entry.blockEntity()), ShipMaterials.quality(entry.blockEntity()));
			ratings.put(entry.pos(), rating);
			model.add(entry.pos(), state, clear, rating.massFactor(), rating.stats());
			if (model.thrusterCount() > before) thrusterIndex.add(index);
			if (state.getBlock() instanceof com.starwake.ship.block.LandingGearBlock) gear.add(entry.pos());
			if (state.getBlock() instanceof HelmBlock) {
				helmLocal = entry.pos();
				forward = state.getValue(HelmBlock.FACING);
			} else if (state.getBlock() instanceof SeatBlock) {
				seats.add(entry.pos());
			}
			for (Direction d : Direction.values()) {
				if (!structure.hasCollision(entry.pos().relative(d)) && structure.hasCollision(entry.pos())) {
					hull.add(state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
							.bounds().move(entry.pos()));
					break;
				}
			}
		}
		dynamics = model.finish();
		mass = dynamics.mass();
		computeProbes();
		if (level() instanceof ServerLevel && tickCount > 0) {
			// M9: trackers get the new model (pilots predict with it).
			var payload = com.starwake.network.ShipDynamicsPayload.of(getId(), dynamics);
			for (ServerPlayer player : net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(this)) {
				net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
			}
		}
		thrusterEntries = thrusterIndex.stream().mapToInt(Integer::intValue).toArray();
		throttles = new double[thrusterEntries.length];

		systems = new ShipSystems();
		for (ShipStructure.Entry entry : structure.entries()) {
			boolean clear = !(entry.state().getBlock() instanceof ThrusterBlock)
					|| structure.get(entry.pos().relative(entry.state().getValue(ThrusterBlock.FACING))) == null;
			ShipMaterials.Rating rating = ratings.get(entry.pos());
			systems.add(entry.state(), clear, rating == null ? ShipStats.get(entry.state()) : rating.stats());
		}
		publishSystems();
	}

	/** Server: pushes systems numbers to the synced HUD fields. */
	/** Unique corners of the hull boxes, as contact probes for {@link com.starwake.ship.physics.ShipMotion}. */
	private void computeProbes() {
		java.util.LinkedHashSet<Long> seen = new java.util.LinkedHashSet<>();
		java.util.List<Double> out = new ArrayList<>();
		double far = 1;
		Vector3d c = dynamics.centre();
		for (AABB box : hull) {
			for (int k = 0; k < 8; k++) {
				double x = (k & 1) == 0 ? box.minX : box.maxX, y = (k & 2) == 0 ? box.minY : box.maxY, z = (k & 4) == 0 ? box.minZ : box.maxZ;
				long key = (Math.round(x * 16) & 0x1FFFFF) | ((Math.round(y * 16) & 0x1FFFFF) << 21) | ((Math.round(z * 16) & 0x1FFFFF) << 42);
				if (!seen.add(key)) continue;
				// Probes sit a hair inside the box, so a hull resting exactly on a block face isn't "inside" it.
				out.add(x + ((k & 1) == 0 ? 0.001 : -0.001));
				out.add(y + ((k & 2) == 0 ? 0.001 : -0.001));
				out.add(z + ((k & 4) == 0 ? 0.001 : -0.001));
				far = Math.max(far, Math.sqrt((x - c.x) * (x - c.x) + (y - c.y) * (y - c.y) + (z - c.z) * (z - c.z)));
			}
		}
		hullProbes = new double[out.size()];
		for (int i = 0; i < hullProbes.length; i++) hullProbes[i] = out.get(i);
		radius = far;
	}

	private void publishSystems() {
		if (level() == null || level().isClientSide()) return;
		entityData.set(SHIELD_MAX, (float) systems.shieldCapacity());
		entityData.set(SHIELD, (float) Math.min(entityData.get(SHIELD), systems.shieldCapacity()));
		int[] p = allocation.percents();
		entityData.set(POWER_SPLIT, p[0] * 1_000_000 + p[1] * 1000 + p[2]);
		entityData.set(ENGINE_FACTOR, (float) systems.engineFactor(allocation));
		entityData.set(THERMAL, (float) systems.thermalFactor());
		entityData.set(ORBIT, orbiting == null ? "" : orbiting.identifier().toString());
	}

	/**
	 * Same rule as the pre-flight check, on the stored blocks: flood-fill air from the pilot
	 * seat; full-collision blocks, doors and trapdoors seal; reaching the edge means a leak.
	 */
	private boolean computeSealed() {
		cabinAir = Set.of();
		if (structure == null) return false;
		BlockPos seat = helmLocal.above();
		Set<BlockPos> visited = new HashSet<>(List.of(seat));
		ArrayDeque<BlockPos> queue = new ArrayDeque<>(visited);
		BlockPos min = structure.min(), max = structure.max();
		while (!queue.isEmpty()) {
			BlockPos pos = queue.poll();
			if (pos.getX() < min.getX() || pos.getY() < min.getY() || pos.getZ() < min.getZ() || pos.getX() > max.getX()
					|| pos.getY() > max.getY() || pos.getZ() > max.getZ() || visited.size() > 32768) {
				return false;
			}
			for (Direction direction : Direction.values()) {
				BlockPos next = pos.relative(direction);
				if (visited.contains(next) || seals(next)) continue;
				visited.add(next);
				queue.add(next);
			}
		}
		cabinAir = Set.copyOf(visited);
		return true;
	}

	/** Air cells inside the sealed cabin (empty when the hull leaks). */
	private Set<BlockPos> cabinAir = Set.of();

	/**
	 * True if a world position is inside this ship's sealed, pressurized cabin (M5 hazards:
	 * breathable air and a stable temperature whatever the planet outside).
	 */
	public boolean isInPressurizedCabin(Vec3 world) {
		if (!sealed || cabinAir.isEmpty()) return false;
		return cabinAir.contains(BlockPos.containing(toLocal(world)));
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
			com.starwake.ship.yard.Shipyard.write(yard, plot, structure.entries());
			com.starwake.StarwakeMod.LOGGER.info("Ship {} moved into shipyard plot {} ({} blocks)", getId(), plot, structure.entries().size());
		}
		com.starwake.ship.yard.Shipyard.track(this, plot);
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
			java.util.Set<BlockPos> detached = com.starwake.ship.edit.ShipEditing.detached(next, helmLocal);
			if (!detached.isEmpty() && level() instanceof ServerLevel world) {
				ShipStructure loose = new ShipStructure(com.starwake.ship.yard.Shipyard.read(yard, plot, detached), next.pivot());
				com.starwake.ship.yard.Shipyard.clear(yard, plot, detached);
				com.starwake.ship.edit.ShipEditing.dropDebris(this, world, loose, detached, null);
				next = next.without(detached);
			}
		}
		if (next.entries().isEmpty()) {
			discard();
			return;
		}
		applyEdit(next);
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
		for (Entity passenger : List.copyOf(getPassengers())) {
			BlockPos seat = seatOf(passenger);
			if (seat != null && next.get(seat) == null) passenger.stopRiding();
		}
		setStructure(next);
		int capacity = 0;
		for (ShipStructure.Entry entry : next.entries()) {
			capacity += ShipMaterials.withQuality(ShipStats.get(entry.state()), ShipMaterials.quality(entry.blockEntity())).fuelCapacity();
		}
		fuelCapacity = capacity;
		entityData.set(FUEL_CAPACITY, capacity);
		if (fuel() > capacity) entityData.set(FUEL, capacity);
		if (fusion() > fusionCapacity()) entityData.set(FUSION, fusionCapacity());
		var payload = new com.starwake.network.ShipStructurePayload(getId(), next);
		for (ServerPlayer player : net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(this)) {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
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
		builder.define(FUEL_CAPACITY, 0);
		builder.define(FUSION, 0);
		builder.define(SHIELD, 0f);
		builder.define(SHIELD_MAX, 0f);
		builder.define(SKIN_TEMP, 0f);
		builder.define(HULL_LIMIT, 0f);
		builder.define(POWER_SPLIT, 60_020_020);
		builder.define(ENGINE_FACTOR, 1f);
		builder.define(THERMAL, 1f);
		builder.define(QUANTUM, -1f);
		builder.define(ORBIT, "");
		builder.define(QT_STATE, 0);
		builder.define(THROTTLES, "");
		builder.define(DECOUPLED, false);
		builder.define(ACCESS, "\n0\n");
		builder.define(WING_LIFT, 0f);
		builder.define(QT_TARGET, "");
		builder.define(QT_INFO, 0);
		builder.define(QT_OFFSET, new Vector3f());
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
	public float thermalFactor() { return entityData.get(THERMAL); }
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
	public int[] powerPercents() {
		int packed = entityData.get(POWER_SPLIT);
		return new int[] {packed / 1_000_000, packed / 1000 % 1000, packed % 1000};
	}
	public ShipSystems systems() { return systems; }
	public PowerAllocation allocation() { return allocation; }

	/** Server: the pilot re-routes power. */
	public void setAllocation(PowerAllocation allocation) {
		this.allocation = allocation;
		publishSystems();
	}
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
	public boolean isSealed() { return sealed; }
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
	private float stickX, stickY;

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
		return predicting ? predictedOrientation : entityData.get(ORIENTATION);
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

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide()) clientTick();
		if (!level().isClientSide() && structure != null) {
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
		if (jumpTicks >= 0) {
			// In a jump tunnel (M6): the hull holds still until it arrives in the other system.
			setDeltaMovement(Vec3.ZERO);
			angularVelocity.zero();
			java.util.Arrays.fill(throttles, 0);
			publishThrottles();
			return tickJumpTravel();
		}
		if (jump != null) {
			// In quantum travel: the hull holds still while the jump plays out.
			setDeltaMovement(Vec3.ZERO);
			angularVelocity.zero();
			java.util.Arrays.fill(throttles, 0);
			publishThrottles();
			tickSystems();
			tickJump(physics);
			return false;
		}

		if (pilot == null && spin.lengthSqr() > 0) {
			Quaternionf next = new Quaternionf(orientation())
					.rotateY((float) Math.toRadians(-spin.x / 20)).rotateX((float) Math.toRadians(spin.y / 20))
					.rotateZ((float) Math.toRadians(spin.z / 20));
			setOrientation(next.normalize());
		}
		if (pilot == null) {
			if (drift.lengthSqr() > 0) {
				velocity = drift;
			} else {
				velocity = unpiloted(velocity, physics);
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
				velocity = fly(pilot, getDeltaMovement(), physics, pilotInput);
				publishThrottles();
				if (moveStep(velocity, physics, pilot)) return true;
			}
			if (steps == 0 && tickCount - lastInputTick > INPUT_TIMEOUT) {
				velocity = fly(pilot, getDeltaMovement(), physics, serverInput(pilot));
				publishThrottles();
				if (moveStep(velocity, physics, pilot)) return true;
			}
		}
		tickSystems();
		tickHull();
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
		measureClearance(); // every step on both sides, so prediction matches
		double maxSpeed = (SpaceTravel.isSpace(level()) ? physics.maxSpeedSpace() : physics.maxSpeed())
				* (pilot != null && boosting ? BOOST_SPEED : 1.0);
		if (tickCount % 10 == 0 && velocity.lengthSqr() > 1.0 && level() instanceof ServerLevel ahead) {
			// Fast ships: start loading the terrain ~3 s ahead so it's there when they arrive.
			ahead.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PORTAL,
					net.minecraft.world.level.ChunkPos.containing(BlockPos.containing(position().add(velocity.scale(60)))), 2);
		}
		if (velocity.lengthSqr() > maxSpeed * maxSpeed) velocity = velocity.normalize().scale(maxSpeed);
		setDeltaMovement(velocity);
		// M9: rigid-body motion with contacts (slides, tips, settles, shoves other ships).
		neighbours = nearbyShips(getBoundingBox().expandTowards(velocity).inflate(1.5));
		var result = com.starwake.ship.physics.ShipMotion.step(this, velocity, neighbours, pilot == null);
		grounded = result.grounded();
		if (impactCooldown > 0) impactCooldown--;
		else {
			boolean upright = worldDirection(Direction.UP).y > 0.8;
			double safe = !result.touchdown() ? SAFE_IMPACT : isGearDown() && upright ? SAFE_GEAR_LANDING : SAFE_BELLY_LANDING;
			if (result.impact() > safe) onImpact(result.impact(), safe, result.blocked());
		}
		return result.moved() && level() instanceof ServerLevel serverLevel && SpaceTravel.checkAltitude(this, serverLevel);
	}

	// --- Hull: re-entry heating (M8e-1) ------------------------------------------------------------

	public float skinTemperature() {
		return entityData.get(SKIN_TEMP);
	}

	public float hullHeatLimit() {
		return entityData.get(HULL_LIMIT);
	}

	/** The local direction closest to a world direction. */
	public Direction localDirection(Vec3 world) {
		Vector3f v = new Vector3f((float) world.x, (float) world.y, (float) world.z);
		orientation().transformInverse(v);
		return Direction.getApproximateNearest(v.x, v.y, v.z);
	}

	/**
	 * Every half second: the leading face heats to 25 + peak x intensity^n x sqrt(air density) C; blocks
	 * whose heat limit (alloy service temperature, or ship_stats {@code heat_limit}) is lower soak heat in
	 * proportion to the excess and burn off at a dose of 1. Cooler blocks shed it again.
	 */
	private void tickHull() {
		if (tickCount % 10 != 0 || structure == null) return;
		HullTuning tuning = HullTuning.get();
		// Mid-tick on the server the half-second ring isn't updated yet: use last tick's movement.
		Vec3 v = lastMove();
		double intensity = Reentry.intensity(this, v);
		double density = Reentry.airDensity(this);
		float skin = intensity < 0.02 || density <= 0 ? 0
				: (float) (25 + tuning.reentryPeak() * Math.pow(intensity, tuning.reentryExponent()) * Math.sqrt(density));
		if (skin <= 0) {
			entityData.set(SKIN_TEMP, 0f);
			heatDose.replaceAll((k, d) -> d * 0.8);
			heatDose.values().removeIf(d -> d < 0.01);
			return;
		}
		Direction lead = localDirection(v.lengthSqr() > 1e-6 ? v : worldDirection(Direction.DOWN));
		double weakest = Double.MAX_VALUE;
		java.util.Set<BlockPos> burned = new java.util.HashSet<>();
		for (BlockPos pos : structure.outerFace(lead)) {
			ShipMaterials.Rating r = ratings.get(pos);
			double limit = r == null ? ShipStats.get(structure.get(pos)).heatLimit() : r.heatLimit();
			weakest = Math.min(weakest, limit);
			double over = skin - limit;
			double dose = heatDose.getOrDefault(pos, 0.0);
			dose = over > 0 ? dose + over * tuning.burnRate() * 0.5 : dose * 0.8;
			if (dose >= 1) burned.add(pos);
			else if (dose > 0.01) heatDose.put(pos, dose);
			else heatDose.remove(pos);
		}
		entityData.set(SKIN_TEMP, skin);
		entityData.set(HULL_LIMIT, weakest == Double.MAX_VALUE ? 0f : (float) weakest);
		if (!burned.isEmpty() && level() instanceof ServerLevel server) {
			burned.forEach(heatDose::remove);
			for (BlockPos pos : burned) {
				Vec3 at = blockCenter(pos);
				server.sendParticles(net.minecraft.core.particles.ParticleTypes.FLAME, at.x, at.y, at.z, 12, 0.4, 0.4, 0.4, 0.05);
			}
			messageAboard(Component.translatable("message.starwake.hull.burned", burned.size()));
			com.starwake.StarwakeMod.LOGGER.info("Ship {}: re-entry at {} C burned off {} blocks", getId(), Math.round(skin), burned.size());
			com.starwake.ship.edit.ShipEditing.destroy(this, burned, false);
		}
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
			double regen = systems.shieldRegen() * systems.shieldFactor(allocation);
			entityData.set(SHIELD, (float) Math.min(systems.shieldCapacity(), shield + regen));
		}
		tickQuantum();
		tickJumpMode();
		if (tickCount % 20 == 0) entityData.set(JD_REACH, (float) systems.jumpRange());
		if (SpaceTravel.isSpace(level()) && tickCount % 20 == 0) {
			// "Orbiting" = the nearest planet, for the HUD and as the fallback home.
			com.starwake.galaxy.SolarSystem.nearest(level(), position())
					.ifPresent(body -> setOrbiting(body.dimension()));
		}
	}

	// =========================================================================================
	// Quantum drive (3g, Star Citizen style)
	// =========================================================================================
	//
	// 1. Quantum mode (B, or "Set course" on the console) arms the drive and starts the spool.
	// 2. The target is the destination nearest the ship's nose (or the course set on the
	//    console). Its marker shows on the HUD; the pilot flies the nose onto it.
	// 3. Engage (hold B) once spooled, aligned, unobstructed and fuelled: the ship accelerates
	//    to quantum speed along the line to the target, cruises, and drops out just outside its
	//    atmosphere. Long trips take minutes. Nothing can steer or stop it once committed.

	/** A jump in progress. */
	private record QuantumJump(ResourceKey<com.starwake.planet.Planet> target, Vec3 from, Vec3 to, int elapsed) {
		double distance() { return from.distanceTo(to); }
	}

	/** Arms the quantum drive (and optionally selects a target). Returns why not, or null. */
	public @Nullable Component enterQuantumMode(@Nullable ResourceKey<com.starwake.planet.Planet> target) {
		if (!SpaceTravel.isSpace(level())) return Component.translatable("message.starwake.course.not_in_space");
		if (!systems.hasQuantumDrive()) return Component.translatable("message.starwake.quantum.no_drive");
		if (jumpTicks >= 0) return Component.translatable("message.starwake.jump.in_tunnel");
		if (jumpMode) {
			jumpMode = false;
			entityData.set(JD_STATE, 0);
			entityData.set(JD_SPOOL, -1f);
		}
		if (jump != null) return null;
		if (!quantumMode) {
			quantumSpool = 0;
			if (level() instanceof ServerLevel serverLevel) {
				serverLevel.playSound(null, BlockPos.containing(position()), com.starwake.registry.ModSounds.QUANTUM_SPOOL,
						net.minecraft.sounds.SoundSource.NEUTRAL, 1.2f, 1.0f);
			}
		}
		quantumMode = true;
		if (target != null) quantumTarget = target;
		tickQuantum();
		return null;
	}

	public void exitQuantumMode(@Nullable Component reason) {
		if (jump != null) return; // committed
		quantumMode = false;
		quantumTarget = null;
		quantumSpool = 0;
		entityData.set(QUANTUM, -1f);
		entityData.set(QT_STATE, 0);
		entityData.set(QT_TARGET, "");
		if (reason != null && pilot() instanceof ServerPlayer player) player.sendOverlayMessage(reason);
	}

	/** Pilot pressed B: toggle quantum mode. */
	public void toggleQuantumMode() {
		if (jump != null) return;
		if (quantumMode) {
			exitQuantumMode(Component.translatable("message.starwake.quantum.off"));
		} else {
			Component error = enterQuantumMode(null);
			if (pilot() instanceof ServerPlayer player) {
				player.sendOverlayMessage(error != null ? error : Component.translatable("message.starwake.quantum.on"));
			}
		}
	}

	/** Spools, auto-targets and evaluates the route every tick while in quantum mode. */
	private void tickQuantum() {
		if (jump != null || !quantumMode) return;
		if (!SpaceTravel.isSpace(level()) || pilot() == null || !systems.hasQuantumDrive()) {
			exitQuantumMode(Component.translatable("message.starwake.quantum.aborted"));
			return;
		}
		ShipPhysics physics = ShipPhysics.get();
		var access = level().registryAccess();
		Vec3 nose = worldDirection(forward);

		// Auto-target whatever destination marker the nose is on (within 10 degrees).
		double bestAngle = Math.toRadians(10);
		for (var body : com.starwake.galaxy.SolarSystem.bodies(level())) {
			if (!body.enterable()) continue;
			Vec3 to = body.body().position().subtract(position());
			double angle = Math.acos(Mth.clamp(nose.dot(to.normalize()), -1, 1));
			if (angle < bestAngle) {
				bestAngle = angle;
				quantumTarget = body.key();
			}
		}

		double rate = systems.quantumFactor(allocation) / Math.max(1.0, systems.quantumSpool() * 20.0);
		boolean wasSpooled = quantumSpool >= 1;
		quantumSpool = Math.min(1.0, quantumSpool + rate);
		if (!wasSpooled && quantumSpool >= 1 && level() instanceof ServerLevel serverLevel) {
			serverLevel.playSound(null, BlockPos.containing(position()), net.minecraft.sounds.SoundEvents.BEACON_POWER_SELECT,
					net.minecraft.sounds.SoundSource.NEUTRAL, 1.0f, 1.4f);
		}
		entityData.set(QUANTUM, (float) quantumSpool);

		int state = QT_MODE | (quantumSpool >= 1 ? QT_SPOOLED : 0);
		var target = quantumTarget == null ? null : com.starwake.galaxy.SolarSystem.get(access, quantumTarget).orElse(null);
		if (target == null) {
			state |= QT_NO_TARGET;
			entityData.set(QT_TARGET, "");
			entityData.set(QT_INFO, 0);
		} else {
			SpaceTravel.Route route = SpaceTravel.route(this, target);
			Vec3 dir = route.arrival().subtract(position()).normalize();
			if (Math.acos(Mth.clamp(nose.dot(dir), -1, 1)) < Math.toRadians(physics.quantumAlignDegrees())) state |= QT_ALIGNED;
			if (route.obstruction() != null) state |= QT_OBSTRUCTED;
			if (route.tooClose()) state |= QT_TOO_CLOSE;
			if (fusion() < route.fuel()) state |= QT_NO_FUEL;
			entityData.set(QT_TARGET, target.key().identifier().toString());
			entityData.set(QT_INFO, route.fuel());
		}
		entityData.set(QT_STATE, state);
	}

	/** Pilot held B: jump if everything checks out, else say what's missing. */
	public void engageQuantum() {
		if (jump != null || !quantumMode || !(pilot() instanceof ServerPlayer player)) return;
		tickQuantum();
		int state = quantumState();
		String problem = (state & QT_NO_TARGET) != 0 ? "no_target"
				: (state & QT_SPOOLED) == 0 ? "not_spooled"
				: (state & QT_TOO_CLOSE) != 0 ? "too_close"
				: (state & QT_OBSTRUCTED) != 0 ? "obstructed"
				: (state & QT_NO_FUEL) != 0 ? "no_fuel"
				: (state & QT_ALIGNED) == 0 ? "not_aligned" : null;
		if (problem != null) {
			player.sendOverlayMessage(Component.translatable("message.starwake.quantum." + problem));
			return;
		}
		var target = com.starwake.galaxy.SolarSystem.get(level().registryAccess(), quantumTarget).orElseThrow();
		SpaceTravel.Route route = SpaceTravel.route(this, target);
		useFusion(route.fuel());
		jump = new QuantumJump(target.key(), position(), route.arrival(), 0);
		quantumMode = false;
		entityData.set(QUANTUM, -1f);
		entityData.set(QT_STATE, QT_TRAVEL);
		entityData.set(QT_INFO, (int) Math.ceil(route.ticks()));
		setDeltaMovement(Vec3.ZERO);
		if (level() instanceof ServerLevel serverLevel) {
			serverLevel.playSound(null, BlockPos.containing(position()), com.starwake.registry.ModSounds.QUANTUM_ENGAGE,
					net.minecraft.sounds.SoundSource.NEUTRAL, 2.0f, 0.5f);
		}
		player.sendOverlayMessage(Component.translatable("message.starwake.quantum.engaged", target.displayName(),
				com.starwake.galaxy.SolarSystem.formatTime(route.ticks())));
	}

	/** Advances a jump in progress; at the end, moves the ship to the drop-out point. */
	private void tickJump(ShipPhysics physics) {
		QuantumJump j = jump;
		jump = new QuantumJump(j.target(), j.from(), j.to(), j.elapsed() + 1);
		double distance = j.distance();
		double total = com.starwake.galaxy.SolarSystem.travelTicks(distance, physics.quantumSpeed(), physics.quantumRamp());
		double travelled = com.starwake.galaxy.SolarSystem.travelled(distance, physics.quantumSpeed(), physics.quantumRamp(), jump.elapsed());
		Vec3 offset = j.to().subtract(j.from()).normalize().scale(travelled).add(j.from().subtract(position()));
		entityData.set(QT_OFFSET, new Vector3f((float) offset.x, (float) offset.y, (float) offset.z));
		entityData.set(QT_INFO, (int) Math.ceil(total - jump.elapsed()));
		if (level() instanceof ServerLevel serverLevel && (int) Math.ceil(total) - jump.elapsed() == 100) {
			// 5 s out: start loading the drop-out point so the ship arrives into ready chunks.
			serverLevel.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PORTAL,
					net.minecraft.world.level.ChunkPos.containing(BlockPos.containing(j.to())), 3);
		}
		if (jump.elapsed() < total) return;

		// Drop out.
		jump = null;
		entityData.set(QT_STATE, 0);
		entityData.set(QT_TARGET, "");
		entityData.set(QT_OFFSET, new Vector3f());
		Vec3 arrival = j.to();
		com.starwake.galaxy.SolarSystem.get(level().registryAccess(), j.target()).ifPresent(t -> setOrbiting(t.dimension()));
		SpaceTravel.jump(this, arrival);
		setDeltaMovement(arrival.subtract(j.from()).normalize().scale(0.6)); // a little momentum out of the jump
		if (level() instanceof ServerLevel serverLevel) {
			serverLevel.playSound(null, BlockPos.containing(position()), com.starwake.registry.ModSounds.QUANTUM_EXIT,
					net.minecraft.sounds.SoundSource.NEUTRAL, 2.0f, 0.7f);
		}
		if (pilot() instanceof ServerPlayer player) {
			player.sendOverlayMessage(Component.translatable("message.starwake.quantum.arrived",
					SpaceTravel.planetName(level(), orbiting)));
		}
	}

	// =========================================================================================
	// Jump drive (M6): between star systems
	// =========================================================================================
	//
	// 1. Pick a star on the galaxy map ({@link #setJumpTarget}); J arms the drive (jump mode).
	// 2. The drive spools on the quantum share of power. The HUD shows the target star (it is
	//    really in the sky, in its true direction) and what's missing: range, fuel, distance
	//    from gravity wells, alignment.
	// 3. Hold J to engage: fuel is burned, the hull holds still in the tunnel for a time that
	//    grows with distance, then the ship and everyone aboard arrive in the target system's
	//    space, on the side facing where they came from, pointed at the star.

	/** Selects the jump target (from the galaxy map; pilot only). Returns why not, or null. */
	public @Nullable Component setJumpTarget(String system) {
		if (!systems.hasJumpDrive()) return Component.translatable("message.starwake.jump.no_drive");
		if (com.starwake.galaxy.Galaxy.star(system).isEmpty()) return Component.translatable("message.starwake.jump.unknown");
		if (system.equals(com.starwake.galaxy.SolarSystem.systemOf(level()))) return Component.translatable("message.starwake.jump.here");
		jumpTarget = system;
		if (!jumpMode) toggleJumpMode();
		return null;
	}

	/** Pilot tapped J: arm or disarm the jump drive. */
	public void toggleJumpMode() {
		if (jumpTicks >= 0) return;
		Player pilot = pilot();
		if (jumpMode) {
			jumpMode = false;
			jumpSpool = 0;
			entityData.set(JD_STATE, 0);
			entityData.set(JD_SPOOL, -1f);
			if (pilot instanceof ServerPlayer p) p.sendOverlayMessage(Component.translatable("message.starwake.jump.off"));
			return;
		}
		Component error = !SpaceTravel.isSpace(level()) ? Component.translatable("message.starwake.course.not_in_space")
				: !systems.hasJumpDrive() ? Component.translatable("message.starwake.jump.no_drive") : null;
		if (error == null && quantumMode) exitQuantumMode(null);
		if (error == null) {
			jumpMode = true;
			jumpSpool = 0;
		}
		if (pilot instanceof ServerPlayer p) p.sendOverlayMessage(error != null ? error : Component.translatable("message.starwake.jump.on"));
	}

	/** Spools and evaluates the jump every tick while armed. */
	private void tickJumpMode() {
		if (!jumpMode || jumpTicks >= 0) return;
		if (!SpaceTravel.isSpace(level()) || !systems.hasJumpDrive()) {
			jumpMode = false;
			entityData.set(JD_STATE, 0);
			entityData.set(JD_SPOOL, -1f);
			return;
		}
		var tuning = com.starwake.ship.data.JumpTuning.get();
		double rate = systems.quantumFactor(allocation) / Math.max(1.0, systems.jumpSpool() * 20.0);
		boolean wasSpooled = jumpSpool >= 1;
		jumpSpool = Math.min(1.0, jumpSpool + rate);
		if (!wasSpooled && jumpSpool >= 1 && level() instanceof ServerLevel serverLevel) {
			serverLevel.playSound(null, BlockPos.containing(position()), com.starwake.registry.ModSounds.QUANTUM_SPOOL,
					net.minecraft.sounds.SoundSource.NEUTRAL, 1.5f, 0.7f);
		}
		entityData.set(JD_SPOOL, (float) jumpSpool);
		int state = JD_MODE | (jumpSpool >= 1 ? JD_SPOOLED : 0);
		var here = com.starwake.galaxy.Galaxy.star(com.starwake.galaxy.SolarSystem.systemOf(level())).orElse(null);
		var there = jumpTarget == null ? null : com.starwake.galaxy.Galaxy.star(jumpTarget).orElse(null);
		if (here == null || there == null) {
			state |= JD_NO_TARGET;
			entityData.set(JD_TARGET, "");
			entityData.set(JD_INFO, 0);
		} else {
			double ly = here.distanceTo(there);
			Vec3 dir = there.position().subtract(here.position()).normalize();
			Vec3 nose = worldDirection(forward());
			if (Math.acos(Mth.clamp(nose.dot(dir), -1, 1)) < Math.toRadians(tuning.alignDegrees())) state |= JD_ALIGNED;
			if (ly > systems.jumpRange()) state |= JD_RANGE;
			int fuel = tuning.fuel(ly);
			if (fusion() < fuel) state |= JD_NO_FUEL;
			for (var body : com.starwake.galaxy.SolarSystem.bodies(level())) {
				double clearance = body.body().star() ? body.body().radius() * tuning.starWell() : body.body().entryRadius() * tuning.planetWell();
				if (position().distanceTo(body.body().position()) < clearance) state |= JD_WELL;
			}
			entityData.set(JD_TARGET, there.id());
			entityData.set(JD_INFO, fuel);
		}
		entityData.set(JD_STATE, state);
	}

	/** Pilot held J: jump if everything is ready, else say what's missing. */
	public void engageJump() {
		if (!jumpMode || jumpTicks >= 0 || !(pilot() instanceof ServerPlayer player)) return;
		int state = jumpState();
		String problem = (state & JD_NO_TARGET) != 0 ? "no_target"
				: (state & JD_RANGE) != 0 ? "out_of_range"
				: (state & JD_SPOOLED) == 0 ? "not_spooled"
				: (state & JD_WELL) != 0 ? "gravity_well"
				: (state & JD_NO_FUEL) != 0 ? "no_fuel"
				: (state & JD_ALIGNED) == 0 ? "not_aligned" : null;
		if (problem != null) {
			player.sendOverlayMessage(Component.translatable("message.starwake.jump." + problem));
			return;
		}
		var here = com.starwake.galaxy.Galaxy.star(com.starwake.galaxy.SolarSystem.systemOf(level())).orElseThrow();
		var there = com.starwake.galaxy.Galaxy.star(jumpTarget).orElseThrow();
		double ly = here.distanceTo(there);
		var tuning = com.starwake.ship.data.JumpTuning.get();
		useFusion(tuning.fuel(ly));
		jumpDirection = there.position().subtract(here.position()).normalize();
		jumpTotal = tuning.ticks(ly);
		jumpTicks = jumpTotal;
		jumpMode = false;
		entityData.set(JD_SPOOL, -1f);
		entityData.set(JD_STATE, JD_TRAVEL);
		entityData.set(JD_INFO, jumpTicks);
		if (level() instanceof ServerLevel serverLevel) {
			serverLevel.playSound(null, BlockPos.containing(position()), com.starwake.registry.ModSounds.QUANTUM_ENGAGE,
					net.minecraft.sounds.SoundSource.NEUTRAL, 2.5f, 0.35f);
		}
		player.sendOverlayMessage(Component.translatable("message.starwake.jump.engaged", there.name(),
				String.format("%.1f", ly), com.starwake.galaxy.SolarSystem.formatTime(jumpTotal)));
	}

	/** One tick in the jump tunnel; at the end, arrive in the target system. True if transferred. */
	private boolean tickJumpTravel() {
		jumpTicks--;
		entityData.set(JD_INFO, Math.max(0, jumpTicks));
		if (!(level() instanceof ServerLevel serverLevel)) return false;
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
		entityData.set(JD_STATE, 0);
		entityData.set(JD_TARGET, "");
		if (space == null) return false;
		Vec3 arrival = jumpArrival(server);
		String name = com.starwake.galaxy.Galaxy.star(jumpTarget).map(com.starwake.galaxy.Galaxy.Star::name).orElse(jumpTarget);
		Player pilot = pilot();
		jumpTarget = null;
		boolean moved = SpaceTravel.transfer(this, space, arrival, new org.joml.Quaternionf());
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

	// =========================================================================================
	// Flight computer (3h): physicalized thrust, Star Citizen IFCS style
	// =========================================================================================
	//
	// The pilot asks for motion (WASD/Space/Ctrl, hover) and rotation (mouse-aim, Z/C roll).
	// That becomes a wanted force and torque in the ship's frame; ShipDynamics.allocate picks
	// a throttle for every thruster; the force and torque they actually produce, from where
	// they actually are, accelerate the ship. Poorly placed thrusters mean less thrust, slow
	// or missing rotation axes, or drift: the design decides how the ship flies.

	/** Pilot input -> thruster throttles -> new velocity (angular velocity is updated too). */
	private Vec3 fly(Player pilot, Vec3 velocity, ShipPhysics physics, Input input) {
		ShipDynamics dynamics = dynamics();
		double mass = mass();
		boolean decoupled = isDecoupled();
		boolean space = SpaceTravel.isSpace(level());
		// Ships are weightless in space (space's 0.15g is only for people on EVA near the hull).
		double gravity = space ? 0 : Planets.gravityOf(level());
		// Power routing and boost scale every thruster; no fuel, no thrust.
		double scale = fuel() > 0 ? engineFactor() * (input.shift() ? physics.boostMultiplier() : 1.0) : 0;
		Quaternionfc q = orientation();

		// Wanted push: "as much as you've got" along each commanded axis.
		Vector3d force = new Vector3d();
		Direction left = forward.getCounterClockWise();
		ask(dynamics, force, forward, input.forward(), scale);
		ask(dynamics, force, forward.getOpposite(), input.backward(), scale);
		ask(dynamics, force, left, input.left(), scale);
		ask(dynamics, force, left.getOpposite(), input.right(), scale);
		ask(dynamics, force, Direction.UP, input.jump(), scale);
		// Ctrl: coupled = controlled descent (below); decoupled = cut lift and push down.
		ask(dynamics, force, Direction.DOWN, input.sprint() && (decoupled || gravity <= 0), scale);
		// Coupled mode (Star Citizen's IFCS): on any axis the pilot isn't commanding, the flight
		// computer fires the opposite thrusters to null the drift. No retros, no braking.
		double perVelocity = mass / physics.thrustToAccel() * COUPLED_BRAKING; // force per (block/tick)
		Vector3f v = q.transformInverse(new Vector3f((float) velocity.x, (float) velocity.y, (float) velocity.z));
		Direction right = forward.getClockWise();
		Vector3d fwdAxis = new Vector3d(forward.getStepX(), 0, forward.getStepZ());
		Vector3d rightAxis = new Vector3d(right.getStepX(), 0, right.getStepZ());
		Vector3d local = new Vector3d(v.x, v.y, v.z);
		boolean hovering = !input.jump() && !(input.sprint() && decoupled) && gravity > 0;
		// Coupled descent (Ctrl): hold a gentle sink rate the landing gear can take.
		double targetClimb = input.sprint() && !decoupled ? -descentRate() : 0;
		boosting = input.shift();
		if (!decoupled) {
			if (!input.forward() && !input.backward()) force.fma(-local.dot(fwdAxis) * perVelocity, fwdAxis);
			if (!input.left() && !input.right()) force.fma(-local.dot(rightAxis) * perVelocity, rightAxis);
			if (!input.sprint() && !input.jump() && !hovering) force.add(0, -local.y * perVelocity, 0);
		}

		// Hover assist: hold the ship's weight, whatever its attitude (top priority), and null
		// vertical drift so it holds altitude.
		Vector3f upLocal = q.transformInverse(new Vector3f(0, 1, 0));
		// (Decoupled: gravity is still cancelled, but vertical drift is the pilot's problem.)
		double weight = hovering
				? Math.max(0, mass * gravity * physics.gravityToAccel() / physics.thrustToAccel()
						- (decoupled ? 0 : (velocity.y - targetClimb) * perVelocity)) : 0;

		// Wanted torque: steer the angular velocity toward what the pilot asks for.
		// Landed (M9): the mouse doesn't twist the ship on its gear; steering starts once it lifts off.
		Vector3d wanted = grounded && !input.jump() ? new Vector3d() : wantedRotation(pilot, physics, space);
		Vector3d alpha = wanted.sub(angularVelocity).mul(0.35);
		Vector3d torque = dynamics.inertia().transform(alpha, new Vector3d()).div(physics.thrustToAccel());

		// Wings (3i): their lift carries part of the weight, so thrusters only make up the rest,
		// and the flight computer counters their pitching/yawing moments where it can.
		double density = space ? 0 : Planets.airDensityOf(level());
		double[] air = dynamics.aero(local, angularVelocity, new Vector3d(fwdAxis), density, physics.wingLift(), physics.wingDrag());
		double airLift = air[0] * upLocal.x + air[1] * upLocal.y + air[2] * upLocal.z;
		double fullWeight = mass * gravity * physics.gravityToAccel() / physics.thrustToAccel();
		if (!level().isClientSide()) entityData.set(WING_LIFT, fullWeight > 0 ? (float) Mth.clamp(airLift / fullWeight, 0, 9) : 0f);
		if (hovering) weight -= airLift; // may go negative: then hold the ship down
		torque.sub(air[3], air[4], air[5]);

		double[] th = level().isClientSide() || throttles.length != dynamics.thrusterCount() ? new double[dynamics.thrusterCount()] : throttles;
		double[] achieved = dynamics.allocate(new Vector3d(upLocal.x, upLocal.y, upLocal.z), hovering, weight, torque, force, scale, th);
		if (scale > 0 && !level().isClientSide()) burnFuel(dynamics.burn(th) * scale);

		for (int j = 0; j < 6; j++) achieved[j] += air[j]; // thrust + aerodynamics
		Vector3f f = q.transform(new Vector3f((float) achieved[0], (float) achieved[1], (float) achieved[2]));
		Vec3 accel = new Vec3(f.x, f.y, f.z).scale(physics.thrustToAccel() / mass).add(0, -gravity * physics.gravityToAccel(), 0);
		Vector3d spin = dynamics.inverseInertia().transform(new Vector3d(achieved[3], achieved[4], achieved[5]))
				.mul(physics.thrustToAccel());
		angularVelocity.add(spin).mul(space ? 0.998 : 0.985); // slight passive damping; the flight computer does the braking
		double cap = Math.toRadians(physics.turnRateDegrees()) / 20.0 * 1.25;
		if (angularVelocity.length() > cap) angularVelocity.normalize(cap);
		return velocity.add(accel).scale(space ? physics.dragSpace() : physics.dragAtmosphere());
	}

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
	private @Nullable Vec3 pilotLook;
	private int lastInputSeq = -1, lastInputTick = -100;

	public void setServerDynamics(ShipDynamics dynamics) {
		this.serverDynamics = dynamics;
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
		PilotHook hook = pilotHook;
		boolean local = hook != null && structure != null && serverDynamics != null && hook.isLocalPilot(this)
				&& jumpTicks < 0 && entityData.get(QT_STATE) != QT_TRAVEL;
		if (!local) {
			if (predicting) {
				predicting = false;
				history.clear();
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
				(float) look.x, (float) look.y, (float) look.z));
		ShipPhysics physics = ShipPhysics.get();
		measureClearance();
		Vec3 velocity = fly(pilot, getDeltaMovement(), physics, input);
		double maxSpeed = (SpaceTravel.isSpace(level()) ? physics.maxSpeedSpace() : physics.maxSpeed()) * (boosting ? BOOST_SPEED : 1.0);
		if (velocity.lengthSqr() > maxSpeed * maxSpeed) velocity = velocity.normalize().scale(maxSpeed);
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

	/** The server's velocity packets don't overwrite a predicted ship's velocity either. */
	@Override
	public void lerpMotion(Vec3 movement) {
		if (!predicting) super.lerpMotion(movement);
	}

	/** Server packets don't move a ship the client is predicting (it reconciles instead). */
	@Override
	protected net.minecraft.world.entity.InterpolationHandler createInterpolationHandler() {
		return new net.minecraft.world.entity.InterpolationHandler() {
			@Override
			public net.minecraft.core.@Nullable PositionAndRotation target() {
				return null;
			}

			@Override
			public boolean interpolateTo(net.minecraft.world.entity.@Nullable PositionPath position, float yRot, float xRot, boolean hasRotation) {
				return predicting;
			}

			@Override
			public void interpolate() {
			}

			@Override
			public void applyPredictedMovement(Vec3 delta) {
			}

			@Override
			public boolean hasActiveInterpolation() {
				return false;
			}

			@Override
			public void cancel() {
			}
		};
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
		return !locked || canPilot(player);
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
		BlockState state = structure.get(gear.get(0));
		return state != null && state.hasProperty(com.starwake.ship.block.LandingGearBlock.DEPLOYED)
				&& state.getValue(com.starwake.ship.block.LandingGearBlock.DEPLOYED);
	}

	/** Pilot pressed N: deploy or retract every landing gear block. */
	public void toggleGear() {
		if (structure == null || gear.isEmpty() || !(level() instanceof ServerLevel serverLevel)) return;
		boolean deploy = !isGearDown();
		ShipStructure before = structure;
		ShipStructure next = structure;
		for (BlockPos pos : gear) {
			BlockState state = next.get(pos);
			if (state != null) next = next.with(pos, state.setValue(com.starwake.ship.block.LandingGearBlock.DEPLOYED, deploy), next.blockEntity(pos));
		}
		setStructure(next);
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
				setStructure(before);
				if (pilot() instanceof ServerPlayer player) player.sendOverlayMessage(Component.translatable("message.starwake.gear.blocked"));
				return;
			}
		}
		for (BlockPos pos : gear) {
			BlockState state = structure.get(pos);
			if (state != null) com.starwake.ship.yard.Shipyard.set(this, pos, state); // the plot follows (M9)
		}
		var payload = new com.starwake.network.ShipStructurePayload(getId(), structure);
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

	/** Fraction of the ship's weight its wings are carrying right now (synced, for the HUD). */
	public float wingLift() {
		return entityData.get(WING_LIFT);
	}

	/** Pilot pressed V: coupled <-> decoupled. */
	public void toggleDecoupled() {
		decoupled = !decoupled;
		entityData.set(DECOUPLED, decoupled);
		if (pilot() instanceof ServerPlayer player) {
			player.sendOverlayMessage(Component.translatable(decoupled ? "message.starwake.decoupled" : "message.starwake.coupled"));
		}
	}

	/** Coupled descent (M9): up to 25 m/s high up, easing to 3 m/s in the last 15 m (blocks/tick). */
	private static final double DESCENT_FAST = 1.25, DESCENT_SLOW = 0.15;

	/** Whether the pilot is holding boost this tick (raises the speed cap). */
	private boolean boosting;

	/** Height of the hull's bottom above the ground below the ship's centre (blocks; 64 = nothing in range). */
	private double clearance = 64;

	private double descentRate() {
		if (clearance <= 15) return DESCENT_SLOW;
		double t = Math.min(1, (clearance - 15) / 45.0);
		return DESCENT_SLOW + (DESCENT_FAST - DESCENT_SLOW) * t;
	}

	/** Measures {@link #clearance} straight down from the bottom of the bounding box (cheap: one column). */
	private void measureClearance() {
		AABB box = getBoundingBox();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		int x = Mth.floor(box.getCenter().x), z = Mth.floor(box.getCenter().z);
		int top = Mth.floor(box.minY);
		for (int dy = 0; dy < 64; dy++) {
			p.set(x, top - dy, z);
			if (!level().getBlockState(p).getCollisionShape(level(), p).isEmpty()) {
				clearance = Math.max(0, box.minY - (top - dy + 1));
				return;
			}
		}
		clearance = 64;
	}

	/** Fraction of uncommanded velocity the flight computer tries to cancel each tick. */
	private static final double COUPLED_BRAKING = 0.12;

	/** Speed cap multiplier while boosting. */
	private static final double BOOST_SPEED = 1.25;

	/** Seconds of idle hover kept in reserve: below it an unpiloted ship lands itself if the ground is near. */
	private static final int HOVER_RESERVE_TICKS = 1200;

	/**
	 * Unpiloted ships (M9): in space they brake to a stop and hold. On a planet they stay put if resting on
	 * something; otherwise they hover at an idle burn (a tenth of what holding their weight on thrusters
	 * costs); with the tank nearly dry they let themselves down gently if the ground is within 64 blocks,
	 * and with it empty they fall.
	 */
	private Vec3 unpiloted(Vec3 velocity, ShipPhysics physics) {
		double gravity = SpaceTravel.isSpace(level()) ? 0 : Planets.gravityOf(level().dimension());
		if (gravity <= 0) {
			velocity = velocity.scale(0.8);
			return velocity.lengthSqr() < 1.0e-6 ? Vec3.ZERO : velocity;
		}
		if (grounded) {
			// Parked on something: gravity and the contacts keep it there (it settles and tips like a real object).
			return velocity.add(0, -gravity * physics.gravityToAccel(), 0).scale(0.9);
		}
		double weight = mass * gravity * physics.gravityToAccel() / physics.thrustToAccel();
		double lift = dynamics.rawThrust(Direction.UP);
		double idle = lift <= 0 ? 0 : dynamics.burnPushing(Direction.UP) * Math.min(1, weight / lift) * 0.1;
		if (fuel() > 0 && lift > 0) {
			burnFuel(idle);
			double sink = fuel() < idle * HOVER_RESERVE_TICKS && clearance < 64 ? -descentRate() : 0;
			Vec3 held = velocity.scale(0.8);
			return new Vec3(held.x, held.y * 0.5 + sink * 0.5, held.z);
		}
		// Out of fuel: it falls.
		return velocity.add(0, -gravity * physics.gravityToAccel(), 0).scale(physics.dragAtmosphere());
	}

	/** Adds an "everything along this local direction" request when the key is held. */
	private static void ask(ShipDynamics dynamics, Vector3d force, Direction direction, boolean held, double scale) {
		if (!held) return;
		double amount = dynamics.rawThrust(direction) * scale;
		force.add(direction.getStepX() * amount, direction.getStepY() * amount, direction.getStepZ() * amount);
	}

	/**
	 * Mouse-aim: the angular velocity (ship frame, rad/tick) that turns the nose toward where the
	 * pilot looks, eased near the target and capped at {@code turn_rate}; Z/C roll; in an
	 * atmosphere the wings ease level when not rolling. Free-look holds attitude.
	 */
	private Vector3d wantedRotation(Player pilot, ShipPhysics physics, boolean space) {
		double max = Math.toRadians(physics.turnRateDegrees()) / 20.0;
		boolean rolling = (controls & (CONTROL_ROLL_LEFT | CONTROL_ROLL_RIGHT)) != 0;
		boolean freeLook = (controls & CONTROL_FREE_LOOK) != 0;
		Quaternionfc q = orientation();
		Vector3d nose = new Vector3d(forward.getStepX(), forward.getStepY(), forward.getStepZ());
		Vector3d want = new Vector3d();
		boolean joystick = (controls & CONTROL_JOYSTICK) != 0;
		if (joystick) {
			// Joystick mode: the stick sets yaw/pitch rates directly (yaw about the deck's up, pitch about its right).
			Direction rightDir = forward.getClockWise();
			want.add(0, -stickX * max, 0);
			want.add(new Vector3d(rightDir.getStepX(), 0, rightDir.getStepZ()).mul(-stickY * max));
		} else if (!freeLook) {
			Vec3 lookWorld = !level().isClientSide() && pilotLook != null && tickCount - lastInputTick < 10 ? pilotLook : pilot.getViewVector(1.0f);
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
			want.add(new Vector3d(nose).mul((controls & CONTROL_ROLL_RIGHT) != 0 ? max : -max));
		} else if (!space) {
			// Atmospheric auto-level: ease the wings level unless the pilot is rolling on purpose.
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
	private void burnFuel(double amount) {
		fuelDebt += amount;
		int whole = (int) fuelDebt;
		if (whole > 0) {
			fuelDebt -= whole;
			entityData.set(FUEL, Math.max(0, fuel() - whole));
		}
	}

	// --- Terrain collision -------------------------------------------------------------------

	/** Ticks before another impact can register: a crash counts once, not once per tick of scraping. */
	private int impactCooldown;

	/** Touchdown limits (blocks/tick): ~6 m/s on deployed gear, ~1.6 m/s on the belly. */
	private static final double SAFE_GEAR_LANDING = 0.3, SAFE_BELLY_LANDING = 0.08;

	/** Impact speed (blocks/tick) a ship absorbs without consequence: a firm landing. */
	private static final double SAFE_IMPACT = 0.35;

	/**
	 * A crash: shields soak the impact energy first; anything left hurts everyone aboard
	 * (crew in seats and people walking inside).
	 */
	private void onImpact(double impact, double safe, Vec3 blocked) {
		if (!(level() instanceof ServerLevel serverLevel)) return;
		impactCooldown = 10;
		// 1 b/t over the limit = 40 shield HP; scraping in on the belly is five times worse.
		double energy = (impact - safe) * (safe == SAFE_BELLY_LANDING ? 200.0 : 40.0); // 1 b/t over the limit = 40 shield HP
		float shield = entityData.get(SHIELD);
		double absorbed = Math.min(shield, energy);
		entityData.set(SHIELD, (float) (shield - absorbed));
		double excess = energy - absorbed;
		serverLevel.playSound(null, BlockPos.containing(position()), net.minecraft.sounds.SoundEvents.ANVIL_LAND,
				net.minecraft.sounds.SoundSource.BLOCKS, (float) Math.min(1.5, impact), absorbed > 0 ? 1.6f : 0.6f);
		if (excess <= 0) return;
		excess -= hullImpact(excess, blocked);
		if (excess <= 1e-6) return;
		float damage = (float) Math.min(30.0, excess / 4.0);
		AABB inside = getBoundingBox().inflate(1.0);
		for (Entity entity : serverLevel.getEntities(this, inside, e -> e instanceof LivingEntity)) {
			if (entity.getVehicle() == this || isSupporting(entity)) {
				entity.hurtServer(serverLevel, serverLevel.damageSources().flyIntoWall(), damage);
			}
		}
	}

	/**
	 * Impact armour (M8e-1): the blocks on the face that hit share the energy left after shields. Each
	 * soaks up to its armour (alloy toughness or ship_stats {@code armor}); a block whose share is more
	 * than its armour breaks off (dropped). The hull soaks at most {@code crumple} of the energy; the
	 * rest reaches the crew. Returns the energy absorbed.
	 */
	private double hullImpact(double energy, Vec3 blocked) {
		if (structure == null || blocked.lengthSqr() < 1e-9) return 0;
		Direction face = localDirection(blocked);
		List<BlockPos> skin = structure.outerFace(face);
		if (skin.isEmpty()) return 0;
		// Only the layer that sticks out furthest toward the impact touched it.
		int extreme = Integer.MIN_VALUE;
		for (BlockPos p : skin) extreme = Math.max(extreme, p.get(face.getAxis()) * face.getAxisDirection().getStep());
		List<BlockPos> contact = new ArrayList<>();
		for (BlockPos p : skin) if (p.get(face.getAxis()) * face.getAxisDirection().getStep() >= extreme) contact.add(p);
		double share = energy / contact.size();
		double absorbed = 0;
		java.util.Set<BlockPos> broken = new java.util.HashSet<>();
		for (BlockPos p : contact) {
			ShipMaterials.Rating r = ratings.get(p);
			double armor = r == null ? ShipStats.get(structure.get(p)).armor() : r.armor();
			absorbed += Math.min(share, armor);
			if (share > armor && !(structure.get(p).getBlock() instanceof HelmBlock)) broken.add(p);
		}
		absorbed = Math.min(absorbed, energy * HullTuning.get().crumple());
		com.starwake.StarwakeMod.LOGGER.info("Ship {}: impact energy {} on {} contact blocks ({} face): hull soaked {}, tore off {}", getId(),
				Math.round(energy), contact.size(), face, Math.round(absorbed), broken.size());
		if (!broken.isEmpty()) {
			messageAboard(Component.translatable("message.starwake.hull.smashed", broken.size()));
			com.starwake.ship.edit.ShipEditing.destroy(this, broken, true);
		}
		return absorbed;
	}

	private double clip(Direction.Axis axis, double distance) {
		if (distance == 0 || structure == null) return distance;
		boolean aligned = isAxisAligned();
		for (AABB local : hull) {
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
		for (AABB local : hull) {
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
		for (AABB local : hull) {
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
		return seats;
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
		allocation = input.read("power", PowerAllocation.CODEC).orElse(PowerAllocation.DEFAULT);
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
			jumpTarget = target;
			jumpTicks = input.getIntOr("jump_ticks", 1);
			jumpTotal = input.getIntOr("jump_total", jumpTicks);
			jumpDirection = input.read("jump_direction", Vec3.CODEC).orElse(new Vec3(1, 0, 0));
			entityData.set(JD_STATE, JD_TRAVEL);
			entityData.set(JD_TARGET, target);
		});
		// A jump in progress resumes where it was (target, start, drop-out point, ticks so far).
		input.read("quantum_target", ResourceKey.codec(com.starwake.planet.Planets.REGISTRY_KEY)).ifPresent(target -> {
			var from = input.read("quantum_from", Vec3.CODEC);
			var to = input.read("quantum_to", Vec3.CODEC);
			if (from.isPresent() && to.isPresent()) {
				jump = new QuantumJump(target, from.get(), to.get(), input.getIntOr("quantum_elapsed", 0));
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
		output.store("power", PowerAllocation.CODEC, allocation);
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
		if (jumpTicks >= 0 && jumpTarget != null) {
			output.putString("jump_target", jumpTarget);
			output.putInt("jump_ticks", jumpTicks);
			output.putInt("jump_total", jumpTotal);
			output.store("jump_direction", Vec3.CODEC, jumpDirection);
		}
		if (jump != null) {
			output.store("quantum_target", ResourceKey.codec(com.starwake.planet.Planets.REGISTRY_KEY), jump.target());
			output.store("quantum_from", Vec3.CODEC, jump.from());
			output.store("quantum_to", Vec3.CODEC, jump.to());
			output.putInt("quantum_elapsed", jump.elapsed());
		}
		if (structure != null) {
			output.store("structure", ShipStructure.CODEC, structure);
		}
		output.putInt("plot", plot);
	}
}
