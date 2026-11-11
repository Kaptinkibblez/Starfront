package com.starwake.client.trade;

import com.mojang.blaze3d.platform.InputConstants;
import com.starwake.client.hud.FlightHud;
import com.starwake.network.TradeActionPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Shared frame for hub terminals (M7): a holographic panel like the Wristcom with the hub name,
 * its faction, your credits and standing, and a scrollable list of rows you can select. The
 * market, outfitter and contract board screens fill in the rows and actions.
 */
public abstract class TerminalScreen extends Screen {
	protected static final int BG = 0xE0081018, FRAME = 0xFF55D8FF, TEXT = 0xFFE6F2FF, DIM = 0xFF7F95AA, GOOD = 0xFF7CFFB2,
			BAD = 0xFFFF6A4D, WARN = 0xFFFFB347, SELECT = 0x4055D8FF, ROWBG = 0x18FFFFFF;

	protected final String hub;
	protected final Component hubName;
	protected final String faction;
	protected long credits;
	protected int selected = -1;
	protected int scroll;

	protected TerminalScreen(Component title, String hub, Component hubName, String faction, long credits) {
		super(title);
		this.hub = hub;
		this.hubName = hubName;
		this.faction = faction;
		this.credits = credits;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	protected int panelX() { return Math.max(8, width / 2 - 230); }
	protected int panelY() { return Math.max(8, height / 2 - 125); }
	protected int panelW() { return Math.min(width - 16, 460); }
	protected int panelH() { return Math.min(height - 16, 250); }
	protected int listTop() { return panelY() + 40; }
	protected int listBottom() { return panelY() + panelH() - 34; }
	protected abstract int rowHeight();
	protected abstract int rows();
	protected abstract void drawRow(GuiGraphicsExtractor g, int index, int x, int y, int w, boolean selected, int mouseX, int mouseY);

	protected int visibleRows() {
		return Math.max(1, (listBottom() - listTop()) / rowHeight());
	}

	protected void send(String action, String arg, int count) {
		ClientPlayNetworking.send(new TradeActionPayload(hub, action, arg, count));
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(g, mouseX, mouseY, partialTick);
		int x = panelX(), y = panelY(), w = panelW(), h = panelH();
		g.fill(x, y, x + w, y + h, BG);
		FlightHud.corners(g, x - 2, y - 2, w + 4, h + 4, FRAME);
		for (int sy = y + 2; sy < y + h; sy += 3) g.fill(x + 1, sy, x + w - 1, sy + 1, 0x08FFFFFF);
		g.fill(x + 6, y + 30, x + w - 6, y + 31, 0x3055D8FF);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		int x = panelX(), y = panelY(), w = panelW();
		g.text(font, title, x + 8, y + 6, FRAME, false);
		g.text(font, hubName, x + 8, y + 17, TEXT, false);
		String cr = String.format("%,d cr", credits);
		g.text(font, cr, x + w - 8 - font.width(cr), y + 6, GOOD, false);
		Component standing = TradeClient.standing(faction);
		g.text(font, standing, x + w - 8 - font.width(standing), y + 17, DIM, false);

		int top = listTop(), rh = rowHeight();
		scroll = Math.clamp(scroll, 0, Math.max(0, rows() - visibleRows()));
		g.enableScissor(x + 4, top, x + w - 4, listBottom());
		for (int i = scroll; i < rows() && i < scroll + visibleRows(); i++) {
			int ry = top + (i - scroll) * rh;
			boolean sel = i == selected;
			g.fill(x + 6, ry, x + w - 6, ry + rh - 1, sel ? SELECT : (i % 2 == 0 ? ROWBG : 0));
			drawRow(g, i, x + 8, ry, w - 16, sel, mouseX, mouseY);
		}
		g.disableScissor();
		if (rows() > visibleRows()) {
			int barH = listBottom() - top;
			int knob = Math.max(10, barH * visibleRows() / rows());
			int ky = top + (barH - knob) * scroll / Math.max(1, rows() - visibleRows());
			g.fill(x + w - 5, ky, x + w - 3, ky + knob, 0x8055D8FF);
		}
		if (rows() == 0) g.centeredText(font, emptyText(), x + w / 2, top + 20, DIM);
	}

	protected Component emptyText() {
		return Component.translatable("screen.starwake.terminal.empty");
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && event.y() >= listTop() && event.y() < listBottom()
				&& event.x() >= panelX() && event.x() < panelX() + panelW()) {
			int i = scroll + (int) ((event.y() - listTop()) / rowHeight());
			if (i < rows()) {
				selected = i;
				selectionChanged();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	protected void selectionChanged() {
		rebuildWidgets();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		scroll -= (int) Math.signum(scrollY) * 2;
		return true;
	}
}
