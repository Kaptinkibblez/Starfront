package com.starwake.client.wristcom;

import com.starwake.galaxy.SolarSystem;
import com.starwake.planet.Planets;
import com.starwake.ship.SpaceTravel;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Small shared readouts for Wristcom apps (location, clock). Client-side, from synced data. */
public final class WristcomStatus {
	private WristcomStatus() {}

	/** "Overworld", "Veyra", or "Space near Veyra". */
	public static Component location() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.player == null) return Component.literal("--");
		if (SpaceTravel.isSpace(minecraft.level)) {
			var near = SolarSystem.nearest(minecraft.level.registryAccess(), minecraft.player.position());
			return near.map(b -> Component.translatable("screen.starwake.wristcom.in_space_near", b.displayName()))
					.orElse(Component.translatable("planet.starwake.space"));
		}
		var planet = Planets.forDimension(minecraft.level.registryAccess(), minecraft.level.dimension());
		if (planet.isPresent()) return planet.get().planet().name();
		return Component.literal(minecraft.level.dimension().identifier().toString());
	}

	/** Local day clock ("Day 3  14:20") from the level's day time. */
	public static String clock() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) return "";
		long time = minecraft.level.getOverworldClockTime();
		long day = time / 24000 + 1, ticks = (time + 6000) % 24000;
		return String.format("Day %d  %02d:%02d", day, ticks / 1000, ticks % 1000 * 60 / 1000);
	}
}
