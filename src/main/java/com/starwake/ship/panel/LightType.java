package com.starwake.ship.panel;

import static com.starwake.ship.panel.PanelGeometry.arc;
import static com.starwake.ship.panel.PanelGeometry.concat;
import static com.starwake.ship.panel.PanelGeometry.extrude;
import static com.starwake.ship.panel.PanelGeometry.px;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.StringRepresentable;

/**
 * Ship lights (M9 pass 3): a dark housing ("dark") and a lens ("lens", drawn emissive in the light's colour when lit).
 * Mounted on the y = 0 face like greebles. The beacon is the navigation strobe: it flashes while switched on.
 */
public enum LightType implements StringRepresentable, PanelGeometry.Shape {
	STRIP("strip", 12),
	PANEL("panel", 14),
	RUNNING("running", 7),
	FLOOD("flood", 15),
	LANDING("landing", 15),
	BEACON("beacon", 10);

	private final String id;
	private final int level;

	LightType(String id, int level) {
		this.id = id;
		this.level = level;
	}

	/** Block light level while lit. */
	public int level() {
		return level;
	}

	@Override
	public List<PanelGeometry.Solid> solids() {
		List<PanelGeometry.Solid> s = new ArrayList<>();
		switch (this) {
			case STRIP -> {
				s.add(px(0, 0, 6, 16, 1, 10, "dark"));
				s.add(px(1, 1, 7, 15, 2, 9, "lens"));
			}
			case PANEL -> {
				s.add(px(1, 0, 1, 15, 1, 15, "dark"));
				s.add(px(2, 1, 2, 14, 1.5, 14, "lens"));
			}
			case RUNNING -> {
				s.add(px(5, 0, 5, 11, 1, 11, "dark"));
				s.add(px(6, 1, 6, 10, 3, 10, "lens"));
			}
			case FLOOD -> {
				s.add(px(3, 0, 4, 13, 7, 12, "dark"));
				s.add(px(4, 1, 3, 12, 6, 4, "lens"));
			}
			case LANDING -> {
				s.add(extrude(1, 0, 1.5 / 16, concat(arc(0.5, 0.5, 6 / 16.0, 0, 360, 16)), "dark"));
				s.add(extrude(1, 1.5 / 16, 2.5 / 16, concat(arc(0.5, 0.5, 4.5 / 16, 0, 360, 16)), "lens"));
			}
			case BEACON -> {
				s.add(px(5, 0, 5, 11, 2, 11, "dark"));
				s.add(px(6, 2, 6, 10, 5, 10, "lens"));
			}
		}
		return s;
	}

	@Override
	public String getSerializedName() {
		return id;
	}

	/** Lens colours. */
	public enum Color implements StringRepresentable {
		WHITE, RED, GREEN, AMBER, BLUE;

		public Color next() {
			return values()[(ordinal() + 1) % values().length];
		}

		@Override
		public String getSerializedName() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}
	}
}
