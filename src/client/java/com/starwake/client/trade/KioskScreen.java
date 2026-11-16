package com.starwake.client.trade;

import com.starwake.network.KioskPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Trade Kiosk screen (M9 player shops): the kiosk's stock with prices. Buyers pick a row and buy 1, 8 or the whole
 * stack; the owner sets the selected slot's price (0 = not for sale). The owner stocks it by sneak-using the kiosk.
 */
public class KioskScreen extends TerminalScreen {
	private KioskPayload data;
	private EditBox price;

	public KioskScreen(KioskPayload p) {
		super(Component.translatable("screen.starwake.kiosk"), "", Component.translatable("screen.starwake.kiosk.owner", p.owner()), "", p.credits());
		this.data = p;
	}

	public static void receive(KioskPayload p) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() instanceof KioskScreen open && open.data.pos().equals(p.pos())) {
			open.data = p;
			open.credits = p.credits();
			open.rebuildWidgets();
		} else {
			mc.gui.setScreen(new KioskScreen(p));
		}
	}

	private void act(String action, long value) {
		if (selected < 0 || selected >= data.lines().size()) return;
		ClientPlayNetworking.send(new KioskPayload.Action(data.pos(), action, data.lines().get(selected).slot(), value));
	}

	@Override
	protected void init() {
		int x = panelX(), y = panelY() + panelH() - 28, w = panelW();
		boolean sel = selected >= 0 && selected < data.lines().size();
		if (data.isOwner()) {
			price = addRenderableWidget(new EditBox(font, x + 8, y + 1, 90, 18, Component.translatable("screen.starwake.kiosk.price")));
			price.setHint(Component.translatable("screen.starwake.kiosk.price"));
			Button set = addRenderableWidget(Button.builder(Component.translatable("screen.starwake.kiosk.set_price"), b -> {
				try {
					act("price", Long.parseLong(price.getValue().trim()));
				} catch (NumberFormatException ignored) {
				}
			}).bounds(x + 102, y, 90, 20).build());
			set.active = sel;
			return;
		}
		int[] amounts = {1, 8, 64};
		int bx = x + w - 3 * 76 - 8;
		for (int n : amounts) {
			Button b = addRenderableWidget(Button.builder(Component.translatable("screen.starwake.kiosk.buy", n), btn -> act("buy", n))
					.bounds(bx, y, 72, 20).build());
			b.active = sel && data.lines().get(selected).price() > 0 && credits >= data.lines().get(selected).price();
			bx += 76;
		}
	}

	@Override
	protected int rowHeight() {
		return 20;
	}

	@Override
	protected int rows() {
		return data.lines().size();
	}

	@Override
	protected Component emptyText() {
		return Component.translatable(data.isOwner() ? "screen.starwake.kiosk.empty_owner" : "screen.starwake.kiosk.empty");
	}

	@Override
	protected void drawRow(GuiGraphicsExtractor g, int index, int x, int y, int w, boolean selected, int mouseX, int mouseY) {
		KioskPayload.Line l = data.lines().get(index);
		g.item(l.stack(), x, y + 2);
		g.text(font, font.plainSubstrByWidth(l.stack().getHoverName().getString(), w - 140), x + 20, y + 6, TEXT, false);
		String count = "x" + l.stack().getCount();
		g.text(font, count, x + w - 110, y + 6, DIM, false);
		String p = l.price() > 0 ? String.format("%,d cr", l.price()) : Component.translatable("screen.starwake.kiosk.not_for_sale").getString();
		g.text(font, p, x + w - 4 - font.width(p), y + 6, l.price() > 0 ? GOOD : DIM, false);
		if (mouseX >= x && mouseX < x + 16 && mouseY >= y + 2 && mouseY < y + 18) g.setTooltipForNextFrame(font, l.stack(), mouseX, mouseY);
	}
}
