package com.starwake.client.logistics;

import com.starwake.StarwakeMod;
import com.starwake.logistics.FluidPipeBlock;
import com.starwake.machine.CableBlock;
import com.starwake.network.LogisticsInfoPayload;
import com.starwake.network.LogisticsProbePayload;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

/**
 * Cable and pipe readout (M9): while the crosshair rests on a cable or pipe, asks the server twice a second for its
 * grid or flow numbers ({@link LogisticsProbePayload}) and draws the answer under the crosshair.
 */
public final class LogisticsReadout {
	private static @Nullable BlockPos looking;
	private static @Nullable BlockPos shown;
	private static List<Component> lines = List.of();
	private static int age;

	private LogisticsReadout() {}

	public static void init() {
		ClientPlayNetworking.registerGlobalReceiver(LogisticsInfoPayload.TYPE, (payload, context) -> {
			shown = payload.pos();
			lines = payload.lines();
			age = 0;
		});
		ClientTickEvents.END_CLIENT_TICK.register(LogisticsReadout::tick);
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, StarwakeMod.id("logistics_readout"), LogisticsReadout::render);
	}

	private static void tick(Minecraft mc) {
		age++;
		looking = null;
		if (mc.level == null || mc.player == null || mc.gui.screen() != null) return;
		HitResult hit = mc.hitResult;
		if (!(hit instanceof BlockHitResult b) || hit.getType() != HitResult.Type.BLOCK) return;
		var block = mc.level.getBlockState(b.getBlockPos()).getBlock();
		if (!(block instanceof CableBlock) && !(block instanceof FluidPipeBlock)) return;
		looking = b.getBlockPos().immutable();
		if (!looking.equals(shown) || age % 10 == 0) {
			if (!looking.equals(shown)) lines = List.of();
			ClientPlayNetworking.send(new LogisticsProbePayload(looking));
		}
	}

	private static void render(GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker delta) {
		if (looking == null || !looking.equals(shown) || lines.isEmpty() || age > 40) return;
		Minecraft mc = Minecraft.getInstance();
		var font = mc.font;
		int cx = graphics.guiWidth() / 2, y = graphics.guiHeight() / 2 + 12;
		int width = 0;
		for (Component line : lines) width = Math.max(width, font.width(line));
		graphics.fill(cx - width / 2 - 4, y - 3, cx + width / 2 + 4, y + lines.size() * 10 + 1, 0x90101418);
		for (Component line : lines) {
			graphics.text(font, line, cx - font.width(line) / 2, y, 0xFFD8E6F0);
			y += 10;
		}
	}
}
