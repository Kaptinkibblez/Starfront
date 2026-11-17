package com.starwake.ship.panel;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.StringRepresentable;

/**
 * Geometry of the auto-connecting hull pieces (M9 pass 4): a surface over the block's base, in model space (up = +Y,
 * the run rising toward +Z, a corner's second run rising toward +X, or -X when mirrored).
 *
 * A piece of an N-block run (slope 1:N, curve, cove) covers t = (part + z) / N of the run's profile, so the pieces of a
 * long run line up exactly. Corners combine two runs: outer corners take the lower of the two (a convex corner; curves
 * round it off spherically), inner corners the higher (a valley). Ridges (wedges) are a peaked roof along Z that ends
 * in a point where the run stops.
 *
 * M10: a corner's second run may have another profile ({@code second}: a slope meeting a curve keeps each side's own
 * surface, so they meet without a step), and the rise can be limited to a band of the block ({@code lo}..{@code hi}:
 * half-height slopes).
 */
public record ProfileShape(Profile profile, int n, Conn conn, int partA, int partB, boolean mirror, Profile second, double lo, double hi)
		implements PanelGeometry.Shape {
	public ProfileShape(Profile profile, int n, Conn conn, int partA, int partB, boolean mirror) {
		this(profile, n, conn, partA, partB, mirror, profile, 0, 1);
	}

	private double band(double rise) {
		return lo + (hi - lo) * rise;
	}

	/** The run's rise across t in 0..1. */
	public enum Profile {
		SLOPE, CURVE, COVE, RIDGE;

		double rise(double t) {
			t = Math.max(0, Math.min(1, t));
			return switch (this) {
				case SLOPE, RIDGE -> t;
				case CURVE -> Math.sqrt(1 - (1 - t) * (1 - t));
				case COVE -> 1 - Math.sqrt(1 - t * t);
			};
		}
	}

	/** How a piece joins its neighbours (like vanilla stairs, plus a wedge's pointed end). */
	public enum Conn implements StringRepresentable {
		STRAIGHT, OUTER, INNER, END;

		@Override
		public String getSerializedName() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	/** Height (0..1) of the surface over model point (x, z). */
	public double height(double x, double z) {
		if (profile == Profile.RIDGE) {
			double roof = 1 - Math.abs(2 * x - 1);
			return conn == Conn.END ? Math.min(roof, z) : roof;
		}
		double tA = (partA + z) / n, tB = (partB + (mirror ? 1 - x : x)) / n;
		double a = band(profile.rise(tA));
		return switch (conn) {
			case STRAIGHT, END -> a;
			case OUTER -> profile == Profile.CURVE && second == Profile.CURVE
					? band(profile.rise(1 - Math.hypot(1 - Math.min(1, tA), 1 - Math.min(1, tB))))
					: Math.min(a, band(second.rise(tB)));
			case INNER -> Math.max(a, band(second.rise(tB)));
		};
	}

	/** Facets per edge: flat slopes are exact with a few, curves need more; a ridge needs its peak sampled. */
	private int grid() {
		if (profile == Profile.CURVE || profile == Profile.COVE || second == Profile.CURVE || second == Profile.COVE) return 8;
		if (profile == Profile.RIDGE) return conn == Conn.END ? 4 : 2;
		return conn == Conn.STRAIGHT ? 1 : 4;
	}

	/**
	 * Height of the faceted surface (exactly what's drawn) over (x, z): the triangle of its grid cell, split the same way
	 * as {@link #solids}. Collision and face orientation use this, so curves' chords count as the surface.
	 */
	public double facetHeight(double x, double z) {
		int g = grid();
		double fx = Math.max(0, Math.min(1, x)) * g, fz = Math.max(0, Math.min(1, z)) * g;
		int i = Math.min(g - 1, (int) fx), k = Math.min(g - 1, (int) fz);
		double u = fx - i, v = fz - k;
		double h00 = height(i / (double) g, k / (double) g), h10 = height((i + 1) / (double) g, k / (double) g);
		double h11 = height((i + 1) / (double) g, (k + 1) / (double) g), h01 = height(i / (double) g, (k + 1) / (double) g);
		if (Math.abs(h00 - h11) <= Math.abs(h10 - h01)) {
			return u >= v ? h00 + (h10 - h00) * u + (h11 - h10) * v : h00 + (h01 - h00) * v + (h11 - h01) * u;
		}
		return u + v <= 1 ? h00 + (h10 - h00) * u + (h01 - h00) * v : h11 + (h01 - h11) * (1 - u) + (h10 - h11) * (1 - v);
	}

	@Override
	public List<PanelGeometry.Solid> solids() {
		int g = grid();
		double[][] h = new double[g + 1][g + 1];
		for (int i = 0; i <= g; i++) for (int k = 0; k <= g; k++) h[i][k] = height(i / (double) g, k / (double) g);
		List<double[][]> faces = new ArrayList<>();
		faces.add(new double[][] {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}});
		for (int i = 0; i < g; i++) {
			for (int k = 0; k < g; k++) {
				double x0 = i / (double) g, x1 = (i + 1) / (double) g, z0 = k / (double) g, z1 = (k + 1) / (double) g;
				double[] a = {x0, h[i][k], z0}, b = {x1, h[i + 1][k], z0}, c = {x1, h[i + 1][k + 1], z1}, d = {x0, h[i][k + 1], z1};
				// Split each cell along the diagonal that follows the surface best (the one through the creases).
				if (Math.abs(h[i][k] - h[i + 1][k + 1]) <= Math.abs(h[i + 1][k] - h[i][k + 1])) {
					faces.add(new double[][] {a, b, c});
					faces.add(new double[][] {a, c, d});
				} else {
					faces.add(new double[][] {a, b, d});
					faces.add(new double[][] {b, c, d});
				}
			}
		}
		// Sides as vertical strips under the surface (any profile, convex or not).
		for (int i = 0; i < g; i++) {
			double s0 = i / (double) g, s1 = (i + 1) / (double) g;
			strip(faces, new double[] {s0, 0, 0}, new double[] {s1, 0, 0}, h[i][0], h[i + 1][0]);
			strip(faces, new double[] {s0, 0, 1}, new double[] {s1, 0, 1}, h[i][g], h[i + 1][g]);
			strip(faces, new double[] {0, 0, s0}, new double[] {0, 0, s1}, h[0][i], h[0][i + 1]);
			strip(faces, new double[] {1, 0, s0}, new double[] {1, 0, s1}, h[g][i], h[g][i + 1]);
		}
		return List.of(PanelGeometry.faceted((x, y, z) -> y <= facetHeight(x, z) + 1e-9, faces, "hull"));
	}

	private static void strip(List<double[][]> faces, double[] p, double[] q, double hp, double hq) {
		List<double[]> v = new ArrayList<>(List.of(p, q));
		if (hq > 1e-9) v.add(new double[] {q[0], hq, q[2]});
		if (hp > 1e-9) v.add(new double[] {p[0], hp, p[2]});
		if (v.size() >= 3) faces.add(v.toArray(double[][]::new));
	}
}
