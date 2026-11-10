package com.starwake.client.screen;

import com.starwake.network.ShipConsoleActionPayload;
import com.starwake.network.ShipConsolePayload;
import com.starwake.ship.scan.PreflightReport;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.ArrayList;
import java.util.List;

/**
 * The Ship Console: shows the pre-flight report for the ship around a Helm, with buttons to
 * re-scan and assemble. It only displays what the server sent; every button is re-validated
 * server-side.
 */
public class ShipConsoleScreen extends Screen {
	private static final int PANEL_WIDTH = 340;
	private static final int LINE_HEIGHT = 11;

	private ShipConsolePayload report;
	private Button assembleButton;

	public ShipConsoleScreen(ShipConsolePayload report) {
		super(Component.translatable("screen.starwake.ship_console"));
		this.report = report;
	}

	/** Updates the report in place (after a re-scan) without closing the screen. */
	public void update(ShipConsolePayload newReport) {
		this.report = newReport;
		if (assembleButton != null) {
			assembleButton.active = newReport.canAssemble();
		}
	}

	public boolean isFor(ShipConsolePayload other) {
		return report.helm().equals(other.helm());
	}

	@Override
	protected void init() {
		int left = (width - PANEL_WIDTH) / 2;
		int buttonY = height - 32;
		int third = (PANEL_WIDTH - 8) / 3;
		addRenderableWidget(Button.builder(Component.translatable("screen.starwake.ship_console.rescan"),
				b -> send(ShipConsoleActionPayload.Action.RESCAN)).bounds(left, buttonY, third, 20).build());
		assembleButton = addRenderableWidget(Button.builder(Component.translatable("screen.starwake.ship_console.assemble"),
				b -> send(ShipConsoleActionPayload.Action.ASSEMBLE)).bounds(left + third + 4, buttonY, third, 20).build());
		assembleButton.active = report.canAssemble();
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(left + 2 * (third + 4), buttonY, third, 20).build());
	}

	private void send(ShipConsoleActionPayload.Action action) {
		ClientPlayNetworking.send(new ShipConsoleActionPayload(report.helm(), action));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int left = (width - PANEL_WIDTH) / 2;
		int top = 24;
		graphics.fill(left - 8, top - 8, left + PANEL_WIDTH + 8, height - 40, 0xC0101418);
		graphics.centeredText(font, title, width / 2, top, 0xFFFFFFFF);

		int y = top + 18;
		PreflightReport.Status[] statuses = PreflightReport.Status.values();
		for (int i = 0; i < report.lines().size(); i++) {
			PreflightReport.Status status = statuses[report.status().get(i)];
			graphics.text(font, status.icon, left, y, status.color);
			for (FormattedCharSequence wrapped : wrap(report.lines().get(i))) {
				graphics.text(font, wrapped, left + 12, y, 0xFFE0E0E0);
				y += LINE_HEIGHT;
			}
			y += 2;
		}
	}

	private List<FormattedCharSequence> wrap(Component text) {
		return new ArrayList<>(font.split(text, PANEL_WIDTH - 12));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
