package com.starwake.client.trade;

import com.starwake.network.ContractBoardPayload;
import com.starwake.trade.Contract;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The Contract Board (M7, player requests M9): your contracts that end here (hand them in), today's faction offers and
 * other players' supply requests in this system (accept up to three at a time), then your own posted requests (cancel
 * while nobody has taken them). The bottom row posts a request for the item in your hand (count + reward, paid into
 * escrow now) and collects goods other players delivered to you.
 */
public class ContractBoardScreen extends TerminalScreen {
	private List<Contract> completable;
	private List<Contract> offers;
	private List<Contract> mine;
	private Map<String, ItemStack> goods;
	private int pickups;
	private EditBox count, reward;

	public ContractBoardScreen(ContractBoardPayload p) {
		super(Component.translatable("screen.starwake.contracts"), p.hub(), p.title(), p.faction(), p.credits());
		read(p);
	}

	private void read(ContractBoardPayload p) {
		this.completable = p.completable();
		this.offers = p.offers();
		this.mine = p.mine();
		this.goods = p.goods();
		this.pickups = p.pickups();
	}

	public void update(ContractBoardPayload p) {
		read(p);
		this.credits = p.credits();
		selected = -1;
		rebuildWidgets();
	}

	private List<Contract> all() {
		List<Contract> list = new ArrayList<>(completable);
		list.addAll(offers);
		list.addAll(mine);
		return list;
	}

	@Override
	protected int listBottom() {
		return panelY() + panelH() - 56;
	}

	@Override
	protected void init() {
		int x = panelX(), y = panelY() + panelH() - 28, w = panelW();
		List<Contract> all = all();
		Contract sel = selected >= 0 && selected < all.size() ? all.get(selected) : null;
		boolean handIn = sel != null && selected < completable.size();
		boolean own = sel != null && selected >= completable.size() + offers.size();
		Button act = addRenderableWidget(Button.builder(Component.translatable(handIn ? "screen.starwake.contracts.complete"
				: own ? "screen.starwake.contracts.cancel" : "screen.starwake.contracts.accept"), b -> {
			if (sel != null) send(handIn ? "complete" : own ? "cancel" : "accept", sel.id(), 0);
		}).bounds(x + w - 112, y, 104, 20).build());
		act.active = sel != null && (handIn || own || TradeClient.contracts().size() < 3);
		// Post a request for the held item; collect delivered goods.
		int py = y - 24;
		count = addRenderableWidget(new EditBox(font, x + 8, py, 44, 18, Component.translatable("screen.starwake.contracts.count")));
		count.setHint(Component.translatable("screen.starwake.contracts.count"));
		reward = addRenderableWidget(new EditBox(font, x + 56, py, 70, 18, Component.translatable("screen.starwake.contracts.reward")));
		reward.setHint(Component.translatable("screen.starwake.contracts.reward"));
		addRenderableWidget(Button.builder(Component.translatable("screen.starwake.contracts.post"), b -> {
			if (!count.getValue().isEmpty() && !reward.getValue().isEmpty()) send("post", count.getValue() + ":" + reward.getValue(), 0);
		}).bounds(x + 130, py, 110, 18).build());
		Button collect = addRenderableWidget(Button.builder(Component.translatable("screen.starwake.contracts.collect", pickups),
				b -> send("collect", "", 0)).bounds(x + 8, y, 120, 20).build());
		collect.active = pickups > 0;
	}

	@Override
	protected int rowHeight() {
		return 24;
	}

	@Override
	protected int rows() {
		return completable.size() + offers.size() + mine.size();
	}

	@Override
	protected Component emptyText() {
		return Component.translatable("screen.starwake.contracts.none");
	}

	@Override
	protected void drawRow(GuiGraphicsExtractor g, int index, int x, int y, int w, boolean selected, int mouseX, int mouseY) {
		Contract c = all().get(index);
		boolean handIn = index < completable.size();
		boolean own = index >= completable.size() + offers.size();
		if (!c.type().equals("exploration") && !c.type().equals("courier")) {
			ItemStack icon = goods.getOrDefault(c.id(), new ItemStack(BuiltInRegistries.ITEM.getValue(c.item())));
			g.item(icon, x, y + 3);
			if (mouseX >= x && mouseX < x + 16 && mouseY >= y + 3 && mouseY < y + 19) g.setTooltipForNextFrame(font, icon, mouseX, mouseY);
		}
		Component text = goods.containsKey(c.id())
				? Component.translatable("contract.starwake.player", c.count(), goods.get(c.id()).getHoverName(), c.targetName())
				: TradeClient.describe(c);
		g.text(font, font.plainSubstrByWidth(text.getString(), w - 110), x + 20, y + 3, handIn ? GOOD : own ? DIM : TEXT, false);
		long left = c.expires() - (Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime());
		String time = left >= 24000 ? String.format("%.1f d", left / 24000.0) : String.format("%d h", Math.max(0, left / 1000));
		Component meta = own ? Component.translatable("screen.starwake.contracts.yours", time)
				: Component.translatable(handIn ? "screen.starwake.contracts.hand_in" : "screen.starwake.contracts.offer",
						c.type().equals("player") ? TradeClient.factionName(c.faction()) : Component.translatable("contract.starwake.type." + c.type()), time);
		g.text(font, meta, x + 20, y + 13, DIM, false);
		String reward = c.rep() > 0 ? String.format("%,d cr  +%d", c.reward(), c.rep()) : String.format("%,d cr", c.reward());
		g.text(font, reward, x + w - 4 - font.width(reward), y + 8, GOOD, false);
	}
}
