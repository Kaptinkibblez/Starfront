package com.starwake.client.trade;

import com.starwake.network.MarketPayload;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The Commodity Terminal (M7): every commodity the hub trades, with its stock, the price you pay
 * and the price you get, and how many you carry. Pick a row and a quantity, then Buy or Sell. A
 * green "P" marks what the hub produces (cheap here), an amber "C" what it consumes (pays well).
 * M9: the Industrial tab lists what you carry that the hub buys by composition (alloys, concentrates,
 * components, parts) with the price per item; pick one and sell a quantity.
 */
public class MarketScreen extends TerminalScreen {
	private static final int[] QUANTITIES = {1, 8, 32, 64};
	private List<MarketPayload.Quote> quotes;
	private List<MarketPayload.Offer> offers;
	private int quantity = 1;
	private static boolean industrial;

	public MarketScreen(MarketPayload p) {
		super(Component.translatable("screen.starwake.market"), p.hub(), p.title(), p.faction(), p.credits());
		this.quotes = p.quotes();
		this.offers = p.industrial();
	}

	/** New data from the server (after a trade): keep the selection and scroll. */
	public void update(MarketPayload p) {
		this.quotes = p.quotes();
		this.offers = p.industrial();
		this.credits = p.credits();
		if (industrial && selected >= offers.size()) selected = -1;
		rebuildWidgets();
	}

	@Override
	protected void init() {
		int x = panelX(), y = panelY() + panelH() - 28, w = panelW();
		int bx = x + 8;
		for (int q : QUANTITIES) {
			Button b = addRenderableWidget(Button.builder(Component.literal("x" + q), btn -> {
				quantity = q;
				rebuildWidgets();
			}).bounds(bx, y, 34, 20).build());
			b.active = quantity != q;
			bx += 36;
		}
		// Tabs (title row, between the terminal name and the credits).
		int tx = x + w / 2 - 59, ty = panelY() + 4;
		Button commodities = addRenderableWidget(Button.builder(Component.translatable("screen.starwake.market.tab_commodities"), b -> {
			industrial = false;
			selected = -1;
			rebuildWidgets();
		}).bounds(tx, ty, 58, 14).build());
		Button ind = addRenderableWidget(Button.builder(Component.translatable("screen.starwake.market.tab_industrial"), b -> {
			industrial = true;
			selected = -1;
			rebuildWidgets();
		}).bounds(tx + 60, ty, 58, 14).build());
		commodities.active = industrial;
		ind.active = !industrial;
		if (industrial) {
			MarketPayload.Offer offer = selected >= 0 && selected < offers.size() ? offers.get(selected) : null;
			Button sell = addRenderableWidget(Button.builder(Component.translatable("screen.starwake.market.sell"),
					b -> { if (offer != null) send("isell", String.valueOf(offer.slot()), quantity); }).bounds(x + w - 92, y, 84, 20).build());
			sell.active = offer != null;
			return;
		}
		MarketPayload.Quote sel = selected >= 0 && selected < quotes.size() ? quotes.get(selected) : null;
		Button buy = addRenderableWidget(Button.builder(Component.translatable("screen.starwake.market.buy"),
				b -> { if (sel != null) send("buy", sel.item().toString(), quantity); }).bounds(x + w - 178, y, 84, 20).build());
		Button sell = addRenderableWidget(Button.builder(Component.translatable("screen.starwake.market.sell"),
				b -> { if (sel != null) send("sell", sel.item().toString(), quantity); }).bounds(x + w - 92, y, 84, 20).build());
		buy.active = sel != null && sel.stock() > 0 && credits >= sel.buy();
		sell.active = sel != null && sel.have() > 0;
	}

	@Override
	protected Component emptyText() {
		return industrial ? Component.translatable("screen.starwake.market.industrial_empty") : super.emptyText();
	}

	@Override
	protected int rowHeight() {
		return 19;
	}

	@Override
	protected int rows() {
		return industrial ? offers.size() : quotes.size();
	}

	@Override
	protected void drawRow(GuiGraphicsExtractor g, int index, int x, int y, int w, boolean selected, int mouseX, int mouseY) {
		if (industrial) {
			MarketPayload.Offer o = offers.get(index);
			g.item(o.stack(), x, y + 1);
			g.text(font, font.plainSubstrByWidth(o.stack().getHoverName().getString(), w - 200), x + 20, y + 5, TEXT, false);
			Component cat = Component.translatable("screen.starwake.market.category." + o.category());
			g.text(font, cat, x + w - 178, y + 5, DIM, false);
			String price = o.price() + "";
			g.text(font, price, x + w - 58 - font.width(price), y + 5, GOOD, false);
			String have = "x" + o.stack().getCount();
			g.text(font, have, x + w - 4 - font.width(have), y + 5, TEXT, false);
			if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 18) g.setTooltipForNextFrame(font, o.stack(), mouseX, mouseY);
			return;
		}
		MarketPayload.Quote q = quotes.get(index);
		ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(q.item()));
		g.item(stack, x, y + 1);
		g.text(font, q.producer() ? "P" : "C", x + 19, y + 5, q.producer() ? GOOD : WARN, false);
		String name = font.plainSubstrByWidth(stack.getHoverName().getString(), w - 270);
		g.text(font, name, x + 28, y + 5, TEXT, false);
		// Stock bar.
		int bx = x + w - 236, bw = 50;
		g.fill(bx, y + 7, bx + bw, y + 11, 0x40FFFFFF);
		g.fill(bx, y + 7, bx + Math.round(bw * Math.min(1f, q.stock() / (float) Math.max(1, q.capacity()))), y + 11, q.producer() ? GOOD : WARN);
		String stock = String.valueOf(q.stock());
		g.text(font, stock, bx + bw + 4, y + 5, DIM, false);
		String buy = q.buy() + "", sell = q.sell() + "";
		g.text(font, buy, x + w - 118 - font.width(buy), y + 5, q.stock() > 0 ? TEXT : DIM, false);
		g.text(font, sell, x + w - 58 - font.width(sell), y + 5, TEXT, false);
		String have = q.have() > 0 ? "x" + q.have() : "";
		g.text(font, have, x + w - 4 - font.width(have), y + 5, GOOD, false);
		if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 18) g.setTooltipForNextFrame(font, stack, mouseX, mouseY);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		int x = panelX() + 8, y = listTop() - 9, w = panelW() - 16;
		if (industrial) {
			g.text(font, Component.translatable("screen.starwake.market.product"), x + 20, y, DIM, false);
			g.text(font, Component.translatable("screen.starwake.market.category"), x + w - 178, y, DIM, false);
			Component each = Component.translatable("screen.starwake.market.each");
			g.text(font, each, x + w - 58 - font.width(each), y, DIM, false);
			return;
		}
		g.text(font, Component.translatable("screen.starwake.market.commodity"), x + 28, y, DIM, false);
		g.text(font, Component.translatable("screen.starwake.market.stock"), x + w - 236, y, DIM, false);
		Component buy = Component.translatable("screen.starwake.market.buy_price"), sell = Component.translatable("screen.starwake.market.sell_price");
		g.text(font, buy, x + w - 118 - font.width(buy), y, DIM, false);
		g.text(font, sell, x + w - 58 - font.width(sell), y, DIM, false);
	}
}
