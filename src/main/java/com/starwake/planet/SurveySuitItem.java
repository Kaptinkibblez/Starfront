package com.starwake.planet;

import com.starwake.registry.ModComponents;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * Survey Suit (M5 hazards): a sealed, insulated suit worn in the chest slot. It carries its own
 * oxygen (5 minutes, shown as the item bar), keeps out toxic air and holds body temperature
 * between {@link #MIN_TEMPERATURE} and {@link #MAX_TEMPERATURE} degrees C. Oxygen refills in
 * breathable air or inside a sealed ship cabin. Better suits come with progression (M8).
 */
public class SurveySuitItem extends Item {
	/** Oxygen capacity in ticks (5 minutes). */
	public static final int MAX_OXYGEN = 6000;
	public static final float MIN_TEMPERATURE = -180, MAX_TEMPERATURE = 160;

	public SurveySuitItem(Item.Properties properties) {
		super(properties.component(ModComponents.OXYGEN, MAX_OXYGEN));
	}

	public static int oxygen(ItemStack stack) {
		return stack.getOrDefault(ModComponents.OXYGEN, 0);
	}

	public static void setOxygen(ItemStack stack, int ticks) {
		stack.set(ModComponents.OXYGEN, Mth.clamp(ticks, 0, MAX_OXYGEN));
	}

	public static boolean protectsFrom(float temperature) {
		return temperature >= MIN_TEMPERATURE && temperature <= MAX_TEMPERATURE;
	}

	@Override
	public boolean isBarVisible(ItemStack stack) {
		return oxygen(stack) < MAX_OXYGEN;
	}

	@Override
	public int getBarWidth(ItemStack stack) {
		return Math.round(13.0f * oxygen(stack) / MAX_OXYGEN);
	}

	@Override
	public int getBarColor(ItemStack stack) {
		return oxygen(stack) < MAX_OXYGEN / 5 ? 0xFF5050 : 0x55D8FF;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> builder,
			TooltipFlag flag) {
		int seconds = oxygen(stack) / 20;
		builder.accept(Component.translatable("item.starwake.survey_suit.oxygen", seconds / 60, String.format("%02d", seconds % 60))
				.withColor(0xFF7FC8E8));
		builder.accept(Component.translatable("item.starwake.survey_suit.rating", (int) MIN_TEMPERATURE, (int) MAX_TEMPERATURE)
				.withColor(0xFF8A8A8A));
	}
}
