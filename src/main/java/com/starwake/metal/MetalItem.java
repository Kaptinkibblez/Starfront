package com.starwake.metal;

import com.starwake.registry.ModComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A piece of metal of any composition (M8a): one item per {@link MetalForm}; what it is made of
 * lives in the {@code starwake:composition} component. Its name comes from the analysis ("304
 * Stainless Steel Plate", "Titanium Ingot", "Fe-Cr-Ni Alloy Rod") and its colour from the
 * custom-model-data tint set when the stack is made ({@link MetalItems#stack}).
 */
public class MetalItem extends Item {
	private final MetalForm form;

	public MetalItem(MetalForm form, Properties properties) {
		super(properties);
		this.form = form;
	}

	public MetalForm form() {
		return form;
	}

	@Override
	public Component getName(ItemStack stack) {
		Composition c = stack.get(ModComponents.COMPOSITION);
		if (c == null || c.isEmpty()) return super.getName(stack);
		return Component.translatable("item.starwake.metal_" + form.id + ".of", Metallurgy.analyze(c).name());
	}
}
