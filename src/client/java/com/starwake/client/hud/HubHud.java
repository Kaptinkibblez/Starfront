package com.starwake.client.hud;

import com.starwake.StarwakeMod;
import com.starwake.client.trade.TradeClient;
import com.starwake.galaxy.SolarSystem;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Trade hub beacons (M7): a diamond in the faction's colour on every station and outpost in your
 * current dimension, with its name and distance, so you can find a station's hangar mouth from
 * orbit or an outpost from the landing site. Hidden once you're inside (closer than 40 blocks).
 */
public final class HubHud {
	private HubHud() {}

	public static void init() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, StarwakeMod.id("hub_markers"), HubHud::render);
	}

	private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null) return;
		Camera camera = minecraft.gameRenderer.mainCamera();
		Matrix4f mvp = camera.getViewRotationProjectionMatrix(new Matrix4f());
		Vector3f forward = new Vector3f(camera.forwardVector());
		Vec3 eye = camera.position();
		int w = g.guiWidth(), h = g.guiHeight();
		var font = minecraft.font;
		for (var hub : TradeClient.hubs()) {
			if (!hub.dimension().equals(minecraft.level.dimension())) continue;
			Vec3 rel = hub.beacon().subtract(eye);
			double distance = rel.length();
			if (distance < 40) continue;
			int[] p = HudProjection.project(mvp, forward, rel, w, h);
			if (p == null) continue;
			int color = TradeClient.factionColor(hub.faction());
			int x = p[0], y = p[1];
			for (int i = 0; i <= 4; i++) {
				g.fill(x - i, y - 4 + i, x + i + 1, y - 3 + i, color);
				g.fill(x - i, y + 4 - i, x + i + 1, y + 5 - i, color);
			}
			g.fill(x - 1, y - 1, x + 2, y + 2, 0xFF081018);
			String label = hub.name();
			String range = SolarSystem.formatDistance(distance);
			g.text(font, label, x - font.width(label) / 2, y + 8, color);
			g.text(font, range, x - font.width(range) / 2, y + 18, 0xFF7F95AA);
		}
	}
}
