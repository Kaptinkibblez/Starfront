package com.starwake.ship.panel;

import static com.starwake.ship.panel.PanelGeometry.arc;
import static com.starwake.ship.panel.PanelGeometry.concat;
import static com.starwake.ship.panel.PanelGeometry.extrude;
import static com.starwake.ship.panel.PanelGeometry.px;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;

/**
 * Geometry of the simpler hull pieces (M9 pass 4): plates (a slab of a given thickness on the mounting face), pillars
 * (a round column) and frames (a truss node with lattice arms toward each connected neighbour; on its own, an open cube).
 */
public final class HullPartShapes {
	private HullPartShapes() {}

	/** A plate {@code sixteenths} thick on the model's base. */
	public record Plate(int sixteenths) implements PanelGeometry.Shape {
		@Override
		public List<PanelGeometry.Solid> solids() {
			return List.of(px(0, 0, 0, 16, sixteenths, 16, "hull"));
		}
	}

	/** A round column along the model's Y axis. */
	public record Pillar() implements PanelGeometry.Shape {
		@Override
		public List<PanelGeometry.Solid> solids() {
			return List.of(extrude(1, 0, 1, concat(arc(0.5, 0.5, 0.5, 0, 360, 20)), "hull"));
		}
	}

	/** A truss: {@code mask} has bit {@code direction.ordinal()} set for each connected side (block space, no rotation). */
	public record Frame(int mask) implements PanelGeometry.Shape {
		@Override
		public List<PanelGeometry.Solid> solids() {
			List<PanelGeometry.Solid> s = new ArrayList<>();
			if (mask == 0) {
				double t = 2;
				for (double a : new double[] {0, 16 - t}) {
					for (double b : new double[] {0, 16 - t}) {
						s.add(px(a, 0, b, a + t, 16, b + t, "hull"));
						s.add(px(0, a, b, 16, a + t, b + t, "hull"));
						s.add(px(a, b, 0, a + t, b + t, 16, "hull"));
					}
				}
				return s;
			}
			s.add(px(4, 4, 4, 12, 12, 12, "dark"));
			for (Direction d : Direction.values()) {
				if ((mask & (1 << d.ordinal())) == 0) continue;
				// Four rails from the node to the face, at the corners of a 6x6 square.
				for (double a : new double[] {5, 9}) {
					for (double b : new double[] {5, 9}) {
						s.add(switch (d.getAxis()) {
							case X -> d == Direction.EAST ? px(12, a, b, 16, a + 2, b + 2, "hull") : px(0, a, b, 4, a + 2, b + 2, "hull");
							case Y -> d == Direction.UP ? px(a, 12, b, a + 2, 16, b + 2, "hull") : px(a, 0, b, a + 2, 4, b + 2, "hull");
							case Z -> d == Direction.SOUTH ? px(a, b, 12, a + 2, b + 2, 16, "hull") : px(a, b, 0, a + 2, b + 2, 4, "hull");
						});
					}
				}
			}
			return s;
		}
	}
}
