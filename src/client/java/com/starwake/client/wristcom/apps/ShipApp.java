package com.starwake.client.wristcom.apps;

import com.starwake.client.hud.FlightHud;
import com.starwake.client.screen.PowerSliders;
import com.starwake.client.ship.ShipLocalMotion;
import com.starwake.client.wristcom.ShipReports;
import com.starwake.client.wristcom.WristcomApp;
import com.starwake.client.wristcom.WristcomScreen;
import com.starwake.network.ShipConsoleActionPayload;
import com.starwake.network.ShipConsolePayload;
import com.starwake.network.ShipQuantumPayload;
import com.starwake.ship.ShipEntity;
import com.starwake.ship.scan.PreflightReport;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * Ship: the ship's console, merged into the Wristcom (3j). Shows the server's report (the
 * pre-flight check at a Helm, or an assembled ship's status and quantum routes) with its
 * actions: re-scan, assemble/disassemble, quantum routes, power routing and flight mode.
 * Without a report (opened with G), it shows live systems from synced data.
 *
 * Open it by using a Helm, pressing R while piloting, or G.
 */
public class ShipApp implements WristcomApp {
	private static final int SIDE = 136;
	private static int scroll;
	/** Where init() put the power sliders (for their label), or -1 if there are none. */
	private int powerLabelY = -1;

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
		int sx = x + width - SIDE, sy = y;
		ShipConsolePayload report = ShipReports.current();
		ShipEntity ship = ship();
		boolean pilot = ship != null && FlightHud.pilotedShip() == ship;

		if (report != null) {
			boolean assembled = report.shipId() >= 0;
			ShipConsoleActionPayload.Action refresh = assembled ? ShipConsoleActionPayload.Action.OPEN_SHIP : ShipConsoleActionPayload.Action.RESCAN;
			screen.add(Button.builder(Component.translatable("screen.starwake.ship_console.rescan"),
					b -> send(report, refresh)).bounds(sx, sy, SIDE, 18).build());
			sy += 20;
			Button main = screen.add(Button.builder(Component.translatable(assembled
							? "screen.starwake.ship_console.disassemble" : "screen.starwake.ship_console.assemble"),
					b -> {
						send(report, assembled ? ShipConsoleActionPayload.Action.DISASSEMBLE : ShipConsoleActionPayload.Action.ASSEMBLE);
						screen.onClose(); // assembling seats you at the helm; disassembling ends the flight
					}).bounds(sx, sy, SIDE, 18).build());
			main.active = report.actionEnabled();
			sy += 24;
			for (int i = 0; i < report.destinations().size(); i++) {
				var target = report.destinations().get(i);
				screen.add(Button.builder(Component.translatable("screen.starwake.ship_console.course", report.destinationNames().get(i)),
						b -> ClientPlayNetworking.send(new ShipConsoleActionPayload(report.helm(), report.shipId(),
								ShipConsoleActionPayload.Action.SET_COURSE, target))).bounds(sx, sy, SIDE, 18).build());
				sy += 20;
			}
		} else if (pilot) {
			screen.add(Button.builder(Component.translatable("wristcom.ship.report"),
					b -> ClientPlayNetworking.send(new ShipConsoleActionPayload(BlockPos.ZERO, ship.getId(),
							ShipConsoleActionPayload.Action.OPEN_SHIP))).bounds(sx, sy, SIDE, 18).build());
			sy += 24;
		}
		powerLabelY = -1;
		if (pilot) {
			powerLabelY = sy;
			new PowerSliders(ship.powerPercents()).create(sx, sy + 12, SIDE, screen::add);
			screen.add(Button.builder(Component.translatable("wristcom.ship.toggle_mode"),
					b -> ClientPlayNetworking.send(new ShipQuantumPayload(ShipQuantumPayload.ACTION_DECOUPLE)))
					.bounds(sx, sy + 80, SIDE, 18).build());
		}
	}

	private static void send(ShipConsolePayload report, ShipConsoleActionPayload.Action action) {
		ClientPlayNetworking.send(new ShipConsoleActionPayload(report.helm(), report.shipId(), action));
	}

	@Override
	public boolean scrolled(double amount) {
		scroll = Math.max(0, scroll - (int) Math.signum(amount) * 3);
		return true;
	}

	@Override
	public void render(WristcomScreen screen, GuiGraphicsExtractor g, int x, int y, int width, int height, int mouseX, int mouseY, float partial) {
		var font = screen.font();
		ShipConsolePayload report = ShipReports.current();
		ShipEntity ship = ship();
		int textWidth = width - SIDE - 8;
		if (report != null) {
			renderReport(screen, g, report, x, y, textWidth, height);
		} else if (ship != null) {
			renderLive(screen, g, ship, x, y);
		} else {
			int ly = y + 8;
			for (FormattedCharSequence line : font.split(Component.translatable("wristcom.ship.none"), textWidth)) {
				g.text(font, line, x + 4, ly, WristcomScreen.DIM, false);
				ly += 11;
			}
		}
		if (powerLabelY >= 0) g.text(font, Component.translatable("wristcom.ship.power"), x + width - SIDE, powerLabelY + 2, accent(), false);
	}

	/** The server's report, wrapped, with status icons; scrolls with the mouse wheel. */
	private void renderReport(WristcomScreen screen, GuiGraphicsExtractor g, ShipConsolePayload report, int x, int y, int width, int height) {
		var font = screen.font();
		PreflightReport.Status[] statuses = PreflightReport.Status.values();
		List<Object[]> rows = new ArrayList<>(); // icon line or text line
		for (int i = 0; i < report.lines().size(); i++) {
			PreflightReport.Status status = statuses[report.status().get(i)];
			boolean first = true;
			for (FormattedCharSequence line : font.split(report.lines().get(i), width - 12)) {
				rows.add(new Object[] {first ? status : null, line});
				first = false;
			}
		}
		int visible = (height - 4) / 10;
		scroll = Math.min(scroll, Math.max(0, rows.size() - visible));
		g.enableScissor(x, y, x + width, y + height);
		for (int i = scroll; i < rows.size() && i - scroll < visible; i++) {
			int ly = y + 2 + (i - scroll) * 10;
			if (rows.get(i)[0] instanceof PreflightReport.Status status) g.text(font, status.icon, x + 2, ly, status.color, false);
			g.text(font, (FormattedCharSequence) rows.get(i)[1], x + 12, ly, WristcomScreen.TEXT, false);
		}
		g.disableScissor();
		if (rows.size() > visible) {
			// Scroll bar.
			int barH = Math.max(10, height * visible / rows.size());
			int barY = y + (height - barH) * scroll / Math.max(1, rows.size() - visible);
			g.fill(x + width - 2, y, x + width - 1, y + height, WristcomScreen.FAINT);
			g.fill(x + width - 3, barY, x + width, barY + barH, WristcomScreen.DIM);
		}
	}

	/** Live readout from synced ship data. */
	private void renderLive(WristcomScreen screen, GuiGraphicsExtractor g, ShipEntity ship, int x, int y) {
		var font = screen.font();
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
		if (!pilot) g.text(font, Component.translatable("wristcom.ship.pilot_only"), x + 4, line + 6, WristcomScreen.DIM, false);
	}
}
