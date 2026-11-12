package com.starwake.client.hud;

import com.starwake.StarwakeMod;
import com.starwake.client.space.SpaceRenderer;
import com.starwake.galaxy.SolarSystem;
import com.starwake.ship.ShipEntity;
import com.starwake.ship.SpaceTravel;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Space HUD (3g), Star Citizen style:
 * - a marker on every planet and the star, with name and distance (km);
 * - in quantum mode: the selected target highlighted, a nose reticle to line up on it, and a
 *   quantum panel (spool, alignment, obstruction, fuel, "hold B");
 * - in quantum travel: target, distance left and ETA, with a blue tunnel tint.
 */
public final class SpaceHud {
	private static final int TEXT = 0xFFE6F2FF, DIM = 0xFF7F95AA, CYAN = 0xFF55D8FF, QT = 0xFFC27BFF,
			WARN = 0xFFFFB347, BAD = 0xFFFF6A4D, GOOD = 0xFF59E38C, BG = 0x90081018, JUMP = 0xFFFFC870;

	private SpaceHud() {}

	public static void init() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, StarwakeMod.id("space_hud"), SpaceHud::render);
	}

	private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null || !SpaceTravel.isSpace(minecraft.level)) return;
		ShipEntity ship = SpaceRenderer.localShip();
		boolean travelling = ship != null && ship.isQuantumTravelling();
		int w = graphics.guiWidth(), h = graphics.guiHeight();
		Font font = minecraft.font;
		if (ship != null && ship.isJumping()) {
			// Jump tunnel (M6): a white-gold wash, brighter at the edges.
			graphics.fill(0, 0, w, h, 0x30FFB347);
			for (int i = 0; i < 8; i++) {
				int inset = i * 5;
				graphics.fill(inset, inset, w - inset, inset + 5, 0x14FFE0A0);
				graphics.fill(inset, h - inset - 5, w - inset, h - inset, 0x14FFE0A0);
			}
		}

		if (travelling) {
			// Quantum tunnel: a blue wash, darker at the edges.
			graphics.fill(0, 0, w, h, 0x182A6CFF);
			for (int i = 0; i < 6; i++) {
				int inset = i * 6;
				graphics.fill(inset, inset, w - inset, inset + 6, 0x103060FF);
				graphics.fill(inset, h - inset - 6, w - inset, h - inset, 0x103060FF);
			}
		}

		Camera camera = minecraft.gameRenderer.mainCamera();
		float partial = deltaTracker.getGameTimeDeltaPartialTick(true);
		Vec3 eye = SpaceRenderer.systemPosition(camera.position(), partial);
		Matrix4f mvp = camera.getViewRotationProjectionMatrix(new Matrix4f());
		Vector3f forward = new Vector3f(camera.forwardVector());
		String target = ship == null ? "" : ship.quantumTargetId();
		boolean quantumMode = ship != null && (ship.quantumState() & ShipEntity.QT_MODE) != 0;

		// Body markers. Labels that would overlap one already drawn step down until clear.
		java.util.List<int[]> placed = new java.util.ArrayList<>();
		for (SolarSystem.Entry body : SolarSystem.bodies(minecraft.level)) {
			if (ship != null && ship.isJumping()) break; // leaving this system: no markers in the tunnel
			Vec3 rel = body.body().position().subtract(eye);
			double distance = rel.length();
			int[] screen = HudProjection.project(mvp, forward, rel, w, h);
			if (screen == null) continue;
			boolean selected = body.key().identifier().toString().equals(target);
			int color = selected ? QT : body.body().star() ? WARN : CYAN;
			int x = screen[0], y = screen[1], s = selected ? 7 : 5;
			bracket(graphics, x, y, s, color);
			String label = body.displayName().getString();
			String range = SolarSystem.formatDistance(Math.max(0, distance - body.body().radius()));
			// Labels go under the marker, or above it when that would run into the systems strip.
			boolean above = y + s + 22 > h - 34;
			int ly = above ? y - s - 22 : y + s + 3;
			int lw = Math.max(font.width(label), font.width(range));
			for (int tries = 0; tries < 5 && overlaps(placed, x - lw / 2, ly, lw, 20); tries++) ly += above ? -21 : 21;
			placed.add(new int[] {x - lw / 2 - 2, ly - 1, x + lw / 2 + 2, ly + 20});
			graphics.text(font, label, x - font.width(label) / 2, ly, color);
			graphics.text(font, range, x - font.width(range) / 2, ly + 10, DIM);
			if (selected) graphics.text(font, "QT", x - font.width("QT") / 2, above ? y + s + 3 : y - s - 11, QT);
		}

		if (ship == null) return;
		if (quantumMode) {
			// Nose reticle: put this on the target marker to align.
			Vec3 nose = ship.worldDirection(ship.forward());
			int[] n = HudProjection.project(mvp, forward, nose.scale(100), w, h);
			if (n != null) {
				int c = (ship.quantumState() & ShipEntity.QT_ALIGNED) != 0 ? GOOD : TEXT;
				graphics.fill(n[0] - 9, n[1], n[0] - 3, n[1] + 1, c);
				graphics.fill(n[0] + 4, n[1], n[0] + 10, n[1] + 1, c);
				graphics.fill(n[0], n[1] - 9, n[0] + 1, n[1] - 3, c);
				graphics.fill(n[0], n[1] + 4, n[0] + 1, n[1] + 10, c);
			}
		}
		if (quantumMode || travelling) quantumPanel(graphics, font, ship, eye, w);
		boolean jumpMode = (ship.jumpState() & (ShipEntity.JD_MODE | ShipEntity.JD_TRAVEL)) != 0;
		if (jumpMode) {
			jumpTargetMarker(graphics, font, ship, mvp, forward, w, h);
			jumpPanel(graphics, font, ship, quantumMode || travelling);
		}
	}

	/** Quantum drive panel (top-left MFD). */
	private static void quantumPanel(GuiGraphicsExtractor graphics, Font font, ShipEntity ship, Vec3 eye, int w) {
		int state = ship.quantumState();
		// Top-left MFD; for crew it sits under their status card.
		int width = 150, x = 8, y = FlightHud.pilotedShip() == ship ? 8 : 56;
		graphics.fill(x - 4, y - 4, x + width + 4, y + 44, FlightHud.PANEL);
		FlightHud.corners(graphics, x - 4, y - 4, width + 8, 48, QT);

		SolarSystem.Entry target = null;
		for (SolarSystem.Entry e : SolarSystem.bodies(ship.level())) {
			if (e.key().identifier().toString().equals(ship.quantumTargetId())) target = e;
		}
		String name = target == null ? "--" : target.displayName().getString();

		if ((state & ShipEntity.QT_TRAVEL) != 0) {
			graphics.text(font, Component.translatable("hud.starwake.qt.travel", name), x, y, QT);
			graphics.text(font, "ETA " + SolarSystem.formatTime(ship.quantumInfo()), x, y + 11, TEXT);
			if (target != null) {
				double left = Math.max(0, eye.distanceTo(target.body().position()) - target.body().entryRadius());
				String range = SolarSystem.formatDistance(left);
				graphics.text(font, range, x + width - font.width(range), y + 11, TEXT);
			}
			graphics.text(font, Component.translatable("hud.starwake.qt.committed"), x, y + 22, DIM);
			return;
		}

		graphics.text(font, Component.translatable("hud.starwake.qt.mode", name), x, y, QT);
		float spool = Math.max(0, ship.quantumProgress());
		graphics.text(font, "SPOOL", x, y + 11, DIM);
		graphics.fill(x + 34, y + 12, x + width, y + 18, 0x60FFFFFF);
		graphics.fill(x + 34, y + 12, x + 34 + (int) ((width - 34) * spool), y + 18, spool >= 1 ? GOOD : QT);
		if (target != null) {
			graphics.text(font, "FUSION " + ship.quantumInfo() + " / " + ship.fusion(), x, y + 22,
					(state & ShipEntity.QT_NO_FUEL) != 0 ? BAD : TEXT);
		}

		Component status;
		int color;
		if ((state & ShipEntity.QT_NO_TARGET) != 0) {
			status = Component.translatable("hud.starwake.qt.no_target");
			color = DIM;
		} else if ((state & ShipEntity.QT_TOO_CLOSE) != 0) {
			status = Component.translatable("hud.starwake.qt.too_close");
			color = WARN;
		} else if ((state & ShipEntity.QT_OBSTRUCTED) != 0) {
			status = Component.translatable("hud.starwake.qt.obstructed");
			color = BAD;
		} else if ((state & ShipEntity.QT_NO_FUEL) != 0) {
			status = Component.translatable("hud.starwake.qt.no_fuel");
			color = BAD;
		} else if ((state & ShipEntity.QT_SPOOLED) == 0) {
			status = Component.translatable("hud.starwake.qt.spooling");
			color = QT;
		} else if ((state & ShipEntity.QT_ALIGNED) == 0) {
			status = Component.translatable("hud.starwake.qt.align");
			color = WARN;
		} else {
			status = Component.translatable("hud.starwake.qt.ready");
			color = GOOD;
		}
		graphics.text(font, status, x, y + 33, color);
	}

	private static boolean overlaps(java.util.List<int[]> placed, int x, int y, int w, int h) {
		for (int[] r : placed) {
			if (x < r[2] && x + w > r[0] && y < r[3] && y + h > r[1]) return true;
		}
		return false;
	}

	/** The jump target star, in its true direction in the sky, with its distance in light-years. */
	private static void jumpTargetMarker(GuiGraphicsExtractor graphics, Font font, ShipEntity ship, Matrix4f mvp, Vector3f forward, int w, int h) {
		var here = com.starwake.client.galaxy.GalaxyClient.here();
		var there = com.starwake.galaxy.Galaxy.star(ship.jumpTargetId()).orElse(null);
		if (here == null || there == null || ship.isJumping()) return;
		Vec3 dir = there.position().subtract(here.position()).normalize();
		int[] p = HudProjection.project(mvp, forward, dir.scale(1000), w, h);
		if (p == null) return;
		int color = (ship.jumpState() & ShipEntity.JD_ALIGNED) != 0 ? GOOD : JUMP;
		bracket(graphics, p[0], p[1], 9, color);
		String label = there.name() + "  " + String.format("%.1f ly", here.distanceTo(there));
		graphics.text(font, label, p[0] - font.width(label) / 2, p[1] + 12, color);
		// Nose reticle, as for quantum alignment.
		Vec3 nose = ship.worldDirection(ship.forward());
		int[] n = HudProjection.project(mvp, forward, nose.scale(100), w, h);
		if (n != null) {
			graphics.fill(n[0] - 9, n[1], n[0] - 3, n[1] + 1, color);
			graphics.fill(n[0] + 4, n[1], n[0] + 10, n[1] + 1, color);
			graphics.fill(n[0], n[1] - 9, n[0] + 1, n[1] - 3, color);
			graphics.fill(n[0], n[1] + 4, n[0] + 1, n[1] + 10, color);
		}
	}

	/** Jump drive panel (top-left MFD, below the quantum panel if both show). */
	private static void jumpPanel(GuiGraphicsExtractor graphics, Font font, ShipEntity ship, boolean belowQuantum) {
		int state = ship.jumpState();
		int width = 150, x = 8, y = (FlightHud.pilotedShip() == ship ? 8 : 56) + (belowQuantum ? 56 : 0);
		graphics.fill(x - 4, y - 4, x + width + 4, y + 44, FlightHud.PANEL);
		FlightHud.corners(graphics, x - 4, y - 4, width + 8, 48, JUMP);
		var target = com.starwake.galaxy.Galaxy.star(ship.jumpTargetId()).orElse(null);
		String name = target == null ? "--" : target.name();
		if ((state & ShipEntity.JD_TRAVEL) != 0) {
			graphics.text(font, Component.translatable("hud.starwake.jump.travel", name), x, y, JUMP);
			graphics.text(font, "ETA " + SolarSystem.formatTime(ship.jumpInfo()), x, y + 11, TEXT);
			graphics.text(font, Component.translatable("hud.starwake.jump.tunnel"), x, y + 22, DIM);
			return;
		}
		graphics.text(font, Component.translatable("hud.starwake.jump.mode", name), x, y, JUMP);
		float spool = Math.max(0, ship.jumpProgress());
		graphics.text(font, "SPOOL", x, y + 11, DIM);
		graphics.fill(x + 34, y + 12, x + width, y + 18, 0x60FFFFFF);
		graphics.fill(x + 34, y + 12, x + 34 + (int) ((width - 34) * spool), y + 18, spool >= 1 ? GOOD : JUMP);
		if (target != null) {
			graphics.text(font, "FUSION " + ship.jumpInfo() + " / " + ship.fusion(), x, y + 22, (state & ShipEntity.JD_NO_FUEL) != 0 ? BAD : TEXT);
		}
		Component status;
		int color;
		if ((state & ShipEntity.JD_NO_TARGET) != 0) {
			status = Component.translatable("hud.starwake.jump.no_target");
			color = DIM;
		} else if ((state & ShipEntity.JD_RANGE) != 0) {
			status = Component.translatable("hud.starwake.jump.out_of_range");
			color = BAD;
		} else if ((state & ShipEntity.JD_WELL) != 0) {
			status = Component.translatable("hud.starwake.jump.gravity_well");
			color = WARN;
		} else if ((state & ShipEntity.JD_NO_FUEL) != 0) {
			status = Component.translatable("hud.starwake.jump.no_fuel");
			color = BAD;
		} else if ((state & ShipEntity.JD_SPOOLED) == 0) {
			status = Component.translatable("hud.starwake.jump.spooling");
			color = JUMP;
		} else if ((state & ShipEntity.JD_ALIGNED) == 0) {
			status = Component.translatable("hud.starwake.jump.align");
			color = WARN;
		} else {
			status = Component.translatable("hud.starwake.jump.ready");
			color = GOOD;
		}
		graphics.text(font, status, x, y + 33, color);
	}

	/** Four corner brackets around (x, y). */
	private static void bracket(GuiGraphicsExtractor graphics, int x, int y, int s, int color) {
		int l = Math.max(2, s / 2);
		graphics.fill(x - s, y - s, x - s + l, y - s + 1, color);
		graphics.fill(x - s, y - s, x - s + 1, y - s + l, color);
		graphics.fill(x + s - l + 1, y - s, x + s + 1, y - s + 1, color);
		graphics.fill(x + s, y - s, x + s + 1, y - s + l, color);
		graphics.fill(x - s, y + s, x - s + l, y + s + 1, color);
		graphics.fill(x - s, y + s - l + 1, x - s + 1, y + s + 1, color);
		graphics.fill(x + s - l + 1, y + s, x + s + 1, y + s + 1, color);
		graphics.fill(x + s, y + s - l + 1, x + s + 1, y + s + 1, color);
	}
}
