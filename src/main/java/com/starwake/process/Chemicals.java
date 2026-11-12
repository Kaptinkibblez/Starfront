package com.starwake.process;

import com.mojang.serialization.Codec;
import com.starwake.StarwakeMod;
import java.util.Map;
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
				Assay assay = variant.getComponents().get(com.starwake.registry.ModComponents.ASSAY);
				if (assay != null) {
					String main = assay.parts().stream().map(Assay.Part::species).filter(s -> !s.equals("H2O")).findFirst().orElse("H2O");
					return Component.translatable("fluid.starwake.solution", Species.get(main).map(Species.Info::displayName).orElse(Component.literal(main)));
				}
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

	/** A fluid for a mixture: a pure species when there is only one, else a solution carrying its assay. */
	public static FluidVariant mixture(Map<String, Long> amounts) {
		java.util.Map<String, Double> m = new java.util.HashMap<>();
		amounts.forEach((k, v) -> { if (v > 0) m.put(k, v.doubleValue()); });
		if (m.size() == 1) return variant(m.keySet().iterator().next());
		return FluidVariant.of(FLUID, DataComponentPatch.builder().set(com.starwake.registry.ModComponents.ASSAY, Assay.of(m)).build());
	}

	/** Mass fractions of species in a fluid: pure species, a solution's assay, or water. Empty if not chemical. */
	public static Map<String, Double> contents(FluidVariant variant) {
		Assay assay = variant.isOf(FLUID) ? variant.getComponents().get(com.starwake.registry.ModComponents.ASSAY) : null;
		if (assay != null) {
			java.util.Map<String, Double> m = new java.util.LinkedHashMap<>();
			assay.parts().forEach(p -> m.put(p.species(), p.fraction()));
			return m;
		}
		return speciesOf(variant).map(s -> Map.of(s, 1.0)).orElse(Map.of());
	}

	public static Optional<String> speciesOf(FluidVariant variant) {
		if (variant.isOf(Fluids.WATER)) return Optional.of("H2O");
		if (!variant.isOf(FLUID)) return Optional.empty();
		return Optional.ofNullable(variant.getComponents().get(SPECIES));
	}
}
