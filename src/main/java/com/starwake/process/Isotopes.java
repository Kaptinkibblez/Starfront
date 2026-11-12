package com.starwake.process;

import com.starwake.registry.ModComponents;
import java.util.Map;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.minecraft.world.item.ItemStack;

/**
 * Uranium isotope tracking (M8c-3b). Chemistry never changes isotope ratios, so every uranium-bearing
 * item, tank and machine just carries the U-235 share of its uranium (component {@code starwake:u235},
 * absent = natural 0.72%), mixed by uranium mass whenever material is combined. Enrichment (gas
 * centrifuges on UF6) arrives with the fission reactors in 8g.
 */
public final class Isotopes {
	/** Natural uranium: 0.72% U-235. */
	public static final double NATURAL = 0.0072;

	private Isotopes() {}

	/** kg of uranium in a mass of species. */
	public static double uraniumKg(Map<String, Double> species) {
		double u = 0;
		for (var e : species.entrySet()) {
			Double f = Species.get(e.getKey()).map(s -> s.elements().get("U")).orElse(null);
			if (f != null) u += f * e.getValue();
		}
		return u;
	}

	public static boolean natural(double f) {
		return Math.abs(f - NATURAL) < 1e-6;
	}

	public static double of(ItemStack stack) {
		Float f = stack.get(ModComponents.U235);
		return f == null ? NATURAL : f;
	}

	public static double of(FluidVariant variant) {
		Float f = variant.getComponents().get(ModComponents.U235);
		return f == null ? NATURAL : f;
	}

	/** Stamps a stack that contains uranium (no component when natural). */
	public static void stamp(ItemStack stack, double fraction) {
		if (natural(fraction)) stack.remove(ModComponents.U235);
		else stack.set(ModComponents.U235, (float) fraction);
	}

	/** Uranium-mass-weighted mix of two fractions. */
	public static double mix(double kgA, double fA, double kgB, double fB) {
		double total = kgA + kgB;
		return total <= 0 ? NATURAL : (kgA * fA + kgB * fB) / total;
	}
}
