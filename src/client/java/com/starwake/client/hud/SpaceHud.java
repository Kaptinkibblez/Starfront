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
			WARN = 0xFFFFB347, BAD = 0xFFFF6A4D, GOOD = 0xFF59E38C, BG = 0x90081018;

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

		// Body markers.
		for (SolarSystem.Entry body : SolarSystem.bodies(minecraft.level.registryAccess())) {
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
			graphics.text(font, label, x - font.width(label) / 2, y + s + 3, color);
			graphics.text(font, range, x - font.width(range) / 2, y + s + 13, DIM);
			if (selected) graphics.text(font, "QT", x - font.width("QT") / 2, y - s - 11, QT);
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
	}

	/** Quantum drive panel (top-left MFD). */
	private static void quantumPanel(GuiGraphicsExtractor graphics, Font font, ShipEntity ship, Vec3 eye, int w) {
		int state = ship.quantumState();
		int width = 150, x = 8, y = 8; // top-left, an MFD next to the heading tape
		graphics.fill(x - 4, y - 4, x + width + 4, y + 44, FlightHud.PANEL);
		FlightHud.corners(graphics, x - 4, y - 4, width + 8, 48, QT);

		SolarSystem.Entry target = null;
		for (SolarSystem.Entry e : SolarSystem.bodies(ship.level().registryAccess())) {
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
			graphics.text(font, "FUEL " + ship.quantumInfo() + " / " + ship.fuel(), x, y + 22,
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
