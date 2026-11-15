package com.starwake.ship.panel;

import net.minecraft.util.StringRepresentable;

/**
 * The shapes a Hull Panel can take (M9 pass 3), in the four families the Hull Tool cycles through. Geometry lives in
 * {@link PanelGeometry}; every shape is described in model space with its "up" along +Y and its front (the low edge of
 * a slope, the open side of a curve) toward -Z (north).
 */
public enum PanelShape implements StringRepresentable {
	// Slopes and wedges
	SLOPE("slope", Family.SLOPES, true),
	SLOPE_2_LOW("slope_2_low", Family.SLOPES, true),
	SLOPE_2_HIGH("slope_2_high", Family.SLOPES, true),
	SLOPE_4_A("slope_4_a", Family.SLOPES, true),
	SLOPE_4_B("slope_4_b", Family.SLOPES, true),
	SLOPE_4_C("slope_4_c", Family.SLOPES, true),
	SLOPE_4_D("slope_4_d", Family.SLOPES, true),
	CORNER("corner", Family.SLOPES, true),
	INNER_CORNER("inner_corner", Family.SLOPES, true),
	PEAK("peak", Family.SLOPES, true),
	// Curves and rounds
	QUARTER_ROUND("quarter_round", Family.CURVES, true),
	COVE("cove", Family.CURVES, true),
	ROUND_CORNER("round_corner", Family.CURVES, true),
	CYLINDER("cylinder", Family.CURVES, false),
	HALF_ROUND("half_round", Family.CURVES, false),
	// Thin panels and plates
	HALF_PLATE("half_plate", Family.PLATES, true),
	QUARTER_PLATE("quarter_plate", Family.PLATES, true),
	ARMOR_PLATE("armor_plate", Family.PLATES, true),
	FIN("fin", Family.PLATES, false),
	// Frames and trusses
	FRAME("frame", Family.FRAMES, false),
	LATTICE("lattice", Family.FRAMES, false),
	RIB("rib", Family.FRAMES, false);

	public enum Family { SLOPES, CURVES, PLATES, FRAMES }

	private final String id;
	private final Family family;
	private final boolean seals;

	PanelShape(String id, Family family, boolean seals) {
		this.id = id;
		this.family = family;
		this.seals = seals;
	}

	public Family family() {
		return family;
	}

	/** Whether a panel of this shape closes its cell for a pressurized cabin (frames and pillars don't). */
	public boolean seals() {
		return seals;
	}

	/** Next shape in the same family (wrapping), for the Hull Tool. */
	public PanelShape next(int step) {
		PanelShape[] all = values();
		java.util.List<PanelShape> same = new java.util.ArrayList<>();
		for (PanelShape s : all) if (s.family == family) same.add(s);
		int i = same.indexOf(this);
		return same.get(Math.floorMod(i + step, same.size()));
	}

	/** First shape of the next family (wrapping). */
	public PanelShape nextFamily() {
		Family f = Family.values()[(family.ordinal() + 1) % Family.values().length];
		for (PanelShape s : values()) if (s.family == f) return s;
		return this;
	}

	@Override
	public String getSerializedName() {
		return id;
	}
}
