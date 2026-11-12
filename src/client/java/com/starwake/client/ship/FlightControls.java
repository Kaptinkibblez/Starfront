package com.starwake.client.ship;

import com.starwake.ship.ShipEntity;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Pilot steering modes (M9). <b>Mouse-aim</b>: the ship turns toward wherever you look (the server chases the
 * pilot's view). <b>Joystick</b>: mouse movement deflects a virtual stick that sets yaw/pitch rates, and the
 * view stays locked to the nose (hold free-look to glance around). The chosen mode is remembered in
 * {@code config/starwake-client.properties}.
 */
public final class FlightControls {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("starwake-client.properties");
	/** Stick travel per mouse count, and the dead zone around the centre. */
	private static final float SENSITIVITY = 0.004f, DEAD_ZONE = 0.06f;

	private static boolean joystick;
	private static boolean loaded;
	private static float stickX, stickY;

	private FlightControls() {}

	public static boolean joystick() {
		load();
		return joystick;
	}

	public static void toggle(Minecraft client) {
		load();
		joystick = !joystick;
		stickX = stickY = 0;
		save();
		if (client.player != null) {
			client.gui.hud.setOverlayMessage(Component.translatable(joystick ? "message.starwake.steering.joystick" : "message.starwake.steering.mouse_aim"), false);
		}
	}

	/** Mouse movement while piloting in joystick mode moves the stick (the view doesn't turn). */
	public static void addStick(double dx, double dy) {
		stickX = Math.clamp(stickX + (float) dx * SENSITIVITY, -1f, 1f);
		stickY = Math.clamp(stickY + (float) dy * SENSITIVITY, -1f, 1f);
		float r = (float) Math.sqrt(stickX * stickX + stickY * stickY);
		if (r > 1) {
			stickX /= r;
			stickY /= r;
		}
	}

	public static void centre() {
		stickX = stickY = 0;
	}

	/** Stick deflection with the dead zone applied (what the server is sent). */
	public static float sentX() {
		return Math.abs(stickX) < DEAD_ZONE ? 0 : stickX;
	}

	public static float sentY() {
		return Math.abs(stickY) < DEAD_ZONE ? 0 : stickY;
	}

	public static float rawX() {
		return stickX;
	}

	public static float rawY() {
		return stickY;
	}

	/**
	 * Joystick mode: keeps the pilot's view on the nose (yaw/pitch of the ship's forward direction; the camera
	 * mixin adds the roll). Called at the end of every client tick; yRotO/xRotO hold last tick's nose so
	 * vanilla's frame interpolation matches the ship's.
	 */
	public static void lockView(Minecraft client, ShipEntity ship, boolean freeLook) {
		if (!joystick() || freeLook || client.player == null) return;
		Vec3 nose = ship.worldDirection(ship.forward());
		float yaw = (float) Math.toDegrees(Math.atan2(-nose.x, nose.z));
		float pitch = (float) Math.toDegrees(-Math.asin(Math.clamp(nose.y, -1, 1)));
		var player = client.player;
		float oldYaw = player.getYRot();
		float newYaw = oldYaw + net.minecraft.util.Mth.wrapDegrees(yaw - oldYaw);
		player.yRotO = player.getYRot();
		player.xRotO = player.getXRot();
		player.setYRot(newYaw);
		player.setXRot(pitch);
		player.setYHeadRot(newYaw);
	}

	private static void load() {
		if (loaded) return;
		loaded = true;
		if (!Files.exists(FILE)) return;
		Properties props = new Properties();
		try (Reader in = Files.newBufferedReader(FILE)) {
			props.load(in);
			joystick = "joystick".equals(props.getProperty("steering", "mouse_aim"));
		} catch (IOException ignored) {
			// Defaults stand.
		}
	}

	private static void save() {
		Properties props = new Properties();
		if (Files.exists(FILE)) {
			try (Reader in = Files.newBufferedReader(FILE)) {
				props.load(in);
			} catch (IOException ignored) {
				// Start fresh.
			}
		}
		props.setProperty("steering", joystick ? "joystick" : "mouse_aim");
		try (Writer out = Files.newBufferedWriter(FILE)) {
			props.store(out, "Starwake client settings");
		} catch (IOException ignored) {
			// Not fatal: the mode just isn't remembered.
		}
	}
}
