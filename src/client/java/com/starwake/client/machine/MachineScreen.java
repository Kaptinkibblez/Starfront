package com.starwake.client.machine;

import com.starwake.machine.MachineMenu;
import com.starwake.registry.ModMachines;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * The screen for every grid machine (M8b), drawn without a texture: a dark panel, slot wells,
 * an energy bar, the work progress (arrow for processors, flame for burners), the machine's
 * power and the grid's supply and demand. Layout comes from the menu's {@link MachineMenu.Layout}.
 */
public class MachineScreen extends AbstractContainerScreen<MachineMenu> {
	private static final int PANEL = 0xF0151A21, EDGE = 0xFF55D8FF, WELL = 0xFF0B0E12, WELL_EDGE = 0xFF3A4350, TEXT = 0xFFE6F2FF,
			DIM = 0xFF8A93A0, GOOD = 0xFF7CFFB2, WARN = 0xFFFFB347, ENERGY = 0xFF55D8FF;

	public MachineScreen(MachineMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, 176, MachineMenu.INVENTORY_Y + 82);
	}

	public static void register() {
		MenuScreens.register(ModMachines.COMBUSTION_GENERATOR_MENU, MachineScreen::new);
		MenuScreens.register(ModMachines.ELECTRIC_FURNACE_MENU, MachineScreen::new);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
		super.extractBackground(g, mouseX, mouseY, partial);
		int x = leftPos, y = topPos;
		g.fill(x, y, x + imageWidth, y + imageHeight, PANEL);
		g.fill(x, y, x + imageWidth, y + 1, EDGE);
		g.fill(x, y + imageHeight - 1, x + imageWidth, y + imageHeight, EDGE);
		g.fill(x, y, x + 1, y + imageHeight, EDGE);
		g.fill(x + imageWidth - 1, y, x + imageWidth, y + imageHeight, EDGE);
		for (Slot slot : menu.slots) {
			int sx = x + slot.x - 1, sy = y + slot.y - 1;
			g.fill(sx, sy, sx + 18, sy + 18, WELL_EDGE);
			g.fill(sx + 1, sy + 1, sx + 17, sy + 17, WELL);
		}
		// Energy bar (left).
		int energy = menu.read(MachineMenu.ENERGY), capacity = menu.read(MachineMenu.CAPACITY);
		int bx = x + 10, by = y + 18, bh = 46;
		g.fill(bx - 1, by - 1, bx + 9, by + bh + 1, WELL_EDGE);
		g.fill(bx, by, bx + 8, by + bh, WELL);
		int fill = capacity <= 0 ? 0 : Math.round(bh * Math.min(1f, energy / (float) capacity));
		g.fill(bx, by + bh - fill, bx + 8, by + bh, ENERGY);
		// Progress: arrow between slots for processors, a flame under the fuel slot for burners.
		int progress = menu.get(MachineMenu.PROGRESS), max = Math.max(1, menu.get(MachineMenu.PROGRESS_MAX));
		if (menu.layout.arrow()) {
			int ax = x + 80, ay = y + 39, w = 24;
			g.fill(ax, ay, ax + w, ay + 6, WELL_EDGE);
			g.fill(ax, ay, ax + Math.round(w * progress / (float) max), ay + 6, GOOD);
		} else if (menu.layout.fuel().length > 0) {
			int[] slot = menu.layout.slots().get(menu.layout.fuel()[0]);
			int fx = x + slot[0], fy = y + slot[1] - 18, h = Math.round(14 * progress / (float) max);
			g.fill(fx + 3, fy, fx + 13, fy + 14, WELL_EDGE);
			g.fill(fx + 3, fy + 14 - h, fx + 13, fy + 14, 0xFFFF8A2A);
		}
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		g.text(font, title, titleLabelX, titleLabelY, EDGE, false);
		g.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, DIM, false);
		// Info strip between the machine and the inventory: status + stored energy, then the grid.
		int strip = MachineMenu.INVENTORY_Y - 34;
		g.fill(6, strip - 3, imageWidth - 6, strip - 2, 0x3055D8FF);
		int power = menu.read(MachineMenu.POWER);
		Component status = power > 0 ? Component.translatable("screen.starwake.machine.power_out", String.format("%.1f", power / 1000.0))
				: power < 0 ? Component.translatable("screen.starwake.machine.power_in", String.format("%.1f", -power / 1000.0))
				: Component.translatable(menu.read(MachineMenu.ENERGY) <= 0 ? "screen.starwake.machine.no_power" : "screen.starwake.machine.idle");
		g.text(font, status, 8, strip, power > 0 ? GOOD : power < 0 ? TEXT : WARN, false);
		Component energy = Component.translatable("screen.starwake.machine.energy", menu.read(MachineMenu.ENERGY), menu.read(MachineMenu.CAPACITY));
		g.text(font, energy, imageWidth - 8 - font.width(energy), strip, DIM, false);
		g.text(font, Component.translatable("screen.starwake.machine.grid", String.format("%.1f", menu.read(MachineMenu.SUPPLY) / 1000.0),
				String.format("%.1f", menu.read(MachineMenu.DEMAND) / 1000.0)), 8, strip + 11, DIM, false);
	}
}
