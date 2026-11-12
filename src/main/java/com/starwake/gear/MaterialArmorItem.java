package com.starwake.gear;

import com.starwake.fab.MaterialCrafted;
import com.starwake.metal.MaterialProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.ArmorType;

/** A fabricated armour piece whose protection, toughness, weight and durability come from its alloy (M8h). */
public class MaterialArmorItem extends Item implements MaterialCrafted {
	private final ArmorType type;

	public MaterialArmorItem(ArmorType type, Properties properties) {
		super(properties);
		this.type = type;
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
		MaterialGear.applyArmor(stack, type, properties, quality);
	}
}
