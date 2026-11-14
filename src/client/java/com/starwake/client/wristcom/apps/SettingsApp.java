package com.starwake.client.wristcom.apps;

import com.starwake.client.ship.FlightControls;
import com.starwake.client.wristcom.WristcomApp;
import com.starwake.client.wristcom.WristcomScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Settings (M9 pass 2): client-side flight and HUD preferences, saved in {@code config/starwake-client.properties}:
 * steering mode (mouse-aim or joystick, also toggled with K), cockpit HUD scale and HUD opacity.
 */
public class SettingsApp implements WristcomApp {
	@Override public Component title() { return Component.translatable("wristcom.app.settings"); }
	@Override public String glyph() { return "Se"; }
	@Override public int accent() { return 0xFF9FB4C7; }

	private static final int ROW = 24;

	@Override
	public void init(WristcomScreen screen, int x, int y, int width, int height) {
		int right = x + width - 150;
		screen.add(Button.builder(Component.translatable(FlightControls.joystick() ? "wristcom.settings.steering.joystick" : "wristcom.settings.steering.mouse_aim"),
				b -> {
					FlightControls.setJoystick(!FlightControls.joystick());
					screen.refreshSoon();
				}).bounds(right, y + 4, 150, 18).build());
		screen.add(Button.builder(Component.literal("-"), b -> {
			FlightControls.stepHudScale(-0.1f);
			screen.refreshSoon();
		}).bounds(right, y + 4 + ROW, 20, 18).build());
		screen.add(Button.builder(Component.literal("+"), b -> {
			FlightControls.stepHudScale(0.1f);
			screen.refreshSoon();
		}).bounds(right + 130, y + 4 + ROW, 20, 18).build());
		screen.add(Button.builder(Component.literal("-"), b -> {
			FlightControls.stepHudOpacity(-0.1f);
			screen.refreshSoon();
		}).bounds(right, y + 4 + ROW * 2, 20, 18).build());
		screen.add(Button.builder(Component.literal("+"), b -> {
			FlightControls.stepHudOpacity(0.1f);
			screen.refreshSoon();
		}).bounds(right + 130, y + 4 + ROW * 2, 20, 18).build());
	}

	@Override
	public void render(WristcomScreen screen, GuiGraphicsExtractor g, int x, int y, int width, int height, int mouseX, int mouseY, float partial) {
		var font = screen.font();
		int right = x + width - 150;
		g.text(font, Component.translatable("wristcom.settings.steering"), x + 4, y + 9, WristcomScreen.TEXT, false);
		g.text(font, Component.translatable("wristcom.settings.hud_scale"), x + 4, y + 9 + ROW, WristcomScreen.TEXT, false);
		g.centeredText(font, Math.round(FlightControls.hudScale() * 100) + "%", right + 75, y + 9 + ROW, WristcomScreen.TEXT);
		g.text(font, Component.translatable("wristcom.settings.hud_opacity"), x + 4, y + 9 + ROW * 2, WristcomScreen.TEXT, false);
		g.centeredText(font, Math.round(FlightControls.hudOpacity() * 100) + "%", right + 75, y + 9 + ROW * 2, WristcomScreen.TEXT);
		g.text(font, Component.translatable("wristcom.settings.note"), x + 4, y + 12 + ROW * 3, WristcomScreen.DIM, false);
	}
}
