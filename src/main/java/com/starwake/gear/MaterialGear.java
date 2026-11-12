package com.starwake.gear;

import com.starwake.metal.MaterialProperties;
import java.util.List;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.level.block.Block;

/**
 * Material tools, weapons and armour (M8h). Every stat comes from the alloy, per stack (data components):
 * <ul>
 *   <li>Mining tier from hardness: under 120 HV stone tier, 120-250 iron, 250-550 diamond, above netherite;
 *   mining speed 3 + HV/50 (max 14).</li>
 *   <li>Durability from toughness, 100 + UTS x min(elongation, 30) / 15, times quality: mild steel ~780, a
 *   quenched-and-tempered alloy steel ~1300, brittle carbide only ~200 however hard it is.</li>
 *   <li>Attack damage bonus UTS / 300 on top of each tool's base.</li>
 *   <li>Armour: defence = iron's per piece x clamp(yield / 250, 0.6, 1.4) (a titanium chestplate ~ 8.4, like
 *   netherite); toughness from UTS x elongation;
 *   knockback resistance for dense alloys; a movement penalty in proportion to density (a full mild-steel set
 *   is -5%, titanium about -3%).</li>
 * </ul>
 */
public final class MaterialGear {
	public enum Kind {
		PICKAXE(BlockTags.MINEABLE_WITH_PICKAXE, 1, -2.8f), AXE(BlockTags.MINEABLE_WITH_AXE, 5, -3.0f),
		SHOVEL(BlockTags.MINEABLE_WITH_SHOVEL, 1.5f, -3.0f), SWORD(null, 3, -2.4f);

		public final TagKey<Block> mines;
		public final float damage, speed;

		Kind(TagKey<Block> mines, float damage, float speed) {
			this.mines = mines;
			this.damage = damage;
			this.speed = speed;
		}
	}

	private MaterialGear() {}

	public static TagKey<Block> incorrectFor(double hv) {
		if (hv < 120) return BlockTags.INCORRECT_FOR_STONE_TOOL;
		if (hv < 250) return BlockTags.INCORRECT_FOR_IRON_TOOL;
		if (hv < 550) return BlockTags.INCORRECT_FOR_DIAMOND_TOOL;
		return BlockTags.INCORRECT_FOR_NETHERITE_TOOL;
	}

	public static int toolDurability(MaterialProperties p, double quality) {
		return (int) Math.round((100 + p.tensileStrength() * Math.min(30, p.elongation()) / 15) * quality);
	}

	public static void applyTool(ItemStack stack, Kind kind, MaterialProperties p, double quality) {
		// At runtime the frozen registry resolves tags itself (the bootstrap lookup only works before freezing).
		HolderGetter<Block> blocks = BuiltInRegistries.BLOCK;
		float speed = (float) Math.min(14, 3 + p.hardness() / 50);
		if (kind == Kind.SWORD) {
			stack.set(DataComponents.TOOL, new Tool(List.of(Tool.Rule.overrideSpeed(blocks.getOrThrow(BlockTags.SWORD_EFFICIENT), 1.5f)), 1.0f, 2, false));
		} else {
			stack.set(DataComponents.TOOL, new Tool(List.of(Tool.Rule.deniesDrops(blocks.getOrThrow(incorrectFor(p.hardness()))),
					Tool.Rule.minesAndDrops(blocks.getOrThrow(kind.mines), speed)), 1.0f, 1, true));
		}
		stack.set(DataComponents.MAX_DAMAGE, Math.max(16, toolDurability(p, quality)));
		stack.set(DataComponents.DAMAGE, 0);
		float damage = (float) (kind.damage + p.tensileStrength() / 300);
		stack.set(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.builder()
				.add(Attributes.ATTACK_DAMAGE, new AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID, damage, AttributeModifier.Operation.ADD_VALUE),
						EquipmentSlotGroup.MAINHAND)
				.add(Attributes.ATTACK_SPEED, new AttributeModifier(Item.BASE_ATTACK_SPEED_ID, kind.speed, AttributeModifier.Operation.ADD_VALUE),
						EquipmentSlotGroup.MAINHAND)
				.build());
	}

	public static void applyArmor(ItemStack stack, ArmorType type, MaterialProperties p, double quality) {
		int base = switch (type) {
			case HELMET -> 2;
			case CHESTPLATE -> 6;
			case LEGGINGS -> 5;
			default -> 2;
		};
		double weight = switch (type) {
			case HELMET -> 0.15;
			case CHESTPLATE -> 0.4;
			case LEGGINGS -> 0.3;
			default -> 0.15;
		};
		double defence = base * Math.max(0.6, Math.min(1.4, p.yieldStrength() / 250)); // quality goes to durability only
		double toughness = Math.max(0, Math.min(4, p.tensileStrength() * Math.min(40, p.elongation()) / 8000 - 0.5));
		double knockback = p.density() >= 10 ? 0.1 : 0;
		double slow = -0.05 * p.density() / 7.87 * weight;
		Identifier id = Identifier.withDefaultNamespace("armor." + type.getName());
		EquipmentSlotGroup group = EquipmentSlotGroup.bySlot(type.getSlot());
		ItemAttributeModifiers.Builder b = ItemAttributeModifiers.builder()
				.add(Attributes.ARMOR, new AttributeModifier(id, Math.round(defence * 2) / 2.0, AttributeModifier.Operation.ADD_VALUE), group)
				.add(Attributes.ARMOR_TOUGHNESS, new AttributeModifier(id, Math.round(toughness * 10) / 10.0, AttributeModifier.Operation.ADD_VALUE), group)
				.add(Attributes.MOVEMENT_SPEED, new AttributeModifier(id, slow, AttributeModifier.Operation.ADD_MULTIPLIED_BASE), group);
		if (knockback > 0) b.add(Attributes.KNOCKBACK_RESISTANCE, new AttributeModifier(id, knockback, AttributeModifier.Operation.ADD_VALUE), group);
		stack.set(DataComponents.ATTRIBUTE_MODIFIERS, b.build());
		int unit = switch (type) {
			case HELMET -> 11;
			case CHESTPLATE -> 16;
			case LEGGINGS -> 15;
			default -> 13;
		};
		double perUnit = (8 + p.tensileStrength() * Math.min(30, p.elongation()) / 1500) * quality;
		stack.set(DataComponents.MAX_DAMAGE, (int) Math.max(30, Math.round(perUnit * unit)));
		stack.set(DataComponents.DAMAGE, 0);
	}
}
