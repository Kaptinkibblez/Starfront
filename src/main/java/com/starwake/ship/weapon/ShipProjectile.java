package com.starwake.ship.weapon;

import com.starwake.config.StarwakeConfig;
import com.starwake.ship.ShipEntity;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * A ship-gun shot (M11): a slug or a laser bolt. It flies in a straight line at its gun's speed (blocks per tick, synced
 * once in {@link #VELOCITY} so clients move it themselves) and, on the server, sweeps the segment it covers each tick
 * against terrain, ships (raycast in each ship's frame, like a click) and living things. The nearest hit wins. Never saved:
 * shots alive at a save are simply gone. Drawn as a tracer by the client's combat renderer.
 */
public class ShipProjectile extends Entity {
	public static final ResourceKey<DamageType> DAMAGE = ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE,
			com.starwake.StarwakeMod.id("ship_weapon"));

	private static final EntityDataAccessor<Vector3fc> VELOCITY = SynchedEntityData.defineId(ShipProjectile.class, EntityDataSerializers.VECTOR3);
	/** Tracer colour (ARGB) and whether it's an energy bolt (drawn longer and brighter). */
	private static final EntityDataAccessor<Integer> COLOR = SynchedEntityData.defineId(ShipProjectile.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Boolean> ENERGY = SynchedEntityData.defineId(ShipProjectile.class, EntityDataSerializers.BOOLEAN);
	/** Where the shot ended (hit or out of range), set one tick before it's removed so clients can draw it reaching there. */
	private static final EntityDataAccessor<Vector3fc> END = SynchedEntityData.defineId(ShipProjectile.class, EntityDataSerializers.VECTOR3);
	/** The muzzle the shot left (clients often first see a shot a tick or two after it was fired). */
	private static final EntityDataAccessor<Vector3fc> ORIGIN = SynchedEntityData.defineId(ShipProjectile.class, EntityDataSerializers.VECTOR3);
	private static final EntityDataAccessor<Boolean> ENDED = SynchedEntityData.defineId(ShipProjectile.class, EntityDataSerializers.BOOLEAN);

	private int ownerShip = -1;
	private @Nullable UUID shooter;
	private double damage, shieldFactor = 1, breakDamage = 1e9;
	private int life = 40;

	public ShipProjectile(EntityType<? extends ShipProjectile> type, Level level) {
		super(type, level);
		noPhysics = true;
		setNoGravity(true);
	}

	/** A new shot from {@code ship}'s gun. */
	public static ShipProjectile fire(ServerLevel level, ShipEntity ship, @Nullable Player shooter, Vec3 muzzle, Vec3 velocity, double damage,
			WeaponSpec spec, int color) {
		ShipProjectile shot = new ShipProjectile(com.starwake.registry.ModEntities.SHIP_PROJECTILE, level);
		shot.setPos(muzzle);
		shot.setOldPosAndRot();
		shot.entityData.set(VELOCITY, new Vector3f((float) velocity.x, (float) velocity.y, (float) velocity.z));
		shot.entityData.set(COLOR, color);
		shot.entityData.set(ORIGIN, new Vector3f((float) muzzle.x, (float) muzzle.y, (float) muzzle.z));
		shot.entityData.set(ENERGY, spec.energyWeapon());
		shot.ownerShip = ship.getId();
		shot.shooter = shooter == null ? null : shooter.getUUID();
		shot.damage = damage;
		shot.shieldFactor = spec.shieldFactor();
		shot.breakDamage = spec.breakDamage();
		shot.life = (int) Math.ceil(spec.range() / Math.max(1, spec.speed()) * 20) + 1;
		level.addFreshEntity(shot);
		return shot;
	}

	public Vec3 velocity() {
		Vector3fc v = entityData.get(VELOCITY);
		return new Vec3(v.x(), v.y(), v.z());
	}

	/** Where the shot was fired from, or null if that hasn't reached this client yet. */
	public @Nullable Vec3 muzzle() {
		Vector3fc o = entityData.get(ORIGIN);
		return o.x() == 0 && o.y() == 0 && o.z() == 0 ? null : new Vec3(o.x(), o.y(), o.z());
	}

	public int color() {
		return entityData.get(COLOR);
	}

	public boolean energy() {
		return entityData.get(ENERGY);
	}

	public int ownerShip() {
		return ownerShip;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(VELOCITY, new Vector3f());
		builder.define(COLOR, 0xFFFFC040);
		builder.define(ENERGY, false);
		builder.define(END, new Vector3f());
		builder.define(ENDED, false);
		builder.define(ORIGIN, new Vector3f());
	}

	/** The end point once the shot has ended (clients draw the tracer up to it), else null. */
	public @Nullable Vec3 end() {
		if (!entityData.get(ENDED)) return null;
		Vector3fc e = entityData.get(END);
		return new Vec3(e.x(), e.y(), e.z());
	}

	@Override
	public void tick() {
		setOldPosAndRot();
		if (entityData.get(ENDED)) {
			if (!level().isClientSide()) discard(); // one tick after ending, so the end point reaches clients first
			return;
		}
		Vec3 from = position();
		Vec3 to = from.add(velocity());
		if (level() instanceof ServerLevel server) {
			// Out of range, or about to leave the chunks that tick (it would hang there, frozen): it ends here.
			boolean gone = --life < 0 || !server.isPositionEntityTicking(BlockPos.containing(to));
			Vec3 hit = sweep(server, from, to);
			if (hit == null && gone) hit = to;
			if (hit != null) {
				setPos(hit);
				entityData.set(END, new Vector3f((float) hit.x, (float) hit.y, (float) hit.z));
				entityData.set(ENDED, true);
				return;
			}
		}
		setPos(to);
	}

	// --- Hits --------------------------------------------------------------------------------

	private record Hit(double distSq, Vec3 at, @Nullable ShipEntity ship, @Nullable BlockPos local, @Nullable Entity entity) {}

	/** Server: the nearest thing between {@code from} and {@code to}; applies it and returns where, or null. */
	private @Nullable Vec3 sweep(ServerLevel level, Vec3 from, Vec3 to) {
		Hit best = null;
		BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
				net.minecraft.world.phys.shapes.CollisionContext.empty()));
		if (block.getType() == HitResult.Type.BLOCK) best = new Hit(block.getLocation().distanceToSqr(from), block.getLocation(), null, null, null);
		AABB sweep = new AABB(from, to).inflate(1);
		for (ShipEntity ship : level.getEntitiesOfClass(ShipEntity.class, sweep)) {
			if (ship.getId() == ownerShip || ship.structure() == null) continue;
			Vec3 a = ship.toLocal(from), b = ship.toLocal(to);
			BlockHitResult local = BlockGetter.traverseBlocks(a, b, ship, (s, pos) -> {
				BlockState state = s.structure().get(pos);
				if (state == null) return null;
				return state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).clip(a, b, pos.immutable());
			}, s -> null);
			if (local == null) continue;
			Vec3 at = ship.toWorld(local.getLocation());
			double d = at.distanceToSqr(from);
			if (best == null || d < best.distSq()) best = new Hit(d, at, ship, local.getBlockPos(), null);
		}
		ShipEntity own = level.getEntity(ownerShip) instanceof ShipEntity s ? s : null;
		for (Entity e : level.getEntities(this, sweep, e -> e instanceof LivingEntity living && living.isAlive() && !e.isSpectator()
				&& (own == null || e.getVehicle() != own && !own.isSupporting(e)))) {
			var clip = e.getBoundingBox().inflate(0.2).clip(from, to);
			if (clip.isEmpty()) continue;
			double d = clip.get().distanceToSqr(from);
			if (best == null || d < best.distSq()) best = new Hit(d, clip.get(), null, null, e);
		}
		if (best == null) return null;
		apply(level, best);
		return best.at();
	}

	private void apply(ServerLevel level, Hit hit) {
		Player shooterPlayer = shooter == null ? null : level.getPlayerByUUID(shooter);
		Vec3 at = hit.at();
		if (hit.ship() != null) {
			ShipEntity target = hit.ship();
			if (!mayHurt(target, shooterPlayer)) {
				sparks(level, at, ParticleTypes.ELECTRIC_SPARK, 4);
				return;
			}
			target.damage.takeHit(hit.local(), at, damage * StarwakeConfig.get(StarwakeConfig.SHIP_DAMAGE), shieldFactor, breakDamage);
			return;
		}
		if (hit.entity() != null) {
			Entity target = hit.entity();
			if (target instanceof Player victim && shooterPlayer != null && victim != shooterPlayer && !StarwakeConfig.on(StarwakeConfig.PVP)) {
				sparks(level, at, ParticleTypes.CRIT, 3);
				return;
			}
			DamageSource source = level.damageSources().source(DAMAGE, this, shooterPlayer);
			target.hurtServer(level, source, (float) (damage * 0.5));
			sparks(level, at, ParticleTypes.CRIT, 6);
			return;
		}
		// Terrain: a puff of dust and sparks (guns don't dig).
		sparks(level, at, energy() ? ParticleTypes.SMALL_FLAME : ParticleTypes.CRIT, 5);
		sparks(level, at, ParticleTypes.SMOKE, 3);
		level.playSound(null, BlockPos.containing(at), com.starwake.registry.ModSounds.WEAPON_IMPACT, SoundSource.NEUTRAL, 0.8f,
				0.9f + level.getRandom().nextFloat() * 0.3f);
	}

	/** PvP off (the default): players' guns don't hurt other players' ships. Ownerless ships and your own are fair game. */
	public static boolean mayHurt(ShipEntity target, @Nullable Player shooter) {
		if (shooter == null || StarwakeConfig.on(StarwakeConfig.PVP)) return true;
		UUID owner = target.owner();
		return owner == null || owner.equals(shooter.getUUID());
	}

	static void sparks(ServerLevel level, Vec3 at, net.minecraft.core.particles.ParticleOptions type, int count) {
		level.sendParticles(type, true, true, at.x, at.y, at.z, count, 0.2, 0.2, 0.2, 0.15);
	}

	// --- Entity plumbing ---------------------------------------------------------------------

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	public boolean isPickable() {
		return false;
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return distance < 1024 * 1024;
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
	}
}
