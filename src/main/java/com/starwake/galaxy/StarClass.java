package com.starwake.galaxy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import com.starwake.planet.proc.Range;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ExtraCodecs;

/**
 * A kind of star (M6), loaded from {@code data/<ns>/starwake/star_class/<id>.json} and synced to
 * clients so they can generate the galaxy map themselves. Real astrophysics: the Morgan-Keenan
 * spectral classes O, B, A, F, G, K, M with their real frequencies (three in four stars are red
 * dwarfs), plus white dwarfs and neutron stars. Values are in solar units.
 *
 * @param name        display name ("Red dwarf (M)")
 * @param symbol      spectral letter shown on the map
 * @param weight      relative frequency among stars
 * @param temperature surface temperature, kelvin
 * @param luminosity  luminosity (Sun = 1): sets the habitable zone, so planet orbits scale by its
 *                    square root
 * @param radius      radius (Sun = 1; 1 = 40,000 blocks in space)
 * @param mass        mass (Sun = 1)
 * @param color       apparent colour
 * @param planets     how many planets its systems have
 * @param binary      chance the star has a companion
 * @param remnant     a stellar remnant (white dwarf, neutron star): dim, small, few planets
 */
public record StarClass(Component name, String symbol, double weight, Range temperature, Range luminosity, Range radius,
		Range mass, int color, Range planets, double binary, boolean remnant) {
	public static final ResourceKey<Registry<StarClass>> REGISTRY_KEY = ResourceKey.createRegistryKey(StarwakeMod.id("star_class"));

	public static final Codec<StarClass> CODEC = RecordCodecBuilder.create(i -> i.group(
			ComponentSerialization.CODEC.fieldOf("name").forGetter(StarClass::name),
			Codec.STRING.fieldOf("symbol").forGetter(StarClass::symbol),
			Codec.doubleRange(0, 100).fieldOf("weight").forGetter(StarClass::weight),
			Range.CODEC.fieldOf("temperature").forGetter(StarClass::temperature),
			Range.CODEC.fieldOf("luminosity").forGetter(StarClass::luminosity),
			Range.CODEC.fieldOf("radius").forGetter(StarClass::radius),
			Range.CODEC.fieldOf("mass").forGetter(StarClass::mass),
			ExtraCodecs.STRING_RGB_COLOR.fieldOf("color").forGetter(StarClass::color),
			Range.CODEC.fieldOf("planets").forGetter(StarClass::planets),
			Codec.doubleRange(0, 1).optionalFieldOf("binary", 0.0).forGetter(StarClass::binary),
			Codec.BOOL.optionalFieldOf("remnant", false).forGetter(StarClass::remnant)
	).apply(i, StarClass::new));
}
