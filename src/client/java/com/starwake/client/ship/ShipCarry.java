package com.starwake.client.ship;

import com.starwake.ship.ShipCollisions;
import com.starwake.ship.ShipEntity;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the local player riding along on a moving ship (standing on deck, not piloting).
 *
 * Runs around the player's own tick (see LocalPlayerMixin): after each tick it records which
 * ship the player stands on and where that ship was, and at the start of the next tick it
 * moves the player by however far that ship has moved since. Applying the carry before the
 * player's own collision step makes it independent of entity tick order and of bursty
 * network position updates.
 */
public final class ShipCarry {
	private static @Nullable ShipEntity standingOn;
	private static Vec3 shipPosAtLastTick = Vec3.ZERO;

	private ShipCarry() {}

	public static void beforePlayerTick(LocalPlayer player) {
		ShipEntity ship = standingOn;
		if (ship == null || ship.isRemoved() || ship.level() != player.level() || player.isPassenger()) {
			return;
		}
		Vec3 delta = ship.position().subtract(shipPosAtLastTick);
		if (delta.lengthSqr() > 0 && delta.lengthSqr() < 64) { // ignore teleports
			player.setPos(player.position().add(delta));
		}
	}

	public static void afterPlayerTick(LocalPlayer player) {
		standingOn = null;
		if (player.isPassenger()) return;
		for (ShipEntity ship : ShipCollisions.shipsIn(player.level())) {
			if (ship.isSupporting(player)) {
				standingOn = ship;
				shipPosAtLastTick = ship.position();
				return;
			}
		}
	}
}
