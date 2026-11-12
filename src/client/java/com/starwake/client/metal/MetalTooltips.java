package com.starwake.client.metal;

import com.starwake.metal.Composition;
import com.starwake.metal.MaterialProperties;
import com.starwake.metal.Metallurgy;
import com.starwake.metal.MetalItems;
import java.util.List;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Metallurgy on the client (M8a): binds the synced alloy grades and base models on join, and adds
 * a materials readout to every metal's tooltip: grade (or which elements are off-spec, or purity),
 * composition, and the properties parts will use. Hold Shift for ductility, stiffness, hardness,
 * specific strength and the grade's note.
 */
public final class MetalTooltips {
	private static final int GOOD = 0xFF7CE0A0, WARN = 0xFFE8B04A, DIM = 0xFF8A93A0, COMP = 0xFF7FC8E8, TEXT = 0xFFC8D0DA;

	private MetalTooltips() {}

	public static void init() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> Metallurgy.bind(handler.registryAccess()));
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> append(stack, lines));
	}

	private static void append(ItemStack stack, List<Component> lines) {
		var composition = MetalItems.compositionOf(stack);
		if (composition.isEmpty()) return;
		Composition c = composition.get();
		Metallurgy.Analysis a = Metallurgy.analyze(c);
		com.starwake.metal.Tempers.Temper temper = com.starwake.metal.Tempers.of(stack);
		MaterialProperties p = com.starwake.metal.Tempers.properties(a, temper, MetalItems.formOf(stack).orElse(com.starwake.metal.MetalForm.INGOT));
		String gradeCondition = !temper.state().isEmpty() || a.grade() == null ? "" : a.grade().condition().orElse("");
		switch (a.kind()) {
			case GRADE -> lines.add((stack.getItem() instanceof com.starwake.metal.MetalItem
					? (gradeCondition.isEmpty() ? Component.translatable("metal.starwake.tooltip.grade_plain")
							: Component.translatable("metal.starwake.tooltip.grade", gradeCondition))
					: (gradeCondition.isEmpty() ? Component.translatable("metal.starwake.tooltip.grade_named_plain", a.name())
							: Component.translatable("metal.starwake.tooltip.grade_named", a.name(), gradeCondition)))
					.withColor(GOOD));
			case OFF_SPEC -> {
				lines.add(Component.translatable("metal.starwake.tooltip.off_spec", a.grade().name()).withColor(WARN));
				for (Metallurgy.Deviation d : a.deviations().subList(0, Math.min(3, a.deviations().size()))) {
					Component what = d.element().equals("*") ? Component.translatable("metal.starwake.tooltip.other")
							: Component.literal(d.element());
					lines.add(Component.translatable("metal.starwake.tooltip.deviation", what, Composition.formatPercent(d.actual()),
							Composition.formatPercent(d.min()), Composition.formatPercent(d.max())).withColor(WARN));
				}
			}
			case PURE -> lines.add(Component.translatable("metal.starwake.tooltip.purity", String.format("%.2f", a.purity())).withColor(TEXT));
			default -> lines.add(Component.translatable("metal.starwake.tooltip.estimated").withColor(DIM));
		}
		String comp = c.format(5) + (c.parts().size() > 5 ? " · +" + (c.parts().size() - 5) : "");
		lines.add(Component.literal(comp).withColor(COMP));
		Component condition = com.starwake.metal.Tempers.describe(temper);
		if (condition != null) {
			lines.add(Component.translatable("metal.starwake.tooltip.condition", condition)
					.withColor(temper.state().equals("cracked") ? WARN : DIM));
		}
		if (temper.progress() > 0) {
			lines.add(Component.translatable("metal.starwake.tooltip.forming", String.format("%.2f", temper.progress())).withColor(DIM));
		}
		lines.add(Component.translatable("metal.starwake.tooltip.strength", String.format("%.2f", p.density()),
				Math.round(p.yieldStrength()), Math.round(p.tensileStrength())).withColor(TEXT));
		lines.add(Component.translatable("metal.starwake.tooltip.heat", Math.round(p.serviceTemp()), Math.round(p.solidus())).withColor(TEXT));
		lines.add(Component.translatable("metal.starwake.tooltip.other_props", String.format("%.1f", p.conductivity()),
				Math.round(p.corrosion())).withColor(TEXT));
		if (Minecraft.getInstance().hasShiftDown()) {
			lines.add(Component.translatable("metal.starwake.tooltip.detail", String.format("%.1f", p.elongation()),
					Math.round(p.modulus()), Math.round(p.hardness()), Math.round(p.specificStrength())).withColor(DIM));
			if (p.elongation() < 2) lines.add(Component.translatable("metal.starwake.tooltip.brittle").withColor(WARN));
			if (a.grade() != null) a.grade().note().ifPresent(n -> lines.add(n.copy().withColor(DIM)));
		} else {
			lines.add(Component.translatable("metal.starwake.tooltip.shift").withColor(DIM));
		}
	}
}
