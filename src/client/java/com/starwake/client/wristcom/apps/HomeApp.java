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
		double gravity = Planets.forDimension(minecraft.level.registryAccess(), minecraft.level.dimension())
				.map(p -> p.planet().gravity()).orElse(1.0);
		var here = Planets.forDimension(minecraft.level.registryAccess(), minecraft.level.dimension());
		Component type = here.flatMap(p -> p.planet().archetype()).orElse(null);
		g.text(font, Component.translatable("wristcom.home.gravity", String.format("%.2f", gravity))
				.append(type == null ? Component.empty() : Component.literal("   ").append(Component.translatable("wristcom.home.type", type))),
				x + 4, y + 30, WristcomScreen.DIM, false);
		var hazards = here.map(p -> p.planet().hazards()).orElse(com.starwake.planet.Hazards.EARTHLIKE);
		Component air = !hazards.breathable() ? Component.translatable("hud.starwake.env.no_air")
				: hazards.toxic() ? Component.translatable("hud.starwake.env.toxic", Math.round(hazards.toxicity() * 100))
				: Component.translatable("hud.starwake.env.breathable");
		boolean sheltered = hazards.any() && com.starwake.planet.PlanetHazards.shelter(minecraft.player, hazards)
				== com.starwake.planet.PlanetHazards.Shelter.CABIN;
		Component env = Component.translatable("wristcom.home.environment", air, Math.round(hazards.temperature()));
		if (sheltered) env = env.copy().append("  ").append(Component.translatable("hud.starwake.env.cabin"));
		g.text(font, env, x + 4, y + 54, hazards.any() && !sheltered ? 0xFFFFB347 : WristcomScreen.DIM, false);

		ShipEntity ship = minecraft.player.getVehicle() instanceof ShipEntity s ? s : ShipLocalMotion.activeShip();
		Component shipLine = ship == null ? Component.translatable("wristcom.home.no_ship")
				: Component.translatable(FlightHud.pilotedShip() == ship ? "wristcom.home.piloting" : "wristcom.home.aboard",
						ship.fuel(), ship.fuelCapacity(), Math.round(ship.shield()));
		g.text(font, shipLine, x + 4, y + 44, ship == null ? WristcomScreen.DIM : WristcomScreen.TEXT, false);

		int top = y + 70;
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
