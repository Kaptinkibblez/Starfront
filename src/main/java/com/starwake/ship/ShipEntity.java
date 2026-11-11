package com.starwake.ship;

import com.starwake.planet.Planets;
import com.starwake.ship.block.HelmBlock;
import com.starwake.ship.block.SeatBlock;
import com.starwake.ship.block.ThrusterBlock;
import com.starwake.ship.data.ShipPhysics;
import com.starwake.ship.data.ShipStats;
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
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
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

	/** Control bits from {@link com.starwake.network.ShipControlPayload}. */
	public static final int CONTROL_ROLL_LEFT = 1, CONTROL_ROLL_RIGHT = 2, CONTROL_FREE_LOOK = 4;

	/** Save-format version: 2 = position is the pivot (1/absent = position was the min corner). */
	private static final int FRAME_VERSION = 2;

	private @Nullable ShipStructure structure;
	private BlockPos helmLocal = BlockPos.ZERO;
	/** The ship's local "forward" (the Helm's facing at assembly). */
	private Direction forward = Direction.NORTH;
	private Vec3 pivot = Vec3.ZERO;
	private int fuelCapacity;
	private @Nullable ResourceKey<Level> orbiting;
	private boolean sealed;

	// Derived from the structure (recomputed on load; never saved). Keyed by LOCAL direction.
	private double mass = 1;
	private final Map<Direction, Double> thrust = new EnumMap<>(Direction.class);
	private final Map<Direction, Double> burn = new EnumMap<>(Direction.class);
	private final List<BlockPos> seats = new ArrayList<>();
	/** Blocks with any exposed face: the hull used for terrain collision. */
	private final List<BlockPos> hull = new ArrayList<>();

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
		setStructure(structure);
	}

	/** Sets the blocks (server on assembly/load; client when the structure payload arrives). */
	public void setStructure(ShipStructure structure) {
		this.structure = structure;
		this.pivot = new Vec3(structure.size().getX() / 2.0, structure.size().getY() / 2.0, structure.size().getZ() / 2.0);
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
		for (Direction d : Direction.values()) {
			thrust.put(d, 0.0);
			burn.put(d, 0.0);
		}
		if (structure == null) return;
		for (ShipStructure.Entry entry : structure.entries()) {
			BlockState state = entry.state();
			ShipStats.BlockStats stats = ShipStats.get(state);
			mass += stats.mass();
			if (state.getBlock() instanceof ThrusterBlock) {
				Direction nozzle = state.getValue(ThrusterBlock.FACING);
				if (structure.get(entry.pos().relative(nozzle)) == null) { // clear exhaust only
					thrust.merge(nozzle.getOpposite(), stats.thrust(), Double::sum);
					burn.merge(nozzle.getOpposite(), stats.fuelBurn(), Double::sum);
				}
			} else if (state.getBlock() instanceof HelmBlock) {
				helmLocal = entry.pos();
				forward = state.getValue(HelmBlock.FACING);
			} else if (state.getBlock() instanceof SeatBlock) {
				seats.add(entry.pos());
			}
			for (Direction d : Direction.values()) {
				if (!structure.hasCollision(entry.pos().relative(d)) && structure.hasCollision(entry.pos())) {
					hull.add(entry.pos());
					break;
				}
			}
		}
		mass = Math.max(mass, 1);
	}

	/**
	 * Same rule as the pre-flight check, on the stored blocks: flood-fill air from the pilot
	 * seat; full-collision blocks, doors and trapdoors seal; reaching the edge means a leak.
	 */
	private boolean computeSealed() {
		if (structure == null) return false;
		BlockPos seat = helmLocal.above();
		Set<BlockPos> visited = new HashSet<>(List.of(seat));
		ArrayDeque<BlockPos> queue = new ArrayDeque<>(visited);
		var size = structure.size();
		while (!queue.isEmpty()) {
			BlockPos pos = queue.poll();
			if (pos.getX() < 0 || pos.getY() < 0 || pos.getZ() < 0 || pos.getX() >= size.getX()
					|| pos.getY() >= size.getY() || pos.getZ() >= size.getZ() || visited.size() > 32768) {
				return false;
			}
			for (Direction direction : Direction.values()) {
				BlockPos next = pos.relative(direction);
				if (visited.contains(next) || seals(next)) continue;
				visited.add(next);
				queue.add(next);
			}
		}
		return true;
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

	/** Containers opened aboard, shared between viewers so two players can't duplicate items. */
	private final Map<BlockPos, net.minecraft.world.SimpleContainer> containers = new HashMap<>();

	/** Server: changes one block's state (e.g. a door) and re-syncs the ship to watchers. */
	public void updateBlock(BlockPos local, BlockState state) {
		if (structure == null) return;
		setStructure(structure.with(local, state, structure.blockEntity(local)));
		var payload = new com.starwake.network.ShipStructurePayload(getId(), structure);
		for (ServerPlayer player : net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(this)) {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
		}
	}

	/**
	 * Server: the inventory of a container block aboard (chest, barrel...), backed by the block
	 * entity data stored in the structure. Changes are written straight back, so they persist
	 * with the ship and survive disassembly.
	 */
	public net.minecraft.world.SimpleContainer container(BlockPos local, int size) {
		return containers.computeIfAbsent(local.immutable(), pos -> {
			var registries = level().registryAccess();
			var items = net.minecraft.core.NonNullList.withSize(size, net.minecraft.world.item.ItemStack.EMPTY);
			structure.blockEntity(pos).ifPresent(tag -> net.minecraft.world.ContainerHelper.loadAllItems(
					net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, registries, tag), items));
			var container = new net.minecraft.world.SimpleContainer(size) {
				@Override
				public void setChanged() {
					super.setChanged();
					saveContainer(pos, this);
				}
			};
			for (int i = 0; i < size; i++) container.setItem(i, items.get(i));
			return container;
		});
	}

	private void saveContainer(BlockPos local, net.minecraft.world.SimpleContainer container) {
		if (structure == null) return;
		var registries = level().registryAccess();
		var items = net.minecraft.core.NonNullList.withSize(container.getContainerSize(), net.minecraft.world.item.ItemStack.EMPTY);
		for (int i = 0; i < items.size(); i++) items.set(i, container.getItem(i));
		var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, registries);
		net.minecraft.world.ContainerHelper.saveAllItems(output, items, true);
		var tag = structure.blockEntity(local).map(net.minecraft.nbt.CompoundTag::copy).orElseGet(net.minecraft.nbt.CompoundTag::new);
		tag.remove("Items");
		tag.merge(output.buildResult());
		// Block-entity data never goes to clients, so no re-sync is needed.
		structure = structure.with(local, structure.get(local), java.util.Optional.of(tag));
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(FUEL, 0);
		builder.define(ORIENTATION, new Quaternionf());
		builder.define(SEATS, "");
	}

	// =========================================================================================
	// Accessors
	// =========================================================================================

	public int fuel() { return entityData.get(FUEL); }
	public int fuelCapacity() { return fuelCapacity; }
	public BlockPos helmLocal() { return helmLocal; }
	public Direction forward() { return forward; }
	public double mass() { return mass; }
	public Vec3 lastMove() { return lastMove; }
	public boolean isSealed() { return sealed; }
	public boolean isGrounded() { return grounded; }
	public @Nullable ResourceKey<Level> orbiting() { return orbiting; }
	public void setOrbiting(@Nullable ResourceKey<Level> orbiting) { this.orbiting = orbiting; }
	public void setDrift(Vec3 drift) { this.drift = drift; }
	/** Admin/testing: constant spin (yaw, pitch, roll in degrees/second about the ship's own axes). Not saved. */
	public void setSpin(Vec3 spin) { this.spin = spin; }
	public void setControls(int controls) { this.controls = controls; }

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
		return entityData.get(ORIENTATION);
	}

	public void setOrientation(Quaternionfc orientation) {
		entityData.set(ORIENTATION, new Quaternionf(orientation).normalize());
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
		var size = structure.size();
		// The world AABB of the rotated local box: transform all 8 corners.
		Quaternionfc rotation = this.entityData == null ? new Quaternionf() : orientation();
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (int i = 0; i < 8; i++) {
			Vec3 corner = toWorld(new Vec3((i & 1) * size.getX(), ((i >> 1) & 1) * size.getY(), ((i >> 2) & 1) * size.getZ()), pos, rotation);
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
		var size = structure.size();
		int minX = Math.max(0, Mth.floor(local.minX)), maxX = Math.min(size.getX() - 1, Mth.floor(local.maxX));
		int minY = Math.max(0, Mth.floor(local.minY)), maxY = Math.min(size.getY() - 1, Mth.floor(local.maxY));
		int minZ = Math.max(0, Mth.floor(local.minZ)), maxZ = Math.min(size.getZ() - 1, Mth.floor(local.maxZ));
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
		var size = structure.size();
		int minX = Math.max(0, Mth.floor(localArea.minX)), maxX = Math.min(size.getX() - 1, Mth.floor(localArea.maxX));
		int minY = Math.max(0, Mth.floor(localArea.minY)), maxY = Math.min(size.getY() - 1, Mth.floor(localArea.maxY));
		int minZ = Math.max(0, Mth.floor(localArea.minZ)), maxZ = Math.min(size.getZ() - 1, Mth.floor(localArea.maxZ));
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
		var size = structure.size();
		return local.x > -margin && local.y > -margin && local.z > -margin
				&& local.x < size.getX() + margin && local.y < size.getY() + margin && local.z < size.getZ() + margin;
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
		if (!level().isClientSide() && structure != null) {
			if (serverTick()) return; // replaced by a copy in another dimension
			if (lastTickPos != null && (movementThisTick().lengthSqr() > 0 || !orientation().equals(lastTickOrientation, 1.0e-7f))) {
				carryEntities();
			}
		}
		// Measure against our own end-of-last-tick state: on the client, updates can be applied
		// between ticks (packet handling), so in-tick snapshots would miss them.
		lastMove = lastTickPos == null ? Vec3.ZERO : position().subtract(lastTickPos);
		lastTickPos = position();
		prevOrientation.set(lastTickOrientation);
		lastTickOrientation.set(orientation());
	}

	/** @return true if the ship was transferred to another dimension (stop ticking it) */
	private boolean serverTick() {
		ShipPhysics physics = ShipPhysics.get();
		Player pilot = pilot();
		Vec3 velocity = getDeltaMovement();

		if (pilot == null && spin.lengthSqr() > 0) {
			Quaternionf next = new Quaternionf(orientation())
					.rotateY((float) Math.toRadians(-spin.x / 20)).rotateX((float) Math.toRadians(spin.y / 20))
					.rotateZ((float) Math.toRadians(spin.z / 20));
			setOrientation(next.normalize());
		}
		if (pilot == null && drift.lengthSqr() > 0) {
			velocity = drift;
		} else if (pilot == null) {
			// Unpiloted ships station-keep: brake to a stop and hold position, free of charge.
			velocity = velocity.scale(0.8);
			if (velocity.lengthSqr() < 1.0e-6) velocity = Vec3.ZERO;
		} else {
			steer(pilot, physics);
			velocity = applyThrust(pilot, velocity, physics);
		}

		if (velocity.lengthSqr() > physics.maxSpeed() * physics.maxSpeed()) {
			velocity = velocity.normalize().scale(physics.maxSpeed());
		}
		setDeltaMovement(velocity);
		if (velocity.lengthSqr() > 0) {
			moveWithCollision(velocity);
			if (level() instanceof ServerLevel serverLevel && SpaceTravel.checkAltitude(this, serverLevel)) {
				return true;
			}
		}

		if (pilot instanceof ServerPlayer player && tickCount % 10 == 0) {
			sendHud(player);
		}
		return false;
	}

	/** The passenger sitting at the Helm, if any. */
	public @Nullable Player pilot() {
		for (Entity passenger : getPassengers()) {
			if (passenger instanceof Player player && helmLocal.equals(seatOf.get(player.getUUID()))) {
				return player;
			}
		}
		return null;
	}

	/**
	 * Mouse-aim steering: rotate the ship's nose toward where the pilot is looking, at most
	 * {@code turnRate} per tick; Z/C roll about the nose. Rotations that would push the hull
	 * into terrain are refused.
	 */
	private void steer(Player pilot, ShipPhysics physics) {
		boolean rolling = (controls & (CONTROL_ROLL_LEFT | CONTROL_ROLL_RIGHT)) != 0;
		boolean freeLook = (controls & CONTROL_FREE_LOOK) != 0;
		if (freeLook && !rolling) return;

		// Agility scales with thrust-to-mass (more engines = nimbler), within 0.5x-2x of base.
		double totalThrust = thrust.values().stream().mapToDouble(Double::doubleValue).sum();
		double agility = Mth.clamp(Math.sqrt(totalThrust / mass / 6.0), 0.5, 2.0);
		float maxStep = (float) Math.toRadians(physics.turnRateDegrees() / 20.0 * agility);

		Quaternionf next = new Quaternionf(orientation());
		Vec3 nose = worldDirection(forward);
		if (!freeLook) {
			Vec3 look = pilot.getViewVector(1.0f);
			double angle = Math.acos(Mth.clamp(nose.dot(look), -1.0, 1.0));
			if (angle > 1.0e-3) {
				Vec3 axis = nose.cross(look);
				if (axis.lengthSqr() < 1.0e-8) axis = worldDirection(Direction.UP); // looking straight back
				float step = (float) Math.min(angle * 0.25, maxStep); // ease in near the target
				next = new Quaternionf().rotateAxis(step, (float) axis.normalize().x, (float) axis.normalize().y,
						(float) axis.normalize().z).mul(next);
			}
		}
		if (rolling) {
			float roll = ((controls & CONTROL_ROLL_RIGHT) != 0 ? 1 : -1) * maxStep;
			next = new Quaternionf().rotateAxis(roll, (float) nose.x, (float) nose.y, (float) nose.z).mul(next);
		} else if (!SpaceTravel.isSpace(level())) {
			// Atmospheric auto-level: mouse-aim turns accumulate roll; ease the wings level unless
			// the pilot is rolling on purpose. (Off in space for true 6DOF.)
			Vector3f up = next.transform(new Vector3f(0, 1, 0));
			Vector3f right = next.transform(new Vector3f(forward.getClockWise().getStepX(), 0, forward.getClockWise().getStepZ()));
			double bank = Math.atan2(-right.y, up.y);
			if (Math.abs(bank) > 1.0e-3 && Math.abs(bank) < Math.PI * 0.9) {
				float correction = (float) Mth.clamp(-bank * 0.1, -maxStep, maxStep);
				Vector3f n = next.transform(new Vector3f(forward.getStepX(), forward.getStepY(), forward.getStepZ()));
				next = new Quaternionf().rotateAxis(correction, n.x, n.y, n.z).mul(next);
			}
		}
		next.normalize();
		if (next.equals(orientation(), 1.0e-6f)) return;
		// If the rotation would dig into terrain, try it slightly higher, so a ship resting
		// on the ground (even upside down) can always roll or pitch itself free.
		for (double lift : new double[] {0, 0.5, 1.0}) {
			Vec3 at = position().add(0, lift, 0);
			if (!hullCollides(at, next)) {
				if (lift > 0) setPos(at);
				setOrientation(next);
				return;
			}
		}
	}

	/**
	 * Pilot input -> acceleration with fuel burn. W/S/A/D/Space/Ctrl thrust along the ship's
	 * own axes, using only thrusters facing that way. Hover assist cancels gravity with
	 * whatever thrust points up (unless Ctrl is held); Shift boosts.
	 */
	private Vec3 applyThrust(Player pilot, Vec3 velocity, ShipPhysics physics) {
		Input input = pilot instanceof ServerPlayer sp ? sp.getLastClientInput() : Input.EMPTY;
		boolean space = SpaceTravel.isSpace(level());
		double gravity = Planets.gravityOf(level().dimension());
		double boost = input.shift() ? physics.boostMultiplier() : 1.0;
		Vec3 accel = new Vec3(0, -gravity * physics.gravityToAccel(), 0);
		double fuelUsed = 0;

		if (fuel() > 0) {
			Direction left = forward.getCounterClockWise();
			Map<Direction, Boolean> wanted = Map.of(
					forward, input.forward(), forward.getOpposite(), input.backward(),
					left, input.left(), left.getOpposite(), input.right(),
					Direction.UP, input.jump(), Direction.DOWN, input.sprint());
			for (var entry : wanted.entrySet()) {
				if (!entry.getValue()) continue;
				double a = thrust.get(entry.getKey()) * boost / mass * physics.thrustToAccel();
				accel = accel.add(worldDirection(entry.getKey()).scale(a));
				fuelUsed += burn.get(entry.getKey()) * boost;
			}

			// Hover assist: cancel gravity using every thruster that has an upward component.
			if (!input.sprint() && !input.jump() && gravity > 0) {
				double capacity = 0, burnPerThrust = 0;
				for (Direction d : Direction.values()) {
					double up = Math.max(0, worldDirection(d).y);
					capacity += thrust.get(d) * up;
					burnPerThrust += burn.get(d) * up;
				}
				double needed = mass * gravity;
				double supplied = Math.min(needed, capacity);
				if (capacity > 0) {
					accel = accel.add(0, supplied / mass * physics.thrustToAccel(), 0);
					fuelUsed += burnPerThrust * (supplied / capacity);
				}
			}
		}
		if (fuelUsed > 0) burnFuel(fuelUsed);
		return velocity.add(accel).scale(space ? physics.dragSpace() : physics.dragAtmosphere());
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

	private void sendHud(ServerPlayer player) {
		Vec3 nose = worldDirection(forward);
		Vec3 up = worldDirection(Direction.UP);
		Vec3 right = worldDirection(forward.getClockWise());
		int heading = Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(nose.x, -nose.z))), 360);
		int pitch = (int) Math.round(Math.toDegrees(Math.asin(Mth.clamp(nose.y, -1, 1))));
		int roll = (int) Math.round(Math.toDegrees(Math.atan2(-right.y, up.y)));
		String speed = String.format("%.1f", getDeltaMovement().length() * 20);
		Component where = SpaceTravel.isSpace(level())
				? Component.translatable("hud.starwake.ship.orbit", SpaceTravel.planetName(level(), orbiting))
				: Component.translatable("hud.starwake.ship.alt", (int) getY());
		player.sendOverlayMessage(Component.translatable("hud.starwake.ship6", fuel(), fuelCapacity, speed,
				heading, pitch, roll, where));
	}

	// --- Terrain collision -------------------------------------------------------------------

	/** Moves axis by axis, clipping each against terrain using the hull blocks' world boxes. */
	private void moveWithCollision(Vec3 velocity) {
		double dy = clip(Direction.Axis.Y, velocity.y);
		grounded = velocity.y < 0 && dy > velocity.y;
		setPos(getX(), getY() + dy, getZ());
		double dx = clip(Direction.Axis.X, velocity.x);
		setPos(getX() + dx, getY(), getZ());
		double dz = clip(Direction.Axis.Z, velocity.z);
		setPos(getX(), getY(), getZ() + dz);
		setDeltaMovement(dx == velocity.x ? velocity.x : 0, dy == velocity.y ? velocity.y : 0, dz == velocity.z ? velocity.z : 0);
	}

	private double clip(Direction.Axis axis, double distance) {
		if (distance == 0 || structure == null) return distance;
		boolean aligned = isAxisAligned();
		for (BlockPos local : hull) {
			AABB box = worldBox(new AABB(local), aligned ? 1.0 : 0.9).deflate(1.0e-7);
			AABB swept = box.expandTowards(axis == Direction.Axis.X ? distance : 0,
					axis == Direction.Axis.Y ? distance : 0, axis == Direction.Axis.Z ? distance : 0);
			for (VoxelShape shape : level().getBlockCollisions(null, swept)) {
				distance = shape.collide(axis, box, distance);
				if (distance == 0) return 0;
			}
		}
		return distance;
	}

	/** Would the hull overlap terrain at this pose? (Used to refuse rotations into the ground.) */
	private boolean hullCollides(Vec3 position, Quaternionfc rotation) {
		boolean aligned = isAxisAligned(rotation);
		for (BlockPos local : hull) {
			AABB box = worldBox(new AABB(local), aligned ? 1.0 : 0.9, position, rotation).deflate(0.05);
			for (VoxelShape shape : level().getBlockCollisions(null, box)) {
				if (!shape.isEmpty() && shape.bounds().intersects(box)) return true;
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
		var size = structure.size();
		return local.x >= 0 && local.z >= 0 && local.y >= 0 && local.x <= size.getX() && local.z <= size.getZ()
				&& local.y <= size.getY() && !entity.onGround();
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
		return new Vec3(seatLocal.getX() + 0.5, seatLocal.getY() + (helm ? 1.0 : 0.5), seatLocal.getZ() + 0.5);
	}

	@Override
	protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
		BlockPos seat = seatOf(passenger);
		Vec3 point = toWorld(seatPoint(seat == null ? helmLocal : seat)).subtract(position());
		return point;
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
		entityData.set(FUEL, input.getIntOr("fuel", 0));
		orbiting = input.read("orbiting", Level.RESOURCE_KEY_CODEC).orElse(null);
		input.read("orientation", ExtraCodecs.QUATERNIONF).ifPresent(q -> entityData.set(ORIENTATION, new Quaternionf(q)));
		int frame = input.getIntOr("frame", 1);
		input.read("structure", ShipStructure.CODEC).ifPresent(this::setStructure);
		if (frame < FRAME_VERSION && structure != null) {
			// Ships saved before 3d stored the min corner as their position.
			setPos(getX() + pivot.x, getY() + pivot.y, getZ() + pivot.z);
		}
		lastTickOrientation.set(orientation());
		prevOrientation.set(orientation());
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.putInt("frame", FRAME_VERSION);
		output.putInt("fuel", fuel());
		output.putInt("fuel_capacity", fuelCapacity);
		output.store("orientation", ExtraCodecs.QUATERNIONF, orientation());
		if (orbiting != null) {
			output.store("orbiting", Level.RESOURCE_KEY_CODEC, orbiting);
		}
		if (structure != null) {
			output.store("structure", ShipStructure.CODEC, structure);
		}
	}
}
