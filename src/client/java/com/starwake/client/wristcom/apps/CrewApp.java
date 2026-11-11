package com.starwake.client.wristcom.apps;

import com.starwake.client.ship.ShipLocalMotion;
import com.starwake.client.wristcom.WristcomApp;
import com.starwake.client.wristcom.WristcomScreen;
import com.starwake.network.ShipCrewPayload;
import com.starwake.ship.ShipEntity;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Crew: who owns the ship you're aboard, its crew list and lock (3j). The owner adds crew by
 * name (they must be online), removes them, and locks or unlocks the ship. Owner and crew may
 * pilot; a locked ship keeps everyone else out of doors, seats and storage.
 */
public class CrewApp implements WristcomApp {
	private @Nullable EditBox nameField;

	@Override public Component title() { return Component.translatable("wristcom.app.crew"); }
	@Override public String glyph() { return "Cr"; }
	@Override public int accent() { return 0xFFFFC857; }

	private static @Nullable ShipEntity ship() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) return null;
		return minecraft.player.getVehicle() instanceof ShipEntity s ? s : ShipLocalMotion.activeShip();
	}

	private static boolean isOwner(ShipEntity ship) {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.player != null && ship.ownerName().equals(minecraft.player.getGameProfile().name());
	}

	@Override
	public void init(WristcomScreen screen, int x, int y, int width, int height) {
		nameField = null;
		ShipEntity ship = ship();
		if (ship == null || !isOwner(ship)) return;
		int right = x + width - 120;
		screen.add(Button.builder(Component.translatable(ship.isLocked() ? "wristcom.crew.unlock" : "wristcom.crew.lock"),
				b -> {
					ClientPlayNetworking.send(new ShipCrewPayload(ship.getId(), ship.isLocked() ? ShipCrewPayload.UNLOCK : ShipCrewPayload.LOCK, ""));
					screen.refreshSoon();
				}).bounds(right, y, 120, 18).build());
		nameField = screen.add(new EditBox(screen.font(), right, y + 30, 120, 18, Component.translatable("wristcom.crew.name")));
		nameField.setMaxLength(16);
		nameField.setHint(Component.translatable("wristcom.crew.name"));
		screen.add(Button.builder(Component.translatable("wristcom.crew.add"), b -> {
			if (nameField != null && !nameField.getValue().isBlank()) {
				ClientPlayNetworking.send(new ShipCrewPayload(ship.getId(), ShipCrewPayload.ADD, nameField.getValue().trim()));
				nameField.setValue("");
				screen.refreshSoon();
			}
		}).bounds(right, y + 50, 120, 18).build());
		List<String> crew = ship.crewNames();
		for (int i = 0; i < crew.size() && i < 8; i++) {
			String name = crew.get(i);
			screen.add(Button.builder(Component.literal("x"), b -> {
				ClientPlayNetworking.send(new ShipCrewPayload(ship.getId(), ShipCrewPayload.REMOVE, name));
				screen.refreshSoon();
			}).bounds(x + 110, y + 50 + i * 14, 12, 12).build());
		}
	}

	@Override
	public void render(WristcomScreen screen, GuiGraphicsExtractor g, int x, int y, int width, int height, int mouseX, int mouseY, float partial) {
		var font = screen.font();
		ShipEntity ship = ship();
		if (ship == null) {
			g.text(font, Component.translatable("wristcom.ship.none"), x + 4, y + 8, WristcomScreen.DIM, false);
			return;
		}
		String owner = ship.ownerName();
		g.text(font, Component.translatable("wristcom.crew.owner", owner.isEmpty() ? "-" : owner), x + 4, y + 4, WristcomScreen.TEXT, false);
		g.text(font, Component.translatable(ship.isLocked() ? "wristcom.crew.locked" : "wristcom.crew.unlocked"), x + 4, y + 18,
				ship.isLocked() ? 0xFFFFB347 : 0xFF7CFFB2, false);
		g.text(font, Component.translatable("wristcom.crew.list"), x + 4, y + 36, accent(), false);
		List<String> crew = ship.crewNames();
		if (crew.isEmpty()) g.text(font, Component.translatable("wristcom.crew.none"), x + 4, y + 52, WristcomScreen.DIM, false);
		for (int i = 0; i < crew.size() && i < 8; i++) {
			g.text(font, crew.get(i), x + 4, y + 52 + i * 14, WristcomScreen.TEXT, false);
		}
		if (!isOwner(ship)) {
			g.text(font, Component.translatable("wristcom.crew.owner_only"), x + 4, y + height - 12, WristcomScreen.DIM, false);
		}
	}
}
