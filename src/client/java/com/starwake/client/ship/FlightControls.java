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
	/** Cockpit HUD size and brightness (M9 pass 2; Wristcom Settings app). */
	private static float hudScale = 1.0f, hudOpacity = 1.0f;
	public static final float HUD_SCALE_MIN = 0.6f, HUD_SCALE_MAX = 1.6f, HUD_OPACITY_MIN = 0.3f;
	private static float stickX, stickY;
	/** M10: the speed limiter (m/s) and the flight mode (SCM or NAV). Both are remembered. */
	private static float speedLimit = com.starwake.ship.ShipFlight.DEFAULT_LIMIT;
	private static boolean nav;

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

	public static float speedLimit() {
		load();
		return speedLimit;
	}

	public static boolean nav() {
		load();
		return nav;
	}

	/** One wheel notch: the limit steps by about a tenth of itself (5 m/s at least), within the mode's ceiling. */
	public static void scrollLimit(Minecraft client, int notches) {
		load();
		var physics = com.starwake.ship.data.ShipPhysics.get();
		boolean space = client.level != null && com.starwake.ship.SpaceTravel.isSpace(client.level);
		float ceiling = (float) (nav ? physics.navSpeed(space) : physics.scmSpeed());
		float base = Math.min(speedLimit, ceiling);
		float step = Math.max(5, Math.round(base * 0.1f / 5) * 5);
		speedLimit = Math.clamp(base + notches * step, 5, ceiling);
		save();
	}

	/** SCM <-> NAV. */
	public static void toggleNav(Minecraft client) {
		load();
		nav = !nav;
		save();
		if (client.player != null) {
			client.gui.hud.setOverlayMessage(Component.translatable(nav ? "message.starwake.mode.nav" : "message.starwake.mode.scm"), false);
		}
	}

	public static float hudScale() {
		load();
		return hudScale;
	}

	public static float hudOpacity() {
		load();
		return hudOpacity;
	}

	/** Steps the HUD scale by {@code delta} (clamped, rounded to 0.1) and remembers it. */
	public static void stepHudScale(float delta) {
		load();
		hudScale = Math.round(Math.clamp(hudScale + delta, HUD_SCALE_MIN, HUD_SCALE_MAX) * 10) / 10f;
		save();
	}

	public static void stepHudOpacity(float delta) {
		load();
		hudOpacity = Math.round(Math.clamp(hudOpacity + delta, HUD_OPACITY_MIN, 1f) * 10) / 10f;
		save();
	}

	/** Sets the steering mode directly (Settings app). */
	public static void setJoystick(boolean on) {
		load();
		joystick = on;
		stickX = stickY = 0;
		save();
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
			hudScale = Math.clamp(Float.parseFloat(props.getProperty("hud_scale", "1.0")), HUD_SCALE_MIN, HUD_SCALE_MAX);
			hudOpacity = Math.clamp(Float.parseFloat(props.getProperty("hud_opacity", "1.0")), HUD_OPACITY_MIN, 1f);
			speedLimit = Math.max(5, Float.parseFloat(props.getProperty("speed_limit", Float.toString(speedLimit))));
			nav = "nav".equals(props.getProperty("flight_mode", "scm"));
		} catch (IOException | NumberFormatException ignored) {
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
		props.setProperty("hud_scale", Float.toString(hudScale));
		props.setProperty("hud_opacity", Float.toString(hudOpacity));
		props.setProperty("speed_limit", Float.toString(speedLimit));
		props.setProperty("flight_mode", nav ? "nav" : "scm");
		try (Writer out = Files.newBufferedWriter(FILE)) {
			props.store(out, "Starwake client settings");
		} catch (IOException ignored) {
			// Not fatal: the mode just isn't remembered.
		}
	}
}
