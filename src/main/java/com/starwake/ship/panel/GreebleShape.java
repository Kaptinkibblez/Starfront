package com.starwake.ship.panel;

import static com.starwake.ship.panel.PanelGeometry.concat;
import static com.starwake.ship.panel.PanelGeometry.arc;
import static com.starwake.ship.panel.PanelGeometry.extrude;
import static com.starwake.ship.panel.PanelGeometry.px;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.StringRepresentable;

/**
 * Greebles (M9 pass 3): surface detail for hulls and interiors. Like Hull Panels they're built from solids in model space,
 * mounted on the y = 0 face (up = out of the surface they're placed on) with "front" toward -Z; wall-mounted pieces run
 * their long axis along Z, which ends up vertical on a wall. Texture keys: hull, dark, vent, pipe, conduit, grate, hazard,
 * tank.
 */
public enum GreebleShape implements StringRepresentable, PanelGeometry.Shape {
	VENT("vent"),
	PIPE("pipe"),
	PIPE_PAIR("pipe_pair"),
	CONDUIT("conduit"),
	SEAM("seam"),
	ANTENNA("antenna"),
	RAILING("railing"),
	LADDER("ladder"),
	CATWALK("catwalk"),
	RIBBED_PANEL("ribbed_panel"),
	HAZARD_TRIM("hazard_trim"),
	CARGO_RACK("cargo_rack"),
	TANK("tank"),
	HEATSINK("heatsink");

	private final String id;

	GreebleShape(String id) {
		this.id = id;
	}

	public GreebleShape next(int step) {
		GreebleShape[] all = values();
		return all[Math.floorMod(ordinal() + step, all.length)];
	}

	@Override
	public List<PanelGeometry.Solid> solids() {
		List<PanelGeometry.Solid> s = new ArrayList<>();
		switch (this) {
			case VENT -> {
				s.add(px(0, 0, 0, 16, 1, 16, "dark"));
				s.add(px(2, 1, 2, 14, 2, 14, "vent"));
			}
			case PIPE -> {
				s.add(extrude(2, 0, 1, concat(arc(0.5, 3.5 / 16, 3 / 16.0, 0, 360, 12)), "pipe"));
				s.add(px(4, 0, 3, 12, 1, 5, "dark"));
				s.add(px(4, 0, 11, 12, 1, 13, "dark"));
			}
			case PIPE_PAIR -> {
				s.add(extrude(2, 0, 1, concat(arc(4.5 / 16, 2.5 / 16, 2 / 16.0, 0, 360, 10)), "pipe"));
				s.add(extrude(2, 0, 1, concat(arc(11.5 / 16, 2.5 / 16, 2 / 16.0, 0, 360, 10)), "pipe"));
				s.add(px(1, 0, 3, 15, 1, 5, "dark"));
				s.add(px(1, 0, 11, 15, 1, 13, "dark"));
			}
			case CONDUIT -> {
				s.add(px(5, 0, 0, 11, 3, 16, "conduit"));
				s.add(px(4.5, 0, 3, 11.5, 3.5, 4, "dark"));
				s.add(px(4.5, 0, 12, 11.5, 3.5, 13, "dark"));
			}
			case SEAM -> s.add(px(0, 0, 7, 16, 1, 9, "dark"));
			case ANTENNA -> {
				s.add(px(5.5, 0, 5.5, 10.5, 1.5, 10.5, "dark"));
				s.add(px(7.25, 0, 7.25, 8.75, 16, 8.75, "dark"));
				s.add(px(4, 11, 7.5, 12, 12, 8.5, "dark"));
				s.add(px(5.5, 14, 7.5, 10.5, 14.75, 8.5, "dark"));
			}
			case RAILING -> {
				for (double x : new double[] {0, 7, 14}) s.add(px(x, 0, 0, x + 2, 15, 2, "dark"));
				s.add(px(0, 13, 0.25, 16, 15, 1.75, "hull"));
				s.add(px(0, 7, 0.5, 16, 8, 1.5, "dark"));
			}
			case LADDER -> {
				s.add(px(2, 0, 0, 4, 2, 16, "dark"));
				s.add(px(12, 0, 0, 14, 2, 16, "dark"));
				for (double z : new double[] {2, 6, 10, 14}) s.add(px(4, 0.5, z, 12, 1.5, z + 1, "hull"));
			}
			case CATWALK -> s.add(px(0, 0, 0, 16, 2, 16, "grate"));
			case RIBBED_PANEL -> {
				s.add(px(0, 0, 0, 16, 2, 16, "hull"));
				for (double z : new double[] {1, 5, 9, 13}) s.add(px(0, 2, z, 16, 3.5, z + 2, "hull"));
			}
			case HAZARD_TRIM -> s.add(px(0, 0, 0, 16, 1, 16, "hazard"));
			case CARGO_RACK -> {
				for (double x : new double[] {0, 14}) {
					for (double z : new double[] {0, 14}) s.add(px(x, 0, z, x + 2, 16, z + 2, "dark"));
				}
				s.add(px(0, 0, 0, 16, 1, 16, "grate"));
				s.add(px(0, 7, 0, 16, 8, 16, "grate"));
				s.add(px(0, 15, 0, 16, 16, 16, "grate"));
			}
			case TANK -> {
				s.add(extrude(0, 1 / 16.0, 15 / 16.0, concat(arc(0.5, 7.5 / 16, 6.5 / 16, 0, 360, 16)), "tank"));
				s.add(px(2, 0, 3, 4, 3, 13, "dark"));
				s.add(px(12, 0, 3, 14, 3, 13, "dark"));
			}
			case HEATSINK -> {
				s.add(px(0, 0, 0, 16, 2, 16, "dark"));
				for (int k = 0; k < 5; k++) s.add(px(1 + 3 * k, 2, 1, 2 + 3 * k, 8, 15, "dark"));
			}
		}
		return s;
	}

	@Override
	public String getSerializedName() {
		return id;
	}
}
