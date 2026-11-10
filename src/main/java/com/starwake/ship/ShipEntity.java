package com.starwake.ship;

import com.starwake.planet.Planets;
import com.starwake.ship.block.HelmBlock;
import com.starwake.ship.block.ThrusterBlock;
import com.starwake.ship.data.ShipPhysics;
import com.starwake.ship.data.ShipStats;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import org.jspecify.annotations.Nullable;

/**
 * An assembled, flying ship: a persistent entity that carries a {@link ShipStructure}.
 *
 * Coordinates: the entity's position is the world position of the structure's local origin
 * (its min corner). Ships translate but never rotate (rotation is Milestone 4).
 *
 * Server: flight physics from the pilot's input, terrain collision, carrying entities on board.
 * Client: rendering (ShipRenderer) and collision shapes, so walking on deck is predicted
 * locally. Block data reaches clients via ShipStructurePayload when tracking starts.
 */
public class ShipEntity extends Entity {
	private static final EntityDataAccessor<Integer> FUEL = SynchedEntityData.defineId(ShipEntity.class, EntityDataSerializers.INT);

	private @Nullable ShipStructure structure;
	private BlockPos helmLocal = BlockPos.ZERO;
	private Direction forward = Direction.NORTH;
	private int fuelCapacity;

	// Derived from the structure (recomputed on load; never saved).
	private double mass = 1;
	private final Map<Direction, Double> thrust = new EnumMap<>(Direction.class);
	private final Map<Direction, Double> burn = new EnumMap<>(Direction.class);

	/** How far the ship moved during the last tick (client and server); used to carry riders. */
	private Vec3 lastMove = Vec3.ZERO;
	private @Nullable Vec3 lastTickPos;
	private boolean grounded;
	/** Admin/testing: constant velocity while unpiloted ({@code /starwake ship drift}). Not saved. */
	private Vec3 drift = Vec3.ZERO;

	public ShipEntity(EntityType<? extends ShipEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true; // we move ourselves; vanilla movement code never runs
	}

	// --- Setup ----------------------------------------------------------------------------

	/** Server: fills a freshly created ship from an assembly. */
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
		recomputeStats();
		setBoundingBox(makeBoundingBox(position()));
	}

	public @Nullable ShipStructure structure() {
		return structure;
	}

	private void recomputeStats() {
		mass = 0;
		for (Direction d : Direction.values()) {
			thrust.put(d, 0.0);
			burn.put(d, 0.0);
		}
		if (structure == null) return;
		for (ShipStructure.Entry entry : structure.entries()) {
			ShipStats.BlockStats stats = ShipStats.get(entry.state());
			mass += stats.mass();
			if (entry.state().getBlock() instanceof ThrusterBlock) {
				Direction nozzle = entry.state().getValue(ThrusterBlock.FACING);
				if (structure.get(entry.pos().relative(nozzle)) == null) { // clear exhaust only
					thrust.merge(nozzle.getOpposite(), stats.thrust(), Double::sum);
					burn.merge(nozzle.getOpposite(), stats.fuelBurn(), Double::sum);
				}
			}
			if (entry.state().getBlock() instanceof HelmBlock) {
				helmLocal = entry.pos();
				forward = entry.state().getValue(HelmBlock.FACING);
			}
		}
		mass = Math.max(mass, 1);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(FUEL, 0);
	}

	public int fuel() {
		return entityData.get(FUEL);
	}

	public int fuelCapacity() {
		return fuelCapacity;
	}

	/** Adds fuel up to capacity; returns the amount actually added. */
	public int addFuel(int amount) {
		int added = Math.max(0, Math.min(amount, fuelCapacity - fuel()));
		entityData.set(FUEL, fuel() + added);
		return added;
	}

	public BlockPos helmLocal() {
		return helmLocal;
	}

	public Direction forward() {
		return forward;
	}

	public double mass() {
		return mass;
	}

	public Vec3 lastMove() {
		return lastMove;
	}

	/** World position of a local block's min corner. */
	public Vec3 toWorld(BlockPos local) {
		return position().add(local.getX(), local.getY(), local.getZ());
	}

	// --- Bounding box and collision --------------------------------------------------------

	@Override
	protected AABB makeBoundingBox(Vec3 pos) {
		if (structure == null) {
			return new AABB(pos, pos.add(1, 1, 1));
		}
		return new AABB(pos, pos.add(structure.size().getX(), structure.size().getY(), structure.size().getZ()));
	}

	/** Adds the world-space collision shapes of ship blocks that intersect {@code area}. */
	public void collectShapes(AABB area, List<VoxelShape> out) {
		if (structure == null) return;
		Vec3 o = position();
		int minX = Math.max(0, (int) Math.floor(area.minX - o.x)), maxX = Math.min(structure.size().getX() - 1, (int) Math.floor(area.maxX - o.x));
		int minY = Math.max(0, (int) Math.floor(area.minY - o.y)), maxY = Math.min(structure.size().getY() - 1, (int) Math.floor(area.maxY - o.y));
		int minZ = Math.max(0, (int) Math.floor(area.minZ - o.z)), maxZ = Math.min(structure.size().getZ() - 1, (int) Math.floor(area.maxZ - o.z));
		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					BlockState state = structure.get(x, y, z);
					if (state == null) continue;
					VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
					if (!shape.isEmpty()) out.add(shape.move(o.x + x, o.y + y, o.z + z));
				}
			}
		}
	}

	// --- Ticking ---------------------------------------------------------------------------

	@Override
	public void tick() {
		super.tick();
		if (!level().isClientSide() && structure != null) {
			serverTick();
		}
		// Measure against our own end-of-last-tick position: on the client, position updates
		// can be applied between ticks (packet handling), so xo/yo/zo and in-tick snapshots
		// would both miss them.
		lastMove = lastTickPos == null ? Vec3.ZERO : position().subtract(lastTickPos);
		lastTickPos = position();
		if (!level().isClientSide() && lastMove.lengthSqr() > 0) {
			carryEntities();
		}
	}

	private void serverTick() {
		ShipPhysics physics = ShipPhysics.get();
		Player pilot = getFirstPassenger() instanceof Player p ? p : null;
		Vec3 velocity = getDeltaMovement();

		if (pilot == null && drift.lengthSqr() > 0) {
			velocity = drift;
		} else if (pilot == null) {
			// Unpiloted ships station-keep: brake to a stop and hold position, free of charge.
			velocity = velocity.scale(0.8);
			if (velocity.lengthSqr() < 1.0e-6) velocity = Vec3.ZERO;
		} else {
			velocity = applyThrust(pilot, velocity, physics);
		}

		if (velocity.lengthSqr() > physics.maxSpeed() * physics.maxSpeed()) {
			velocity = velocity.normalize().scale(physics.maxSpeed());
		}
		setDeltaMovement(velocity);
		if (velocity.lengthSqr() > 0) {
			moveWithCollision(velocity);
		}

		if (pilot instanceof ServerPlayer player && tickCount % 10 == 0) {
			player.sendOverlayMessage(Component.translatable("hud.starwake.ship",
					fuel(), fuelCapacity, String.format("%.1f", getDeltaMovement().length() * 20), (int) getY()));
		}
	}

	/** Pilot input -> acceleration, with fuel burn. Returns the new velocity (before drag cap). */
	private Vec3 applyThrust(Player pilot, Vec3 velocity, ShipPhysics physics) {
		Input input = pilot instanceof ServerPlayer sp ? sp.getLastClientInput() : Input.EMPTY;
		double gravity = Planets.gravityOf(level().dimension());
		double weight = mass * gravity;
		boolean hasFuel = fuel() > 0;
		double fuelUsed = 0;
		Vec3 accel = Vec3.ZERO;

		if (hasFuel) {
			// Horizontal: only directions the ship has thrusters for.
			Direction left = forward.getCounterClockWise();
			if (input.forward()) { accel = accel.add(push(forward, 1.0, physics)); fuelUsed += burn.get(forward); }
			if (input.backward()) { accel = accel.add(push(forward.getOpposite(), 1.0, physics)); fuelUsed += burn.get(forward.getOpposite()); }
			if (input.left()) { accel = accel.add(push(left, 1.0, physics)); fuelUsed += burn.get(left); }
			if (input.right()) { accel = accel.add(push(left.getOpposite(), 1.0, physics)); fuelUsed += burn.get(left.getOpposite()); }

			// Vertical: lift engines throttle automatically to hover; Space = full, Sprint = cut.
			double maxLift = thrust.get(Direction.UP);
			double lift;
			if (input.jump()) lift = maxLift;
			else if (input.sprint()) lift = 0;
			else lift = Math.min(maxLift, weight); // hover assist
			if (maxLift > 0) {
				accel = accel.add(0, lift / mass * physics.thrustToAccel(), 0);
				fuelUsed += burn.get(Direction.UP) * (lift / maxLift);
			}
			if (input.sprint() && thrust.get(Direction.DOWN) > 0) {
				accel = accel.add(push(Direction.DOWN, 1.0, physics));
				fuelUsed += burn.get(Direction.DOWN);
			}
		}

		accel = accel.add(0, -gravity * physics.gravityToAccel(), 0);
		if (fuelUsed > 0) burnFuel(fuelUsed);
		return velocity.add(accel).scale(physics.dragAtmosphere());
	}

	private Vec3 push(Direction direction, double throttle, ShipPhysics physics) {
		double a = thrust.get(direction) * throttle / mass * physics.thrustToAccel();
		return new Vec3(direction.getStepX() * a, direction.getStepY() * a, direction.getStepZ() * a);
	}

	/** Fractional fuel accumulates so small burns still drain the tank over time. */
	private double fuelDebt;

	private void burnFuel(double amount) {
		fuelDebt += amount;
		int whole = (int) fuelDebt;
		if (whole > 0) {
			fuelDebt -= whole;
			entityData.set(FUEL, Math.max(0, fuel() - whole));
		}
	}

	/** Moves axis by axis, clipping each against terrain using only the ship's outer skin. */
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
		Direction leading = Direction.fromAxisAndDirection(axis, distance > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
		Vec3 o = position();
		for (BlockPos local : structure.outerFace(leading)) {
			AABB box = new AABB(o.x + local.getX(), o.y + local.getY(), o.z + local.getZ(),
					o.x + local.getX() + 1, o.y + local.getY() + 1, o.z + local.getZ() + 1).deflate(1.0e-7);
			AABB swept = box.expandTowards(axis == Direction.Axis.X ? distance : 0, axis == Direction.Axis.Y ? distance : 0, axis == Direction.Axis.Z ? distance : 0);
			for (VoxelShape shape : level().getBlockCollisions(null, swept)) {
				distance = shape.collide(axis, box, distance);
				if (distance == 0) return 0;
			}
		}
		return distance;
	}

	/**
	 * Server: moves non-player entities standing on or inside the ship along with it.
	 * Players move themselves (client-authoritative); the client carries the local player.
	 */
	private void carryEntities() {
		AABB zone = getBoundingBox().expandTowards(0, 1.0, 0).inflate(0.01);
		for (Entity entity : level().getEntities(this, zone, e -> !(e instanceof Player) && !e.isPassenger() && !(e instanceof ShipEntity))) {
			if (isSupporting(entity, lastMove)) {
				entity.setPos(entity.position().add(lastMove));
			}
		}
	}

	/**
	 * True if the entity stands on a ship block, or is airborne inside the hull's bounds (e.g.
	 * jumping in the cabin). {@code shipShift} offsets the test into the ship's previous
	 * position (the server checks after the ship has already moved this tick).
	 */
	public boolean isSupporting(Entity entity, Vec3 shipShift) {
		if (structure == null) return false;
		AABB feet = entity.getBoundingBox();
		AABB probe = new AABB(feet.minX, feet.minY - 0.2, feet.minZ, feet.maxX, feet.minY + 0.05, feet.maxZ).move(shipShift);
		List<VoxelShape> shapes = new java.util.ArrayList<>();
		collectShapes(probe, shapes);
		for (VoxelShape shape : shapes) {
			for (AABB box : shape.toAabbs()) {
				if (box.intersects(probe)) return true;
			}
		}
		return !entity.onGround() && getBoundingBox().move(shipShift.reverse()).contains(entity.position());
	}

	public boolean isSupporting(Entity entity) {
		return isSupporting(entity, Vec3.ZERO);
	}

	public void setDrift(Vec3 drift) {
		this.drift = drift;
	}

	public boolean isGrounded() {
		return grounded;
	}

	// --- Riding: the pilot sits on the Helm --------------------------------------------------

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return getPassengers().isEmpty() && passenger instanceof Player;
	}

	@Override
	protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scale) {
		return new Vec3(helmLocal.getX() + 0.5, helmLocal.getY() + 1.0, helmLocal.getZ() + 0.5);
	}

	@Override
	public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
		return toWorld(helmLocal).add(0.5, 1.0, 0.5);
	}

	@Override
	public @Nullable LivingEntity getControllingPassenger() {
		return null; // the server flies the ship from the pilot's input; no client-side steering
	}

	// --- Misc entity behaviour ---------------------------------------------------------------

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		return false;
	}

	@Override
	public boolean isPickable() {
		return false; // interaction goes through ship-block raycasts instead (see ShipInteraction)
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

	// --- Persistence ---------------------------------------------------------------------------

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		fuelCapacity = input.getIntOr("fuel_capacity", 0);
		entityData.set(FUEL, input.getIntOr("fuel", 0));
		input.read("structure", ShipStructure.CODEC).ifPresent(this::setStructure);
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.putInt("fuel", fuel());
		output.putInt("fuel_capacity", fuelCapacity);
		if (structure != null) {
			output.store("structure", ShipStructure.CODEC, structure);
		}
	}
}
