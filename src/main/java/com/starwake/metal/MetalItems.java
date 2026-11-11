package com.starwake.metal;

import com.starwake.StarwakeMod;
import com.starwake.chem.Chemistry;
import com.starwake.registry.ModComponents;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;

/**
 * Registers the metal form items and answers "what is this stack made of?" for any item (M8a):
 * metal items carry a composition; vanilla iron is plain low-carbon iron (AISI 1010: user,
 * "vanilla stays, ours goes deeper"), vanilla copper is ETP copper, gold is fine gold; element
 * ingots and powders are the pure element.
 */
public final class MetalItems {
	private static final Map<MetalForm, MetalItem> ITEMS = new EnumMap<>(MetalForm.class);

	/** Vanilla iron: AISI 1010 low-carbon steel. */
	public static final Composition VANILLA_IRON = Composition.withBalance("Fe", Map.of("C", 0.10, "Mn", 0.45, "P", 0.02, "S", 0.03));
	public static final Composition VANILLA_COPPER = Composition.withBalance("Cu", Map.of("O", 0.03));
	public static final Composition VANILLA_GOLD = Composition.withBalance("Au", Map.of("Ag", 0.08, "Cu", 0.02));

	private MetalItems() {}

	public static void init() {
		for (MetalForm form : MetalForm.values()) {
			ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, StarwakeMod.id("metal_" + form.id));
			ITEMS.put(form, Registry.register(BuiltInRegistries.ITEM, key, new MetalItem(form, new Item.Properties().setId(key))));
		}
	}

	public static MetalItem item(MetalForm form) {
		return ITEMS.get(form);
	}

	public static List<MetalItem> all() {
		return List.copyOf(ITEMS.values());
	}

	/** A metal stack with its composition and tint. */
	public static ItemStack stack(MetalForm form, Composition composition, int count) {
		ItemStack stack = new ItemStack(item(form), count);
		stack.set(ModComponents.COMPOSITION, composition);
		int color = Metallurgy.analyze(composition).color();
		stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(), List.of(color)));
		return stack;
	}

	/** What a stack is made of, if it is a metal (or an element). */
	public static Optional<Composition> compositionOf(ItemStack stack) {
		Composition c = stack.get(ModComponents.COMPOSITION);
		if (c != null && !c.isEmpty()) return Optional.of(c);
		if (stack.is(Items.IRON_INGOT) || stack.is(Items.IRON_NUGGET)) return Optional.of(VANILLA_IRON);
		if (stack.is(Items.COPPER_INGOT)) return Optional.of(VANILLA_COPPER);
		if (stack.is(Items.GOLD_INGOT) || stack.is(Items.GOLD_NUGGET)) return Optional.of(VANILLA_GOLD);
		return Chemistry.elementOf(BuiltInRegistries.ITEM.getKey(stack.getItem()))
				.filter(e -> e.form().equals("ingot") || e.form().equals("powder"))
				.map(e -> Composition.pure(e.symbol()));
	}

	/** The form of a stack when it is (or counts as) a metal item. */
	public static Optional<MetalForm> formOf(ItemStack stack) {
		if (stack.getItem() instanceof MetalItem m) return Optional.of(m.form());
		if (stack.is(Items.IRON_INGOT) || stack.is(Items.COPPER_INGOT) || stack.is(Items.GOLD_INGOT)) return Optional.of(MetalForm.INGOT);
		return Chemistry.elementOf(BuiltInRegistries.ITEM.getKey(stack.getItem()))
				.map(e -> e.form().equals("ingot") ? MetalForm.INGOT : e.form().equals("powder") ? MetalForm.DUST : null);
	}
}
