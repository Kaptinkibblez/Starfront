package com.starwake.gear;

import com.starwake.fab.MaterialCrafted;
import com.starwake.metal.MaterialProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** A fabricated pickaxe, axe, shovel or sword whose stats come from its alloy (M8h; {@link MaterialGear}). */
public class MaterialToolItem extends Item implements MaterialCrafted {
	private final MaterialGear.Kind kind;

	public MaterialToolItem(MaterialGear.Kind kind, Properties properties) {
		super(properties);
		this.kind = kind;
	}

	/** "<alloy> Pickaxe" once fabricated. */
	@Override
	public net.minecraft.network.chat.Component getName(ItemStack stack) {
		var c = stack.get(com.starwake.registry.ModComponents.COMPOSITION);
		if (c == null || c.isEmpty()) return super.getName(stack);
		return net.minecraft.network.chat.Component.translatable(getDescriptionId() + ".of", com.starwake.metal.Metallurgy.analyze(c).name());
	}

	@Override
	public void applyMaterial(ItemStack stack, MaterialProperties properties, double quality) {
		MaterialGear.applyTool(stack, kind, properties, quality);
	}
}
