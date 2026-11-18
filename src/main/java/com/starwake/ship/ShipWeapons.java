package com.starwake.ship;

import com.starwake.registry.ModSounds;
import com.starwake.ship.block.ShipWeaponBlock;
import com.starwake.ship.data.ShipMaterials;
import com.starwake.ship.weapon.ShipProjectile;
import com.starwake.ship.weapon.WeaponSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A ship's guns at run time (M11): the mounts aboard, their cooldowns, the ammo magazine, the locked target, and firing.
 *
 * <h2>Firing</h2> While the pilot holds fire ({@link ShipEntity#CONTROL_FIRE} in their input), every working gun whose
 * barrel cell is clear shoots at its rate: ballistic guns spend a round from the ship's magazine, energy guns draw on the
 * weapon capacitor. Each shot adds heat. Worn guns hit less hard; disabled ones (below 25%) don't fire.
 *
 * <h2>Aiming (Star Citizen style)</h2> Fixed mounts fire along their barrel. Gimballed mounts turn toward the aim point
 * within their cone: the lead point of the locked target for that gun's shot speed, or else where the pilot is looking.
 * The HUD draws the same lead point as a pip.
 *
 * <h2>Ammo</h2> Rounds are loaded by using an ammo item on a gun aboard (any gun of that calibre shares the magazine).
 */
public final class ShipWeapons {
	/** Rounds a ship's magazine holds per ammo type. */
	public static final int MAGAZINE = 2000;
	/** Distance (m) the pilot's aim converges at with no target. */
	private static final double AIM_DISTANCE = 600;

	record Mount(BlockPos pos, WeaponSpec spec, String id) {}

	private final ShipEntity ship;
	private List<Mount> mounts = List.of();
	private double[] cooldown = new double[0];
	/** Rounds by ammo item id. */
	private final Map<String, Integer> rounds = new LinkedHashMap<>();
	private int target = -1;
	private boolean warnedEmpty;
	/** Admin/testing: ticks to keep firing without a pilot (along the nose, or at the locked target). */
	private int testFire;

	ShipWeapons(ShipEntity ship) {
		this.ship = ship;
	}

	/** After the ship's parts changed: the guns aboard (both sides, for the HUD's lead pip). */
	void rebuild() {
		List<Mount> next = new ArrayList<>();
		if (ship.structure() != null) {
			for (BlockPos pos : ship.components()) {
				BlockState state = ship.structure().get(pos);
				if (state == null || !(state.getBlock() instanceof ShipWeaponBlock)) continue;
				WeaponSpec spec = WeaponSpec.of(state.getBlock());
				if (spec != null) next.add(new Mount(pos.immutable(), spec, BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath()));
			}
		}
		next.sort(java.util.Comparator.comparing(Mount::pos));
		mounts = List.copyOf(next);
		cooldown = new double[mounts.size()];
	}

	public boolean armed() {
		return !mounts.isEmpty();
	}

	// --- Target ------------------------------------------------------------------------------

	public int target() {
		return target;
	}

	/** Server: the pilot locks a target (another ship or a living thing), or clears it with -1. */
	public void setTarget(int id) {
		Entity e = id < 0 ? null : ship.level().getEntity(id);
		target = e != null && e != ship && (e instanceof ShipEntity || e instanceof LivingEntity) ? id : -1;
		ship.getEntityData().set(ShipEntity.TARGET, target);
	}

	/** The locked target, if it's still there and in the same level. */
	public static @Nullable Entity targetOf(ShipEntity ship) {
		int id = ship.getEntityData().get(ShipEntity.TARGET);
		Entity e = id < 0 ? null : ship.level().getEntity(id);
		return e == null || e.isRemoved() ? null : e;
	}

	/** Velocity (blocks/tick) of anything, measured the same way on both sides. */
	public static Vec3 velocityOf(Entity e) {
		if (e instanceof ShipEntity s) return s.lastMove();
		return new Vec3(e.getX() - e.xo, e.getY() - e.yo, e.getZ() - e.zo);
	}

	/** Where a target will be when a shot from {@code muzzle} at {@code speed} b/t meets it (relative to the shooter's motion). */
	public static Vec3 lead(Vec3 muzzle, Vec3 shooterVelocity, Entity target, double speed) {
		Vec3 aim = target instanceof ShipEntity s ? s.position() : target.getBoundingBox().getCenter();
		Vec3 r = aim.subtract(muzzle);
		Vec3 v = velocityOf(target).subtract(shooterVelocity);
		double a = v.dot(v) - speed * speed, b = 2 * r.dot(v), c = r.dot(r);
		double t;
		if (Math.abs(a) < 1e-9) t = b == 0 ? 0 : -c / b;
		else {
			double disc = b * b - 4 * a * c;
			if (disc < 0) return aim;
			double s = Math.sqrt(disc);
			double t1 = (-b - s) / (2 * a), t2 = (-b + s) / (2 * a);
			t = t1 > 0 && t2 > 0 ? Math.min(t1, t2) : Math.max(t1, t2);
		}
		if (!(t > 0) || t > 400) return aim;
		return aim.add(v.scale(t));
	}

	// --- Firing ------------------------------------------------------------------------------

	/** Server, every tick. */
	void tick() {
		if (target >= 0 && targetOf(ship) == null) setTarget(-1);
		for (int i = 0; i < cooldown.length; i++) cooldown[i] = Math.max(0, cooldown[i] - 1);
		if (ship.tickCount % 10 == 0) publish();
		Player pilot = ship.pilot();
		boolean testing = testFire > 0;
		if (testing) testFire--;
		boolean fire = pilot != null && (ship.controls & ShipEntity.CONTROL_FIRE) != 0 || testing;
		if (!fire || mounts.isEmpty() || ship.drives.jump != null || ship.drives.jumpTicks >= 0 || !(ship.level() instanceof ServerLevel level)) {
			return;
		}
		Vec3 nose = ship.worldDirection(ship.forward());
		Vec3 look = pilot == null ? nose : ship.pilotLook != null ? ship.pilotLook : pilot.getLookAngle();
		Vec3 eye = pilot == null ? ship.position() : pilot.getEyePosition();
		Entity locked = targetOf(ship);
		boolean cannon = false, laser = false, dry = false;
		for (int i = 0; i < mounts.size(); i++) {
			Mount m = mounts.get(i);
			if (cooldown[i] > 0) continue;
			BlockState state = ship.structure().get(m.pos());
			if (state == null || !(state.getBlock() instanceof ShipWeaponBlock)) continue;
			float health = ship.power.health(m.pos());
			if (health < ShipMaterials.DISABLED_BELOW) continue;
			Direction facing = state.getValue(ShipWeaponBlock.FACING);
			if (ship.structure().get(m.pos().relative(facing)) != null) continue; // barrel blocked
			WeaponSpec spec = m.spec();
			if (spec.energyWeapon()) {
				if (!ship.power.drawCapacitor(spec.energy())) continue;
			} else {
				String ammo = spec.ammo().map(Identifier::toString).orElse("");
				int left = rounds.getOrDefault(ammo, 0);
				if (left <= 0) {
					dry = true;
					continue;
				}
				rounds.put(ammo, left - 1);
			}
			cooldown[i] += 20.0 / Math.max(0.1, spec.rate());
			Vec3 barrel = ship.worldDirection(facing).normalize();
			Vec3 muzzle = ship.toWorld(Vec3.atCenterOf(m.pos()).add(Vec3.atLowerCornerOf(facing.getUnitVec3i()).scale(1.1)));
			boolean gimbal = state.getValue(ShipWeaponBlock.GIMBAL);
			Vec3 dir = barrel;
			if (gimbal) {
				double speed = spec.speed() / 20.0;
				Vec3 aim = locked != null && locked.distanceToSqr(muzzle) < spec.range() * spec.range() * 2.25
						? lead(muzzle, ship.getDeltaMovement(), locked, speed)
						: eye.add(look.normalize().scale(Math.min(AIM_DISTANCE, spec.range())));
				dir = clampCone(barrel, aim.subtract(muzzle).normalize(), Math.toRadians(spec.gimbalCone()));
			}
			dir = spread(dir, Math.toRadians(spec.spread()), level.getRandom());
			Vec3 velocity = dir.scale(spec.speed() / 20.0).add(ship.getDeltaMovement());
			double damage = spec.damage() * (gimbal ? spec.gimbalFactor() : 1) * ShipMaterials.effectiveness(health);
			ShipProjectile.fire(level, ship, pilot, muzzle, velocity, damage, spec, colorOf(m.id(), spec));
			ship.power.addHeat(spec.heat());
			if (spec.energyWeapon()) laser = true;
			else cannon = true;
		}
		if (cannon) level.playSound(null, ship.blockPosition(), ModSounds.CANNON_FIRE, SoundSource.NEUTRAL, 1.6f, 0.95f + level.getRandom().nextFloat() * 0.1f);
		if (laser) level.playSound(null, ship.blockPosition(), ModSounds.LASER_FIRE, SoundSource.NEUTRAL, 1.4f, 0.95f + level.getRandom().nextFloat() * 0.1f);
		if (dry && !warnedEmpty) {
			ship.messageAboard(Component.translatable("message.starwake.weapon.no_ammo"));
			warnedEmpty = true;
		} else if (!dry) {
			warnedEmpty = false;
		}
	}

	private static int colorOf(String id, WeaponSpec spec) {
		if (!spec.energyWeapon()) return 0xFFFFC860;
		if (id.endsWith("_mk3")) return 0xFF60D0FF;
		if (id.endsWith("_mk2")) return 0xFF70FF90;
		return 0xFFFF4A3A;
	}

	/** Turns {@code barrel} toward {@code want} by at most {@code cone} radians. */
	static Vec3 clampCone(Vec3 barrel, Vec3 want, double cone) {
		double cos = barrel.dot(want);
		if (cos >= Math.cos(cone)) return want;
		Vec3 perp = want.subtract(barrel.scale(cos));
		if (perp.lengthSqr() < 1e-9) return barrel;
		return barrel.scale(Math.cos(cone)).add(perp.normalize().scale(Math.sin(cone)));
	}

	private static Vec3 spread(Vec3 dir, double cone, net.minecraft.util.RandomSource random) {
		if (cone <= 0) return dir;
		Vec3 a = Math.abs(dir.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
		Vec3 u = dir.cross(a).normalize(), v = dir.cross(u);
		double r = cone * Math.sqrt(random.nextDouble()), t = random.nextDouble() * 2 * Math.PI;
		return dir.add(u.scale(Math.tan(r) * Math.cos(t))).add(v.scale(Math.tan(r) * Math.sin(t))).normalize();
	}

	// --- Ammo --------------------------------------------------------------------------------

	/** Server: ammo used on a gun aboard. Returns the rounds loaded (0 = not this gun's ammo or the magazine is full). */
	public int load(BlockPos gun, ItemStack stack) {
		BlockState state = ship.structure() == null ? null : ship.structure().get(gun);
		WeaponSpec spec = state == null ? null : WeaponSpec.of(state.getBlock());
		if (spec == null || spec.ammo().isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(spec.ammo().get())) return 0;
		String ammo = spec.ammo().get().toString();
		int have = rounds.getOrDefault(ammo, 0);
		int items = Math.min(stack.getCount(), (MAGAZINE - have) / Math.max(1, spec.roundsPerItem()));
		if (items <= 0) return 0;
		stack.shrink(items);
		rounds.put(ammo, have + items * spec.roundsPerItem());
		publish();
		return items * spec.roundsPerItem();
	}

	/** Admin/testing: fire for {@code ticks} without a pilot. */
	public void testFire(int ticks) {
		testFire = ticks;
	}

	/** Admin/testing: full magazines for every calibre aboard. */
	public void fillForTesting() {
		for (Mount m : mounts) m.spec().ammo().ifPresent(a -> rounds.put(a.toString(), MAGAZINE));
		publish();
	}

	public int rounds(String ammo) {
		return rounds.getOrDefault(ammo, 0);
	}

	/**
	 * Synced summary for the HUD: per gun type "id,count,speed m/s,rounds (-1 = energy),gimballed count" joined by ';'.
	 */
	private void publish() {
		Map<String, int[]> groups = new LinkedHashMap<>();
		Map<String, WeaponSpec> specs = new LinkedHashMap<>();
		for (Mount m : mounts) {
			BlockState state = ship.structure() == null ? null : ship.structure().get(m.pos());
			int[] g = groups.computeIfAbsent(m.id(), k -> new int[2]);
			g[0]++;
			if (state != null && state.getBlock() instanceof ShipWeaponBlock && state.getValue(ShipWeaponBlock.GIMBAL)) g[1]++;
			specs.put(m.id(), m.spec());
		}
		StringBuilder out = new StringBuilder();
		groups.forEach((id, g) -> {
			WeaponSpec spec = specs.get(id);
			int left = spec.energyWeapon() ? -1 : rounds(spec.ammo().map(Identifier::toString).orElse(""));
			if (!out.isEmpty()) out.append(';');
			out.append(id).append(',').append(g[0]).append(',').append(Math.round(spec.speed())).append(',').append(left).append(',').append(g[1]);
		});
		String text = out.toString();
		if (!text.equals(ship.getEntityData().get(ShipEntity.WEAPONS))) ship.getEntityData().set(ShipEntity.WEAPONS, text);
		ship.getEntityData().set(ShipEntity.CAPACITOR, (float) ship.power.capacitor());
		ship.getEntityData().set(ShipEntity.CAPACITOR_MAX, (float) ship.systems().capacitor());
	}

	// --- Saving ------------------------------------------------------------------------------

	private static final com.mojang.serialization.Codec<Map<String, Integer>> ROUNDS_CODEC =
			com.mojang.serialization.Codec.unboundedMap(com.mojang.serialization.Codec.STRING, com.mojang.serialization.Codec.INT);

	void read(net.minecraft.world.level.storage.ValueInput input) {
		rounds.clear();
		input.read("rounds", ROUNDS_CODEC).ifPresent(rounds::putAll);
	}

	void write(net.minecraft.world.level.storage.ValueOutput output) {
		output.store("rounds", ROUNDS_CODEC, rounds);
	}
}
