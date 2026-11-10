package com.starwake.client.ship;

import com.starwake.ship.ShipCollisions;
import com.starwake.ship.ShipEntity;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the local player riding along on a moving ship (standing on deck, not seated).
 *
 * Runs around the player's own tick (LocalPlayerMixin). After each tick it records which ship
 * the player stands on and their position *in that ship's frame*. At the start of the next
 * tick it puts the player back at that ship-local position under the ship's new transform,
 * so the player follows translation and rotation, and turns their view with the ship's
 * heading. Doing this before the player's own collision step makes it independent of tick
 * order and of bursty network updates.
 */
public final class ShipCarry {
	private static @Nullable ShipEntity standingOn;
	private static Vec3 localPos = Vec3.ZERO;
	private static double shipHeading;

	private ShipCarry() {}

	public static void beforePlayerTick(LocalPlayer player) {
		ShipEntity ship = standingOn;
		if (ship == null || ship.isRemoved() || ship.level() != player.level() || player.isPassenger()) {
			return;
		}
		Vec3 target = ship.toWorld(localPos);
		if (target.distanceToSqr(player.position()) < 64) { // ignore teleports
			player.setPos(target);
			float turn = (float) (heading(ship) - shipHeading);
			player.setYRot(player.getYRot() + turn);
			player.setYHeadRot(player.getYHeadRot() + turn);
		}
	}

	public static void afterPlayerTick(LocalPlayer player) {
		standingOn = null;
		if (player.isPassenger()) return;
		for (ShipEntity ship : ShipCollisions.shipsIn(player.level())) {
			if (ship.isSupporting(player)) {
				standingOn = ship;
				localPos = ship.toLocal(player.position());
				shipHeading = heading(ship);
				return;
			}
		}
	}

	/** The ship's nose heading in Minecraft yaw degrees. */
	private static double heading(ShipEntity ship) {
		Vec3 nose = ship.worldDirection(ship.forward());
		return Math.toDegrees(Math.atan2(-nose.x, nose.z));
	}
}
