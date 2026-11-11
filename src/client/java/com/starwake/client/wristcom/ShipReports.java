package com.starwake.client.wristcom;

import com.starwake.network.ShipConsolePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The latest ship report the server sent (pre-flight at a Helm, or an assembled ship's
 * status), shown by the Wristcom's Ship app. Server-formatted, so the client only displays it.
 */
public final class ShipReports {
	private static @Nullable ShipConsolePayload latest;

	private ShipReports() {}

	public static void set(ShipConsolePayload report) {
		latest = report;
	}

	/** The latest report if it still applies to where the player is, else null. */
	public static @Nullable ShipConsolePayload current() {
		Minecraft minecraft = Minecraft.getInstance();
		ShipConsolePayload report = latest;
		if (report == null || minecraft.player == null || minecraft.level == null) return null;
		if (report.shipId() >= 0) {
			var vehicle = minecraft.player.getVehicle();
			var walking = com.starwake.client.ship.ShipLocalMotion.activeShip();
			boolean aboard = (vehicle != null && vehicle.getId() == report.shipId())
					|| (walking != null && walking.getId() == report.shipId());
			return aboard ? report : null;
		}
		// A Helm standing in the world: valid while you're near it and it's still there.
		boolean near = minecraft.player.position().distanceToSqr(Vec3.atCenterOf(report.helm())) < 10 * 10;
		boolean exists = minecraft.level.getBlockState(report.helm()).is(com.starwake.registry.ModBlocks.HELM);
		return near && exists ? report : null;
	}
}
