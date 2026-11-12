package com.starwake.client.wristcom.apps;

import com.google.gson.JsonParser;
import com.starwake.client.wristcom.WristcomApp;
import com.starwake.client.wristcom.WristcomScreen;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Wristcom Codex (M8i): the in-game guide to Starwake's systems (power, ore processing, wet chemistry, rare
 * elements, steelmaking, forming and heat treatment, fabrication, electronics, ship parts, propellants and
 * fusion fuel, nuclear power, gear). Entries are bundled ({@code /starwake/codex.json}, from {@code tools/codex.py});
 * pick one on the left, scroll the text with the mouse wheel.
 */
public class CodexApp implements WristcomApp {
	private record Entry(String title, String category, List<String> text) {}

	private static List<Entry> entries;
	private int selected;
	private int scroll;

	private static List<Entry> entries() {
		if (entries == null) {
			List<Entry> out = new ArrayList<>();
			try (var in = CodexApp.class.getResourceAsStream("/starwake/codex.json")) {
				if (in != null) {
					for (var e : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray()) {
						var o = e.getAsJsonObject();
						List<String> text = new ArrayList<>();
						o.getAsJsonArray("text").forEach(t -> text.add(t.getAsString()));
						out.add(new Entry(o.get("title").getAsString(), o.get("category").getAsString(), text));
					}
				}
			} catch (java.io.IOException ignored) {
				// No codex bundled: the app shows nothing.
			}
			entries = List.copyOf(out);
		}
		return entries;
	}

	@Override public Component title() { return Component.translatable("wristcom.app.codex"); }
	@Override public String glyph() { return "Cx"; }
	@Override public int accent() { return 0xFF8CE0C8; }

	@Override
	public void init(WristcomScreen screen, int x, int y, int width, int height) {
		List<Entry> list = entries();
		for (int i = 0; i < list.size(); i++) {
			int index = i;
			String title = list.get(i).title();
			var font = net.minecraft.client.Minecraft.getInstance().font;
			String label = title;
			if (font.width(label) > 88) {
				while (label.length() > 1 && font.width(label + "...") > 88) label = label.substring(0, label.length() - 1);
				label = label.strip() + "...";
			}
			screen.add(Button.builder(Component.literal(label), b -> {
				selected = index;
				scroll = 0;
			}).bounds(x + 2, y + 2 + i * 15, 96, 14).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(title))).build());
		}
	}

	@Override
	public boolean scrolled(double amount) {
		scroll = Math.max(0, scroll - (int) Math.signum(amount) * 3);
		return true;
	}

	@Override
	public void render(WristcomScreen screen, GuiGraphicsExtractor g, int x, int y, int width, int height, int mouseX, int mouseY, float partial) {
		var font = screen.font();
		List<Entry> list = entries();
		if (list.isEmpty()) return;
		Entry e = list.get(Math.min(selected, list.size() - 1));
		int tx = x + 104, tw = width - 108;
		g.text(font, Component.literal(e.title()), tx, y + 4, accent(), false);
		g.text(font, Component.literal(e.category()), tx + tw - font.width(e.category()), y + 4, WristcomScreen.DIM, false);
		List<FormattedCharSequence> lines = new ArrayList<>();
		for (String p : e.text()) {
			lines.addAll(font.split(Component.literal(p), tw));
			lines.add(FormattedCharSequence.EMPTY);
		}
		int visible = (height - 22) / 10;
		scroll = Math.min(scroll, Math.max(0, lines.size() - visible));
		for (int i = 0; i < visible && i + scroll < lines.size(); i++) {
			g.text(font, lines.get(i + scroll), tx, y + 18 + i * 10, WristcomScreen.TEXT, false);
		}
		if (lines.size() > visible) {
			g.text(font, Component.literal((scroll + visible < lines.size() ? "v " : "") + (scroll > 0 ? "^" : "")), tx + tw - 12, y + height - 10,
					WristcomScreen.DIM, false);
		}
	}
}
