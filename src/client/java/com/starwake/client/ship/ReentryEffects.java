package com.starwake.client.ship;

import com.starwake.galaxy.SolarSystem;
import com.starwake.registry.ModSounds;
import com.starwake.ship.ShipCollisions;
import com.starwake.ship.ShipEntity;
import com.starwake.ship.SpaceTravel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Re-entry heat (3j): fast ships near the edge of an atmosphere glow. The intensity (0..1)
 * comes from speed, scaled by how close the ship is to the atmosphere boundary (the top of a
 * planet's sky, or a planet's atmosphere sphere in space), plus a short burst whenever your
 * ship crosses between space and a planet. It drives:
 * <ul>
 *   <li>a plasma trail: flame and smoke streaming back from the ship's leading side (any ship);</li>
 *   <li>for your own ship: the re-entry roar, an orange heat haze at the screen edges
 *       ({@code FlightHud}) and camera shake ({@code CameraMixin}).</li>
 * </ul>
 * Purely cosmetic and client-side.
 */
public final class ReentryEffects {
	private static float localIntensity, previousIntensity;
	private static int burst;
	private static @Nullable ResourceKey<Level> lastDimension;
	private static @Nullable Roar roar;

	private ReentryEffects() {}

	/** Your ship's re-entry intensity, interpolated for rendering (0..1). */
	public static float intensity(float partialTick) {
		return Mth.lerp(partialTick, previousIntensity, localIntensity);
	}

	/** A small random camera offset while the hull is shaking. */
	public static Vec3 shake(float partialTick) {
		float i = intensity(partialTick);
		if (i < 0.05f) return Vec3.ZERO;
		RandomSource random = RandomSource.create(System.nanoTime() / 16_000_000L); // stable within a frame or so
		double a = 0.08 * i * i;
		return new Vec3((random.nextDouble() - 0.5) * a, (random.nextDouble() - 0.5) * a, (random.nextDouble() - 0.5) * a);
	}

	static float intensityOf(ShipEntity ship) {
		return com.starwake.ship.Reentry.intensity(ship);
	}

	public static void tick(Minecraft client) {
		previousIntensity = localIntensity;
		if (client.level == null || client.player == null || client.isPaused()) return;

		ShipEntity mine = client.player.getVehicle() instanceof ShipEntity s ? s : ShipLocalMotion.activeShip();
		ResourceKey<Level> dimension = client.level.dimension();
		if (lastDimension != null && !lastDimension.equals(dimension) && mine != null) burst = 50; // just crossed over
		lastDimension = dimension;
		if (burst > 0) burst--;
		localIntensity = mine == null ? 0 : Math.max(intensityOf(mine), burst / 50f);

		if (localIntensity > 0.05f && (roar == null || roar.isStopped())) {
			roar = new Roar();
			client.getSoundManager().play(roar);
		}

		RandomSource random = client.level.getRandom();
		for (ShipEntity ship : ShipCollisions.shipsIn(client.level)) {
			if (ship.distanceToSqr(client.player) > 160 * 160 || ship.structure() == null) continue;
			float intensity = ship == mine ? localIntensity : intensityOf(ship);
			if (intensity < 0.05f) continue;
			Vec3 velocity = ship.recentVelocity();
			Vec3 forward = velocity.lengthSqr() > 1.0e-4 ? velocity.normalize() : new Vec3(0, -1, 0);
			var size = ship.structure().size();
			double radius = Math.max(size.getX(), Math.max(size.getY(), size.getZ())) * 0.45;
			// Two vectors across the direction of travel.
			Vec3 side = forward.cross(Math.abs(forward.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize();
			Vec3 other = forward.cross(side);
			int count = Math.round(intensity * 14);
			for (int i = 0; i < count; i++) {
				double a = random.nextDouble() * Math.PI * 2, r = Math.sqrt(random.nextDouble()) * radius;
				Vec3 at = ship.position().add(forward.scale(radius * 0.8))
						.add(side.scale(Math.cos(a) * r)).add(other.scale(Math.sin(a) * r));
				Vec3 trail = forward.scale(-0.4 - random.nextDouble() * 0.4).add(velocity);
				client.level.addParticle(random.nextFloat() < 0.75f ? ParticleTypes.FLAME : ParticleTypes.LARGE_SMOKE,
						at.x, at.y, at.z, trail.x, trail.y, trail.z);
			}
		}
	}

	/** The roar of re-entry, following your ship's intensity. */
	private static final class Roar extends AbstractTickableSoundInstance {
		private int quiet;

		Roar() {
			super(ModSounds.REENTRY_LOOP, SoundSource.NEUTRAL, RandomSource.create());
			looping = true;
			delay = 0;
			volume = 0.01f;
			relative = true;
			attenuation = Attenuation.NONE;
		}

		@Override
		public void tick() {
			volume += (localIntensity * 1.1f - volume) * 0.1f;
			pitch = 0.8f + 0.4f * localIntensity;
			quiet = localIntensity < 0.03f && volume < 0.03f ? quiet + 1 : 0;
			if (quiet > 20) stop();
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}
	}
}
