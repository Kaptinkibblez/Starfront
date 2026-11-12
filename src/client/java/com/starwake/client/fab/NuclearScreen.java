package com.starwake.client.fab;

import com.starwake.client.machine.MachineScreen;
import com.starwake.machine.MachineMenu;
import com.starwake.nuclear.FissionReactorBlockEntity;
import com.starwake.nuclear.GasCentrifugeBlockEntity;
import com.starwake.registry.ModNuclear;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Screens for the M8g machines: the generic machine screen plus each machine's readout (fission: status,
 * core U-235 share and irradiation progress; centrifuge: last feed and heads shares; fusion: status).
 */
public class NuclearScreen extends MachineScreen {
	private static final int TEXT = 0xFFE6F2FF, DIM = 0xFF8A93A0, WARN = 0xFFFFB347, GOOD = 0xFF7CFFB2;

	public NuclearScreen(MachineMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title);
	}

	@Override
	protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		super.extractLabels(g, mouseX, mouseY);
		var type = menu.getType();
		int status = menu.get(MachineMenu.STATUS);
		if (type == ModNuclear.FISSION_REACTOR_MENU) {
			Component st = Component.translatable("screen.starwake.fission.status." + status);
			g.text(font, st, imageWidth - 8 - font.width(st), inventoryLabelY, status == FissionReactorBlockEntity.RUNNING ? GOOD : WARN, false);
			g.text(font, Component.translatable("screen.starwake.fission.core", String.format("%.2f", menu.get(FissionReactorBlockEntity.ENRICHMENT) / 100.0)),
					24, 56, TEXT, false);
			g.text(font, Component.translatable("screen.starwake.fission.irradiation", menu.get(FissionReactorBlockEntity.IRRADIATION)), 80, 40, DIM, false);
		} else if (type == ModNuclear.GAS_CENTRIFUGE_MENU) {
			g.text(font, Component.translatable("screen.starwake.centrifuge.shares", String.format("%.3f", menu.get(GasCentrifugeBlockEntity.SHARE_IN) / 100.0),
					String.format("%.3f", menu.get(GasCentrifugeBlockEntity.SHARE_HEADS) / 100.0)), 24, 56, TEXT, false);
		} else if (type == ModNuclear.FUSION_REACTOR_MENU) {
			Component st = Component.translatable("screen.starwake.fusion.status." + status);
			g.text(font, st, imageWidth - 8 - font.width(st), inventoryLabelY, status == 1 ? GOOD : WARN, false);
		}
	}
}
