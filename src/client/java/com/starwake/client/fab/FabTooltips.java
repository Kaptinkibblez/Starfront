package com.starwake.client.fab;

import com.starwake.fab.SemiconductorBlockEntity;
import com.starwake.registry.ModComponents;
import com.starwake.registry.ModFabrication;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.network.chat.Component;

/**
 * Tooltips for fabricated things (M8e-1): a part's quality (how far its materials beat the design's
 * gates) and a wafer's doping type. The material itself comes from the metal tooltip.
 */
public final class FabTooltips {
	private static final int GOOD = 0xFF7CE0A0, DIM = 0xFF8A93A0, TEXT = 0xFFC8D0DA;

	private FabTooltips() {}

	public static void init() {
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
			if (stack.is(ModFabrication.SILICON_WAFER)) {
				String dopant = SemiconductorBlockEntity.waferDopant(stack);
				lines.add(dopant == null ? Component.translatable("tooltip.starwake.wafer.intrinsic").withColor(DIM)
						: Component.translatable(SemiconductorBlockEntity.P_TYPE.contains(dopant) ? "tooltip.starwake.wafer.p" : "tooltip.starwake.wafer.n",
								dopant).withColor(TEXT));
			}
			Float q = stack.get(ModComponents.QUALITY);
			if (q != null) {
				lines.add(Component.translatable("tooltip.starwake.quality", String.format("%.2f", q), Math.round((q - 1) * 100))
						.withColor(q > 1.001 ? GOOD : DIM));
			}
		});
	}
}
