package com.starwake.client.wristcom;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * One app on the Wristcom (the player's wrist computer, in the spirit of Star Citizen's
 * mobiGlas). Apps are client-side views: anything authoritative goes through payloads.
 * Later milestones (trading, contracts, cargo) register their own apps in {@link WristcomApps}.
 */
public interface WristcomApp {
	/** Short name for the app bar. */
	Component title();

	/** One or two characters shown on the app tile. */
	String glyph();

	/** Accent colour (ARGB). */
	int accent();

	/** Called when the app is opened (or the screen resized): add widgets to the content area. */
	default void init(WristcomScreen screen, int x, int y, int width, int height) {}

	/** Draws the app into its content area. */
	void render(WristcomScreen screen, GuiGraphicsExtractor graphics, int x, int y, int width, int height,
			int mouseX, int mouseY, float partialTick);
}
