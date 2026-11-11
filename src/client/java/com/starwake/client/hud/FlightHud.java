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
		int[] n = HudProjection.project(mvp, look, nose, w, h);
		if (n != null) noseMarker(graphics, n[0], n[1]);
		if (speed > 0.5) {
			int[] v = HudProjection.project(mvp, look, velocity, w, h);
			if (v != null) velocityMarker(graphics, v[0], v[1]);
		}
		headingTape(graphics, font, cx, 4, heading, Math.min(80, Math.round(w * 0.18f)));
		speedTape(graphics, font, cx - tapeOffset, cy, tapeHalf, speed, piloted, space);
		altitudeTape(graphics, font, cx + tapeOffset, cy, tapeHalf, piloted, velocity.y, space);
		systemsStrip(graphics, font, cx, h - 26, Math.min(w - 16, 300), piloted, space);
		alerts(graphics, font, cx, 34, piloted);
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
		ShipPhysics physics = ShipPhysics.get();
		double max = (space ? physics.maxSpeedSpace() : physics.maxSpeed()) * 20;
		int top = cy - half, bottom = cy + half;
		tape(g, x, top, bottom, speed / max, HOLO);
		g.fill(x - 6, top, x + 1, top + 1, WARN); // top speed
		String value = String.format("%.0f m/s", speed);
		int boxW = font.width(value) + 8;
		box(g, x - 8 - boxW, cy - 6, boxW, 12);
		g.text(font, value, x - 4 - boxW, cy - 4, WHITE, false);
		rightText(g, font, space ? "NAV" : "SCM", x + 2, top - 11, HOLO);
		boolean decoupled = ship.isDecoupled();
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
		g.text(font, "PWR", x, row, HOLO_DIM, false);
		int px = x + 26, pw = 48;
		int[] split = ship.powerPercents();
		int e = pw * split[0] / 100, sh = pw * split[1] / 100;
		g.fill(px, row + 1, px + e, row + 7, GOOD);
		g.fill(px + e, row + 1, px + e + sh, row + 7, 0xFF6EA8FF);
		g.fill(px + e + sh, row + 1, px + pw, row + 7, 0xFFC27BFF);

		java.util.List<String> items = new java.util.ArrayList<>();
		java.util.List<Integer> colors = new java.util.ArrayList<>();
		items.add("ENG " + Math.round(ship.engineFactor() * 100) + "%");
		colors.add(ship.engineFactor() < 0.75 ? WARN : HOLO);
		float thermal = ship.thermalFactor();
		items.add(thermal < 0.999f ? "HEAT " + Math.round(thermal * 100) + "%" : "TEMP OK");
		colors.add(thermal < 0.999f ? ALERT : HOLO_DIM);
		if (ship.gearCount() > 0) {
			items.add(ship.isGearDown() ? "GEAR DN" : "GEAR UP");
			colors.add(ship.isGearDown() ? GOOD : HOLO_DIM);
		}
		if (!space) {
			int lift = Math.round(ship.wingLift() * 100);
			items.add("WING " + lift + "%");
			colors.add(lift > 0 ? HOLO : HOLO_DIM);
		} else if (ship.isQuantumTravelling()) {
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
		if (ship.thermalFactor() < 0.999f) {
			texts.add(Component.translatable("hud.starwake.overheat"));
			colors.add(ALERT);
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
