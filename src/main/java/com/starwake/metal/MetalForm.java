package com.starwake.metal;

/**
 * The shapes metal comes in (M8a). Ingots come out of furnaces; plates, rods and wire are formed
 * from them (rolling mill, extruder, wire drawer in 8d); dust is what crushing and powder
 * metallurgy work with. Mass is per item, for recipes that track metal by weight.
 */
public enum MetalForm {
	INGOT("ingot", 1.0), PLATE("plate", 1.0), ROD("rod", 0.5), WIRE("wire", 0.25), DUST("dust", 1.0);

	public final String id;
	/** kg of metal in one item. */
	public final double kg;

	MetalForm(String id, double kg) {
		this.id = id;
		this.kg = kg;
	}

	public static MetalForm byId(String id) {
		for (MetalForm f : values()) if (f.id.equals(id)) return f;
		throw new IllegalArgumentException("Unknown metal form " + id);
	}
}
