package com.starwake.client.wristcom.apps;

import com.starwake.client.trade.TradeClient;
import com.starwake.client.wristcom.WristcomApp;
import com.starwake.client.wristcom.WristcomScreen;
import com.starwake.network.CommsMessagePayload;
import com.starwake.network.CommsPayload;
import com.starwake.ship.ShipEntity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * Comms (M9), the Wristcom radio. Left: contacts (online players, trade hubs in this system, ships within 2 km).
 * Right: the conversation with the selected contact, a text box and Send / Hail. Players get messages; hubs answer a
 * hail with docking information; ships forward hails and messages to their pilot and owner. The inbox lives on the
 * client for the session (last 40 lines per contact); an incoming message also shows on the action bar.
 */
public class CommsApp implements WristcomApp {
	/** A contact: its channel key (player name, "hub:id", "ship:id") and display name. */
	private record Contact(String channel, Component name, String kind) {}

	private record Line(Component from, Component text, String kind) {}

	private static final Map<String, List<Line>> INBOX = new LinkedHashMap<>();
	private static final Set<String> UNREAD = new HashSet<>();
	private static @Nullable String selected;
	private @Nullable EditBox field;
	private int contactScroll;

	@Override public Component title() { return Component.translatable("wristcom.app.comms"); }
	@Override public String glyph() { return "Cm"; }
	@Override public int accent() { return 0xFFB59CFF; }

	/** Called by the networking receiver. */
	public static void receive(CommsMessagePayload p) {
		List<Line> lines = INBOX.computeIfAbsent(p.channel(), k -> new ArrayList<>());
		lines.add(new Line(p.from(), p.text(), p.kind()));
		if (lines.size() > 40) lines.removeFirst();
		Minecraft mc = Minecraft.getInstance();
		boolean showing = mc.gui.screen() instanceof WristcomScreen w && w.currentApp() instanceof CommsApp && p.channel().equals(selected);
		if (!showing && (p.kind().equals("msg") || p.kind().equals("hail"))) {
			UNREAD.add(p.channel());
			if (mc.player != null) mc.player.sendOverlayMessage(Component.translatable("wristcom.comms.incoming", p.from(), p.text()));
		}
		if (showing && mc.gui.screen() instanceof WristcomScreen open) open.refreshSoon();
	}

	private static List<Contact> contacts() {
		Minecraft mc = Minecraft.getInstance();
		List<Contact> out = new ArrayList<>();
		if (mc.player == null || mc.getConnection() == null) return out;
		String me = mc.player.getGameProfile().name();
		for (var info : mc.getConnection().getOnlinePlayers()) {
			String name = info.getProfile().name();
			if (!name.equals(me)) out.add(new Contact(name, Component.literal(name), "player"));
		}
		for (var hub : TradeClient.hubs()) out.add(new Contact("hub:" + hub.id(), Component.literal(hub.name()), "hub"));
		if (mc.level != null) {
			for (var e : mc.level.entitiesForRendering()) {
				if (e instanceof ShipEntity s && s.distanceTo(mc.player) < 2000 && mc.player.getVehicle() != s
						&& com.starwake.client.ship.ShipLocalMotion.activeShip() != s && !s.getBoundingBox().inflate(1).contains(mc.player.position())) {
					String owner = s.ownerName();
					out.add(new Contact("ship:" + s.getId(), owner.isEmpty() ? Component.translatable("wristcom.comms.ship_unregistered",
							Math.round(s.distanceTo(mc.player))) : Component.translatable("wristcom.comms.ship", owner,
							Math.round(s.distanceTo(mc.player))), "ship"));
				}
			}
		}
		// Conversations with people who have since logged off stay listed.
		for (String channel : INBOX.keySet()) {
			if (out.stream().noneMatch(c -> c.channel().equals(channel)) && !channel.contains(":")) {
				out.add(new Contact(channel, Component.literal(channel), "player"));
			}
		}
		return out;
	}

	private static @Nullable Contact current(List<Contact> all) {
		for (Contact c : all) if (c.channel().equals(selected)) return c;
		return null;
	}

	@Override
	public void init(WristcomScreen screen, int x, int y, int width, int height) {
		List<Contact> all = contacts();
		int listW = 120;
		int rows = Math.max(1, (height - 4) / 16);
		contactScroll = Math.clamp(contactScroll, 0, Math.max(0, all.size() - rows));
		for (int i = contactScroll; i < all.size() && i < contactScroll + rows; i++) {
			Contact c = all.get(i);
			boolean unread = UNREAD.contains(c.channel());
			String full = (unread ? "• " : "") + (c.kind().equals("hub") ? "[H] " : c.kind().equals("ship") ? "[S] " : "") + c.name().getString();
			String cut = screen.font().plainSubstrByWidth(full, listW - 8);
			Component label = Component.literal(cut.length() < full.length() ? screen.font().plainSubstrByWidth(full, listW - 16) + ".." : full);
			Button b = screen.add(Button.builder(label, btn -> {
				selected = c.channel();
				UNREAD.remove(c.channel());
				screen.refresh();
			}).bounds(x, y + (i - contactScroll) * 16, listW, 14).build());
			b.active = !c.channel().equals(selected);
		}
		Contact c = current(all);
		field = null;
		if (c == null) return;
		int fx = x + listW + 8, fw = width - listW - 8;
		if (c.kind().equals("hub")) {
			screen.add(Button.builder(Component.translatable("wristcom.comms.hail"), b -> {
				ClientPlayNetworking.send(new CommsPayload("hail_hub", c.channel().substring(4), ""));
			}).bounds(fx + fw - 70, y + height - 18, 70, 18).build());
			return;
		}
		field = screen.add(new EditBox(screen.font(), fx, y + height - 18, fw - 76, 18, Component.translatable("wristcom.comms.message")));
		field.setMaxLength(256);
		field.setHint(Component.translatable("wristcom.comms.message"));
		screen.add(Button.builder(Component.translatable(c.kind().equals("ship") ? "wristcom.comms.hail" : "wristcom.comms.send"), b -> send(c))
				.bounds(fx + fw - 70, y + height - 18, 70, 18).build());
	}

	private void send(Contact c) {
		String text = field == null ? "" : field.getValue().trim();
		if (c.kind().equals("ship")) ClientPlayNetworking.send(new CommsPayload("hail_ship", c.channel().substring(5), text));
		else if (!text.isEmpty()) ClientPlayNetworking.send(new CommsPayload("msg", c.channel(), text));
		if (field != null) field.setValue("");
	}

	@Override
	public boolean scrolled(double amount) {
		contactScroll = Math.max(0, contactScroll - (int) Math.signum(amount));
		return true;
	}

	@Override
	public void render(WristcomScreen screen, GuiGraphicsExtractor g, int x, int y, int width, int height, int mouseX, int mouseY, float partial) {
		var font = screen.font();
		List<Contact> all = contacts();
		if (all.isEmpty()) g.text(font, Component.translatable("wristcom.comms.no_contacts"), x + 4, y + 4, WristcomScreen.DIM, false);
		Contact c = current(all);
		int fx = x + 128, fw = width - 128;
		g.fill(fx - 5, y, fx - 4, y + height, WristcomScreen.FAINT);
		if (c == null) {
			g.text(font, Component.translatable("wristcom.comms.pick"), fx, y + 4, WristcomScreen.DIM, false);
			return;
		}
		g.text(font, c.name(), fx, y + 2, accent(), false);
		List<Line> lines = INBOX.getOrDefault(c.channel(), List.of());
		// Wrap from the bottom up above the input row.
		List<FormattedCharSequence> wrapped = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		Component previous = null;
		for (Line l : lines) {
			int color = switch (l.kind()) {
				case "sent" -> WristcomScreen.DIM;
				case "error" -> 0xFFFFB347;
				case "hub" -> 0xFF7CFFB2;
				default -> WristcomScreen.TEXT;
			};
			// Consecutive lines from one speaker only name them once.
			Component full = previous != null && previous.getString().equals(l.from().getString()) ? Component.literal("  ").append(l.text())
					: Component.empty().append(l.from()).append(": ").append(l.text());
			previous = l.from();
			for (FormattedCharSequence s : font.split(full, fw - 4)) {
				wrapped.add(s);
				colors.add(color);
			}
		}
		int bottom = y + height - 24, top = y + 14;
		int max = Math.max(0, (bottom - top) / 10);
		int start = Math.max(0, wrapped.size() - max);
		for (int i = start; i < wrapped.size(); i++) g.text(font, wrapped.get(i), fx, top + (i - start) * 10, colors.get(i), false);
		if (lines.isEmpty()) {
			g.text(font, Component.translatable(c.kind().equals("hub") ? "wristcom.comms.hub_hint" : "wristcom.comms.empty"), fx, top,
					WristcomScreen.DIM, false);
		}
	}
}
