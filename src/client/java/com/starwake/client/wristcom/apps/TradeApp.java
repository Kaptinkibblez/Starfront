package com.starwake.client.wristcom.apps;

import com.starwake.client.trade.TradeClient;
import com.starwake.client.wristcom.WristcomApp;
import com.starwake.client.wristcom.WristcomScreen;
import com.starwake.network.HubListPayload;
import com.starwake.trade.Faction;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Wristcom Trade app (M7): your credits, your standing with every faction (a bar from hostile to
 * honored), and the trade hubs of this system with their faction and distance.
 */
public class TradeApp implements WristcomApp {
	@Override public Component title() { return Component.translatable("wristcom.app.trade"); }
	@Override public String glyph() { return "Tr"; }
	@Override public int accent() { return 0xFF7CFFB2; }

	@Override
	public void render(WristcomScreen screen, GuiGraphicsExtractor g, int x, int y, int width, int height, int mouseX, int mouseY, float partial) {
		var font = screen.font();
		Minecraft minecraft = Minecraft.getInstance();
		g.text(font, Component.translatable("wristcom.trade.credits", String.format("%,d", TradeClient.credits())), x + 4, y + 4, 0xFF7CFFB2, false);

		int half = width / 2 - 6;
		int ry = y + 22;
		g.text(font, Component.translatable("wristcom.trade.standing"), x + 4, ry, WristcomScreen.FRAME, false);
		ry += 12;
		if (minecraft.level != null) {
			var registry = minecraft.level.registryAccess().lookup(Faction.REGISTRY_KEY);
			if (registry.isPresent()) {
				for (var f : registry.get().listElements().sorted(Comparator.comparing(h -> h.key().identifier().toString())).toList()) {
					String id = f.key().identifier().getPath();
					int rep = TradeClient.rep().getOrDefault(id, 0);
					int color = f.value().color() | 0xFF000000;
					g.text(font, font.plainSubstrByWidth(f.value().name().getString(), half - 4), x + 4, ry, color, false);
					ry += 10;
					int bx = x + 4, bw = half - 50;
					g.fill(bx, ry + 2, bx + bw, ry + 5, 0x40FFFFFF);
					int mid = bx + bw / 2;
					int fill = Math.round(bw / 2f * rep / 1000f);
					g.fill(Math.min(mid, mid + fill), ry + 2, Math.max(mid, mid + fill), ry + 5, rep < 0 ? 0xFFFF6A4D : color);
					g.fill(mid, ry + 1, mid + 1, ry + 6, 0xFFFFFFFF);
					g.text(font, TradeClient.rankName(rep), bx + bw + 4, ry, WristcomScreen.DIM, false);
					ry += 12;
				}
			}
		}

		// Hubs of this system.
		int hx = x + half + 12;
		int hy = y + 22;
		g.text(font, Component.translatable("wristcom.trade.hubs"), hx, hy, WristcomScreen.FRAME, false);
		hy += 12;
		List<HubListPayload.HubView> hubs = TradeClient.hubs();
		if (hubs.isEmpty()) g.text(font, Component.translatable("wristcom.trade.no_hubs"), hx, hy, WristcomScreen.DIM, false);
		for (var h : hubs) {
			if (hy > y + height - 20) break;
			g.text(font, font.plainSubstrByWidth(h.name(), half - 10), hx, hy, TradeClient.factionColor(h.faction()), false);
			hy += 10;
			String where;
			if (minecraft.level != null && minecraft.player != null && h.dimension().equals(minecraft.level.dimension())) {
				where = com.starwake.galaxy.SolarSystem.formatDistance(minecraft.player.position().distanceTo(h.beacon()));
			} else {
				where = h.station() ? Component.translatable("wristcom.trade.station_near", h.planet()).getString()
						: Component.translatable("wristcom.trade.on_planet", h.planet()).getString();
			}
			g.text(font, Component.translatable("hub_kind.starwake." + h.kind()).getString() + " · " + where, hx + 4, hy, WristcomScreen.DIM, false);
			hy += 12;
		}
	}
}
