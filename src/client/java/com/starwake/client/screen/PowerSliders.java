package com.starwake.client.screen;

import com.starwake.network.ShipPowerPayload;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

/**
 * Star Citizen-style power triangle as three linked sliders. Raising one subsystem takes
 * power proportionally from the other two, so the split always totals 100%. Every change is
 * sent to the server, which normalizes and applies it.
 */
final class PowerSliders {
	private static final String[] KEYS = {"screen.starwake.power.engines", "screen.starwake.power.shields", "screen.starwake.power.quantum"};

	private final double[] split = new double[3];
	private final Slider[] sliders = new Slider[3];

	PowerSliders(int[] percents) {
		for (int i = 0; i < 3; i++) split[i] = percents[i] / 100.0;
	}

	/** Creates the three sliders stacked at (x, y). */
	void create(int x, int y, int width, Consumer<AbstractWidget> add) {
		for (int i = 0; i < 3; i++) {
			sliders[i] = new Slider(i, x, y + i * 22, width);
			add.accept(sliders[i]);
		}
	}

	private void set(int index, double value) {
		double others = 1.0 - split[index];
		double remaining = 1.0 - value;
		for (int i = 0; i < 3; i++) {
			if (i == index) continue;
			// Share what's left in proportion to the others' current shares (evenly if both 0).
			split[i] = others <= 1.0e-6 ? remaining / 2 : split[i] / others * remaining;
		}
		split[index] = value;
		for (Slider slider : sliders) slider.refresh();
		ClientPlayNetworking.send(new ShipPowerPayload((int) Math.round(split[0] * 100),
				(int) Math.round(split[1] * 100), (int) Math.round(split[2] * 100)));
	}

	private final class Slider extends AbstractSliderButton {
		private final int index;
		private boolean refreshing;

		Slider(int index, int x, int y, int width) {
			super(x, y, width, 20, Component.empty(), split[index]);
			this.index = index;
			updateMessage();
		}

		void refresh() {
			refreshing = true;
			value = split[index];
			updateMessage();
			refreshing = false;
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.translatable(KEYS[index], Math.round(split[index] * 100)));
		}

		@Override
		protected void applyValue() {
			if (!refreshing) set(index, value);
		}
	}
}
