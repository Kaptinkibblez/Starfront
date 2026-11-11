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
 *   <li>bottom: systems strip (fuel, shields, power triangle, engines, heat, wing lift);</li>
 *   <li>alerts under the reticle: DECOUPLED, LOW FUEL, OVERHEAT, SHIELDS DOWN.</li>
 * </ul>
 * Crew and people walking aboard get a compact status card instead. While piloting, vanilla's
 * hotbar, hearts, food, armour, XP and crosshair are hidden. Everything shown comes from synced
 * ship data.
 */
public final class FlightHud {
	public static final int HOLO = 0xFF6FE3FF, HOLO_DIM = 0x996FE3FF, HOLO_FAINT = 0x406FE3FF, WHITE = 0xFFE9F6FF,
			WARN = 0xFFFFB347, ALERT = 0xFFFF5A4A, GOOD = 0xFF7CFFB2, PANEL = 0x58061018;

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
		ShipEntity piloted = pilotedShip();
		if (piloted == null) {
			ShipEntity aboard = minecraft.player.getVehicle() instanceof ShipEntity seated ? seated : ShipLocalMotion.activeShip();
			if (aboard != null) crewCard(graphics, minecraft.font, aboard, minecraft.player.isPassenger());
			return;
		}
		Font font = minecraft.font;
		int w = graphics.guiWidth(), h = graphics.guiHeight(), cx = w / 2, cy = h / 2;
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

		pitchLadder(graphics, font, cx, cy, pitch, roll, space);
		reticle(graphics, cx, cy);
		int[] n = HudProjection.project(mvp, look, nose, w, h);
		if (n != null) noseMarker(graphics, n[0], n[1]);
		if (speed > 0.5) {
			int[] v = HudProjection.project(mvp, look, velocity, w, h);
			if (v != null) velocityMarker(graphics, v[0], v[1]);
		}
		headingTape(graphics, font, cx, 6, heading);
		speedTape(graphics, font, cx - 118, cy, speed, piloted, space);
		altitudeTape(graphics, font, cx + 118, cy, piloted, velocity.y, space);
		systemsStrip(graphics, font, cx, h - 30, piloted, space);
		alerts(graphics, font, cx, cy + 34, piloted);
	}

	// --- centre -------------------------------------------------------------------------------

	private static void reticle(GuiGraphicsExtractor g, int cx, int cy) {
		g.fill(cx - 7, cy, cx - 3, cy + 1, HOLO);
		g.fill(cx + 4, cy, cx + 8, cy + 1, HOLO);
		g.fill(cx, cy - 7, cx + 1, cy - 3, HOLO);
		g.fill(cx, cy + 4, cx + 1, cy + 8, HOLO);
		g.fill(cx, cy, cx + 1, cy + 1, WHITE);
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

	/** Horizon and pitch lines every 10 degrees, rotated by roll, scrolled by pitch. */
	private static void pitchLadder(GuiGraphicsExtractor g, Font font, int cx, int cy, double pitch, double roll, boolean space) {
		float pxPerDeg = 2.2f;
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().rotate((float) Math.toRadians(-roll));
		int base = (int) Math.floor(pitch / 10) * 10;
		for (int deg = base - 30; deg <= base + 30; deg += 10) {
			if (deg < -90 || deg > 90) continue;
			int y = Math.round((float) (pitch - deg) * pxPerDeg);
			if (Math.abs(y) > 70) continue;
			int color = deg == 0 ? (space ? HOLO_DIM : HOLO) : HOLO_FAINT;
			int inner = 22, outer = deg == 0 ? 70 : 44;
			g.fill(-outer, y, -inner, y + 1, color);
			g.fill(inner, y, outer, y + 1, color);
			if (deg != 0) {
				// Ticks point toward the horizon, like an aircraft ladder.
				int tick = deg > 0 ? 3 : -3;
				g.fill(-outer, Math.min(y, y + tick), -outer + 1, Math.max(y, y + tick) + 1, color);
				g.fill(outer - 1, Math.min(y, y + tick), outer, Math.max(y, y + tick) + 1, color);
				String label = Integer.toString(Math.abs(deg));
				g.text(font, label, outer + 3, y - 3, color, false);
			}
		}
		g.pose().popMatrix();
	}

	// --- tapes ----------------------------------------------------------------------------------

	private static void headingTape(GuiGraphicsExtractor g, Font font, int cx, int y, double heading) {
		int half = 90;
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

	private static void speedTape(GuiGraphicsExtractor g, Font font, int x, int cy, double speed, ShipEntity ship, boolean space) {
		ShipPhysics physics = ShipPhysics.get();
		double max = (space ? physics.maxSpeedSpace() : physics.maxSpeed()) * 20;
		int top = cy - 50, bottom = cy + 50;
		tape(g, x, top, bottom, speed / max, HOLO);
		g.fill(x - 6, top, x + 1, top + 1, WARN); // top speed
		String value = String.format("%.0f", speed);
		box(g, x - 34, cy - 6, 30, 12);
		g.text(font, value, x - 6 - font.width(value), cy - 4, WHITE, false);
		g.text(font, "m/s", x - 22, cy + 9, HOLO_DIM, false);
		g.text(font, space ? "NAV" : "SCM", x - 22, top - 10, HOLO, false);
		boolean decoupled = ship.isDecoupled();
		g.text(font, decoupled ? "DCPL" : "CPLD", x - 26, bottom + 4, decoupled ? WARN : HOLO, false);
		if (Minecraft.getInstance().options.keyShift.isDown()) g.text(font, "BOOST", x - 30, bottom + 14, WARN, false);
	}

	private static void altitudeTape(GuiGraphicsExtractor g, Font font, int x, int cy, ShipEntity ship, double climb, boolean space) {
		int top = cy - 50, bottom = cy + 50;
		String value, label;
		double fraction;
		if (space) {
			var nearest = SolarSystem.nearest(ship.level().registryAccess(), ship.position());
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
		tape(g, x, top, bottom, fraction, HOLO);
		box(g, x + 4, cy - 6, Math.max(34, font.width(value) + 6), 12);
		g.text(font, value, x + 7, cy - 4, WHITE, false);
		g.text(font, label, x + 5, top - 10, HOLO, false);
		// Vertical speed with a direction arrow.
		String vs = String.format("%s%.0f", climb >= 0 ? "+" : "", climb);
		int color = Math.abs(climb) < 0.5 ? HOLO_DIM : climb > 0 ? GOOD : WARN;
		g.text(font, "VS " + vs, x + 5, bottom + 4, color, false);
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

	private static void systemsStrip(GuiGraphicsExtractor g, Font font, int cx, int y, ShipEntity ship, boolean space) {
		int width = 230, x = cx - width / 2;
		g.fill(x - 4, y - 4, x + width + 4, y + 24, PANEL);
		corners(g, x - 4, y - 4, width + 8, 28, HOLO_DIM);
		bar(g, font, x, y, 70, "FUEL", ship.fuel(), ship.fuelCapacity(), ship.fuel() < ship.fuelCapacity() * 0.15 ? ALERT : WARN);
		bar(g, font, x, y + 11, 70, "SHLD", ship.shield(), ship.shieldMax(), 0xFF6EA8FF);

		// Power triangle as three columns.
		int px = x + 112;
		int[] split = ship.powerPercents();
		String[] names = {"E", "S", "Q"};
		int[] colors = {GOOD, 0xFF6EA8FF, 0xFFC27BFF};
		for (int i = 0; i < 3; i++) {
			int bx = px + i * 12;
			g.fill(bx, y, bx + 6, y + 16, HOLO_FAINT);
			int fill = 16 * split[i] / 100;
			g.fill(bx, y + 16 - fill, bx + 6, y + 16, colors[i]);
			g.text(font, names[i], bx, y + 17, HOLO_DIM, false);
		}

		int sx = x + 158;
		g.text(font, "ENG " + Math.round(ship.engineFactor() * 100) + "%", sx, y, ship.engineFactor() < 0.75 ? WARN : HOLO, false);
		float thermal = ship.thermalFactor();
		g.text(font, thermal < 0.999f ? "HEAT " + Math.round(thermal * 100) + "%" : "TEMP OK", sx, y + 9, thermal < 0.999f ? ALERT : HOLO_DIM, false);
		if (!space) {
			int lift = Math.round(ship.wingLift() * 100);
			g.text(font, "WING " + lift + "%", sx, y + 18, lift > 0 ? HOLO : HOLO_DIM, false);
		} else if (ship.isQuantumTravelling()) {
			g.text(font, "QUANTUM", sx, y + 18, 0xFFC27BFF, false);
		}
	}

	private static void bar(GuiGraphicsExtractor g, Font font, int x, int y, int width, String label, double value, double max, int color) {
		g.text(font, label, x, y, HOLO_DIM, false);
		int bx = x + 26;
		g.fill(bx, y + 1, bx + width, y + 7, HOLO_FAINT);
		if (max > 0) g.fill(bx, y + 1, bx + (int) (width * Mth.clamp(value / max, 0, 1)), y + 7, color);
		String text = max > 0 ? String.format("%.0f", value) : "--";
		g.text(font, text, bx + width + 3, y, WHITE, false);
	}

	private static void alerts(GuiGraphicsExtractor g, Font font, int cx, int y, ShipEntity ship) {
		boolean blink = (System.currentTimeMillis() / 400) % 2 == 0;
		int line = 0;
		if (ship.isDecoupled()) line = alert(g, font, cx, y, line, Component.translatable("hud.starwake.decoupled"), WARN);
		if (ship.fuelCapacity() > 0 && ship.fuel() < ship.fuelCapacity() * 0.15 && blink) {
			line = alert(g, font, cx, y, line, Component.translatable("hud.starwake.low_fuel"), ALERT);
		}
		if (ship.thermalFactor() < 0.999f) line = alert(g, font, cx, y, line, Component.translatable("hud.starwake.overheat"), ALERT);
		if (ship.shieldMax() > 0 && ship.shield() < 1 && blink) {
			alert(g, font, cx, y, line, Component.translatable("hud.starwake.shields_down"), ALERT);
		}
	}

	private static int alert(GuiGraphicsExtractor g, Font font, int cx, int y, int line, Component text, int color) {
		int width = font.width(text);
		int ty = y + line * 12;
		g.fill(cx - width / 2 - 4, ty - 2, cx + width / 2 + 4, ty + 9, 0x70100808);
		g.text(font, text, cx - width / 2, ty, color, false);
		return line + 1;
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
