package com.starwake.ship.panel;

import java.util.ArrayList;
import java.util.List;

/**
 * Hull Form geometry (M9 pass 4): a block whose four top corners stand at their own heights (0..{@link #STEPS} eighths)
 * above its base, in model space (up = +Y). Corners: 0 = (x0, z0), 1 = (x1, z0), 2 = (x1, z1), 3 = (x0, z1). The top
 * surface is two triangles split along the diagonal whose corners are higher (so ridges stay sharp and valleys stay
 * flat); edges are straight lines, so neighbouring forms with matching corners meet without gaps. Any slope ratio,
 * corner, wedge, taper or twist is a combination of corner heights.
 */
public record FormShape(int h0, int h1, int h2, int h3) implements PanelGeometry.Shape {
	public static final int STEPS = 8;

	public int height(int corner) {
		return switch (corner) {
			case 0 -> h0;
			case 1 -> h1;
			case 2 -> h2;
			default -> h3;
		};
	}

	/** Surface height (0..1) above model point (x, z). */
	public double surface(double x, double z) {
		double a = h0 / (double) STEPS, b = h1 / (double) STEPS, c = h2 / (double) STEPS, d = h3 / (double) STEPS;
		if (h0 + h2 >= h1 + h3) {
			return x >= z ? a + (b - a) * x + (c - b) * z : a + (d - a) * z + (c - d) * x;
		}
		return x + z <= 1 ? a + (b - a) * x + (d - a) * z : c + (d - c) * (1 - x) + (b - c) * (1 - z);
	}

	@Override
	public List<PanelGeometry.Solid> solids() {
		double a = h0 / (double) STEPS, b = h1 / (double) STEPS, c = h2 / (double) STEPS, d = h3 / (double) STEPS;
		double[] t0 = {0, a, 0}, t1 = {1, b, 0}, t2 = {1, c, 1}, t3 = {0, d, 1};
		double[] b0 = {0, 0, 0}, b1 = {1, 0, 0}, b2 = {1, 0, 1}, b3 = {0, 0, 1};
		List<double[][]> faces = new ArrayList<>();
		faces.add(new double[][] {b0, b1, b2, b3});
		if (h0 + h2 >= h1 + h3) {
			faces.add(new double[][] {t0, t1, t2});
			faces.add(new double[][] {t0, t2, t3});
		} else {
			faces.add(new double[][] {t0, t1, t3});
			faces.add(new double[][] {t1, t2, t3});
		}
		side(faces, b0, b1, t1, t0);
		side(faces, b1, b2, t2, t1);
		side(faces, b2, b3, t3, t2);
		side(faces, b3, b0, t0, t3);
		return List.of(PanelGeometry.faceted((x, y, z) -> y <= surface(x, z) + 1e-9, faces, "hull"));
	}

	/** A side wall, dropping corners where the top meets the base (height 0). */
	private static void side(List<double[][]> faces, double[] p, double[] q, double[] qTop, double[] pTop) {
		List<double[]> v = new ArrayList<>(List.of(p, q));
		if (qTop[1] > 0) v.add(qTop);
		if (pTop[1] > 0) v.add(pTop);
		if (v.size() >= 3) faces.add(v.toArray(double[][]::new));
	}
}
