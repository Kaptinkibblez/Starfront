package com.starwake.client.metal;

import com.starwake.client.machine.MachineScreen;
import com.starwake.machine.MachineMenu;
import com.starwake.metal.FormingBlockEntity;
import com.starwake.metal.HeatTreatFurnaceBlockEntity;
import com.starwake.metal.Tempers;
import com.starwake.registry.ModMetalworks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Screens for the M8d-2 metalworking machines: the forming machines (hot/cold toggle, strain done
 * against the route, hot-work temperature, status) and the Heat Treatment Furnace (program and
 * setpoint buttons, furnace temperature, status). Buttons are container button clicks handled by
 * the block entity's {@code onButton}.
 */
public class MetalworkScreen extends MachineScreen {
	private static final int TEXT = 0xFFE6F2FF, DIM = 0xFF8A93A0, WARN = 0xFFFFB347, HOT = 0xFFFF9A4A, COLD = 0xFF7FC8E8;
	private final boolean furnace;
	private Button modeButton;

	public MetalworkScreen(MachineMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title);
		furnace = menu.getType() == ModMetalworks.HEAT_TREAT_MENU;
	}

	@Override
	protected void init() {
		super.init();
		if (furnace) {
			addRenderableWidget(Button.builder(Component.literal("<"), b -> click(HeatTreatFurnaceBlockEntity.BUTTON_PREV))
					.bounds(leftPos + 24, topPos + 42, 12, 12).build());
			addRenderableWidget(Button.builder(Component.literal(">"), b -> click(HeatTreatFurnaceBlockEntity.BUTTON_NEXT))
					.bounds(leftPos + 156, topPos + 42, 12, 12).build());
			addRenderableWidget(Button.builder(Component.literal("-"), b -> click(HeatTreatFurnaceBlockEntity.BUTTON_COLDER))
					.bounds(leftPos + 24, topPos + 56, 12, 12).build());
			addRenderableWidget(Button.builder(Component.literal("+"), b -> click(HeatTreatFurnaceBlockEntity.BUTTON_HOTTER))
					.bounds(leftPos + 156, topPos + 56, 12, 12).build());
		} else {
			modeButton = addRenderableWidget(Button.builder(Component.translatable("screen.starwake.forming.cold"), b -> click(0))
					.bounds(leftPos + 108, topPos + 44, 62, 14).build());
		}
	}

	private void click(int id) {
		var mode = Minecraft.getInstance().gameMode;
		if (mode != null) mode.handleInventoryButtonClick(menu.containerId, id);
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		super.extractLabels(g, mouseX, mouseY);
		int status = menu.get(MachineMenu.STATUS);
		if (furnace) {
			Tempers.Program[] all = Tempers.Program.values();
			Tempers.Program p = all[Math.floorMod(menu.get(HeatTreatFurnaceBlockEntity.PROGRAM), all.length)];
			Component name = Component.translatable("program.starwake." + p.name().toLowerCase(java.util.Locale.ROOT));
			g.text(font, name, 96 - font.width(name) / 2, 44, TEXT, false);
			Component set = Component.translatable("screen.starwake.heat_treat.setpoint", menu.get(HeatTreatFurnaceBlockEntity.SETPOINT));
			g.text(font, set, 96 - font.width(set) / 2, 58, p.setTemperature() ? TEXT : DIM, false);
			int t = menu.get(HeatTreatFurnaceBlockEntity.FURNACE_T);
			Component right = status == HeatTreatFurnaceBlockEntity.IDLE && t > 0
					? Component.translatable("screen.starwake.heat_treat.furnace", t)
					: Component.translatable("screen.starwake.heat_treat.status." + status);
			g.text(font, right, imageWidth - 8 - font.width(right), inventoryLabelY,
					status == HeatTreatFurnaceBlockEntity.TOO_HOT || status == HeatTreatFurnaceBlockEntity.BLOCKED ? WARN : DIM, false);
		} else {
			boolean hot = menu.get(FormingBlockEntity.MODE) == 1;
			if (modeButton != null) modeButton.setMessage(Component.translatable(hot ? "screen.starwake.forming.hot" : "screen.starwake.forming.cold"));
			int route = menu.get(FormingBlockEntity.ROUTE_STRAIN);
			if (route > 0) {
				g.text(font, Component.translatable("screen.starwake.forming.strain", String.format("%.2f", menu.get(FormingBlockEntity.PROGRESS_STRAIN) / 100.0),
						String.format("%.2f", route / 100.0)), 24, 44, TEXT, false);
				if (hot) g.text(font, Component.translatable("screen.starwake.forming.hot_t", menu.get(FormingBlockEntity.HOT_T)), 24, 56, HOT, false);
			} else {
				g.text(font, Component.translatable("screen.starwake.forming.status." + status), 24, 44, status == FormingBlockEntity.CRACKED ? WARN : DIM,
						false);
			}
			Component st = Component.translatable("screen.starwake.forming.status." + status);
			g.text(font, st, imageWidth - 8 - font.width(st), inventoryLabelY,
					status == FormingBlockEntity.CRACKED || status == FormingBlockEntity.BLOCKED ? WARN : hot ? HOT : COLD, false);
		}
	}
}
