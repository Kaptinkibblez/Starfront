package com.starwake.process;

import com.starwake.planet.PlanetHazards;
import com.starwake.planet.SurveySuitItem;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Toxic chemical releases (M8c; user: "real hazards on leaks"). Venting a toxic gas (a machine's
 * full off-gas tank, a broken tank) makes a short-lived cloud that drifts slightly up, shows as
 * coloured haze and hurts anything breathing in it, scaled by the species' toxicity. A Survey Suit
 * with oxygen protects. Harmless gases (CO2 below 0.2 toxicity, steam, nitrogen) vent silently.
 */
public final class GasHazards {
	private static final class Cloud {
		final ServerLevel level;
		Vec3 pos;
		double radius;
		int ttl;
		final double toxicity;
		final int color;
		/** Radioactivity in the cloud (Bq; radon). */
		double activity;

		Cloud(ServerLevel level, Vec3 pos, double radius, int ttl, double toxicity, int color) {
			this.level = level;
			this.pos = pos;
			this.radius = radius;
			this.ttl = ttl;
			this.toxicity = toxicity;
			this.color = color;
		}
	}

	private static final List<Cloud> CLOUDS = new ArrayList<>();

	private GasHazards() {}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 10 == 0) tick();
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> CLOUDS.clear());
	}

	/** Releases kg of a species at pos. */
	public static void vent(ServerLevel level, BlockPos pos, String species, double kg) {
		Species.Info s = Species.get(species).orElse(null);
		if (s == null || kg <= 0 || s.toxicity() < 0.2) return;
		Vec3 at = Vec3.atCenterOf(pos);
		for (Cloud c : CLOUDS) {
			if (c.level == level && c.pos.distanceTo(at) < 3 && c.color == s.color()) {
				c.ttl = Math.min(1200, c.ttl + (int) (kg * 60));
				c.radius = Math.min(8, c.radius + Math.sqrt(kg) * 0.5);
				c.activity += Radiation.perKg(species)[0] * kg;
				return;
			}
		}
		Cloud cloud = new Cloud(level, at, Math.min(8, 1.5 + Math.sqrt(kg) * 1.5), Math.min(1200, 100 + (int) (kg * 60)), s.toxicity(), s.color());
		cloud.activity = Radiation.perKg(species)[0] * kg;
		CLOUDS.add(cloud);
	}

	/** Activity concentration (Bq/m^3) a player is breathing (0 with a Survey Suit on oxygen). */
	public static double inhaledActivity(Player player) {
		var suit = PlanetHazards.suit(player);
		if (!suit.isEmpty() && SurveySuitItem.oxygen(suit) > 0) return 0;
		double c = 0;
		for (Cloud cloud : CLOUDS) {
			if (cloud.activity <= 0 || cloud.level != player.level() || player.position().distanceTo(cloud.pos) > cloud.radius) continue;
			c += cloud.activity / (4.0 / 3 * Math.PI * Math.pow(cloud.radius, 3));
		}
		return c;
	}

	private static void tick() {
		Iterator<Cloud> it = CLOUDS.iterator();
		while (it.hasNext()) {
			Cloud c = it.next();
			c.ttl -= 10;
			c.pos = c.pos.add(0, 0.03, 0);
			if (c.ttl <= 0) {
				it.remove();
				continue;
			}
			int particles = (int) Math.min(40, c.radius * 8);
			c.level.sendParticles(new DustParticleOptions(c.color, 2.0f), c.pos.x, c.pos.y, c.pos.z, particles, c.radius * 0.5, c.radius * 0.3,
					c.radius * 0.5, 0.01);
			if (c.ttl % 20 != 0) continue; // damage once a second: time to get out
			AABB box = new AABB(c.pos, c.pos).inflate(c.radius);
			for (LivingEntity e : c.level.getEntitiesOfClass(LivingEntity.class, box)) {
				if (e.position().distanceTo(c.pos) > c.radius) continue;
				if (e instanceof Player p) {
					if (p.isCreative() || p.isSpectator()) continue;
					var suit = PlanetHazards.suit(p);
					if (!suit.isEmpty() && SurveySuitItem.oxygen(suit) > 0) continue;
				}
				e.hurtServer(c.level, c.level.damageSources().source(PlanetHazards.TOXIC_ATMOSPHERE), (float) (0.5 + 2.5 * c.toxicity));
			}
		}
	}
}
