package com.starwake.client.wristcom.apps;

import com.starwake.client.hud.FlightHud;
import com.starwake.client.wristcom.WristcomApp;
import com.starwake.client.wristcom.WristcomScreen;
import com.starwake.galaxy.SolarSystem;
import com.starwake.network.ShipConsoleActionPayload;
import com.starwake.ship.ShipEntity;
import com.starwake.ship.SpaceTravel;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Starmap: a top-down map of the system (star, orbits, planets, you), distances, and "plot
 * route" buttons that set the quantum target when you're piloting in space. Milestone 6 turns
 * this into the galaxy map.
 */
public class StarmapApp implements WristcomApp {
	/** Destination list row height: name over distance, with a QT button beside them. */
	private static final int ROW = 22;

	@Override public Component title() { return Component.translatable("wristcom.app.starmap"); }
	@Override public String glyph() { return "M"; }
	@Override public int accent() { return 0xFFC27BFF; }

	private static List<SolarSystem.Entry> bodies() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.level == null ? List.of() : SolarSystem.bodies(minecraft.level);
	}

	/** The player's position in the system: in space, where they are; on a planet, that planet. */
	private static Vec3 here() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.player == null) return Vec3.ZERO;
		if (SpaceTravel.isSpace(minecraft.level)) return minecraft.player.position();
		return SolarSystem.forDimension(minecraft.level.registryAccess(), minecraft.level.dimension())
				.map(b -> b.body().position()).orElse(Vec3.ZERO);
	}

	@Override
	public void init(WristcomScreen screen, int x, int y, int width, int height) {
		Minecraft minecraft = Minecraft.getInstance();
		ShipEntity ship = FlightHud.pilotedShip();
		boolean canPlot = ship != null && minecraft.level != null && SpaceTravel.isSpace(minecraft.level);
		int listX = x + height + 6, row = 0;
		// The galaxy map (M6), from the system map's corner.
		screen.add(Button.builder(Component.translatable("wristcom.starmap.galaxy"), b ->
				Minecraft.getInstance().gui.setScreen(new com.starwake.client.galaxy.GalaxyMapScreen()))
				.bounds(x + height - 4 - 50, y + 4, 50, 14).build());
		for (SolarSystem.Entry body : bodies()) {
			if (!body.enterable()) continue;
			if (16 + (row + 1) * ROW > height) break; // no room for more rows
			Button button = Button.builder(Component.literal("QT"),
					b -> ClientPlayNetworking.send(new ShipConsoleActionPayload(BlockPos.ZERO, ship.getId(),
							ShipConsoleActionPayload.Action.SET_COURSE, body.key().identifier())))
					.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("wristcom.starmap.plot", body.displayName())))
					.bounds(x + width - 28, y + 17 + row * ROW, 26, 16).build();
			button.active = canPlot;
			screen.add(button);
			row++;
		}
	}

	@Override
	public void render(WristcomScreen screen, GuiGraphicsExtractor g, int x, int y, int width, int height, int mouseX, int mouseY, float partial) {
		var font = screen.font();
		List<SolarSystem.Entry> bodies = bodies();
		int size = height - 4;
		Vec3 here = here();
		// Fit the map to the bodies (and you): centre on their bounding box.
		double minX = here.x, maxX = here.x, minZ = here.z, maxZ = here.z;
		for (SolarSystem.Entry b : bodies) {
			Vec3 p = b.body().position();
			minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
			minZ = Math.min(minZ, p.z); maxZ = Math.max(maxZ, p.z);
		}
		double extent = Math.max(1, Math.max(maxX - minX, maxZ - minZ)) * 1.25;
		double scale = (size - 12) / extent;
		int cx = x + size / 2 - (int) Math.round((minX + maxX) / 2 * scale);
		int cy = y + size / 2 - (int) Math.round((minZ + maxZ) / 2 * scale);
		g.enableScissor(x, y, x + size, y + size);

		g.fill(x, y, x + size, y + size, 0x40000814);
		g.outline(x, y, size, size, WristcomScreen.FAINT);
		for (SolarSystem.Entry b : bodies) {
			Vec3 p = b.body().position();
			double orbit = Math.hypot(p.x, p.z) * scale;
			if (!b.body().star() && orbit > 2) circle(g, cx, cy, orbit, WristcomScreen.FAINT);
		}
		// Route line from you to each destination (dim) and the bodies themselves.
		int hx = cx + (int) Math.round(here.x * scale), hz = cy + (int) Math.round(here.z * scale);
		// Labels go above each marker (the star's below), moved down a row at a time if they'd
		// overlap one already drawn, so close neighbours stay readable.
		List<int[]> placed = new java.util.ArrayList<>();
		for (SolarSystem.Entry b : bodies) {
			Vec3 p = b.body().position();
			int px = cx + (int) Math.round(p.x * scale), pz = cy + (int) Math.round(p.z * scale);
			int r = b.body().star() ? 5 : 3;
			g.fill(px - r, pz - r, px + r + 1, pz + r + 1, b.body().star() ? 0xFFFFD27A : b.body().color() | 0xFF000000);
			int w = font.width(b.displayName());
			int ly = b.body().star() ? pz + r + 3 : pz - r - 11;
			for (int tries = 0; tries < 6 && overlaps(placed, px - w / 2, ly, w); tries++) ly += 10;
			placed.add(new int[] {px - w / 2 - 2, ly - 1, px + w / 2 + 2, ly + 9});
			g.centeredText(font, b.displayName(), px, ly, b.body().star() ? 0xFFFFD27A : WristcomScreen.TEXT);
		}
		// You: a blinking diamond.
		if ((System.currentTimeMillis() / 500) % 2 == 0) {
			g.fill(hx - 1, hz - 3, hx + 2, hz + 4, 0xFF7CFFB2);
			g.fill(hx - 3, hz - 1, hx + 4, hz + 2, 0xFF7CFFB2);
		}
		g.disableScissor();
		g.text(font, Component.translatable("wristcom.starmap.you"), x + 4, y + size - 12, 0xFF7CFFB2, false);

		// Destination list with distances.
		int listX = x + size + 6, row = 0;
		g.text(font, Component.translatable("wristcom.starmap.destinations"), listX, y + 4, accent(), false);
		for (SolarSystem.Entry b : bodies) {
			if (!b.enterable()) continue;
			if (16 + (row + 1) * ROW > height) break;
			int ry = y + 19 + row * ROW;
			double distance = Math.max(0, here.distanceTo(b.body().position()) - b.body().entryRadius());
			int room = x + width - 32 - listX;
			g.text(font, font.plainSubstrByWidth(b.displayName().getString(), room), listX, ry, WristcomScreen.TEXT, false);
			g.text(font, SolarSystem.formatDistance(distance), listX, ry + 10, WristcomScreen.DIM, false);
			row++;
		}
		Minecraft minecraft = Minecraft.getInstance();
		boolean canPlot = FlightHud.pilotedShip() != null && minecraft.level != null && SpaceTravel.isSpace(minecraft.level);
		int hintTop = y + 18 + row * ROW + 2;
		if (!canPlot) {
			var lines = font.split(Component.translatable("wristcom.starmap.plot_hint"), x + width - listX);
			if (hintTop + lines.size() * 10 <= y + height) {
				for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), listX, hintTop + i * 10, WristcomScreen.DIM, false);
			}
		}
	}

	private static boolean overlaps(List<int[]> placed, int x, int y, int w) {
		for (int[] r : placed) {
			if (x < r[2] && x + w > r[0] && y < r[3] && y + 9 > r[1]) return true;
		}
		return false;
	}

	private static void circle(GuiGraphicsExtractor g, int cx, int cy, double radius, int color) {
		int steps = (int) Math.max(24, radius * 2);
		for (int i = 0; i < steps; i++) {
			double a = 2 * Math.PI * i / steps;
			int px = cx + (int) Math.round(Math.cos(a) * radius), py = cy + (int) Math.round(Math.sin(a) * radius);
			g.fill(px, py, px + 1, py + 1, color);
		}
	}
}
