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
	@Override public Component title() { return Component.translatable("wristcom.app.starmap"); }
	@Override public String glyph() { return "M"; }
	@Override public int accent() { return 0xFFC27BFF; }

	private static List<SolarSystem.Entry> bodies() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.level == null ? List.of() : SolarSystem.bodies(minecraft.level.registryAccess());
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
		for (SolarSystem.Entry body : bodies()) {
			if (!body.enterable()) continue;
			Button button = Button.builder(Component.translatable("wristcom.starmap.plot", body.displayName()),
					b -> ClientPlayNetworking.send(new ShipConsoleActionPayload(BlockPos.ZERO, ship.getId(),
							ShipConsoleActionPayload.Action.SET_COURSE, body.key().identifier())))
					.bounds(listX, y + 30 + row * 46, width - height - 8, 18).build();
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
		for (SolarSystem.Entry b : bodies) {
			Vec3 p = b.body().position();
			int px = cx + (int) Math.round(p.x * scale), pz = cy + (int) Math.round(p.z * scale);
			int r = b.body().star() ? 5 : 3;
			g.fill(px - r, pz - r, px + r + 1, pz + r + 1, b.body().star() ? 0xFFFFD27A : b.body().color() | 0xFF000000);
			// The star's label goes below it, planets' to the right, so neighbours don't collide.
			if (b.body().star()) g.centeredText(font, b.displayName(), px, pz + r + 3, 0xFFFFD27A);
			else g.text(font, b.displayName(), px + r + 3, pz - 4, WristcomScreen.TEXT, false);
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
			double distance = Math.max(0, here.distanceTo(b.body().position()) - b.body().entryRadius());
			g.text(font, b.displayName(), listX, y + 18 + row * 46, WristcomScreen.TEXT, false);
			String d = SolarSystem.formatDistance(distance);
			g.text(font, d, x + width - font.width(d) - 2, y + 18 + row * 46, WristcomScreen.DIM, false);
			row++;
		}
		Minecraft minecraft = Minecraft.getInstance();
		boolean canPlot = FlightHud.pilotedShip() != null && minecraft.level != null && SpaceTravel.isSpace(minecraft.level);
		if (!canPlot) {
			var lines = font.split(Component.translatable("wristcom.starmap.plot_hint"), x + width - listX);
			for (int i = 0; i < lines.size(); i++) {
				g.text(font, lines.get(i), listX, y + height - 10 - (lines.size() - 1 - i) * 10, WristcomScreen.DIM, false);
			}
		}
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
