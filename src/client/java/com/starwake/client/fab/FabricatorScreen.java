package com.starwake.client.fab;

import com.starwake.client.machine.MachineScreen;
import com.starwake.fab.Blueprint;
import com.starwake.fab.FabricatorBlockEntity;
import com.starwake.machine.MachineMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Fabricator screen (M8e-1): the machine's nine input slots and output on the left; a side panel with
 * the selected blueprint (&lt; &gt; to change it), its product, and every input as a line: green when
 * satisfied, grey when missing, orange naming the gate the present items fail ("service 450 &lt; 700 C").
 * The same {@link Blueprint#match} the server runs is evaluated here on the menu's slots, so the panel
 * also previews the product's quality.
 */
public class FabricatorScreen extends MachineScreen {
	private static final int PANEL = 0xF0151A21, EDGE = 0xFF55D8FF, TEXT = 0xFFE6F2FF, DIM = 0xFF8A93A0, GOOD = 0xFF7CFFB2, WARN = 0xFFFFB347,
			GOLD = 0xFFE8C860;
	private static final int SIDE_W = 190;

	public FabricatorScreen(MachineMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title);
	}

	@Override
	protected void init() {
		super.init();
		leftPos -= (SIDE_W + 4) / 2;
		int sx = leftPos + imageWidth + 4;
		addRenderableWidget(Button.builder(Component.literal("<"), b -> click(0)).bounds(sx + 4, topPos + 4, 14, 12).build());
		addRenderableWidget(Button.builder(Component.literal(">"), b -> click(1)).bounds(sx + SIDE_W - 18, topPos + 4, 14, 12).build());
	}

	private void click(int id) {
		var mode = Minecraft.getInstance().gameMode;
		if (mode != null) mode.handleInventoryButtonClick(menu.containerId, id);
	}

	private Map.@Nullable Entry<Identifier, Blueprint> selected() {
		var level = Minecraft.getInstance().level;
		if (level == null) return null;
		var all = level.registryAccess().lookup(Blueprint.REGISTRY_KEY).map(Blueprint::sorted).orElse(List.of());
		if (all.isEmpty()) return null;
		return all.get(Math.floorMod(menu.get(FabricatorBlockEntity.SELECTED), all.size()));
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractBackground(g, mouseX, mouseY, partial);
		int x = leftPos + imageWidth + 4, y = topPos, w = SIDE_W, h = imageHeight;
		g.fill(x, y, x + w, y + h, PANEL);
		g.fill(x, y, x + w, y + 1, EDGE);
		g.fill(x, y + h - 1, x + w, y + h, EDGE);
		g.fill(x, y, x + 1, y + h, EDGE);
		g.fill(x + w - 1, y, x + w, y + h, EDGE);
		var entry = selected();
		if (entry == null) {
			g.text(font, Component.translatable("screen.starwake.fabricator.none"), x + 5, y + 22, DIM);
			return;
		}
		Blueprint bp = entry.getValue();
		ItemStack product = new ItemStack(BuiltInRegistries.ITEM.getValue(bp.output()), bp.count());
		Component name = bp.count() > 1 ? Component.literal(bp.count() + " x ").append(product.getHoverName()) : product.getHoverName();
		g.centeredText(font, name, x + w / 2, y + 6, GOLD);
		g.text(font, Component.translatable("screen.starwake.fabricator.energy", String.format("%.1f", bp.energy() / 1000.0)), x + 5, y + 20, DIM);

		List<ItemStack> slots = new ArrayList<>();
		for (int i = 0; i < FabricatorBlockEntity.INPUTS; i++) slots.add(menu.getSlot(i).getItem());
		Blueprint.Match match = bp.match(slots);
		int ly = y + 32;
		for (int n = 0; n < bp.inputs().size(); n++) {
			Blueprint.Input in = bp.inputs().get(n);
			int st = match.status()[n];
			int color = st == -1 ? GOOD : st == -2 ? DIM : WARN;
			g.text(font, Component.literal((st == -1 ? "✔ " : st == -2 ? "• " : "✖ ") + in.count() + " x ").append(what(in))
					.append(in.main() ? Component.literal(" *") : Component.empty()), x + 5, ly, color);
			ly += 9;
			List<String> gates = in.gates();
			StringBuilder line = new StringBuilder();
			for (int g2 = 0; g2 < gates.size(); g2++) {
				String[] kv = gates.get(g2).split(":");
				double limit = kv[0].equals("min") ? in.min().get(kv[1]) : in.max().get(kv[1]);
				if (line.length() > 0) line.append(", ");
				line.append(kv[1]).append(kv[0].equals("min") ? " ≥ " : " ≤ ").append(fmt(limit));
				String unit = Blueprint.unit(kv[1]);
				if (unit != null) line.append(' ').append(unit);
			}
			if (in.base().isPresent()) line.append(line.length() > 0 ? ", " : "").append(in.base().get());
			if (line.length() > 0) {
				g.text(font, Component.literal("   " + line), x + 5, ly, DIM);
				ly += 9;
			}
			if (st >= 0) {
				// Name the failing gate with the best present item's value.
				String[] kv = gates.get(st).split(":");
				double actual = Double.NaN;
				for (ItemStack stack : slots) {
					if (in.kind(stack)) {
						actual = Blueprint.property(stack, kv[1]);
						break;
					}
				}
				g.text(font, Component.translatable("screen.starwake.fabricator.fails", kv[1], Double.isNaN(actual) ? "?" : fmt(actual)), x + 5, ly, WARN);
				ly += 9;
			}
			ly += 2;
		}
		if (match.complete()) {
			g.text(font, Component.translatable("screen.starwake.fabricator.quality", String.format("%.2f", match.quality())), x + 5, y + h - 14, GOOD);
		}
	}

	private static Component what(Blueprint.Input in) {
		if (in.item().isPresent()) return new ItemStack(BuiltInRegistries.ITEM.getValue(in.item().get())).getHoverName();
		if (in.form().isPresent()) return Component.translatable("screen.starwake.fabricator.form." + in.form().get());
		return Component.translatable("screen.starwake.fabricator.element", in.element().orElse("?"));
	}

	private static String fmt(double v) {
		return Math.abs(v - Math.round(v)) < 1e-6 ? Long.toString(Math.round(v)) : String.format("%.1f", v);
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		super.extractLabels(g, mouseX, mouseY);
		int status = menu.get(MachineMenu.STATUS);
		Component st = Component.translatable("screen.starwake.fabricator.status." + status);
		g.text(font, st, imageWidth - 8 - font.width(st), inventoryLabelY, status == FabricatorBlockEntity.BLOCKED ? WARN : DIM, false);
	}
}
