package com.starwake.client.metal;

import com.starwake.client.machine.MachineScreen;
import com.starwake.machine.MachineMenu;
import com.starwake.metal.AlloyGrade;
import com.starwake.metal.Composition;
import com.starwake.metal.Melt;
import com.starwake.metal.MeltFurnaceBlockEntity;
import com.starwake.metal.Metallurgy;
import com.starwake.network.MeltStatePayload;
import com.starwake.process.Species;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import org.jspecify.annotations.Nullable;

/**
 * Melting furnace screen (M8d-1): the machine panel plus a bath panel on the right with the live
 * composition, liquidus against the furnace limit, the analysed grade, the slag, a target grade with
 * the assistant's additions ({@link Melt#plan}), and the tap toggle. State comes from
 * {@link MeltStatePayload} every half second; buttons are container button clicks.
 */
public class MeltFurnaceScreen extends MachineScreen {
	private static final int PANEL = 0xF0151A21, EDGE = 0xFF55D8FF, TEXT = 0xFFE6F2FF, DIM = 0xFF8A93A0, GOOD = 0xFF7CFFB2, WARN = 0xFFFFB347,
			BAD = 0xFFFF6A4D, GOLD = 0xFFE8C860;
	private static final int SIDE_W = 168;
	private static @Nullable MeltStatePayload state;
	private Button tapButton;

	public MeltFurnaceScreen(MachineMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title);
	}

	public static void registerReceiver() {
		ClientPlayNetworking.registerGlobalReceiver(MeltStatePayload.TYPE, (p, c) -> state = p);
	}

	@Override
	protected void init() {
		super.init();
		leftPos -= (SIDE_W + 4) / 2;
		int sx = leftPos + imageWidth + 4;
		addRenderableWidget(Button.builder(Component.literal("<"), b -> click(2)).bounds(sx + 4, topPos + 114, 14, 12).build());
		addRenderableWidget(Button.builder(Component.literal(">"), b -> click(1)).bounds(sx + SIDE_W - 34, topPos + 114, 14, 12).build());
		addRenderableWidget(Button.builder(Component.literal("x"), b -> click(3)).bounds(sx + SIDE_W - 18, topPos + 114, 14, 12).build());
		tapButton = addRenderableWidget(Button.builder(Component.translatable("screen.starwake.melt.tap_off"), b -> click(0))
				.bounds(sx + 4, topPos + imageHeight - 16, SIDE_W - 8, 12).build());
	}

	private void click(int id) {
		var mode = Minecraft.getInstance().gameMode;
		if (mode != null) mode.handleInventoryButtonClick(menu.containerId, id);
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
		MeltStatePayload s = state;
		var font = this.font;
		int ty = y + 5;
		if (s == null || s.metal().isEmpty()) {
			g.text(font, Component.translatable("screen.starwake.melt.empty"), x + 5, ty, DIM);
			drawStatus(g, x + 5, y + 18, s);
			if (tapButton != null && s != null) tapButton.setMessage(Component.translatable(s.tapping() ? "screen.starwake.melt.tap_on" : "screen.starwake.melt.tap_off"));
			return;
		}
		double mass = 0;
		for (double v : s.metal().values()) mass += v;
		Composition comp = Composition.of(s.metal());
		var analysis = Metallurgy.analyze(comp);
		g.text(font, Component.translatable("screen.starwake.melt.bath", String.format("%.2f", mass)), x + 5, ty, TEXT);
		ty += 10;
		int liqColor = s.liquidus() > s.maxTemperature() ? BAD : s.liquidus() > s.maxTemperature() - 100 ? WARN : DIM;
		g.text(font, Component.translatable("screen.starwake.melt.liquidus", Math.round(s.liquidus()), Math.round(s.maxTemperature())), x + 5, ty,
				liqColor);
		ty += 10;
		g.text(font, analysis.name(), x + 5, ty, analysis.estimated() ? WARN : GOOD);
		ty += 11;
		// Composition, top 7, two columns.
		List<Composition.Part> parts = comp.parts();
		for (int i = 0; i < Math.min(8, parts.size()); i++) {
			Composition.Part p = parts.get(i);
			int cx = x + 5 + (i % 2) * 80, cy = ty + (i / 2) * 9;
			g.text(font, p.element() + " " + Composition.formatPercent(p.percent()) + "%", cx, cy, TEXT);
		}
		ty += 37;
		// Slag.
		double slag = 0;
		String main = null;
		double most = 0;
		for (var e : s.slag().entrySet()) {
			slag += e.getValue();
			if (e.getValue() > most) {
				most = e.getValue();
				main = e.getKey();
			}
		}
		g.text(font, slag <= 1e-6 ? Component.translatable("screen.starwake.melt.no_slag")
				: Component.translatable("screen.starwake.melt.slag", String.format("%.2f", slag), main == null ? "" : main), x + 5, ty, DIM);
		ty += 11;
		drawStatus(g, x + 5, ty, s);
		// Target row (buttons at y + 128).
		AlloyGrade target = s.target().isEmpty() ? null : Metallurgy.grade(Identifier.parse(s.target())).orElse(null);
		Component tname = target == null ? Component.translatable("screen.starwake.melt.no_target") : target.name();
		g.text(font, Component.translatable("screen.starwake.melt.target"), x + 5, y + 104, DIM);
		g.centeredText(font, tname, x + w / 2 - 8, y + 116, GOLD);
		if (target != null) {
			List<Melt.Step> steps = Melt.plan(s.metal(), target);
			int ly = y + 130;
			if (steps.isEmpty() || analysis.grade() == target) {
				g.text(font, Component.translatable("screen.starwake.melt.on_spec"), x + 5, ly, GOOD);
			} else {
				for (int i = 0; i < Math.min(4, steps.size()); i++) {
					Melt.Step st = steps.get(i);
					Component line = st.kg() > 0 ? Component.translatable("screen.starwake.melt.add", String.format("%.3f", st.kg()), st.element())
							: Component.translatable("screen.starwake.melt.blow", st.element(), String.format("%.3f", -st.kg()));
					g.text(font, line, x + 5, ly, st.kg() > 0 ? TEXT : WARN);
					ly += 9;
				}
			}
		}
		if (tapButton != null) {
			tapButton.setMessage(Component.translatable(s.tapping() ? "screen.starwake.melt.tap_on" : "screen.starwake.melt.tap_off"));
		}
	}

	private void drawStatus(GuiGraphicsExtractor g, int x, int y, @Nullable MeltStatePayload s) {
		int status = menu.get(MachineMenu.STATUS);
		Component text = switch (status) {
			case MeltFurnaceBlockEntity.MELTING -> Component.translatable("screen.starwake.melt.status.melting");
			case MeltFurnaceBlockEntity.TOO_REFRACTORY -> Component.translatable("screen.starwake.melt.status.too_hot");
			case MeltFurnaceBlockEntity.FULL -> Component.translatable("screen.starwake.melt.status.full");
			case MeltFurnaceBlockEntity.BLOWING -> Component.translatable("screen.starwake.melt.status.blowing");
			case MeltFurnaceBlockEntity.NO_POWER -> Component.translatable("screen.starwake.machine.no_power");
			default -> s != null && s.vacuum() ? Component.translatable("screen.starwake.melt.status.vacuum")
					: Component.translatable("screen.starwake.melt.status.air");
		};
		int color = status == MeltFurnaceBlockEntity.TOO_REFRACTORY || status == MeltFurnaceBlockEntity.FULL ? BAD
				: status == MeltFurnaceBlockEntity.BLOWING ? WARN : DIM;
		g.text(font, text, x, y, color);
	}
}
