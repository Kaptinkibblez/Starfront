package com.starwake.ship;

import com.starwake.network.ShipFootingPayload;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Server side of walking aboard ships (M9): players' positions in their ship's frame, reported by their clients
 * ({@link ShipFootingPayload}). Each tick, after a ship moves, everyone standing on it is placed at the ship's
 * transform of their local position, so on the server (and so for every other player) walkers move exactly with
 * the deck. Their own client keeps moving them in the ship frame (ShipLocalMotion).
 */
public final class ShipFooting {
	/** A walker: which ship, where in its frame, on the deck or not, and when we last heard. */
	private record Footing(int shipId, Vec3 local, boolean onGround, int tick) {}

	private static final Map<UUID, Footing> FOOTINGS = new HashMap<>();
	/** Reports older than this (ticks) are dropped: the player stepped off without telling us. */
	private static final int STALE = 40;

	private ShipFooting() {}

	public static void handle(ServerPlayer player, ShipFootingPayload payload) {
		if (payload.shipId() < 0 || player.isPassenger() || player.isSpectator()) {
			FOOTINGS.remove(player.getUUID());
			return;
		}
		if (!(player.level().getEntity(payload.shipId()) instanceof ShipEntity ship) || ship.structure() == null) return;
		Vec3 local = new Vec3(payload.x(), payload.y(), payload.z());
		// Only accept positions on or in the hull (with a little slack), never teleports across the world.
		if (!ship.isNearLocal(local, 2.0)) return;
		FOOTINGS.put(player.getUUID(), new Footing(ship.getId(), local, payload.onGround(), player.tickCount));
	}

	public static void forget(ServerPlayer player) {
		FOOTINGS.remove(player.getUUID());
	}

	/** After {@code ship} moved this tick: put everyone standing on it where they stand in its frame. */
	public static void carry(ShipEntity ship) {
		if (FOOTINGS.isEmpty() || !(ship.level() instanceof net.minecraft.server.level.ServerLevel level)) return;
		Iterator<Map.Entry<UUID, Footing>> it = FOOTINGS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Footing> entry = it.next();
			Footing footing = entry.getValue();
			if (footing.shipId() != ship.getId()) continue;
			ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
			if (player == null || player.level() != level || player.isPassenger() || !player.isAlive()
					|| player.tickCount - footing.tick() > STALE) {
				it.remove();
				continue;
			}
			Vec3 at = ship.toWorld(footing.local());
			player.setPos(at.x, at.y, at.z);
			player.setOnGround(footing.onGround());
			if (footing.onGround()) player.resetFallDistance();
		}
	}
}
