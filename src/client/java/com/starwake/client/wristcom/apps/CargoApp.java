package com.starwake.client.wristcom.apps;

import com.starwake.client.ship.ShipLocalMotion;
import com.starwake.client.wristcom.WristcomApp;
import com.starwake.client.wristcom.WristcomScreen;
import com.starwake.network.CargoPayload;
import com.starwake.ship.ShipEntity;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Cargo (M9): the manifest of the ship you're aboard (or the nearest ship you own or crew within 64 blocks): fuel and
 * fusion, every fluid in its tanks and every item in its containers, summed. Asks the server
 * ({@link CargoPayload}) when opened and every two seconds while open; scroll the item list with the wheel.
 */
public class CargoApp implements WristcomApp {
	private static @Nullable CargoPayload manifest;
	private static long asked;
	private int scroll;

	@Override public Component title() { return Component.translatable("wristcom.app.cargo"); }
	@Override public String glyph() { return "Ca"; }
	@Override public int accent() { return 0xFFFF9F6B; }

	/** Called by the networking receiver. */
	public static void receive(CargoPayload payload) {
		manifest = payload;
	}

	static @Nullable ShipEntity ship() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return null;
		if (mc.player.getVehicle() instanceof ShipEntity s) return s;
		ShipEntity active = ShipLocalMotion.activeShip();
		if (active != null) return active;
		ShipEntity best = null;
		String me = mc.player.getGameProfile().name();
		for (var e : mc.level.entitiesForRendering()) {
			if (e instanceof ShipEntity s && s.distanceTo(mc.player) < 64 && (s.ownerName().equals(me) || s.crewNames().contains(me))
					&& (best == null || s.distanceTo(mc.player) < best.distanceTo(mc.player))) {
				best = s;
			}
		}
		return best;
	}

	private static void ask() {
		ShipEntity ship = ship();
		if (ship == null) return;
		long now = System.currentTimeMillis();
		if (now - asked < 2000) return;
		asked = now;
		ClientPlayNetworking.send(CargoPayload.request(ship.getId()));
	}

	@Override
	public void init(WristcomScreen screen, int x, int y, int width, int height) {
		asked = 0;
		ask();
	}

	@Override
	public boolean scrolled(double amount) {
		scroll = Math.max(0, scroll - (int) Math.signum(amount));
		return true;
	}

	@Override
	public void render(WristcomScreen screen, GuiGraphicsExtractor g, int x, int y, int width, int height, int mouseX, int mouseY, float partial) {
		var font = screen.font();
		ShipEntity ship = ship();
		if (ship == null) {
			g.text(font, Component.translatable("wristcom.ship.none"), x + 4, y + 8, WristcomScreen.DIM, false);
			return;
		}
		ask();
		CargoPayload m = manifest;
		if (m == null || m.ship() != ship.getId()) {
			g.text(font, Component.translatable("wristcom.cargo.loading"), x + 4, y + 8, WristcomScreen.DIM, false);
			return;
		}
		if (m.containers() < 0) {
			g.text(font, Component.translatable("wristcom.cargo.denied"), x + 4, y + 8, 0xFFFFB347, false);
			return;
		}
		// Fuel and fusion bars.
		bar(screen, g, x + 4, y + 2, width / 2 - 12, Component.translatable("wristcom.cargo.fuel", m.fuel(), m.fuelCapacity()),
				m.fuelCapacity() <= 0 ? 0 : m.fuel() / (float) m.fuelCapacity(), 0xFFFFC857);
		bar(screen, g, x + width / 2 + 4, y + 2, width / 2 - 12, Component.translatable("wristcom.cargo.fusion", m.fusion(), m.fusionCapacity()),
				m.fusionCapacity() <= 0 ? 0 : m.fusion() / (float) m.fusionCapacity(), 0xFF6FE3FF);
		int row = y + 30;
		if (!m.tanks().isEmpty()) {
			g.text(font, Component.translatable("wristcom.cargo.tanks"), x + 4, row, accent(), false);
			row += 11;
			for (int i = 0; i < m.tanks().size() && i < 4; i++) {
				var t = m.tanks().get(i);
				g.text(font, t.name(), x + 10, row, WristcomScreen.TEXT, false);
				String kg = t.kg() >= 10 ? String.format("%.0f kg", t.kg()) : String.format("%.2f kg", t.kg());
				g.text(font, kg, x + width - 8 - font.width(kg), row, WristcomScreen.DIM, false);
				row += 10;
			}
			row += 4;
		}
		g.text(font, Component.translatable("wristcom.cargo.items", m.containers()), x + 4, row, accent(), false);
		row += 12;
		if (m.items().isEmpty()) {
			g.text(font, Component.translatable("wristcom.cargo.empty"), x + 10, row, WristcomScreen.DIM, false);
			return;
		}
		int perRow = Math.max(1, (width - 8) / 120);
		int rowsVisible = Math.max(1, (y + height - row) / 18);
		scroll = Math.min(scroll, Math.max(0, (m.items().size() + perRow - 1) / perRow - rowsVisible));
		for (int i = scroll * perRow; i < m.items().size(); i++) {
			int col = (i - scroll * perRow) % perRow, r = (i - scroll * perRow) / perRow;
			if (r >= rowsVisible) break;
			var line = m.items().get(i);
			int ix = x + 4 + col * 120, iy = row + r * 18;
			g.item(line.item(), ix, iy);
			String name = font.plainSubstrByWidth(line.item().getHoverName().getString(), 70);
			g.text(font, name, ix + 18, iy + 4, WristcomScreen.TEXT, false);
			String count = "x" + line.count();
			g.text(font, count, ix + 116 - font.width(count), iy + 4, WristcomScreen.DIM, false);
			if (mouseX >= ix && mouseX < ix + 16 && mouseY >= iy && mouseY < iy + 16) g.setTooltipForNextFrame(font, line.item(), mouseX, mouseY);
		}
	}

	private static void bar(WristcomScreen screen, GuiGraphicsExtractor g, int x, int y, int w, Component label, float fill, int color) {
		g.text(screen.font(), label, x, y, WristcomScreen.TEXT, false);
		g.fill(x, y + 11, x + w, y + 15, WristcomScreen.FAINT);
		g.fill(x, y + 11, x + Math.round(w * Math.clamp(fill, 0f, 1f)), y + 15, color);
	}
}
