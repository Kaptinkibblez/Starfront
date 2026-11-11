package com.starwake.client.wristcom.apps;

import com.starwake.client.wristcom.WristcomApp;
import com.starwake.client.wristcom.WristcomScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** A reserved slot for an app a later milestone delivers (contracts, trade, cargo, comms). */
public class ComingSoonApp implements WristcomApp {
	private final String id, glyph;
	private final int accent, milestone;

	public ComingSoonApp(String id, String glyph, int accent, int milestone) {
		this.id = id;
		this.glyph = glyph;
		this.accent = accent;
		this.milestone = milestone;
	}

	@Override public Component title() { return Component.translatable("wristcom.app." + id); }
	@Override public String glyph() { return glyph; }
	@Override public int accent() { return accent; }

	@Override
	public void render(WristcomScreen screen, GuiGraphicsExtractor g, int x, int y, int width, int height, int mouseX, int mouseY, float partial) {
		var font = screen.font();
		int line = y + 8;
		for (FormattedCharSequence text : font.split(Component.translatable("wristcom.app." + id + ".desc"), width - 8)) {
			g.text(font, text, x + 4, line, WristcomScreen.TEXT, false);
			line += 11;
		}
		g.text(font, Component.translatable("wristcom.coming_soon", milestone), x + 4, line + 8, accent, false);
		// A dim placeholder frame where the app will live.
		g.outline(x + 4, line + 24, width - 8, Math.max(20, height - (line + 24 - y) - 6), WristcomScreen.FAINT);
	}
}
