package com.starwake.registry;

import com.starwake.StarwakeMod;
import com.starwake.gear.MaterialArmorItem;
import com.starwake.gear.MaterialGear;
import com.starwake.gear.MaterialToolItem;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.equipment.ArmorMaterials;
import net.minecraft.world.item.equipment.ArmorType;

/**
 * Material gear (M8h): pickaxe, axe, shovel, sword and four armour pieces, fabricated from any alloy. The
 * registered defaults are iron-like; the Fabricator replaces every stat per stack from the material.
 */
public final class ModGear {
	private static final List<Item> ALL = new ArrayList<>();
	public static final Item PICKAXE = item("material_pickaxe", p -> new MaterialToolItem(MaterialGear.Kind.PICKAXE, p.pickaxe(ToolMaterial.IRON, 1, -2.8f)));
	public static final Item AXE = item("material_axe", p -> new MaterialToolItem(MaterialGear.Kind.AXE, p.axe(ToolMaterial.IRON, 6, -3.1f)));
	public static final Item SHOVEL = item("material_shovel", p -> new MaterialToolItem(MaterialGear.Kind.SHOVEL, p.shovel(ToolMaterial.IRON, 1.5f, -3f)));
	public static final Item SWORD = item("material_sword", p -> new MaterialToolItem(MaterialGear.Kind.SWORD, p.sword(ToolMaterial.IRON, 3, -2.4f)));
	public static final Item HELMET = item("material_helmet", p -> new MaterialArmorItem(ArmorType.HELMET, p.humanoidArmor(ArmorMaterials.IRON, ArmorType.HELMET)));
	public static final Item CHESTPLATE = item("material_chestplate",
			p -> new MaterialArmorItem(ArmorType.CHESTPLATE, p.humanoidArmor(ArmorMaterials.IRON, ArmorType.CHESTPLATE)));
	public static final Item LEGGINGS = item("material_leggings",
			p -> new MaterialArmorItem(ArmorType.LEGGINGS, p.humanoidArmor(ArmorMaterials.IRON, ArmorType.LEGGINGS)));
	public static final Item BOOTS = item("material_boots", p -> new MaterialArmorItem(ArmorType.BOOTS, p.humanoidArmor(ArmorMaterials.IRON, ArmorType.BOOTS)));

	private ModGear() {}

	public static void init() {}

	public static List<Item> all() {
		return List.copyOf(ALL);
	}

	private static Item item(String name, Function<Item.Properties, Item> factory) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, StarwakeMod.id(name));
		Item item = Registry.register(BuiltInRegistries.ITEM, key, factory.apply(new Item.Properties().setId(key)));
		ALL.add(item);
		return item;
	}
}
