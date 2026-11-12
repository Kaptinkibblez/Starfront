package com.starwake.gear;

import com.starwake.registry.ModComponents;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * Survey Suit upgrades (M8h), fitted in the Fabricator, stored in {@code starwake:suit_upgrades}, at most
 * {@link #MAX_EACH} of each:
 * <ul>
 *   <li>{@code lead_lining}: lead plates (density >= 11) halve the external gamma dose rate per lining.</li>
 *   <li>{@code oxygen_tank}: a pressure vessel adds the suit's base 5 minutes of oxygen again.</li>
 *   <li>{@code thermal_layer}: a high-temperature alloy shell plus a heat exchanger widen the rating by 100 C on
 *   the cold side and 240 C on the hot side.</li>
 *   <li>{@code armor_insert}: high-strength plates give +4 armour and +1 toughness on the chest.</li>
 * </ul>
 */
public final class SuitUpgrades {
	public static final int MAX_EACH = 2;

	private SuitUpgrades() {}

	public static List<String> of(ItemStack stack) {
		List<String> list = stack.get(ModComponents.SUIT_UPGRADES);
		return list == null ? List.of() : list;
	}

	public static int count(ItemStack stack, String upgrade) {
		int n = 0;
		for (String u : of(stack)) if (u.equals(upgrade)) n++;
		return n;
	}

	public static boolean canAdd(ItemStack stack, String upgrade) {
		return count(stack, upgrade) < MAX_EACH;
	}

	public static void add(ItemStack stack, String upgrade) {
		if (!canAdd(stack, upgrade)) return;
		List<String> list = new ArrayList<>(of(stack));
		list.add(upgrade);
		stack.set(ModComponents.SUIT_UPGRADES, List.copyOf(list));
		int armor = count(stack, "armor_insert");
		if (armor > 0) {
			Identifier id = Identifier.fromNamespaceAndPath("starwake", "suit_armor");
			stack.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.builder()
					.add(Attributes.ARMOR, new AttributeModifier(id, 4.0 * armor, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.CHEST)
					.add(Attributes.ARMOR_TOUGHNESS, new AttributeModifier(id, 1.0 * armor, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.CHEST)
					.build());
		}
	}

	/** Factor on external gamma dose rate while worn. */
	public static double shielding(ItemStack suit) {
		return Math.pow(0.5, count(suit, "lead_lining"));
	}
}
