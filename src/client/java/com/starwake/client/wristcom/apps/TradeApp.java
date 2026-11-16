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
 * Wristcom Trade app (M7, prices M9). Overview: your credits, your standing with every faction, and the trade hubs of
 * this system. Prices: every hub whose terminal you've used or hailed, with how old that information is and the hub's
 * tier, its prices as you saw them, and for a picked item its price history at that hub and the best place you know
 * to sell it and to buy it.
 */
public class TradeApp implements WristcomApp {
	private static boolean prices;
	private static com.starwake.network.@org.jspecify.annotations.Nullable TradeIntelPayload intel;
	private static String hub = "", item = "";
	private int scroll;

	@Override public Component title() { return Component.translatable("wristcom.app.trade"); }
	@Override public String glyph() { return "Tr"; }
	@Override public int accent() { return 0xFF7CFFB2; }

	/** Called by the networking receiver. */
	public static void receive(com.starwake.network.TradeIntelPayload payload) {
		intel = payload;
		if (Minecraft.getInstance().gui.screen() instanceof WristcomScreen w && w.currentApp() instanceof TradeApp) w.refresh();
	}

	private static long asked;

	private static void ask() {
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new com.starwake.network.TradeIntelPayload.Request(hub, item));
	}

	private static com.starwake.network.TradeIntelPayload.@org.jspecify.annotations.Nullable Hub selectedHub() {
		if (intel == null) return null;
		for (var h : intel.hubs()) if (h.id().equals(hub)) return h;
		return intel.hubs().isEmpty() ? null : intel.hubs().getFirst();
	}

	@Override
	public void init(WristcomScreen screen, int x, int y, int width, int height) {
		screen.add(net.minecraft.client.gui.components.Button.builder(Component.translatable("wristcom.trade.tab_overview"), b -> {
			prices = false;
			screen.refresh();
		}).bounds(x + width - 164, y, 80, 14).build()).active = prices;
		screen.add(net.minecraft.client.gui.components.Button.builder(Component.translatable("wristcom.trade.tab_prices"), b -> {
			prices = true;
			ask();
			screen.refresh();
		}).bounds(x + width - 82, y, 80, 14).build()).active = !prices;
		if (!prices) return;
		// Re-ask when the tab opens or was left open (throttled: a reply refreshes the screen, which comes back here).
		long now = System.currentTimeMillis();
		if (intel == null || now - asked > 3000) {
			asked = now;
			ask();
		}
		if (intel == null) return;
		var sel = selectedHub();
		if (sel != null) hub = sel.id();
		// Hubs you know (left), items at the selected hub (right).
		int hy = y + 30;
		for (var h : intel.hubs()) {
			if (hy > y + height - 16) break;
			String label = screen.font().plainSubstrByWidth(h.name(), 112);
			var b = screen.add(net.minecraft.client.gui.components.Button.builder(Component.literal(label), btn -> {
				hub = h.id();
				item = "";
				scroll = 0;
				ask();
				screen.refresh();
			}).bounds(x, hy, 118, 14).build());
			b.active = !h.id().equals(hub);
			hy += 26;
		}
		if (sel == null) return;
		int rx = x + 126, rows = Math.max(1, (height - 110) / 12);
		scroll = Math.clamp(scroll, 0, Math.max(0, sel.rows().size() - rows));
		for (int i = scroll; i < sel.rows().size() && i < scroll + rows; i++) {
			var r = sel.rows().get(i);
			var b = screen.add(net.minecraft.client.gui.components.Button.builder(Component.empty(), btn -> {
				item = r.item().toString();
				ask();
				screen.refresh();
			}).bounds(rx, y + 30 + (i - scroll) * 12, width - 126, 12).build());
			b.setAlpha(0.15f);
		}
	}

	@Override
	public boolean scrolled(double amount) {
		if (!prices) return false;
		scroll = Math.max(0, scroll - (int) Math.signum(amount));
		if (Minecraft.getInstance().gui.screen() instanceof WristcomScreen w) w.refresh();
		return true;
	}

	@Override
	public void render(WristcomScreen screen, GuiGraphicsExtractor g, int x, int y, int width, int height, int mouseX, int mouseY, float partial) {
		var font = screen.font();
		g.text(font, Component.translatable("wristcom.trade.credits", String.format("%,d", TradeClient.credits())), x + 4, y + 4, 0xFF7CFFB2, false);
		if (prices) {
			renderPrices(screen, g, x, y, width, height, mouseX, mouseY);
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();

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

	private void renderPrices(WristcomScreen screen, GuiGraphicsExtractor g, int x, int y, int width, int height, int mouseX, int mouseY) {
		var font = screen.font();
		if (intel == null || intel.hubs().isEmpty()) {
			int ly = y + 30;
			for (var line : font.split(Component.translatable("wristcom.trade.no_intel"), width - 8)) {
				g.text(font, line, x + 4, ly, WristcomScreen.DIM, false);
				ly += 10;
			}
			return;
		}
		// Ages under the hub buttons.
		int hy = y + 30;
		for (var h : intel.hubs()) {
			if (hy > y + height - 16) break;
			g.text(font, Component.translatable("wristcom.trade.seen", age(h.age()), h.tier() + 1), x + 2, hy + 15,
					h.age() > 72000 ? 0xFFFFB347 : WristcomScreen.DIM, false);
			hy += 26;
		}
		var sel = selectedHub();
		if (sel == null) return;
		int rx = x + 126, rw = width - 126, rows = Math.max(1, (height - 110) / 12);
		g.text(font, Component.translatable("wristcom.trade.col_item"), rx + 2, y + 20, WristcomScreen.FRAME, false);
		g.text(font, Component.translatable("wristcom.trade.col_prices"), rx + rw - 4 - font.width(Component.translatable("wristcom.trade.col_prices")), y + 20, WristcomScreen.FRAME, false);
		for (int i = scroll; i < sel.rows().size() && i < scroll + rows; i++) {
			var r = sel.rows().get(i);
			int ry = y + 30 + (i - scroll) * 12;
			var stack = new net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(r.item()));
			boolean picked = r.item().toString().equals(item);
			String p = r.buy() + "/" + r.sell() + " x" + r.stock();
			String name = stack.getHoverName().getString(), cut = font.plainSubstrByWidth(name, rw - 12 - font.width(p));
			if (cut.length() < name.length()) cut = font.plainSubstrByWidth(name, rw - 20 - font.width(p)) + "..";
			g.text(font, cut, rx + 2, ry + 2, picked ? 0xFF7CFFB2 : WristcomScreen.TEXT, false);
			g.text(font, p, rx + rw - 4 - font.width(p), ry + 2, WristcomScreen.DIM, false);
		}
		// The picked item: history graph and where you know it sells best / is cheapest.
		if (item.isEmpty()) return;
		int gy = y + height - 74, gh = 40, gw = rw - 8;
		g.fill(rx, gy, rx + gw, gy + gh, 0x30000000);
		List<Float> h = intel.historyHub().equals(sel.id()) && intel.historyItem().equals(item) ? intel.history() : List.of();
		if (h.size() >= 2) {
			float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
			for (float v : h) {
				lo = Math.min(lo, v);
				hi = Math.max(hi, v);
			}
			float span = Math.max(0.01f, hi - lo);
			for (int i = 1; i < h.size(); i++) {
				int x0 = rx + (i - 1) * gw / (h.size() - 1), x1 = rx + i * gw / (h.size() - 1);
				int y0 = gy + gh - 2 - Math.round((h.get(i - 1) - lo) / span * (gh - 4)), y1 = gy + gh - 2 - Math.round((h.get(i) - lo) / span * (gh - 4));
				for (int xx = x0; xx <= x1; xx++) {
					int yy = x1 == x0 ? y1 : y0 + (y1 - y0) * (xx - x0) / (x1 - x0);
					g.fill(xx, yy, xx + 1, yy + 2, 0xFF7CFFB2);
				}
			}
			g.text(font, String.format("%.1f - %.1f cr", lo, hi), rx + 2, gy + 2, WristcomScreen.DIM, false);
		} else {
			g.text(font, Component.translatable("wristcom.trade.no_history"), rx + 4, gy + 16, WristcomScreen.DIM, false);
		}
		String bestSell = "", bestBuy = "";
		long sell = -1, buy = Long.MAX_VALUE;
		for (var other : intel.hubs()) {
			for (var r : other.rows()) {
				if (!r.item().toString().equals(item)) continue;
				if (r.sell() > sell) {
					sell = r.sell();
					bestSell = other.name();
				}
				if (r.stock() > 0 && r.buy() < buy) {
					buy = r.buy();
					bestBuy = other.name();
				}
			}
		}
		if (sell >= 0) g.text(font, Component.translatable("wristcom.trade.best_sell", sell, bestSell), rx, gy + gh + 4, 0xFF7CFFB2, false);
		if (buy < Long.MAX_VALUE) g.text(font, Component.translatable("wristcom.trade.best_buy", buy, bestBuy), rx, gy + gh + 16, WristcomScreen.DIM, false);
	}

	private static String age(long ticks) {
		if (ticks < 1200) return Component.translatable("wristcom.trade.age_now").getString();
		if (ticks < 72000) return (ticks / 1200) + " min";
		if (ticks < 24000L * 3) return String.format("%.1f h", ticks / 72000.0);
		return String.format("%.1f d", ticks / 24000.0);
	}
}
