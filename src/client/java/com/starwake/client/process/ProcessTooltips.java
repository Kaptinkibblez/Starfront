package com.starwake.client.process;

import com.starwake.chem.Chemistry;
import com.starwake.process.Assay;
import com.starwake.process.Species;
import com.starwake.registry.ModComponents;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Process material tooltips (M8c): bulk items show their assay (main species) and the valuable
 * elements they carry; raw ore chunks, fuels and fluxes show what they are made of and their mass.
 * Shift lists every species, down to trace parts per billion.
 */
public final class ProcessTooltips {
	private static final int COMP = 0xFF7FC8E8, DIM = 0xFF8A93A0, TEXT = 0xFFC8D0DA, GOOD = 0xFF7CE0A0;
	private static final java.util.Set<String> COMMON = java.util.Set.of("O", "Si", "S", "C", "H", "Al", "Ca", "K", "Mg", "N", "F", "P", "Na");

	private ProcessTooltips() {}

	public static void init() {
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> append(stack, lines));
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> extras(stack, lines));
	}

	/** Gas cylinder contents and radioactivity (M8c-3a), for any item. */
	private static void extras(ItemStack stack, List<Component> lines) {
		var fill = stack.get(com.starwake.registry.ModComponents.GAS);
		if (stack.getItem() instanceof com.starwake.process.GasCylinderItem) {
			double kg = fill == null ? 0 : fill.kg();
			lines.add(Component.translatable("item.starwake.gas_cylinder.contents", String.format("%.3f", kg),
					String.format("%.0f", com.starwake.process.GasCylinderItem.CAPACITY_KG)).withColor(DIM));
			if (fill != null && fill.species().equals("O2")) lines.add(Component.translatable("item.starwake.gas_cylinder.suit").withColor(DIM));
			lines.add(Component.translatable("item.starwake.gas_cylinder.help").withColor(DIM));
		}
		double[] r = com.starwake.process.Radiation.perItem(stack);
		if (r[0] * stack.getCount() >= 1000) {
			double a = r[0] * stack.getCount(), g = r[1] * stack.getCount();
			lines.add(Component.translatable("process.starwake.tooltip.radioactive", becquerels(a),
					com.starwake.client.hud.EnvironmentHud.sievertsPerHour(g)).withColor(0xFFE0C040));
		}
	}

	private static String becquerels(double bq) {
		String[] units = {"Bq", "kBq", "MBq", "GBq", "TBq", "PBq", "EBq"};
		int u = 0;
		while (bq >= 1000 && u < units.length - 1) {
			bq /= 1000;
			u++;
		}
		return String.format(bq >= 100 ? "%.0f %s" : "%.1f %s", bq, units[u]);
	}

	private static void append(ItemStack stack, List<Component> lines) {
		Assay assay = stack.get(ModComponents.ASSAY);
		double mass = 1;
		if (assay == null) {
			var feed = Species.feed(BuiltInRegistries.ITEM.getKey(stack.getItem())).orElse(null);
			if (feed == null) return;
			assay = feed.assay();
			mass = feed.mass();
			lines.add(Component.translatable("process.starwake.tooltip.feed", String.format("%.1f", mass)).withColor(DIM));
		}
		boolean shift = Minecraft.getInstance().hasShiftDown();
		int n = shift ? assay.parts().size() : 4;
		for (Assay.Part p : assay.parts().subList(0, Math.min(n, assay.parts().size()))) {
			Component name = Species.get(p.species()).map(Species.Info::displayName).orElse(Component.literal(p.species()));
			lines.add(Component.literal("  ").append(name).append(" (" + p.species() + ") " + Assay.percent(p.fraction() * 100) + "%").withColor(COMP));
		}
		if (!shift && assay.parts().size() > 4) {
			lines.add(Component.translatable("process.starwake.tooltip.more", assay.parts().size() - 4).withColor(DIM));
		}
		// Valuable elements: anything but the common rock-formers.
		Map<String, Double> elements = assay.elements();
		final double kg = mass;
		String valuable = elements.entrySet().stream().filter(e -> !COMMON.contains(e.getKey()) || e.getKey().equals("Al") && e.getValue() > 0.2)
				.sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())).limit(shift ? 20 : 6)
				.map(e -> e.getKey() + " " + grams(e.getValue() * kg)).collect(Collectors.joining(", "));
		if (!valuable.isEmpty()) lines.add(Component.translatable("process.starwake.tooltip.elements", valuable).withColor(GOOD));
		if (!shift) lines.add(Component.translatable("metal.starwake.tooltip.shift").withColor(DIM));
	}

	private static String grams(double kg) {
		if (kg >= 1) return String.format("%.2f kg", kg);
		if (kg >= 0.001) return String.format("%.0f g", kg * 1000);
		if (kg >= 1e-6) return String.format("%.0f mg", kg * 1e6);
		return String.format("%.0f µg", kg * 1e9);
	}
}
