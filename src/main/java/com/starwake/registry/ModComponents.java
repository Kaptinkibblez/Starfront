package com.starwake.registry;

import com.starwake.StarwakeMod;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.util.ExtraCodecs;

/** Starwake item data components. */
public final class ModComponents {
	/** Survey Suit oxygen left, in ticks (M5 hazards). */
	public static final DataComponentType<Integer> OXYGEN = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
			StarwakeMod.id("oxygen"), DataComponentType.<Integer>builder().persistent(ExtraCodecs.NON_NEGATIVE_INT)
					.networkSynchronized(ByteBufCodecs.VAR_INT).ignoreSwapAnimation().build());

	private ModComponents() {}

	public static void init() {}
}
