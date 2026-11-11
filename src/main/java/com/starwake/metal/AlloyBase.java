package com.starwake.metal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;

/**
 * How a base metal responds to what's dissolved in it (M8a), from
 * {@code data/<ns>/starwake/alloy_base/<symbol>.json}: the pure metal's properties plus per-solute
 * effects. Used to estimate compositions that match no grade (and for density, which is always
 * computed from the composition).
 */
public record AlloyBase(String element, MaterialProperties pure, Map<String, Solute> solutes) {
	public static final ResourceKey<Registry<AlloyBase>> REGISTRY_KEY = ResourceKey.createRegistryKey(StarwakeMod.id("alloy_base"));

	/**
	 * One solute's effect, per wt%.
	 *
	 * @param strength     solid-solution strengthening, MPa per sqrt(wt%) (up to {@code cap})
	 * @param cap          wt% beyond which more adds no strength or heat resistance
	 * @param ductility    elongation is multiplied by exp(-ductility x wt%) (negative = more ductile)
	 * @param conductivity conductivity divides by (1 + sum of conductivity x wt%)
	 * @param corrosion    corrosion resistance per wt%
	 * @param service      service temperature, C per wt% (up to cap)
	 * @param solidus      solidus depression, C per wt% (default from the melting points)
	 * @param threshold    wt% at which a protective effect switches on (Cr 10.5% makes steel stainless)
	 * @param bonus        corrosion bonus past the threshold
	 * @param brittleAbove wt% past which the alloy turns brittle (C 2.1% in iron = cast iron)
	 */
	public record Solute(double strength, double cap, double ductility, double conductivity, double corrosion, double service,
			Optional<Double> solidus, Optional<Double> threshold, double bonus, Optional<Double> brittleAbove) {
		public static final Codec<Solute> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.DOUBLE.optionalFieldOf("strength", 15.0).forGetter(Solute::strength),
				Codec.DOUBLE.optionalFieldOf("cap", 20.0).forGetter(Solute::cap),
				Codec.DOUBLE.optionalFieldOf("ductility", 0.02).forGetter(Solute::ductility),
				Codec.DOUBLE.optionalFieldOf("conductivity", 0.5).forGetter(Solute::conductivity),
				Codec.DOUBLE.optionalFieldOf("corrosion", 0.0).forGetter(Solute::corrosion),
				Codec.DOUBLE.optionalFieldOf("service", 0.0).forGetter(Solute::service),
				Codec.DOUBLE.optionalFieldOf("solidus").forGetter(Solute::solidus),
				Codec.DOUBLE.optionalFieldOf("threshold").forGetter(Solute::threshold),
				Codec.DOUBLE.optionalFieldOf("bonus", 0.0).forGetter(Solute::bonus),
				Codec.DOUBLE.optionalFieldOf("brittle_above").forGetter(Solute::brittleAbove)
		).apply(i, Solute::new));

		public static final Solute DEFAULT = new Solute(15, 20, 0.02, 0.5, 0, 0, Optional.empty(), Optional.empty(), 0, Optional.empty());
	}

	public static final Codec<AlloyBase> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("element").forGetter(AlloyBase::element),
			MaterialProperties.CODEC.fieldOf("pure").forGetter(AlloyBase::pure),
			Codec.unboundedMap(Codec.STRING, Solute.CODEC).optionalFieldOf("solutes", Map.of()).forGetter(AlloyBase::solutes)
	).apply(i, AlloyBase::new));

	public Solute solute(String element) {
		return solutes.getOrDefault(element, Solute.DEFAULT);
	}
}
