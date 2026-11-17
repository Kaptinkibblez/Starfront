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
 * M10: the diagonal wall (a 45-degree wall in plan), the taper (a square frustum) and the nose (a rounded cone).
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

	/** M10: a full-height wall cut on the diagonal in plan (the back-right half of the cell; the cut faces front-left). */
	public record Diagonal() implements PanelGeometry.Shape {
		@Override
		public List<PanelGeometry.Solid> solids() {
			return List.of(extrude(1, 0, 1, PanelGeometry.poly(1, 0, 1, 1, 0, 1), "hull"));
		}
	}

	/** M10: a square frustum along the model's Z: full 1x1 at the back (z = 1), {@code front} wide at the front, centred. */
	public record Taper(double front) implements PanelGeometry.Shape {
		private double half(double z) {
			return 0.5 * (front + (1 - front) * z);
		}

		@Override
		public List<PanelGeometry.Solid> solids() {
			double f = half(0), b = half(1);
			double[][] fr = {{0.5 - f, 0.5 - f, 0}, {0.5 + f, 0.5 - f, 0}, {0.5 + f, 0.5 + f, 0}, {0.5 - f, 0.5 + f, 0}};
			double[][] bk = {{0.5 - b, 0.5 - b, 1}, {0.5 + b, 0.5 - b, 1}, {0.5 + b, 0.5 + b, 1}, {0.5 - b, 0.5 + b, 1}};
			List<double[][]> faces = new ArrayList<>(List.of(fr, bk));
			for (int i = 0; i < 4; i++) {
				int j = (i + 1) % 4;
				faces.add(new double[][] {fr[i], fr[j], bk[j], bk[i]});
			}
			return List.of(PanelGeometry.faceted((x, y, z) -> {
				double h = half(Math.max(0, Math.min(1, z))) + 1e-9;
				return Math.abs(x - 0.5) <= h && Math.abs(y - 0.5) <= h;
			}, faces, "hull"));
		}
	}

	/**
	 * M10: a rounded nose cone along the model's Z: radius 0.5 at the back (z = 1, matching a pillar), closing to a blunt
	 * point at the front (an ogive, r = 0.5 sqrt(z)).
	 */
	public record Nose() implements PanelGeometry.Shape {
		private static final int SEGMENTS = 16, RINGS = 8;

		private static double radius(double z) {
			return 0.5 * Math.sqrt(Math.max(0, Math.min(1, z)));
		}

		@Override
		public List<PanelGeometry.Solid> solids() {
			List<double[][]> faces = new ArrayList<>();
			double[][][] ring = new double[RINGS + 1][SEGMENTS][];
			for (int k = 0; k <= RINGS; k++) {
				double z = (double) k / RINGS * (double) k / RINGS; // denser near the tip, where it curves most
				double r = radius(z);
				for (int i = 0; i < SEGMENTS; i++) {
					double a = 2 * Math.PI * i / SEGMENTS;
					ring[k][i] = new double[] {0.5 + r * Math.cos(a), 0.5 + r * Math.sin(a), z};
				}
			}
			for (int k = 0; k < RINGS; k++) {
				for (int i = 0; i < SEGMENTS; i++) {
					int j = (i + 1) % SEGMENTS;
					if (k == 0) faces.add(new double[][] {ring[0][i], ring[1][i], ring[1][j]});
					else faces.add(new double[][] {ring[k][i], ring[k + 1][i], ring[k + 1][j], ring[k][j]});
				}
			}
			faces.add(ring[RINGS]);
			return List.of(PanelGeometry.faceted((x, y, z) -> Math.hypot(x - 0.5, y - 0.5) <= radius(z) + 1e-9, faces, "hull"));
		}
	}
}
