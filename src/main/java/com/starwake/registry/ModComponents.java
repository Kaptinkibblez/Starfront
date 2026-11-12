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

	/** What a piece of metal is made of (M8a metallurgy). */
	public static final DataComponentType<com.starwake.metal.Composition> COMPOSITION = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
			StarwakeMod.id("composition"), DataComponentType.<com.starwake.metal.Composition>builder()
					.persistent(com.starwake.metal.Composition.CODEC)
					.networkSynchronized(com.starwake.metal.Composition.STREAM_CODEC).build());

	/** What a bulk process material is made of (M8c chemistry). */
	public static final DataComponentType<com.starwake.process.Assay> ASSAY = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
			StarwakeMod.id("assay"), DataComponentType.<com.starwake.process.Assay>builder()
					.persistent(com.starwake.process.Assay.CODEC)
					.networkSynchronized(com.starwake.process.Assay.STREAM_CODEC).build());

	/** What a Gas Cylinder holds (M8c-3a). */
	public static final DataComponentType<com.starwake.process.GasCylinderItem.Fill> GAS = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
			StarwakeMod.id("gas"), DataComponentType.<com.starwake.process.GasCylinderItem.Fill>builder()
					.persistent(com.starwake.process.GasCylinderItem.Fill.CODEC)
					.networkSynchronized(com.starwake.process.GasCylinderItem.Fill.STREAM_CODEC).build());

	/** U-235 share of the uranium in an item or fluid (M8c-3b; absent = natural 0.72%). */
	public static final DataComponentType<Float> U235 = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
			StarwakeMod.id("u235"), DataComponentType.<Float>builder().persistent(com.mojang.serialization.Codec.floatRange(0, 1))
					.networkSynchronized(ByteBufCodecs.FLOAT).build());

	private ModComponents() {}

	public static void init() {}
}
