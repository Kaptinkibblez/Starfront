package com.starwake.client.wristcom.apps;

import com.starwake.client.hud.FlightHud;
import com.starwake.client.ship.ShipLocalMotion;
import com.starwake.client.wristcom.WristcomApp;
import com.starwake.client.wristcom.WristcomScreen;
import com.starwake.client.wristcom.WristcomStatus;
import com.starwake.planet.Planets;
import com.starwake.ship.ShipEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Home: where you are, your ship at a glance, and the flight controls cheat sheet. */
public class HomeApp implements WristcomApp {
	private static final String[][] CONTROLS = {
			{"Mouse", "aim the nose"}, {"W/S A/D", "thrust fwd/back, strafe"}, {"Space / Ctrl", "up / down"},
			{"Z / C", "roll"}, {"Shift", "boost"}, {"Alt", "free look"}, {"V", "coupled / decoupled"},
			{"B tap / hold", "quantum mode / engage"}, {"R", "ship console"}, {"X", "leave seat"}, {"G", "Wristcom"}};

	@Override public Component title() { return Component.translatable("wristcom.app.home"); }
	@Override public String glyph() { return "H"; }
	@Override public int accent() { return WristcomScreen.FRAME; }

	@Override
	public void render(WristcomScreen screen, GuiGraphicsExtractor g, int x, int y, int width, int height, int mouseX, int mouseY, float partial) {
		Minecraft minecraft = Minecraft.getInstance();
		var font = screen.font();
		if (minecraft.player == null || minecraft.level == null) return;
		g.text(font, Component.translatable("wristcom.home.hello", minecraft.player.getName()), x + 4, y + 4, WristcomScreen.TEXT, false);
		g.text(font, Component.translatable("wristcom.home.location", WristcomStatus.location()), x + 4, y + 18, WristcomScreen.DIM, false);
		double gravity = 1.0;
		var registry = minecraft.level.registryAccess().lookup(Planets.REGISTRY_KEY);
		if (registry.isPresent()) {
			for (var planet : registry.get()) {
				if (planet.dimension().equals(minecraft.level.dimension())) gravity = planet.gravity();
			}
		}
		g.text(font, Component.translatable("wristcom.home.gravity", String.format("%.2f", gravity)), x + 4, y + 30, WristcomScreen.DIM, false);

		ShipEntity ship = minecraft.player.getVehicle() instanceof ShipEntity s ? s : ShipLocalMotion.activeShip();
		Component shipLine = ship == null ? Component.translatable("wristcom.home.no_ship")
				: Component.translatable(FlightHud.pilotedShip() == ship ? "wristcom.home.piloting" : "wristcom.home.aboard",
						ship.fuel(), ship.fuelCapacity(), Math.round(ship.shield()));
		g.text(font, shipLine, x + 4, y + 44, ship == null ? WristcomScreen.DIM : WristcomScreen.TEXT, false);

		int top = y + 64;
		g.text(font, Component.translatable("wristcom.home.controls"), x + 4, top, WristcomScreen.FRAME, false);
		// Two columns when there's room, else one.
		int keyWidth = 0;
		for (String[] c : CONTROLS) keyWidth = Math.max(keyWidth, font.width(c[0]));
		int colWidth = keyWidth + 8 + 120;
		int columns = width >= colWidth * 2 ? 2 : 1;
		int perColumn = (CONTROLS.length + columns - 1) / columns;
		for (int i = 0; i < CONTROLS.length; i++) {
			int cx = x + 4 + (i / perColumn) * colWidth, cy = top + 14 + (i % perColumn) * 10;
			g.text(font, CONTROLS[i][0], cx, cy, WristcomScreen.TEXT, false);
			g.text(font, CONTROLS[i][1], cx + keyWidth + 8, cy, WristcomScreen.DIM, false);
		}
	}
}
