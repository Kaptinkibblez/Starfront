package com.starwake.client.trade;

import com.starwake.network.ShopPayload;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The Outfitter (M7): ship components, suits and tools at set prices (moved by your standing).
 * Some items need a minimum reputation with the hub's faction; they show the rank required.
 */
public class OutfitterScreen extends TerminalScreen {
	private List<ShopPayload.Line> lines;

	public OutfitterScreen(ShopPayload p) {
		super(Component.translatable("screen.starwake.outfitter"), p.hub(), p.title(), p.faction(), p.credits());
		this.lines = p.lines();
	}

	public void update(ShopPayload p) {
		this.lines = p.lines();
		this.credits = p.credits();
		rebuildWidgets();
	}

	@Override
	protected void init() {
		int x = panelX(), y = panelY() + panelH() - 28, w = panelW();
		ShopPayload.Line sel = selected >= 0 && selected < lines.size() ? lines.get(selected) : null;
		final int index = selected;
		Button buy = addRenderableWidget(Button.builder(Component.translatable("screen.starwake.market.buy"),
				b -> send("shop", "", index)).bounds(x + w - 92, y, 84, 20).build());
		buy.active = sel != null && !sel.locked() && credits >= sel.price();
	}

	@Override
	protected int rowHeight() {
		return 19;
	}

	@Override
	protected int rows() {
		return lines.size();
	}

	@Override
	protected void drawRow(GuiGraphicsExtractor g, int index, int x, int y, int w, boolean selected, int mouseX, int mouseY) {
		ShopPayload.Line l = lines.get(index);
		ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(l.item()), l.count());
		g.item(stack, x, y + 1);
		g.itemDecorations(font, stack, x, y + 1);
		g.text(font, font.plainSubstrByWidth(stack.getHoverName().getString(), w - 170), x + 22, y + 5, l.locked() ? DIM : TEXT, false);
		if (l.locked()) {
			Component need = Component.translatable("screen.starwake.outfitter.needs", TradeClient.rankName(l.rep()));
			g.text(font, need, x + w - 90 - font.width(need), y + 5, WARN, false);
		}
		String price = String.format("%,d cr", l.price());
		g.text(font, price, x + w - 4 - font.width(price), y + 5, credits >= l.price() ? TEXT : BAD, false);
		if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 18) g.setTooltipForNextFrame(font, stack, mouseX, mouseY);
	}
}
