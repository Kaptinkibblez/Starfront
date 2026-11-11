package com.starwake.client.trade;

import com.starwake.network.ContractBoardPayload;
import com.starwake.trade.Contract;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The Contract Board (M7): first your contracts that end at this hub (hand them in), then
 * today's offers (accept up to three at a time). Each row says what to do, where, the reward in
 * credits and reputation, and how long is left.
 */
public class ContractBoardScreen extends TerminalScreen {
	private List<Contract> completable;
	private List<Contract> offers;

	public ContractBoardScreen(ContractBoardPayload p) {
		super(Component.translatable("screen.starwake.contracts"), p.hub(), p.title(), p.faction(), p.credits());
		this.completable = p.completable();
		this.offers = p.offers();
	}

	public void update(ContractBoardPayload p) {
		this.completable = p.completable();
		this.offers = p.offers();
		this.credits = p.credits();
		selected = -1;
		rebuildWidgets();
	}

	private List<Contract> all() {
		List<Contract> list = new ArrayList<>(completable);
		list.addAll(offers);
		return list;
	}

	@Override
	protected void init() {
		int x = panelX(), y = panelY() + panelH() - 28, w = panelW();
		List<Contract> all = all();
		Contract sel = selected >= 0 && selected < all.size() ? all.get(selected) : null;
		boolean handIn = sel != null && selected < completable.size();
		Button act = addRenderableWidget(Button.builder(Component.translatable(handIn ? "screen.starwake.contracts.complete"
				: "screen.starwake.contracts.accept"), b -> {
			if (sel != null) send(handIn ? "complete" : "accept", sel.id(), 0);
		}).bounds(x + w - 112, y, 104, 20).build());
		act.active = sel != null && (handIn || TradeClient.contracts().size() < 3);
	}

	@Override
	protected int rowHeight() {
		return 24;
	}

	@Override
	protected int rows() {
		return completable.size() + offers.size();
	}

	@Override
	protected Component emptyText() {
		return Component.translatable("screen.starwake.contracts.none");
	}

	@Override
	protected void drawRow(GuiGraphicsExtractor g, int index, int x, int y, int w, boolean selected, int mouseX, int mouseY) {
		Contract c = all().get(index);
		boolean handIn = index < completable.size();
		if (!c.type().equals("exploration") && !c.type().equals("courier")) {
			g.item(new ItemStack(BuiltInRegistries.ITEM.getValue(c.item())), x, y + 3);
		}
		g.text(font, TradeClient.describe(c), x + 20, y + 3, handIn ? GOOD : TEXT, false);
		long left = c.expires() - (Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime());
		Component meta = Component.translatable(handIn ? "screen.starwake.contracts.hand_in" : "screen.starwake.contracts.offer",
				Component.translatable("contract.starwake.type." + c.type()), Math.max(0, left / 1000) / 24.0 >= 1
						? String.format("%.1f d", left / 24000.0) : String.format("%d h", Math.max(0, left / 1000)));
		g.text(font, meta, x + 20, y + 13, DIM, false);
		String reward = String.format("%,d cr  +%d", c.reward(), c.rep());
		g.text(font, reward, x + w - 4 - font.width(reward), y + 8, GOOD, false);
	}
}
