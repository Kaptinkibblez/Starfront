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
		MenuScreens.register(ModMachines.WATER_PUMP_MENU, MachineScreen::new);
		MenuScreens.register(ModMachines.CONDENSER_MENU, MachineScreen::new);
		MenuScreens.register(ModMachines.GAS_WELL_MENU, MachineScreen::new);
		MenuScreens.register(com.starwake.registry.ModMetalworks.INDUCTION_MENU, com.starwake.client.metal.MeltFurnaceScreen::new);
		MenuScreens.register(com.starwake.registry.ModMetalworks.VACUUM_ARC_MENU, com.starwake.client.metal.MeltFurnaceScreen::new);
		MenuScreens.register(com.starwake.registry.ModMetalworks.SINTER_PRESS_MENU, MachineScreen::new);
		for (var m : com.starwake.registry.ModMetalworks.formingMenus()) MenuScreens.register(m, com.starwake.client.metal.MetalworkScreen::new);
		MenuScreens.register(com.starwake.registry.ModMetalworks.HEAT_TREAT_MENU, com.starwake.client.metal.MetalworkScreen::new);
		MenuScreens.register(com.starwake.registry.ModFabrication.FABRICATOR_MENU, com.starwake.client.fab.FabricatorScreen::new);
		MenuScreens.register(com.starwake.registry.ModFabrication.CRYSTAL_PULLER_MENU, com.starwake.client.fab.SemiconductorScreen::new);
		MenuScreens.register(com.starwake.registry.ModFabrication.CLEAN_FAB_MENU, com.starwake.client.fab.SemiconductorScreen::new);
		com.starwake.client.metal.MeltFurnaceScreen.registerReceiver();
		for (var p : com.starwake.process.ProcessMachineBlockEntity.Process.values()) {
			MenuScreens.register(com.starwake.registry.ModProcess.menu(p), MachineScreen::new);
		}
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
		if (menu.layout.arrow() != null) {
			int ax = x + menu.layout.arrow()[0], ay = y + menu.layout.arrow()[1], w = 24;
			g.fill(ax, ay, ax + w, ay + 6, WELL_EDGE);
			g.fill(ax, ay, ax + Math.round(w * progress / (float) max), ay + 6, GOOD);
		} else if (menu.layout.fuel().length > 0) {
			int[] slot = menu.layout.slots().get(menu.layout.fuel()[0]);
			int fx = x + slot[0], fy = y + slot[1] - 18, h = Math.round(14 * progress / (float) max);
			g.fill(fx + 3, fy, fx + 13, fy + 14, WELL_EDGE);
			g.fill(fx + 3, fy + 14 - h, fx + 13, fy + 14, 0xFFFF8A2A);
		}
		// Tank gauges (inlet, gas outlet, liquid outlet).
		for (int t = 0; t < 3; t++) {
			int tx = menu.layout.tanks()[t];
			if (tx < 0) continue;
			int gx = x + tx, gy = y + 18, gh = 44;
			g.fill(gx - 1, gy - 1, gx + 9, gy + gh + 1, WELL_EDGE);
			g.fill(gx, gy, gx + 8, gy + gh, WELL);
			int amount = menu.get(MachineMenu.TANKS + 3 * t), tankCap = Math.max(1, menu.get(MachineMenu.TANKS + 3 * t + 1) * 100);
			int species = menu.get(MachineMenu.TANKS + 3 * t + 2);
			int color = species < 0 ? 0xFF888888 : 0xFF000000 | com.starwake.process.Species.byIndex(species).color();
			int h = Math.round(gh * Math.min(1f, amount / (float) tankCap));
			g.fill(gx, gy + gh - h, gx + 8, gy + gh, color);
			g.fill(gx, gy + gh + 3, gx + 8, gy + gh + 4, t == 0 ? 0xFF55D8FF : t == 1 ? 0xFFE8E0A8 : 0xFF3A6AFF);
		}
	}

	@Override
	protected void extractTooltip(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		super.extractTooltip(g, mouseX, mouseY);
		for (int t = 0; t < 3; t++) {
			int tx = menu.layout.tanks()[t];
			if (tx < 0) continue;
			int gx = leftPos + tx, gy = topPos + 18;
			if (mouseX < gx - 1 || mouseX > gx + 9 || mouseY < gy - 1 || mouseY > gy + 45) continue;
			int amount = menu.get(MachineMenu.TANKS + 3 * t), capacity = menu.get(MachineMenu.TANKS + 3 * t + 1);
			int species = menu.get(MachineMenu.TANKS + 3 * t + 2);
			Component name = species < 0 ? Component.translatable("screen.starwake.machine.tank_empty")
					: com.starwake.process.Species.byIndex(species).displayName();
			String role = t == 0 ? "inlet" : t == 1 ? "gas" : "liquid";
			var process = process();
			String special = process == null ? null : "screen.starwake.machine.tank." + process.machine + "." + role;
			Component kind = Component.translatable(special != null && net.minecraft.locale.Language.getInstance().has(special) ? special
					: "screen.starwake.machine.tank." + role);
			g.setTooltipForNextFrame(java.util.List.of(kind.getVisualOrderText(),
					name.copy().append(String.format("  %.2f / %d kg", amount / 100.0, capacity)).getVisualOrderText()), mouseX, mouseY);
		}
	}

	/** The process machine behind this screen, if any. */
	private com.starwake.process.ProcessMachineBlockEntity.@org.jspecify.annotations.Nullable Process process() {
		for (var p : com.starwake.process.ProcessMachineBlockEntity.Process.values()) {
			if (com.starwake.registry.ModProcess.menu(p) == menu.getType()) return p;
		}
		return null;
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		g.text(font, title, titleLabelX, titleLabelY, EDGE, false);
		if (process() == com.starwake.process.ProcessMachineBlockEntity.Process.SOLVENT_EXTRACTION) {
			int cut = menu.get(MachineMenu.STATUS);
			Component label = cut <= 0 ? Component.translatable("screen.starwake.machine.sx_auto")
					: Component.translatable("screen.starwake.machine.sx_cut", com.starwake.process.Species.byIndex(cut - 1).formula());
			g.text(font, label, 46, 46, TEXT, false);
			g.text(font, Component.translatable("screen.starwake.machine.sx_hint"), 46, 56, DIM, false);
		}
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
