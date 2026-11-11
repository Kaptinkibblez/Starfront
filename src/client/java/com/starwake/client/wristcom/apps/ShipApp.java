package com.starwake.client.wristcom.apps;

import com.starwake.client.hud.FlightHud;
import com.starwake.client.screen.PowerSliders;
import com.starwake.client.ship.ShipLocalMotion;
import com.starwake.client.wristcom.WristcomApp;
import com.starwake.client.wristcom.WristcomScreen;
import com.starwake.network.ShipQuantumPayload;
import com.starwake.ship.ShipEntity;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Ship: live systems readout; for the pilot, the power triangle and flight mode. */
public class ShipApp implements WristcomApp {
	@Override public Component title() { return Component.translatable("wristcom.app.ship"); }
	@Override public String glyph() { return "S"; }
	@Override public int accent() { return 0xFF7CFFB2; }

	private static @Nullable ShipEntity ship() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) return null;
		return minecraft.player.getVehicle() instanceof ShipEntity s ? s : ShipLocalMotion.activeShip();
	}

	@Override
	public void init(WristcomScreen screen, int x, int y, int width, int height) {
		ShipEntity ship = ship();
		if (ship == null || FlightHud.pilotedShip() != ship) return;
		new PowerSliders(ship.powerPercents()).create(x + width - 134, y + 20, 130, screen::add);
		screen.add(Button.builder(Component.translatable("wristcom.ship.toggle_mode"),
				b -> ClientPlayNetworking.send(new ShipQuantumPayload(ShipQuantumPayload.ACTION_DECOUPLE)))
				.bounds(x + width - 134, y + 92, 130, 20).build());
	}

	@Override
	public void render(WristcomScreen screen, GuiGraphicsExtractor g, int x, int y, int width, int height, int mouseX, int mouseY, float partial) {
		var font = screen.font();
		ShipEntity ship = ship();
		if (ship == null) {
			g.text(font, Component.translatable("wristcom.ship.none"), x + 4, y + 8, WristcomScreen.DIM, false);
			return;
		}
		boolean pilot = FlightHud.pilotedShip() == ship;
		int line = y + 4;
		String[][] rows = {
				{"Role", pilot ? "Pilot" : "Crew"},
				{"Blocks", ship.structure() == null ? "-" : Integer.toString(ship.structure().entries().size())},
				{"Fuel", ship.fuel() + " / " + ship.fuelCapacity()},
				{"Shields", Math.round(ship.shield()) + " / " + Math.round(ship.shieldMax())},
				{"Engines", Math.round(ship.engineFactor() * 100) + "% power"},
				{"Plants", ship.thermalFactor() < 0.999f ? "OVERHEATING " + Math.round(ship.thermalFactor() * 100) + "%" : "nominal"},
				{"Flight", ship.isDecoupled() ? "DECOUPLED" : "Coupled"},
				{"Wing lift", Math.round(ship.wingLift() * 100) + "% of weight"},
				{"Speed", String.format("%.0f m/s", ship.recentVelocity().length() * 20)},
				{"Quantum", ship.isQuantumTravelling() ? "in transit" : ship.quantumProgress() >= 0 ? "armed" : "idle"}};
		for (String[] row : rows) {
			g.text(font, row[0], x + 4, line, WristcomScreen.DIM, false);
			g.text(font, row[1], x + 70, line, WristcomScreen.TEXT, false);
			line += 12;
		}
		if (pilot) {
			g.text(font, Component.translatable("wristcom.ship.power"), x + width - 134, y + 6, accent(), false);
		} else {
			g.text(font, Component.translatable("wristcom.ship.pilot_only"), x + 4, line + 6, WristcomScreen.DIM, false);
		}
	}
}
