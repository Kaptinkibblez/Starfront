package com.starwake.metal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Engineering properties of a material (M8a), in real units, room temperature, in the stated
 * condition. Parts, armour and tools read these: strength and density set mass and toughness,
 * service temperature gates hot parts (engines, re-entry hulls), conductivity matters for wiring,
 * corrosion for toxic worlds.
 *
 * @param density         g/cm3
 * @param yieldStrength   MPa
 * @param tensileStrength ultimate tensile strength, MPa
 * @param elongation      % at break (ductility; under ~2% is brittle)
 * @param modulus         Young's modulus, GPa
 * @param hardness        Vickers HV
 * @param solidus         where it starts to melt, C
 * @param serviceTemp     highest temperature it keeps useful strength / resists oxidation, C
 * @param conductivity    electrical conductivity, % IACS (annealed copper = 100)
 * @param corrosion       corrosion resistance 0 (rusts away) to 10 (inert)
 */
public record MaterialProperties(double density, double yieldStrength, double tensileStrength, double elongation, double modulus,
		double hardness, double solidus, double serviceTemp, double conductivity, double corrosion) {
	public static final Codec<MaterialProperties> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.DOUBLE.fieldOf("density").forGetter(MaterialProperties::density),
			Codec.DOUBLE.fieldOf("yield_strength").forGetter(MaterialProperties::yieldStrength),
			Codec.DOUBLE.fieldOf("tensile_strength").forGetter(MaterialProperties::tensileStrength),
			Codec.DOUBLE.fieldOf("elongation").forGetter(MaterialProperties::elongation),
			Codec.DOUBLE.fieldOf("modulus").forGetter(MaterialProperties::modulus),
			Codec.DOUBLE.fieldOf("hardness").forGetter(MaterialProperties::hardness),
			Codec.DOUBLE.fieldOf("solidus").forGetter(MaterialProperties::solidus),
			Codec.DOUBLE.fieldOf("service_temp").forGetter(MaterialProperties::serviceTemp),
			Codec.DOUBLE.fieldOf("conductivity").forGetter(MaterialProperties::conductivity),
			Codec.DOUBLE.fieldOf("corrosion").forGetter(MaterialProperties::corrosion)
	).apply(i, MaterialProperties::new));

	/** Strength per density (MPa per g/cm3): what matters for anything that flies. */
	public double specificStrength() {
		return density <= 0 ? 0 : yieldStrength / density;
	}

	public MaterialProperties withDensity(double d) {
		return new MaterialProperties(d, yieldStrength, tensileStrength, elongation, modulus, hardness, solidus, serviceTemp, conductivity, corrosion);
	}
}
