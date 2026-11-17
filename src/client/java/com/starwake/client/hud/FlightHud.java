package com.starwake.client.hud;

import com.starwake.StarwakeMod;
import com.starwake.client.ship.ShipLocalMotion;
import com.starwake.galaxy.SolarSystem;
import com.starwake.ship.ShipEntity;
import com.starwake.ship.SpaceTravel;
import com.starwake.ship.data.ShipPhysics;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The cockpit HUD (3i overhaul), a Star Citizen-style holographic flight display drawn while
 * you pilot a ship:
 * <ul>
 *   <li>centre: view reticle, nose marker (where the ship points), velocity vector (where it's
 *       actually going: they split when you slide, especially decoupled), and a pitch ladder
 *       that rolls with the ship;</li>
 *   <li>left tape: speed (m/s) with the top-speed mark, flight mode (CPLD/DCPL) and boost;</li>
 *   <li>right tape: altitude (or range to the nearest planet in space) and vertical speed;</li>
 *   <li>top: heading tape;</li>
 *   <li>bottom: systems strip (fuel, shields, power triangle, engines, heat, gear);</li>
 *   <li>alerts under the reticle: DECOUPLED, LOW FUEL, OVERHEAT, SHIELDS DOWN.</li>
 * </ul>
 * Crew and people walking aboard get a compact status card instead. While piloting, vanilla's
 * hotbar, hearts, food, armour, XP and crosshair are hidden. Everything shown comes from synced
 * ship data.
 */
public final class FlightHud {
	/** Palette at full opacity; the live colours below are these scaled by the HUD opacity setting each frame. */
	private static final int BASE_HOLO = 0xFF6FE3FF, BASE_HOLO_DIM = 0x996FE3FF, BASE_HOLO_FAINT = 0x406FE3FF, BASE_WHITE = 0xFFE9F6FF,
			BASE_WARN = 0xFFFFB347, BASE_ALERT = 0xFFFF5A4A, BASE_GOOD = 0xFF7CFFB2, BASE_PANEL = 0x58061018;
	public static int HOLO = BASE_HOLO, HOLO_DIM = BASE_HOLO_DIM, HOLO_FAINT = BASE_HOLO_FAINT, WHITE = BASE_WHITE,
			WARN = BASE_WARN, ALERT = BASE_ALERT, GOOD = BASE_GOOD, PANEL = BASE_PANEL;

	/** Applies the HUD opacity setting to the palette (M9 pass 2). */
	private static void applyOpacity() {
		float o = com.starwake.client.ship.FlightControls.hudOpacity();
		HOLO = fade(BASE_HOLO, o);
		HOLO_DIM = fade(BASE_HOLO_DIM, o);
		HOLO_FAINT = fade(BASE_HOLO_FAINT, o);
		WHITE = fade(BASE_WHITE, o);
		WARN = fade(BASE_WARN, o);
		ALERT = fade(BASE_ALERT, o);
		GOOD = fade(BASE_GOOD, o);
		PANEL = fade(BASE_PANEL, o);
	}

	private static int fade(int argb, float opacity) {
		int alpha = Math.round((argb >>> 24) * opacity);
		return (Math.max(alpha, 8) << 24) | (argb & 0xFFFFFF);
	}

	private FlightHud() {}

	public static void init() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, StarwakeMod.id("flight_hud"), FlightHud::render);
		// The cockpit replaces vanilla's survival HUD while piloting.
		for (Identifier id : new Identifier[] {VanillaHudElements.HOTBAR, VanillaHudElements.HEALTH_BAR,
				VanillaHudElements.FOOD_BAR, VanillaHudElements.ARMOR_BAR, VanillaHudElements.AIR_BAR,
				VanillaHudElements.INFO_BAR, VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.CROSSHAIR,
				VanillaHudElements.MOUNT_HEALTH}) {
			HudElementRegistry.replaceElement(id, original -> hiddenWhilePiloting(original));
		}
	}

	private static HudElement hiddenWhilePiloting(HudElement original) {
		return (graphics, delta) -> {
			if (pilotedShip() == null) original.extractRenderState(graphics, delta);
		};
	}

	/** The ship the local player is piloting, or null. */
	public static @Nullable ShipEntity pilotedShip() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null && minecraft.player.getVehicle() instanceof ShipEntity ship && ship.pilot() == minecraft.player) {
			return ship;
		}
		return null;
	}

	private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) return; // (F1 already hides HUD elements)
		applyOpacity();
		ShipEntity piloted = pilotedShip();
		if (piloted == null) {
			ShipEntity aboard = minecraft.player.getVehicle() instanceof ShipEntity seated ? seated : ShipLocalMotion.activeShip();
			if (aboard != null) crewCard(graphics, minecraft.font, aboard, minecraft.player.isPassenger());
			return;
		}
		// HUD scale (M9 pass 2): draw everything in a scaled space whose logical size is the screen over the scale.
		float scale = com.starwake.client.ship.FlightControls.hudScale();
		graphics.pose().pushMatrix();
		graphics.pose().scale(scale, scale);
		try {
			cockpit(graphics, deltaTracker, minecraft, piloted, Math.round(graphics.guiWidth() / scale), Math.round(graphics.guiHeight() / scale));
		} finally {
			graphics.pose().popMatrix();
		}
	}

	private static void cockpit(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, Minecraft minecraft, ShipEntity piloted, int w, int h) {
		Font font = minecraft.font;
		int cx = w / 2, cy = h / 2;
		heatHaze(graphics, w, h, com.starwake.client.ship.ReentryEffects.intensity(deltaTracker.getGameTimeDeltaPartialTick(true)));
		Camera camera = minecraft.gameRenderer.mainCamera();
		Matrix4f mvp = camera.getViewRotationProjectionMatrix(new Matrix4f());
		Vector3f look = new Vector3f(camera.forwardVector());

		Vec3 nose = piloted.worldDirection(piloted.forward());
		Vec3 up = piloted.worldDirection(Direction.UP);
		Vec3 right = piloted.worldDirection(piloted.forward().getClockWise());
		double pitch = Math.toDegrees(Math.asin(Mth.clamp(nose.y, -1, 1)));
		double roll = Math.toDegrees(Math.atan2(-right.y, up.y));
		double heading = Mth.positiveModulo(Math.toDegrees(Math.atan2(nose.x, -nose.z)), 360);
		Vec3 velocity = piloted.recentVelocity().scale(20); // m/s
		double speed = velocity.length();
		boolean space = SpaceTravel.isSpace(piloted.level());

		// Layout scales with the screen: tapes out at about a third of the width, the ladder
		// small in the middle, alerts under the heading tape, systems along the bottom. The
		// centre stays clear for flying.
		int tapeOffset = Mth.clamp(Math.round(w * 0.32f), 100, 260);
		int tapeHalf = Mth.clamp(Math.round(h * 0.18f), 28, 70);
		pitchLadder(graphics, font, cx, cy, pitch, roll, space, tapeHalf, tapeOffset - 48);
		reticle(graphics, cx, cy);
		if (com.starwake.client.ship.FlightControls.joystick()) stickGauge(graphics, cx, cy, Mth.clamp(Math.round(h * 0.12f), 24, 48));
		int[] n = HudProjection.project(mvp, look, nose, w, h);
		if (n != null) noseMarker(graphics, n[0], n[1]);
		if (speed > 0.5) {
			int[] v = HudProjection.project(mvp, look, velocity, w, h);
			if (v != null) velocityMarker(graphics, v[0], v[1]);
		}
		contacts(graphics, font, mvp, look, camera.position(), piloted, w, h);
		headingTape(graphics, font, cx, 4, heading, Math.min(80, Math.round(w * 0.18f)));
		speedTape(graphics, font, cx - tapeOffset, cy, tapeHalf, speed, piloted, space);
		altitudeTape(graphics, font, cx + tapeOffset, cy, tapeHalf, piloted, velocity.y, space);
		systemsStrip(graphics, font, cx, h - 26, Math.min(w - 16, 300), piloted, space);
		alerts(graphics, font, cx, 34, piloted);
	}

	/** Joystick mode (M9): a ring around the reticle with the stick's deflection as a dot (dead zone dimmed). */
	private static void stickGauge(GuiGraphicsExtractor g, int cx, int cy, int radius) {
		for (int k = 0; k < 48; k++) {
			double a = k * Math.PI * 2 / 48;
			int x = cx + (int) Math.round(Math.cos(a) * radius), y = cy + (int) Math.round(Math.sin(a) * radius);
			g.fill(x, y, x + 1, y + 1, k % 6 == 0 ? HOLO : HOLO_DIM);
		}
		float sx = com.starwake.client.ship.FlightControls.rawX(), sy = com.starwake.client.ship.FlightControls.rawY();
		int x = cx + Math.round(sx * radius), y = cy + Math.round(sy * radius);
		boolean live = com.starwake.client.ship.FlightControls.sentX() != 0 || com.starwake.client.ship.FlightControls.sentY() != 0;
		if (live) {
			// A line from the centre shows which way and how hard the ship is being turned.
			int steps = Math.max(Math.abs(x - cx), Math.abs(y - cy));
			for (int i = 3; i < steps; i += 2) {
				g.fill(cx + (x - cx) * i / steps, cy + (y - cy) * i / steps, cx + (x - cx) * i / steps + 1, cy + (y - cy) * i / steps + 1, HOLO_DIM);
			}
		}
		g.fill(x - 2, y - 2, x + 3, y + 3, live ? WHITE : HOLO_DIM);
	}

	/** Re-entry: an orange glow creeping in from the screen edges. */
	private static void heatHaze(GuiGraphicsExtractor g, int w, int h, float intensity) {
		if (intensity < 0.03f) return;
		int bands = 8;
		for (int i = 0; i < bands; i++) {
			int inset = i * Math.max(4, Math.min(w, h) / 40);
			int alpha = (int) (intensity * 70 * (1 - i / (float) bands));
			int color = (alpha << 24) | 0xFF6A1E;
			g.fill(inset, inset, w - inset, inset + 6, color);
			g.fill(inset, h - inset - 6, w - inset, h - inset, color);
			g.fill(inset, inset + 6, inset + 6, h - inset - 6, color);
			g.fill(w - inset - 6, inset + 6, w - inset, h - inset - 6, color);
		}
	}

	// --- centre -------------------------------------------------------------------------------

	private static void reticle(GuiGraphicsExtractor g, int cx, int cy) {
		g.fill(cx - 7, cy, cx - 3, cy + 1, HOLO);
		g.fill(cx + 4, cy, cx + 8, cy + 1, HOLO);
		g.fill(cx, cy - 7, cx + 1, cy - 3, HOLO);
		g.fill(cx, cy + 4, cx + 1, cy + 8, HOLO);
		g.fill(cx, cy, cx + 1, cy + 1, WHITE);
	}

	/** Contacts (M9 pass 2): the nearest other ships in view, bracketed, with range and closing speed. */
	private static void contacts(GuiGraphicsExtractor g, Font font, Matrix4f mvp, Vector3f look, Vec3 eye, ShipEntity own, int w, int h) {
		java.util.List<ShipEntity> ships = new java.util.ArrayList<>();
		for (var e : own.level().getEntities(own, own.getBoundingBox().inflate(2000), e -> e instanceof ShipEntity)) ships.add((ShipEntity) e);
		ships.sort(java.util.Comparator.comparingDouble(s -> s.position().distanceToSqr(eye)));
		Vec3 ownVelocity = own.recentVelocity();
		int shown = 0;
		for (ShipEntity ship : ships) {
			if (shown >= 8) break;
			Vec3 rel = ship.position().subtract(eye);
			double range = rel.length();
			if (range < 1) continue;
			int[] p = HudProjection.project(mvp, look, rel, w, h);
			if (p == null) continue;
			shown++;
			// Bracket size shrinks with range (but stays readable).
			int r = (int) Mth.clamp(Math.round(ship.getBoundingBox().getSize() * 120 / Math.max(range, 1)), 5, 40);
			double closing = -ship.recentVelocity().subtract(ownVelocity).dot(rel.scale(1 / range)) * 20; // m/s, + = closing
			int color = closing > 20 ? ALERT : closing > 5 ? WARN : HOLO;
			int x = p[0], y = p[1], arm = Math.max(2, r / 3);
			for (int sx : new int[] {-1, 1}) {
				for (int sy : new int[] {-1, 1}) {
					int bx = x + sx * r, by = y + sy * r;
					g.fill(Math.min(bx, bx - sx * arm), by, Math.max(bx, bx - sx * arm) + 1, by + 1, color);
					g.fill(bx, Math.min(by, by - sy * arm), bx + 1, Math.max(by, by - sy * arm) + 1, color);
				}
			}
			String label = range >= 1000 ? String.format("%.1f km", range / 1000) : Math.round(range) + " m";
			String rate = (closing >= 0 ? "+" : "") + Math.round(closing) + " m/s";
			String name = ship.ownerName().isEmpty() ? "SHIP" : ship.ownerName().toUpperCase(java.util.Locale.ROOT);
			g.text(font, name, x + r + 3, y - r, color, false);
			g.text(font, label, x + r + 3, y - r + 9, HOLO_DIM, false);
			g.text(font, rate, x + r + 3, y - r + 18, color, false);
		}
	}

	/** Where the nose points: a "W" gun-cross. */
	private static void noseMarker(GuiGraphicsExtractor g, int x, int y) {
		g.fill(x - 12, y, x - 6, y + 1, WHITE);
		g.fill(x + 7, y, x + 13, y + 1, WHITE);
		g.fill(x - 6, y, x - 5, y + 4, WHITE);
		g.fill(x + 6, y, x + 7, y + 4, WHITE);
		g.fill(x - 1, y + 2, x + 2, y + 3, WHITE);
	}

	/** Where the ship is going (prograde): a ring with wings and a tail. */
	private static void velocityMarker(GuiGraphicsExtractor g, int x, int y) {
		g.outline(x - 3, y - 3, 7, 7, GOOD);
		g.fill(x - 9, y, x - 3, y + 1, GOOD);
		g.fill(x + 4, y, x + 10, y + 1, GOOD);
		g.fill(x, y - 8, x + 1, y - 3, GOOD);
	}

	/**
	 * Horizon and pitch lines every 10 degrees, rotated by roll and scrolled by pitch. The
	 * numbers ride along with their lines but stay upright, so they read at any bank angle.
	 */
	private static void pitchLadder(GuiGraphicsExtractor g, Font font, int cx, int cy, double pitch, double roll, boolean space,
			int range, int maxWidth) {
		float pxPerDeg = 1.6f;
		int lineOuter = Math.min(34, maxWidth - 20), horizonOuter = Math.min(52, maxWidth), inner = 12;
		double angle = Math.toRadians(-roll);
		float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
		int base = (int) Math.floor(pitch / 10) * 10;
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().rotate((float) angle);
		for (int deg = base - 30; deg <= base + 30; deg += 10) {
			if (deg < -90 || deg > 90) continue;
			int y = Math.round((float) (pitch - deg) * pxPerDeg);
			if (Math.abs(y) > range) continue;
			int color = deg == 0 ? (space ? HOLO_FAINT : HOLO_DIM) : HOLO_FAINT;
			int outer = deg == 0 ? horizonOuter : lineOuter;
			g.fill(-outer, y, -inner, y + 1, color);
			g.fill(inner, y, outer, y + 1, color);
			if (deg != 0) {
				// Ticks point toward the horizon, like an aircraft ladder.
				int tick = deg > 0 ? 3 : -3;
				g.fill(-outer, Math.min(y, y + tick), -outer + 1, Math.max(y, y + tick) + 1, color);
				g.fill(outer - 1, Math.min(y, y + tick), outer, Math.max(y, y + tick) + 1, color);
			}
		}
		g.pose().popMatrix();
		for (int deg = base - 30; deg <= base + 30; deg += 10) {
			if (deg < -90 || deg > 90 || deg == 0) continue;
			float y = (float) (pitch - deg) * pxPerDeg;
			if (Math.abs(y) > range) continue;
			String label = Integer.toString(Math.abs(deg));
			for (int side : new int[] {-1, 1}) {
				float lx = side * (lineOuter + 3 + font.width(label) / 2f), ly = y;
				float rx = lx * cos - ly * sin, ry = lx * sin + ly * cos;
				g.text(font, label, Math.round(cx + rx - font.width(label) / 2f), Math.round(cy + ry - 3), HOLO_FAINT, false);
			}
		}
	}

	// --- tapes ----------------------------------------------------------------------------------

	private static void headingTape(GuiGraphicsExtractor g, Font font, int cx, int y, double heading, int half) {
		g.fill(cx - half, y + 10, cx + half, y + 11, HOLO_FAINT);
		float pxPerDeg = 2.0f;
		int start = (int) Math.floor((heading - half / pxPerDeg) / 5) * 5;
		for (int d = start; d <= heading + half / pxPerDeg; d += 5) {
			int x = cx + Math.round((float) (d - heading) * pxPerDeg);
			if (x < cx - half || x > cx + half) continue;
			int deg = Math.floorMod(d, 360);
			boolean major = deg % 30 == 0;
			g.fill(x, y + (major ? 6 : 8), x + 1, y + 10, major ? HOLO : HOLO_DIM);
			if (major) {
				String label = switch (deg) { case 0 -> "N"; case 90 -> "E"; case 180 -> "S"; case 270 -> "W"; default -> Integer.toString(deg / 10); };
				g.text(font, label, x - font.width(label) / 2 + 1, y - 3, deg % 90 == 0 ? WHITE : HOLO_DIM, false);
			}
		}
		String readout = String.format("%03d", Math.round(heading) % 360);
		box(g, cx - 12, y + 13, 25, 11);
		g.text(font, readout, cx - font.width(readout) / 2 + 1, y + 15, WHITE, false);
	}

	private static void speedTape(GuiGraphicsExtractor g, Font font, int x, int cy, int half, double speed, ShipEntity ship, boolean space) {
		// M10: no speed caps. The tape's full scale is the current mode's ceiling (SCM or NAV); the caret is the pilot's
		// speed limit (mouse wheel), which the flight computer holds in coupled flight.
		ShipPhysics physics = ShipPhysics.get();
		boolean nav = com.starwake.client.ship.FlightControls.nav();
		boolean decoupled = ship.isDecoupled();
		double ceiling = nav ? physics.navSpeed(space) : physics.scmSpeed();
		double limit = Math.min(com.starwake.client.ship.FlightControls.speedLimit(), ceiling);
		int top = cy - half, bottom = cy + half;
		tape(g, x, top, bottom, Math.min(1, speed / ceiling), speed > limit * 1.02 && !decoupled ? WARN : HOLO);
		int caret = bottom - (int) Math.round((bottom - top) * Math.min(1, limit / ceiling));
		g.fill(x - 7, caret, x + 2, caret + 1, decoupled ? HOLO_DIM : WHITE);
		g.fill(x - 9, caret - 2, x - 7, caret + 3, decoupled ? HOLO_DIM : WHITE);
		String value = String.format("%.0f m/s", speed);
		int boxW = font.width(value) + 8;
		box(g, x - 8 - boxW, cy - 6, boxW, 12);
		g.text(font, value, x - 4 - boxW, cy - 4, WHITE, false);
		String mode = (nav ? "NAV " : "SCM ") + (decoupled ? "--" : String.format("%.0f", limit));
		rightText(g, font, mode, x + 2, top - 11, nav ? GOOD : HOLO);
		rightText(g, font, decoupled ? "DCPL" : "CPLD", x + 2, bottom + 4, decoupled ? WARN : HOLO);
		if (Minecraft.getInstance().options.keyShift.isDown()) rightText(g, font, "BOOST", x + 2, bottom + 14, WARN);
	}

	private static void altitudeTape(GuiGraphicsExtractor g, Font font, int x, int cy, int half, ShipEntity ship, double climb, boolean space) {
		int top = cy - half, bottom = cy + half;
		String value, label;
		double fraction;
		if (space) {
			var nearest = SolarSystem.nearest(ship.level(), ship.position());
			double range = nearest.map(b -> ship.position().distanceTo(b.body().position()) - b.body().entryRadius()).orElse(0.0);
			value = SolarSystem.formatDistance(Math.max(0, range));
			label = nearest.map(b -> b.displayName().getString()).orElse("--");
			fraction = Mth.clamp(Math.log10(Math.max(1, range)) / 7.0, 0, 1);
		} else {
			double alt = ship.getY();
			value = String.format("%.0f", alt);
			label = "ALT";
			int min = ship.level().getMinY(), maxY = ship.level().getMaxY() + SpaceTravel.EXIT_MARGIN;
			fraction = (alt - min) / (maxY - min);
		}
		if (label.length() > 10) label = label.substring(0, 10);
		tape(g, x, top, bottom, fraction, HOLO);
		int boxW = font.width(value) + 8;
		box(g, x + 8, cy - 6, boxW, 12);
		g.text(font, value, x + 12, cy - 4, WHITE, false);
		g.text(font, label, x - 2, top - 11, HOLO, false);
		String vs = String.format("VS %s%.0f", climb >= 0 ? "+" : "", climb);
		int color = Math.abs(climb) < 0.5 ? HOLO_DIM : climb > 0 ? GOOD : WARN;
		g.text(font, vs, x - 2, bottom + 4, color, false);
		// Landing aid: height above ground when it's close.
		if (!space) {
			double agl = heightAboveGround(ship);
			if (agl >= 0 && agl < 64) {
				g.text(font, String.format("AGL %.1f", agl), x - 2, bottom + 14, agl < 4 ? WHITE : HOLO, false);
			}
		}
	}

	/** Text whose right edge is at {@code right}. */
	private static void rightText(GuiGraphicsExtractor g, Font font, String text, int right, int y, int color) {
		g.text(font, text, right - font.width(text), y, color, false);
	}

	/** Distance from the ship's lowest point to the ground below it, or -1 if out of range. */
	public static double heightAboveGround(ShipEntity ship) {
		var box = ship.getBoundingBox();
		Vec3 from = new Vec3(ship.getX(), box.minY, ship.getZ());
		var hit = ship.level().clip(new net.minecraft.world.level.ClipContext(from, from.add(0, -96, 0),
				net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.ANY,
				net.minecraft.world.phys.shapes.CollisionContext.empty()));
		return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? -1 : from.y - hit.getLocation().y;
	}

	/** A vertical gauge: frame, fill from the bottom, ticks. */
	private static void tape(GuiGraphicsExtractor g, int x, int top, int bottom, double fraction, int color) {
		g.fill(x, top, x + 1, bottom, HOLO_FAINT);
		for (int i = 0; i <= 10; i++) {
			int y = bottom - (bottom - top) * i / 10;
			g.fill(x - (i % 5 == 0 ? 5 : 3), y, x, y + 1, HOLO_DIM);
		}
		int fill = (int) ((bottom - top) * Mth.clamp(fraction, 0, 1));
		g.fill(x + 1, bottom - fill, x + 3, bottom, color);
	}

	// --- systems --------------------------------------------------------------------------------

	/**
	 * Two rows along the bottom. Row 1: fuel and shield bars side by side. Row 2: the power
	 * split as one segmented bar, then status items laid out left to right by their measured
	 * widths, so nothing can overlap whatever the GUI scale.
	 */
	private static void systemsStrip(GuiGraphicsExtractor g, Font font, int cx, int y, int width, ShipEntity ship, boolean space) {
		int x = cx - width / 2;
		g.fill(x - 4, y - 4, x + width + 4, y + 21, PANEL);
		corners(g, x - 4, y - 4, width + 8, 25, HOLO_DIM);
		int half = (width - 8) / 2;
		bar(g, font, x, y, half - 52, "FUEL", ship.fuel(), ship.fuelCapacity(), ship.fuel() < ship.fuelCapacity() * 0.15 ? ALERT : WARN);
		bar(g, font, x + half + 8, y, half - 52, "SHLD", ship.shield(), ship.shieldMax(), 0xFF6EA8FF);

		int row = y + 11;
		// M10: pips per subsystem as one segmented bar (ENG cyan, SHD blue, WPN red, CLR green, QT violet), then heat.
		g.text(font, "PWR", x, row, HOLO_DIM, false);
		int px = x + 26, pw = 48;
		int[] pips = ship.pips();
		int[] pipColors = {HOLO, 0xFF6EA8FF, ALERT, GOOD, 0xFFC27BFF};
		float seg = pw / (float) com.starwake.ship.systems.ShipSystems.PIPS;
		int k = 0;
		g.fill(px, row + 1, px + pw, row + 7, HOLO_FAINT);
		for (int i = 0; i < pips.length; i++) {
			for (int n = 0; n < pips[i]; n++, k++) {
				int x0 = px + Math.round(k * seg), x1 = px + Math.round((k + 1) * seg) - 1;
				g.fill(x0, row + 1, Math.max(x0 + 1, x1), row + 7, pipColors[i]);
			}
		}

		java.util.List<String> items = new java.util.ArrayList<>();
		java.util.List<Integer> colors = new java.util.ArrayList<>();
		items.add("ENG " + Math.round(ship.engineFactor() * 100) + "%");
		colors.add(ship.engineFactor() < 0.75 ? WARN : HOLO);
		float heat = ship.heatLevel();
		items.add("HEAT " + Math.round(heat * 100) + "%");
		colors.add(heat >= 1 ? ALERT : heat > 0.75f ? WARN : HOLO_DIM);
		if (ship.gearCount() > 0) {
			items.add(ship.isGearDown() ? "GEAR DN" : "GEAR UP");
			colors.add(ship.isGearDown() ? GOOD : HOLO_DIM);
		}
		if (space && ship.isQuantumTravelling()) {
			items.add("QUANTUM");
			colors.add(0xFFC27BFF);
		}
		int ix = px + pw + 10;
		for (int i = 0; i < items.size(); i++) {
			int iw = font.width(items.get(i));
			if (ix + iw > x + width) break; // out of room: drop the rest rather than overlap
			g.text(font, items.get(i), ix, row, colors.get(i), false);
			ix += iw + 10;
		}
	}

	private static void bar(GuiGraphicsExtractor g, Font font, int x, int y, int width, String label, double value, double max, int color) {
		g.text(font, label, x, y, HOLO_DIM, false);
		int bx = x + 26;
		width = Math.max(16, width);
		g.fill(bx, y + 1, bx + width, y + 7, HOLO_FAINT);
		if (max > 0) g.fill(bx, y + 1, bx + (int) (width * Mth.clamp(value / max, 0, 1)), y + 7, color);
		String text = max > 0 ? String.format("%.0f", value) : "--";
		g.text(font, text, bx + width + 3, y, WHITE, false);
	}

	/** Active warnings, side by side in one centred row under the heading tape. */
	private static void alerts(GuiGraphicsExtractor g, Font font, int cx, int y, ShipEntity ship) {
		boolean blink = (System.currentTimeMillis() / 400) % 2 == 0;
		java.util.List<Component> texts = new java.util.ArrayList<>();
		java.util.List<Integer> colors = new java.util.ArrayList<>();
		if (ship.isDecoupled()) {
			texts.add(Component.translatable("hud.starwake.decoupled"));
			colors.add(WARN);
		}
		if (ship.fuelCapacity() > 0 && ship.fuel() < ship.fuelCapacity() * 0.15) {
			texts.add(Component.translatable("hud.starwake.low_fuel"));
			colors.add(blink ? ALERT : WARN);
		}
		if (ship.gearCount() > 0 && !ship.isGearDown() && !SpaceTravel.isSpace(ship.level())) {
			double agl = heightAboveGround(ship);
			if (agl >= 0 && agl < 15 && ship.recentVelocity().y < -0.02) {
				texts.add(Component.translatable("hud.starwake.gear_up"));
				colors.add(blink ? ALERT : WARN);
			}
		}
		if (ship.heatLevel() >= 1) {
			texts.add(Component.translatable("hud.starwake.overheat"));
			colors.add(ALERT);
		}
		if (ship.skinTemperature() > 0 && ship.skinTemperature() > ship.hullHeatLimit() * 0.5f) {
			// Re-entry (M8e-1): skin temperature against the weakest block on the leading face (from half the limit,
			// M9 pass 2: a mild warm-up isn't worth an alert).
			float skin = ship.skinTemperature(), limit = ship.hullHeatLimit();
			boolean over = limit > 0 && skin > limit;
			texts.add(Component.translatable(over ? "hud.starwake.hull_burning" : "hud.starwake.hull_temp", Math.round(skin), Math.round(limit)));
			colors.add(over ? (blink ? ALERT : WARN) : skin > limit * 0.8f ? WARN : 0xFFE6F2FF);
		}
		if (ship.shieldMax() > 0 && ship.shield() < 1) {
			texts.add(Component.translatable("hud.starwake.shields_down"));
			colors.add(blink ? ALERT : WARN);
		}
		if (texts.isEmpty()) return;
		int gap = 8, total = -gap;
		for (Component t : texts) total += font.width(t) + 8 + gap;
		int x = cx - total / 2;
		for (int i = 0; i < texts.size(); i++) {
			int tw = font.width(texts.get(i));
			g.fill(x, y - 2, x + tw + 8, y + 9, 0x70100808);
			g.outline(x, y - 2, tw + 8, 11, (colors.get(i) & 0x00FFFFFF) | 0x80000000);
			g.text(font, texts.get(i), x + 4, y, colors.get(i), false);
			x += tw + 8 + gap;
		}
	}

	/** Compact status for crew and people walking aboard. */
	private static void crewCard(GuiGraphicsExtractor g, Font font, ShipEntity ship, boolean seated) {
		int x = 8, y = 8, width = 120;
		g.fill(x - 4, y - 4, x + width + 4, y + 34, PANEL);
		corners(g, x - 4, y - 4, width + 8, 38, HOLO_DIM);
		g.text(font, seated ? "CREW" : "ABOARD", x, y, HOLO, false);
		String speed = String.format("%.0f m/s", ship.recentVelocity().length() * 20);
		g.text(font, speed, x + width - font.width(speed), y, WHITE, false);
		bar(g, font, x, y + 11, 60, "FUEL", ship.fuel(), ship.fuelCapacity(), WARN);
		bar(g, font, x, y + 21, 60, "SHLD", ship.shield(), ship.shieldMax(), 0xFF6EA8FF);
	}

	// --- shared bits ----------------------------------------------------------------------------

	public static void box(GuiGraphicsExtractor g, int x, int y, int width, int height) {
		g.fill(x, y, x + width, y + height, PANEL);
		g.outline(x, y, width, height, HOLO_DIM);
	}

	/** SC-style corner brackets around a rectangle. */
	public static void corners(GuiGraphicsExtractor g, int x, int y, int width, int height, int color) {
		int l = 5;
		g.fill(x, y, x + l, y + 1, color);
		g.fill(x, y, x + 1, y + l, color);
		g.fill(x + width - l, y, x + width, y + 1, color);
		g.fill(x + width - 1, y, x + width, y + l, color);
		g.fill(x, y + height - 1, x + l, y + height, color);
		g.fill(x, y + height - l, x + 1, y + height, color);
		g.fill(x + width - l, y + height - 1, x + width, y + height, color);
		g.fill(x + width - 1, y + height - l, x + width, y + height, color);
	}
}
