package com.starwake.process;

import com.mojang.serialization.Codec;
import com.starwake.StarwakeMod;
import java.util.Optional;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributeHandler;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributes;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.level.material.Fluids;

/**
 * Chemical fluids (M8c): the {@code starwake:chemical} fluid plus a {@code starwake:species}
 * component saying which species it is. Water is vanilla water (species H2O). 1 bucket = 1 kg.
 */
public final class Chemicals {
	/** Droplets per kg (1 bucket = 1 kg). */
	public static final long KG = FluidConstants.BUCKET;

	public static final DataComponentType<String> SPECIES = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, StarwakeMod.id("species"),
			DataComponentType.<String>builder().persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8).build());
	public static final ChemicalFluid FLUID = Registry.register(BuiltInRegistries.FLUID, StarwakeMod.id("chemical"), new ChemicalFluid());

	private Chemicals() {}

	public static void init() {
		FluidVariantAttributes.register(FLUID, new FluidVariantAttributeHandler() {
			@Override
			public Component getName(FluidVariant variant) {
				return speciesOf(variant).flatMap(Species::get).map(Species.Info::displayName)
						.orElse(Component.translatable("fluid.starwake.chemical"));
			}

			@Override
			public boolean isLighterThanAir(FluidVariant variant) {
				return speciesOf(variant).flatMap(Species::get).map(Species.Info::gasAtRoom).orElse(false);
			}
		});
	}

	public static FluidVariant variant(String species) {
		if (species.equals("H2O")) return FluidVariant.of(Fluids.WATER);
		return FluidVariant.of(FLUID, DataComponentPatch.builder().set(SPECIES, species).build());
	}

	public static Optional<String> speciesOf(FluidVariant variant) {
		if (variant.isOf(Fluids.WATER)) return Optional.of("H2O");
		if (!variant.isOf(FLUID)) return Optional.empty();
		return Optional.ofNullable(variant.getComponents().get(SPECIES));
	}
}
