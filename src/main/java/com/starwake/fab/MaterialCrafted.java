package com.starwake.fab;

import com.starwake.metal.MaterialProperties;
import net.minecraft.world.item.ItemStack;

/**
 * An item whose stats come from what it's made of (M8h: tools, weapons, armour). When the Fabricator makes
 * one, it copies the main input's composition and temper onto the product and then calls
 * {@link #applyMaterial} with that material's properties and the product's quality.
 */
public interface MaterialCrafted {
	void applyMaterial(ItemStack stack, MaterialProperties properties, double quality);
}
