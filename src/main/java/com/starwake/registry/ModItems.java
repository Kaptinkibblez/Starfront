package com.starwake.registry;

import com.starwake.StarwakeMod;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/**
 * Registry of standalone Starwake items. Block items live in {@link ModBlocks}.
 */
public final class ModItems {
	private static final List<Item> ALL = new ArrayList<>();

	/** Raw Ferrite: the first crafting resource (a test item in Milestone 1). */
	public static final Item RAW_FERRITE = register("raw_ferrite", Item::new, new Item.Properties());

	/** Survey Suit armour material: light protection; the suit is about the environment, not combat. */
	public static final net.minecraft.world.item.equipment.ArmorMaterial SURVEY_SUIT_MATERIAL = new net.minecraft.world.item.equipment.ArmorMaterial(
			20, java.util.Map.of(net.minecraft.world.item.equipment.ArmorType.CHESTPLATE, 4), 8,
			net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_LEATHER, 0.0f, 0.0f,
			net.minecraft.tags.ItemTags.REPAIRS_IRON_ARMOR,
			ResourceKey.create(net.minecraft.world.item.equipment.EquipmentAssets.ROOT_ID, StarwakeMod.id("survey_suit")));

	/** Survey Suit (M5): oxygen supply, toxic-air seal and thermal protection on hostile planets. */
	public static final Item SURVEY_SUIT = register("survey_suit", com.starwake.planet.SurveySuitItem::new,
			new Item.Properties().humanoidArmor(SURVEY_SUIT_MATERIAL, net.minecraft.world.item.equipment.ArmorType.CHESTPLATE));

	private ModItems() {}

	/** Registers an item under {@code starwake:<name>}. */
	static Item register(String name, Function<Item.Properties, Item> factory, Item.Properties properties) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, StarwakeMod.id(name));
		Item item = Registry.register(BuiltInRegistries.ITEM, key, factory.apply(properties.setId(key)));
		ALL.add(item);
		return item;
	}

	/** All standalone Starwake items in registration order. */
	public static List<Item> all() {
		return Collections.unmodifiableList(ALL);
	}

	public static int count() {
		return ALL.size();
	}

	/** Forces static initialization so every field above is registered. */
	public static void init() {}
}
