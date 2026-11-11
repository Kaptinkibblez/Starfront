package com.starwake.client.chem;

import com.starwake.chem.Chemistry;
import java.util.List;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Chemistry tooltips (M5): every element item shows its symbol, atomic number, category and
 * physical data, plus radioactivity and where it comes from; every ore block and raw mineral
 * shows its formula, major elements by mass and trace by-products. Works for vanilla items that
 * are elements or minerals too (iron/copper/gold ingots, iron ore, cinnabar...).
 */
public final class ChemistryTooltips {
	private static final int DIM = 0xFF8A93A0, ELEMENT = 0xFF7FC8E8, WARN = 0xFFE8C820, SYNTH = 0xFFC27BFF;

	private ChemistryTooltips() {}

	public static void init() {
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> append(stack, lines));
	}

	private static void append(ItemStack stack, List<Component> lines) {
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		Chemistry.elementOf(id).ifPresent(e -> {
			lines.add(Component.translatable("chem.starwake.element", e.symbol(), e.z(),
					Component.translatable("chem.starwake.category." + e.category())).withColor(ELEMENT));
			lines.add(Component.translatable("chem.starwake.properties", String.format("%.3f", e.mass()),
					String.format(e.density() < 0.01 ? "%.5f" : "%.2f", e.density()),
					e.meltingPoint() <= 0 ? "?" : String.valueOf(Math.round(e.meltingPoint()))).withColor(DIM));
			if (e.radioactive()) lines.add(Component.translatable("chem.starwake.radioactive").withColor(WARN));
			switch (e.occurrence()) {
				case "synthetic" -> lines.add(Component.translatable("chem.starwake.synthetic").withColor(SYNTH));
				case "trace" -> lines.add(Component.translatable("chem.starwake.trace_occurrence").withColor(DIM));
				default -> { }
			}
			if (!e.sources().isEmpty()) {
				Component sources = Component.literal(e.sources().stream().limit(6).map(ChemistryTooltips::sourceName)
						.collect(Collectors.joining(", ")) + (e.sources().size() > 6 ? ", ..." : ""));
				lines.add(Component.translatable("chem.starwake.sources", sources).withColor(DIM));
			}
		});
		Chemistry.mineralOf(id).ifPresent(m -> {
			String major = m.composition().entrySet().stream()
					.map(en -> en.getKey() + " " + Math.round(en.getValue() * 100) + "%").collect(Collectors.joining(", "));
			lines.add(Component.translatable("chem.starwake.mineral", m.formula(), major).withColor(ELEMENT));
			if (!m.trace().isEmpty()) {
				lines.add(Component.translatable("chem.starwake.trace", String.join(", ", m.trace())).withColor(DIM));
			}
		});
	}

	private static String sourceName(String source) {
		String key = "chem.starwake.source." + source;
		if (net.minecraft.locale.Language.getInstance().has(key)) return Component.translatable(key).getString();
		return Component.translatable("mineral.starwake." + source).getString();
	}
}
