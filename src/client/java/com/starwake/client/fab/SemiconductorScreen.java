package com.starwake.client.fab;

import com.starwake.client.machine.MachineScreen;
import com.starwake.fab.SemiconductorBlockEntity;
import com.starwake.machine.MachineMenu;
import com.starwake.registry.ModFabrication;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Crystal Puller / Clean Fab screen (M8e-1): the generic machine screen plus the status (impure
 * silicon, no argon, same-type dopant...) and how many dopant doses and interconnect wafers are left.
 */
public class SemiconductorScreen extends MachineScreen {
	private static final int TEXT = 0xFFE6F2FF, DIM = 0xFF8A93A0, WARN = 0xFFFFB347;
	private final boolean fab;

	public SemiconductorScreen(MachineMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title);
		fab = menu.getType() == ModFabrication.CLEAN_FAB_MENU;
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		super.extractLabels(g, mouseX, mouseY);
		int status = menu.get(MachineMenu.STATUS);
		Component st = Component.translatable("screen.starwake.semiconductor.status." + status);
		boolean bad = status >= SemiconductorBlockEntity.IMPURE;
		g.text(font, st, imageWidth - 8 - font.width(st), inventoryLabelY, bad ? WARN : DIM, false);
		g.text(font, Component.translatable("screen.starwake.semiconductor.doses", menu.get(SemiconductorBlockEntity.DOSES_LEFT)), 52, 22, TEXT, false);
		if (fab) {
			g.text(font, Component.translatable("screen.starwake.semiconductor.metal", menu.get(SemiconductorBlockEntity.METAL_LEFT)), 72, 50, TEXT, false);
		} else {
			g.text(font, Component.translatable("screen.starwake.semiconductor.argon"), 52, 50, DIM, false);
		}
	}
}
