package com.starwake.client.hud;

import com.starwake.StarwakeMod;
import com.starwake.client.ship.ShipLocalMotion;
import com.starwake.planet.Planets;
import com.starwake.ship.ShipEntity;
import com.starwake.ship.SpaceTravel;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The cockpit HUD (Star Citizen-style multi-function display), shown while you're seated in
 * or walking aboard a ship. Top-left panel: speed, altitude/orbit and attitude, then bars
 * for fuel, shields and power (split across engines/shields/quantum), plus heat and quantum
 * spool warnings. Everything it shows comes from synced ship data.
 */
public final class ShipHud {
	private static final int WIDTH = 150;
	private static final int TEXT = 0xFFE6F2FF, DIM = 0xFF7F95AA, CYAN = 0xFF55D8FF, BG = 0x90081018;

	private ShipHud() {}

	public static void init() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, StarwakeMod.id("ship_hud"), ShipHud::render);
	}

	private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) return; // (F1 hides all HUD elements already)
		ShipEntity ship = minecraft.player.getVehicle() instanceof ShipEntity seated ? seated : ShipLocalMotion.activeShip();
		if (ship == null) return;
		boolean piloting = minecraft.player.getVehicle() == ship && ship.pilot() == minecraft.player;
		Font font = minecraft.font;

		int x = 6, y = 8; // top-left: the bottom-left corner belongs to chat
		graphics.fill(x - 3, y - 3, x + WIDTH + 3, y + 104, BG);
		graphics.fill(x - 3, y - 3, x + WIDTH + 3, y - 2, CYAN);

		// Flight data.
		Vec3 nose = ship.worldDirection(ship.forward());
		Vec3 up = ship.worldDirection(Direction.UP);
		Vec3 right = ship.worldDirection(ship.forward().getClockWise());
		int heading = Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(nose.x, -nose.z))), 360);
		int pitch = (int) Math.round(Math.toDegrees(Math.asin(Mth.clamp(nose.y, -1, 1))));
		int roll = (int) Math.round(Math.toDegrees(Math.atan2(-right.y, up.y)));
		double speed = ship.lastMove().length() * 20;
		graphics.text(font, String.format("%5.1f m/s", speed), x, y, TEXT);
		String where = SpaceTravel.isSpace(ship.level())
				? "ORBIT " + orbitName(ship).getString()
				: "ALT " + (int) ship.getY();
		graphics.text(font, where, x + 70, y, TEXT);
		graphics.text(font, String.format("HDG %03d  PIT %+d  ROL %+d", heading, pitch, roll), x, y + 11, DIM);

		// Bars.
		bar(graphics, font, x, y + 26, "FUEL", ship.fuel(), ship.fuelCapacity(), 0xFFFFB347);
		bar(graphics, font, x, y + 40, "SHLD", ship.shield(), ship.shieldMax(), 0xFF6EA8FF);

		// Power triangle split.
		int[] split = ship.powerPercents();
		graphics.text(font, "PWR", x, y + 56, DIM);
		int bx = x + 30, bw = WIDTH - 30;
		int e = bw * split[0] / 100, s = bw * split[1] / 100;
		graphics.fill(bx, y + 56, bx + e, y + 63, 0xFF59E38C);
		graphics.fill(bx + e, y + 56, bx + e + s, y + 63, 0xFF6EA8FF);
		graphics.fill(bx + e + s, y + 56, bx + bw, y + 63, 0xFFC27BFF);
		graphics.text(font, String.format("E%d S%d Q%d   ENG %d%%", split[0], split[1], split[2],
				Math.round(ship.engineFactor() * 100)), x, y + 68, DIM);

		// Warnings and status.
		int line = y + 82;
		if (ship.thermalFactor() < 0.999f) {
			graphics.text(font, String.format("OVERHEAT  plants %d%%", Math.round(ship.thermalFactor() * 100)), x, line, 0xFFFF6A4D);
			line += 10;
		}
		float quantum = ship.quantumProgress();
		if (quantum >= 0) {
			graphics.text(font, Component.translatable("hud.starwake.qt", Math.round(quantum * 100)), x, line, 0xFFC27BFF);
			graphics.fill(x + 50, line + 2, x + 50 + (int) ((WIDTH - 50) * quantum), line + 6, 0xFFC27BFF);
			line += 10;
		}
		if (!piloting) {
			String role = minecraft.player.isPassenger() ? "CREW" : "ABOARD";
			graphics.text(font, role, x + WIDTH - font.width(role), y + 90, CYAN);
		}
	}

	private static void bar(GuiGraphicsExtractor graphics, Font font, int x, int y, String label, double value, double max, int color) {
		graphics.text(font, label, x, y, DIM);
		int bx = x + 30, bw = WIDTH - 30;
		graphics.fill(bx, y, bx + bw, y + 8, 0x60FFFFFF);
		if (max > 0) graphics.fill(bx, y, bx + (int) (bw * Mth.clamp(value / max, 0, 1)), y + 8, color);
		graphics.text(font, max > 0 ? String.format("%.0f", value) : "--", bx + bw - 24, y, TEXT);
	}

	private static Component orbitName(ShipEntity ship) {
		String id = ship.orbitId();
		if (id.isEmpty()) return Component.literal("?");
		var registry = ship.level().registryAccess().lookup(Planets.REGISTRY_KEY);
		if (registry.isPresent()) {
			for (var planet : registry.get()) {
				if (planet.dimension().identifier().equals(Identifier.parse(id))) return planet.name();
			}
		}
		return Component.literal(id);
	}
}
