package com.starwake.ship.panel;

import static com.starwake.ship.panel.PanelGeometry.arc;
import static com.starwake.ship.panel.PanelGeometry.concat;
import static com.starwake.ship.panel.PanelGeometry.extrude;
import static com.starwake.ship.panel.PanelGeometry.px;

import java.util.ArrayList;
import java.util.List;

/** Geometry of the Hinge and Rotor blocks (M9 pass 3): a housing with a turning plate on its +Y (facing) side. */
public enum JointShape implements PanelGeometry.Shape {
	HINGE, ROTOR;

	@Override
	public List<PanelGeometry.Solid> solids() {
		List<PanelGeometry.Solid> s = new ArrayList<>();
		s.add(px(0, 0, 0, 16, 9, 16, "dark"));
		if (this == HINGE) {
			s.add(extrude(1, 9 / 16.0, 13 / 16.0, concat(arc(0.5, 0.5, 6 / 16.0, 0, 360, 16)), "pipe"));
			s.add(px(1, 13, 1, 15, 16, 15, "hazard"));
		} else {
			s.add(extrude(1, 9 / 16.0, 12 / 16.0, concat(arc(0.5, 0.5, 7 / 16.0, 0, 360, 20)), "pipe"));
			s.add(extrude(1, 12 / 16.0, 16 / 16.0, concat(arc(0.5, 0.5, 7.5 / 16, 0, 360, 20)), "dark"));
		}
		return s;
	}
}
