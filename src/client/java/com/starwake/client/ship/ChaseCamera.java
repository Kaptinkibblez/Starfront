package com.starwake.client.ship;

import com.starwake.ship.ShipEntity;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Star Citizen's third-person chase camera (F4, 3j). While seated on a ship, the camera sits
 * behind and above the whole ship, looking where you look, so mouse-aim steering still points
 * the nose at the crosshair. Distance scales with the ship's size. Vanilla's third-person
 * camera would sit a few blocks behind your head, inside the hull.
 *
 * It uses vanilla's THIRD_PERSON_BACK mode (so your character is drawn) and moves the camera
 * in {@code CameraMixin}. Pressing F5 to another mode, or leaving the seat, turns it off.
 */
public final class ChaseCamera {
	private static boolean active;

	private ChaseCamera() {}

	/** F4: toggle between the cockpit and the chase view. */
	public static void toggle(Minecraft client) {
		if (client.player == null || !(client.player.getVehicle() instanceof ShipEntity)) return;
		active = !isActive(client);
		client.options.setCameraType(active ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON);
	}

	private static boolean isActive(Minecraft client) {
		return active && client.options.getCameraType() == CameraType.THIRD_PERSON_BACK
				&& client.player != null && client.player.getVehicle() instanceof ShipEntity;
	}

	/**
	 * Called every client tick: drop out of chase view when it no longer applies. Leaving the
	 * seat ends it after a short grace period, so crossing between space and a planet (where
	 * you're briefly re-seated) keeps the chase view.
	 */
	public static void tick(Minecraft client) {
		if (!active) return;
		if (client.options.getCameraType() != CameraType.THIRD_PERSON_BACK) {
			active = false; // the player picked another view (F5)
			return;
		}
		boolean seated = client.player != null && client.player.getVehicle() instanceof ShipEntity;
		unseatedTicks = seated ? 0 : unseatedTicks + 1;
		if (unseatedTicks > 60) {
			active = false;
			client.options.setCameraType(CameraType.FIRST_PERSON);
		}
	}

	private static int unseatedTicks;

	/** Where the chase camera sits this frame, or null when it's not active. */
	public static @Nullable Vec3 position(float partialTicks) {
		Minecraft client = Minecraft.getInstance();
		if (!isActive(client) || !(client.player.getVehicle() instanceof ShipEntity ship) || ship.structure() == null) return null;
		var size = ship.structure().size();
		double extent = Math.max(size.getX(), Math.max(size.getY(), size.getZ()));
		double distance = extent * 1.4 + 6, height = extent * 0.25 + 2;
		Vec3 centre = ship.getPosition(partialTicks);
		Vec3 look = client.player.getViewVector(partialTicks);
		Vector3f up = ship.orientation(partialTicks).transform(new Vector3f(0, 1, 0));
		return centre.subtract(look.scale(distance)).add(up.x * height, up.y * height, up.z * height);
	}
}
