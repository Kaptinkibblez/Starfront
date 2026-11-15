package com.starwake.planet;

import com.starwake.galaxy.Body;
import com.starwake.registry.ModEntities;
import com.starwake.ship.ShipEntity;
import com.starwake.ship.SpaceTravel;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Finite planets (M9): a wrapping planet's surface is the square [-L/2, L/2) in X and Z ({@link Body#circumference()},
 * 8-32 km), and its terrain repeats outside it without a seam ({@link com.starwake.worldgen.WrapDensity}). Whoever
 * crosses an edge is moved one circumference back, onto identical ground: players and ships (with their crew) every
 * tick, everything else once a second. Near an edge, the chunks on the far side are loaded ahead so the move lands on
 * ready ground.
 */
public final class PlanetWrap {
	/** Pre-load the far side when this close to an edge. */
	private static final int PRELOAD = 64;

	private PlanetWrap() {}

	public static void init() {
		ServerTickEvents.END_LEVEL_TICK.register(PlanetWrap::tick);
	}

	/** Surface size of the planet in this level, or 0 when it doesn't wrap (the Overworld, space). */
	public static int period(ServerLevel level) {
		return Planets.forDimension(level.dimension()).flatMap(p -> p.planet().body())
				.filter(Body::wrap).map(Body::circumference).orElse(0);
	}

	/** The coordinate inside the tile [-L/2, L/2). */
	public static double tile(double c, int period) {
		return c - Math.floor((c + period / 2.0) / period) * period;
	}

	private static void tick(ServerLevel level) {
		int period = period(level);
		if (period <= 0) return;
		// Ships first: they carry their pilot and everyone walking aboard.
		for (ShipEntity ship : level.getEntities(ModEntities.SHIP, ship -> true)) {
			Vec3 to = wrapped(ship.position(), period);
			if (to != null) SpaceTravel.jump(ship, to);
		}
		for (ServerPlayer player : List.copyOf(level.players())) {
			if (player.isPassenger() || !level.getEntities(ModEntities.SHIP, player.getBoundingBox().inflate(16), s -> true).isEmpty()) {
				continue; // aboard (or about to board) a ship: the ship moves them
			}
			Vec3 to = wrapped(player.position(), period);
			if (to != null) {
				move(player, level, to);
			} else if (level.getGameTime() % 40 == 0) {
				preload(level, player.position(), period);
			}
		}
		if (level.getGameTime() % 20 != 0) return;
		List<Entity> others = new ArrayList<>();
		for (Entity entity : level.getAllEntities()) {
			if (entity instanceof ServerPlayer || entity instanceof ShipEntity || entity.isPassenger()) continue;
			if (wrapped(entity.position(), period) != null) others.add(entity);
		}
		for (Entity entity : others) {
			Vec3 to = wrapped(entity.position(), period);
			if (to != null) move(entity, level, to);
		}
	}

	/** Where a position outside the tile belongs, or null if it's inside. */
	private static Vec3 wrapped(Vec3 pos, int period) {
		double x = tile(pos.x, period), z = tile(pos.z, period);
		if (x == pos.x && z == pos.z) return null;
		return new Vec3(x, pos.y, z);
	}

	private static void move(Entity entity, ServerLevel level, Vec3 to) {
		level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, ChunkPos.containing(BlockPos.containing(to)), 2);
		entity.teleport(new TeleportTransition(level, to, entity.getDeltaMovement(), entity.getYRot(), entity.getXRot(),
				TeleportTransition.DO_NOTHING));
	}

	/** Near an edge: load the matching ground on the far side so the crossing lands on ready chunks. */
	private static void preload(ServerLevel level, Vec3 pos, int period) {
		double half = period / 2.0;
		double x = pos.x, z = pos.z;
		boolean nearX = half - Math.abs(x) < PRELOAD, nearZ = half - Math.abs(z) < PRELOAD;
		if (!nearX && !nearZ) return;
		double fx = nearX ? x - Math.signum(x) * period : x, fz = nearZ ? z - Math.signum(z) * period : z;
		level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, ChunkPos.containing(BlockPos.containing(fx, pos.y, fz)), 3);
	}
}
