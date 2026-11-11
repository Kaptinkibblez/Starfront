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

	private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft minecraft = Minecraft.getInstance();
		var player = minecraft.player;
		if (player == null || minecraft.level == null) return;
		Hazards hazards = Planets.forDimension(minecraft.level.registryAccess(), minecraft.level.dimension())
				.map(p -> p.planet().hazards()).orElse(Hazards.EARTHLIKE);
		ItemStack suit = PlanetHazards.suit(player);
		if (!hazards.any() && suit.isEmpty()) return;

		Font font = minecraft.font;
		int width = 120, x = g.guiWidth() - width - 6;
		int y = player.getActiveEffects().isEmpty() ? 6 : 56;
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
			float f = oxygen / (float) SurveySuitItem.MAX_OXYGEN;
			g.text(font, Component.translatable("hud.starwake.env.suit"), x, y + 12, DIM);
			int seconds = oxygen / 20;
			String left = String.format("%d:%02d", seconds / 60, seconds % 60);
			g.text(font, left, x + width - font.width(left), y + 12, f < 0.2f ? BAD : TEXT);
			g.fill(x, y + 23, x + width, y + 27, 0x60FFFFFF);
			g.fill(x, y + 23, x + Math.round(width * f), y + 27, f < 0.2f ? BAD : CYAN);
			if (!SurveySuitItem.protectsFrom(hazards.temperature())) {
				g.text(font, Component.translatable("hud.starwake.env.suit_rating"), x, y + 29 - 1, BAD);
			}
		}
		if (shelter == PlanetHazards.Shelter.NONE && hazards.any() && (System.currentTimeMillis() / 400) % 2 == 0) {
			g.text(font, Component.translatable("hud.starwake.env.exposed"), x, suit.isEmpty() ? y + 12 : y + 29 - 1, BAD);
		}
	}
}
