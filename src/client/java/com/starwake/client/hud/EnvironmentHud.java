package com.starwake.client.hud;

import com.starwake.StarwakeMod;
import com.starwake.planet.Hazards;
import com.starwake.planet.PlanetHazards;
import com.starwake.planet.Planets;
import com.starwake.planet.SurveySuitItem;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Environment card (M5 hazards), top-right: the planet's temperature and air, and what is
 * keeping you alive (sealed cabin, or suit oxygen with a bar and time left), or EXPOSED in red.
 * Shown on hostile planets and in space, and whenever a Survey Suit is worn.
 */
public final class EnvironmentHud {
	private static final int TEXT = 0xFFE6F2FF, DIM = 0xFF7F95AA, GOOD = 0xFF59E38C, WARN = 0xFFFFB347, BAD = 0xFFFF6A4D,
			CYAN = 0xFF55D8FF;

	private EnvironmentHud() {}

	public static void init() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, StarwakeMod.id("environment_hud"), EnvironmentHud::render);
	}

	/** Dose-rate and acute-dose card (M8c-3a), below the environment card. */
	private static void radiation(GuiGraphicsExtractor g, Font font, int x, int y, int width, com.starwake.process.Radiation.State state) {
		double rate = state.rate(), dose = state.dose();
		int color = dose >= 2 || rate >= 0.1 ? BAD : dose >= 0.5 || rate >= 1e-3 ? WARN : CYAN;
		g.fill(x - 4, y - 4, x + width + 4, y + 22, FlightHud.PANEL);
		FlightHud.corners(g, x - 4, y - 4, width + 8, 26, color);
		g.text(font, Component.translatable("hud.starwake.rad.rate"), x, y, DIM);
		String r = sievertsPerHour(rate);
		g.text(font, r, x + width - font.width(r), y, color);
		g.text(font, Component.translatable("hud.starwake.rad.dose"), x, y + 11, DIM);
		String d = sieverts(dose);
		g.text(font, d, x + width - font.width(d), y + 11, dose >= 2 ? BAD : dose >= 0.5 ? WARN : TEXT);
	}

	public static String sievertsPerHour(double svh) {
		if (svh >= 1) return String.format("%.2f Sv/h", svh);
		if (svh >= 1e-3) return String.format("%.1f mSv/h", svh * 1e3);
		return String.format("%.1f µSv/h", svh * 1e6);
	}

	public static String sieverts(double sv) {
		if (sv >= 1) return String.format("%.2f Sv", sv);
		if (sv >= 1e-3) return String.format("%.0f mSv", sv * 1e3);
		return String.format("%.2f mSv", sv * 1e3);
	}

	private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft minecraft = Minecraft.getInstance();
		var player = minecraft.player;
		if (player == null || minecraft.level == null) return;
		Hazards hazards = Planets.forDimension(minecraft.level.registryAccess(), minecraft.level.dimension())
				.map(p -> p.planet().hazards()).orElse(Hazards.EARTHLIKE);
		ItemStack suit = PlanetHazards.suit(player);
		Font font = minecraft.font;
		int width = 120, x = g.guiWidth() - width - 6;
		int y = player.getActiveEffects().isEmpty() ? 6 : 56;
		var radiation = player.getAttached(com.starwake.process.Radiation.STATE);
		boolean showRadiation = radiation != null && (radiation.rate() > 1e-6 || radiation.dose() > 1e-3);
		boolean showEnvironment = hazards.any() || !suit.isEmpty();
		if (showRadiation) radiation(g, font, x, showEnvironment ? y + 44 : y, width, radiation);
		if (!showEnvironment) return;

		g.fill(x - 4, y - 4, x + width + 4, y + 34, FlightHud.PANEL);
		FlightHud.corners(g, x - 4, y - 4, width + 8, 38, hazards.any() ? WARN : CYAN);

		// Line 1: air.
		Component air = !hazards.breathable() ? Component.translatable("hud.starwake.env.no_air")
				: hazards.toxic() ? Component.translatable("hud.starwake.env.toxic", Math.round(hazards.toxicity() * 100))
				: Component.translatable("hud.starwake.env.breathable");
		g.text(font, air, x, y, hazards.breathable() && !hazards.toxic() ? GOOD : WARN);
		String temp = Math.round(hazards.temperature()) + " °C";
		int tempColor = hazards.cold() ? CYAN : hazards.hot() ? BAD : TEXT;
		g.text(font, temp, x + width - font.width(temp), y, tempColor);

		// Line 2-3: shelter.
		PlanetHazards.Shelter shelter = PlanetHazards.shelter(player, hazards);
		if (shelter == PlanetHazards.Shelter.CABIN) {
			g.text(font, Component.translatable("hud.starwake.env.cabin"), x, y + 12, GOOD);
		} else if (!suit.isEmpty()) {
			int oxygen = SurveySuitItem.oxygen(suit);
			float f = oxygen / (float) SurveySuitItem.maxOxygen(suit);
			g.text(font, Component.translatable("hud.starwake.env.suit"), x, y + 12, DIM);
			int seconds = oxygen / 20;
			String left = String.format("%d:%02d", seconds / 60, seconds % 60);
			g.text(font, left, x + width - font.width(left), y + 12, f < 0.2f ? BAD : TEXT);
			g.fill(x, y + 23, x + width, y + 27, 0x60FFFFFF);
			g.fill(x, y + 23, x + Math.round(width * f), y + 27, f < 0.2f ? BAD : CYAN);
			if (!SurveySuitItem.protectsFrom(suit, hazards.temperature())) {
				g.text(font, Component.translatable("hud.starwake.env.suit_rating"), x, y + 29 - 1, BAD);
			}
		}
		if (shelter == PlanetHazards.Shelter.NONE && hazards.any() && (System.currentTimeMillis() / 400) % 2 == 0) {
			g.text(font, Component.translatable("hud.starwake.env.exposed"), x, suit.isEmpty() ? y + 12 : y + 29 - 1, BAD);
		}
	}
}
