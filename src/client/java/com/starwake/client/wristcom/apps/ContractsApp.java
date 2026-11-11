package com.starwake.client.wristcom.apps;

import com.starwake.client.trade.TradeClient;
import com.starwake.client.wristcom.WristcomApp;
import com.starwake.client.wristcom.WristcomScreen;
import com.starwake.network.TradeActionPayload;
import com.starwake.trade.Contract;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Wristcom Contracts app (M7): your active contracts (up to three): what, where, reward, time
 * left, and an Abandon button. Take new ones at hub Contract Boards.
 */
public class ContractsApp implements WristcomApp {
	@Override public Component title() { return Component.translatable("wristcom.app.contracts"); }
	@Override public String glyph() { return "Co"; }
	@Override public int accent() { return 0xFFFFC857; }

	@Override
	public void init(WristcomScreen screen, int x, int y, int width, int height) {
		List<Contract> list = TradeClient.contracts();
		for (int i = 0; i < list.size(); i++) {
			Contract c = list.get(i);
			screen.add(Button.builder(Component.translatable("wristcom.contracts.abandon"), b -> {
				ClientPlayNetworking.send(new TradeActionPayload("", "abandon", c.id(), 0));
				screen.refresh();
			}).bounds(x + width - 64, y + 20 + i * 40 + 8, 60, 16).build());
		}
	}

	@Override
	public void render(WristcomScreen screen, GuiGraphicsExtractor g, int x, int y, int width, int height, int mouseX, int mouseY, float partial) {
		var font = screen.font();
		List<Contract> list = TradeClient.contracts();
		g.text(font, Component.translatable("wristcom.contracts.active", list.size(), 3), x + 4, y + 4, accent(), false);
		if (list.isEmpty()) {
			g.text(font, Component.translatable("wristcom.contracts.none"), x + 4, y + 22, WristcomScreen.DIM, false);
			return;
		}
		long now = Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime();
		for (int i = 0; i < list.size(); i++) {
			Contract c = list.get(i);
			int ry = y + 20 + i * 40;
			g.fill(x + 2, ry - 2, x + width - 2, ry + 36, 0x14FFFFFF);
			g.text(font, TradeClient.describe(c), x + 6, ry, WristcomScreen.TEXT, false);
			g.text(font, Component.translatable("wristcom.contracts.for", TradeClient.factionName(c.faction())), x + 6, ry + 11,
					TradeClient.factionColor(c.faction()), false);
			long left = Math.max(0, c.expires() - now);
			g.text(font, String.format("%,d cr  +%d rep  ·  %.1f days left", c.reward(), c.rep(), left / 24000.0), x + 6, ry + 22,
					WristcomScreen.DIM, false);
		}
	}
}
