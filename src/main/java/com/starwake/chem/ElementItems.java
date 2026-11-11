package com.starwake.chem;

import com.starwake.StarwakeMod;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

/**
 * Registers one item per element (M5): {@code starwake:<name>_<form>} where form is ingot,
 * powder, canister (gases), flask (liquids) or sample (short-lived radioactive elements).
 * Iron, copper and gold use the vanilla ingots instead. Synthetic elements are rare items.
 */
public final class ElementItems {
	private static final List<Item> ALL = new ArrayList<>();

	private ElementItems() {}

	public static void init() {
		for (Chemistry.Element element : Chemistry.elements()) {
			if (!element.item().getNamespace().equals(StarwakeMod.MOD_ID)) continue;
			ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, element.item());
			Item.Properties properties = new Item.Properties().setId(key);
			if (element.occurrence().equals("synthetic")) properties.rarity(Rarity.EPIC);
			else if (element.radioactive()) properties.rarity(Rarity.UNCOMMON);
			if (element.form().equals("sample") || element.form().equals("canister") || element.form().equals("flask")) {
				properties.stacksTo(16);
			}
			ALL.add(Registry.register(BuiltInRegistries.ITEM, key, new Item(properties)));
		}
	}

	/** Element items in atomic-number order (excluding the vanilla ingots). */
	public static List<Item> all() {
		return Collections.unmodifiableList(ALL);
	}
}
