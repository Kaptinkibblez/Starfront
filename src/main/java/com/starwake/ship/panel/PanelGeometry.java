package com.starwake.ship.panel;

import com.mojang.math.OctahedralGroup;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix3f;
import org.joml.Vector3f;

/**
 * Hull Panel geometry (M9 pass 3), shared by both sides. Each {@link PanelShape} is a union of solids in model space
 * (unit cube, up = +Y, front = -Z) that can answer "is this point inside?" and list their faces. From that one
 * description come the rendered polygons (client: {@code HullPanelModel} bakes them into true sloped and curved quads)
 * and the collision shape (a 16^3 voxelisation merged into boxes), so the two always agree.
 *
 * Orientation: 24 proper rotations, indexed by the direction the shape's up points to and one of the four fronts
 * around it ({@link #orient}); the Hull Tool turns a panel about its up axis.
 */
public final class PanelGeometry {
	public static final int ORIENTATIONS = 24;
	private static final Direction[] UP = new Direction[ORIENTATIONS], FRONT = new Direction[ORIENTATIONS];
	private static final Matrix3f[] MATRIX = new Matrix3f[ORIENTATIONS];
	private static final int RES = 16;
	private static final VoxelShape[][] SHAPES = new VoxelShape[PanelShape.values().length][ORIENTATIONS];
	private static final double[] VOLUME = new double[PanelShape.values().length];
	private static final List<List<double[][]>> FACES = new ArrayList<>();

	static {
		int i = 0;
		for (Direction up : Direction.values()) {
			Direction front = null;
			for (Direction d : Direction.values()) {
				if (d.getAxis() != up.getAxis()) {
					front = d;
					break;
				}
			}
			for (int k = 0; k < 4; k++) {
				UP[i] = up;
				FRONT[i] = front;
				MATRIX[i] = find(up, front);
				i++;
				front = front.getClockWise(up.getAxis());
			}
		}
		for (PanelShape shape : PanelShape.values()) FACES.add(buildFaces(solids(shape)));
	}

	private PanelGeometry() {}

	private static Matrix3f find(Direction up, Direction front) {
		for (OctahedralGroup g : OctahedralGroup.values()) {
			if (g.rotate(Direction.UP) == up && g.rotate(Direction.NORTH) == front && g.transformation().determinant() > 0) {
				return new Matrix3f(g.transformation());
			}
		}
		throw new IllegalStateException("no rotation for " + up + "/" + front);
	}

	/** Orientation index whose up and front are these (front must be perpendicular to up). */
	public static int orient(Direction up, Direction front) {
		for (int i = 0; i < ORIENTATIONS; i++) if (UP[i] == up && FRONT[i] == front) return i;
		return up.ordinal() * 4; // front parallel to up: any front
	}

	public static Direction up(int orient) {
		return UP[orient];
	}

	public static Direction front(int orient) {
		return FRONT[orient];
	}

	/** The same up, front turned a quarter clockwise about it. */
	public static int turned(int orient, int quarters) {
		int base = orient / 4 * 4;
		return base + Math.floorMod(orient - base + quarters, 4);
	}

	/** Model point (unit cube) to block space for an orientation: rotated about the cube's centre. */
	public static Vector3f transform(int orient, double x, double y, double z) {
		Vector3f v = new Vector3f((float) (x - 0.5), (float) (y - 0.5), (float) (z - 0.5));
		MATRIX[orient].transform(v);
		return v.add(0.5f, 0.5f, 0.5f);
	}

	/** Faces in model space (outward winding, hidden ones dropped): each an array of {x, y, z} vertices. */
	public static List<double[][]> faces(PanelShape shape) {
		return FACES.get(shape.ordinal());
	}

	/** Share of the cube the shape fills (mass scales with it). */
	public static double volume(PanelShape shape) {
		if (VOLUME[shape.ordinal()] == 0) shape(shape, 0);
		return VOLUME[shape.ordinal()];
	}

	private static final VoxelShape[][] OUTLINES = new VoxelShape[PanelShape.values().length][ORIENTATIONS];

	/**
	 * Selection outline: a coarser 4-step version (a 16-step staircase draws as stripes); thin shapes that vanish at
	 * that resolution keep the fine shape.
	 */
	public static VoxelShape outline(PanelShape shape, int orient) {
		VoxelShape cached = OUTLINES[shape.ordinal()][orient];
		if (cached != null) return cached;
		VoxelShape coarse = voxelize(shape, orient, 4, false);
		if (coarse.isEmpty()) coarse = shape(shape, orient);
		OUTLINES[shape.ordinal()][orient] = coarse;
		return coarse;
	}

	/** Collision and occlusion shape (16 steps), cached. */
	public static VoxelShape shape(PanelShape shape, int orient) {
		VoxelShape cached = SHAPES[shape.ordinal()][orient];
		if (cached != null) return cached;
		VoxelShape result = voxelize(shape, orient, RES, true);
		SHAPES[shape.ordinal()][orient] = result;
		return result;
	}

	private static VoxelShape voxelize(PanelShape shape, int orient, int RES, boolean recordVolume) {
		List<Solid> solids = solids(shape);
		Matrix3f inverse = new Matrix3f(MATRIX[orient]).transpose();
		boolean[][][] filled = new boolean[RES][RES][RES];
		int count = 0;
		Vector3f p = new Vector3f();
		for (int x = 0; x < RES; x++) {
			for (int y = 0; y < RES; y++) {
				for (int z = 0; z < RES; z++) {
					p.set((x + 0.5f) / RES - 0.5f, (y + 0.5f) / RES - 0.5f, (z + 0.5f) / RES - 0.5f);
					inverse.transform(p);
					if (inside(solids, p.x + 0.5, p.y + 0.5, p.z + 0.5)) {
						filled[x][y][z] = true;
						count++;
					}
				}
			}
		}
		if (recordVolume) VOLUME[shape.ordinal()] = Math.max(1.0 / (RES * RES * RES), count / (double) (RES * RES * RES));
		VoxelShape result = Shapes.empty();
		boolean[][][] used = new boolean[RES][RES][RES];
		for (int x = 0; x < RES; x++) {
			for (int z = 0; z < RES; z++) {
				for (int y = 0; y < RES; y++) {
					if (!filled[x][y][z] || used[x][y][z]) continue;
					int y1 = y;
					while (y1 + 1 < RES && filled[x][y1 + 1][z] && !used[x][y1 + 1][z]) y1++;
					int z1 = z;
					while (z1 + 1 < RES && column(filled, used, x, y, y1, z1 + 1)) z1++;
					int x1 = x;
					while (x1 + 1 < RES && slab(filled, used, x1 + 1, y, y1, z, z1)) x1++;
					for (int a = x; a <= x1; a++) for (int b = y; b <= y1; b++) for (int c = z; c <= z1; c++) used[a][b][c] = true;
					result = Shapes.or(result, Shapes.box(x / (double) RES, y / (double) RES, z / (double) RES,
							(x1 + 1) / (double) RES, (y1 + 1) / (double) RES, (z1 + 1) / (double) RES));
				}
			}
		}
		return result.optimize();
	}

	private static boolean column(boolean[][][] filled, boolean[][][] used, int x, int y0, int y1, int z) {
		for (int y = y0; y <= y1; y++) if (!filled[x][y][z] || used[x][y][z]) return false;
		return true;
	}

	private static boolean slab(boolean[][][] filled, boolean[][][] used, int x, int y0, int y1, int z0, int z1) {
		for (int z = z0; z <= z1; z++) if (!column(filled, used, x, y0, y1, z)) return false;
		return true;
	}

	// ---------------------------------------------------------------------------------------------
	// Solids
	// ---------------------------------------------------------------------------------------------

	private interface Solid {
		boolean inside(double x, double y, double z);

		/** Faces with any winding; {@link #buildFaces} orients them outward. */
		List<double[][]> rawFaces();
	}

	private static boolean inside(List<Solid> solids, double x, double y, double z) {
		if (x < 0 || y < 0 || z < 0 || x > 1 || y > 1 || z > 1) return false;
		for (Solid s : solids) if (s.inside(x, y, z)) return true;
		return false;
	}

	/** A 2D profile swept along one axis from lo to hi. Axis 0 = X with (u, v) = (z, y); 1 = Y with (x, z); 2 = Z with (x, y). */
	private record Extrusion(int axis, double lo, double hi, double[][] profile) implements Solid {
		double[] at(double u, double v, double t) {
			return switch (axis) {
				case 0 -> new double[] {t, v, u};
				case 1 -> new double[] {u, t, v};
				default -> new double[] {u, v, t};
			};
		}

		@Override
		public boolean inside(double x, double y, double z) {
			double t = axis == 0 ? x : axis == 1 ? y : z;
			double u = axis == 0 ? z : x, v = axis == 0 ? y : axis == 1 ? z : y;
			return t >= lo && t <= hi && pointInPolygon(profile, u, v);
		}

		@Override
		public List<double[][]> rawFaces() {
			List<double[][]> out = new ArrayList<>();
			for (int[] tri : triangulate(profile)) {
				for (double t : new double[] {lo, hi}) {
					out.add(new double[][] {at(profile[tri[0]][0], profile[tri[0]][1], t), at(profile[tri[1]][0], profile[tri[1]][1], t),
							at(profile[tri[2]][0], profile[tri[2]][1], t)});
				}
			}
			for (int i = 0; i < profile.length; i++) {
				double[] a = profile[i], b = profile[(i + 1) % profile.length];
				out.add(new double[][] {at(a[0], a[1], lo), at(b[0], b[1], lo), at(b[0], b[1], hi), at(a[0], a[1], hi)});
			}
			return out;
		}
	}

	/** A solid given by a test and its faces (corners). */
	private record Faceted(Inside test, List<double[][]> faces) implements Solid {
		@Override
		public boolean inside(double x, double y, double z) {
			return test.inside(x, y, z);
		}

		@Override
		public List<double[][]> rawFaces() {
			return faces;
		}
	}

	@FunctionalInterface
	private interface Inside {
		boolean inside(double x, double y, double z);
	}

	private static Extrusion box(double x0, double y0, double z0, double x1, double y1, double z1) {
		return new Extrusion(1, y0, y1, new double[][] {{x0, z0}, {x1, z0}, {x1, z1}, {x0, z1}});
	}

	private static double[][] arc(double cu, double cv, double r, double fromDeg, double toDeg, int n) {
		double[][] out = new double[n + 1][];
		for (int i = 0; i <= n; i++) {
			double a = Math.toRadians(fromDeg + (toDeg - fromDeg) * i / n);
			out[i] = new double[] {cu + r * Math.cos(a), cv + r * Math.sin(a)};
		}
		return out;
	}

	private static double[][] concat(double[][]... parts) {
		List<double[]> out = new ArrayList<>();
		for (double[][] part : parts) {
			for (double[] p : part) {
				if (!out.isEmpty()) {
					double[] last = out.get(out.size() - 1);
					if (Math.abs(last[0] - p[0]) < 1e-9 && Math.abs(last[1] - p[1]) < 1e-9) continue;
				}
				out.add(p);
			}
		}
		if (out.size() > 1) {
			double[] first = out.get(0), last = out.get(out.size() - 1);
			if (Math.abs(last[0] - first[0]) < 1e-9 && Math.abs(last[1] - first[1]) < 1e-9) out.remove(out.size() - 1);
		}
		return out.toArray(double[][]::new);
	}

	private static double[][] poly(double... uv) {
		double[][] out = new double[uv.length / 2][];
		for (int i = 0; i < out.length; i++) out[i] = new double[] {uv[2 * i], uv[2 * i + 1]};
		return out;
	}

	private static final double T = 2 / 16.0;

	private static List<Solid> solids(PanelShape shape) {
		List<Solid> s = new ArrayList<>();
		switch (shape) {
			case SLOPE -> s.add(new Extrusion(0, 0, 1, poly(0, 0, 1, 0, 1, 1)));
			case SLOPE_2_LOW -> s.add(new Extrusion(0, 0, 1, poly(0, 0, 1, 0, 1, 0.5)));
			case SLOPE_2_HIGH -> s.add(new Extrusion(0, 0, 1, poly(0, 0, 1, 0, 1, 1, 0, 0.5)));
			case SLOPE_4_A -> s.add(new Extrusion(0, 0, 1, poly(0, 0, 1, 0, 1, 0.25)));
			case SLOPE_4_B -> s.add(new Extrusion(0, 0, 1, poly(0, 0, 1, 0, 1, 0.5, 0, 0.25)));
			case SLOPE_4_C -> s.add(new Extrusion(0, 0, 1, poly(0, 0, 1, 0, 1, 0.75, 0, 0.5)));
			case SLOPE_4_D -> s.add(new Extrusion(0, 0, 1, poly(0, 0, 1, 0, 1, 1, 0, 0.75)));
			case PEAK -> s.add(new Extrusion(0, 0, 1, poly(0, 0, 1, 0, 0.5, 1)));
			case CORNER -> {
				double[] a = {0, 0, 0}, b = {1, 0, 0}, c = {1, 0, 1}, d = {0, 0, 1}, top = {1, 1, 1};
				s.add(new Faceted((x, y, z) -> y <= Math.min(x, z),
						List.of(new double[][] {a, b, c, d}, new double[][] {b, c, top}, new double[][] {d, c, top},
								new double[][] {a, b, top}, new double[][] {a, top, d})));
			}
			case INNER_CORNER -> {
				double[] a = {0, 0, 0}, b = {1, 0, 0}, c = {1, 0, 1}, d = {0, 0, 1};
				double[] bt = {1, 1, 0}, ct = {1, 1, 1}, dt = {0, 1, 1};
				s.add(new Faceted((x, y, z) -> y <= Math.max(x, z),
						List.of(new double[][] {a, b, c, d}, new double[][] {b, c, ct, bt}, new double[][] {d, c, ct, dt},
								new double[][] {a, b, bt}, new double[][] {a, d, dt}, new double[][] {a, bt, ct}, new double[][] {a, ct, dt})));
			}
			case QUARTER_ROUND -> s.add(new Extrusion(0, 0, 1, concat(poly(1, 0), arc(1, 0, 1, 90, 180, 8))));
			case COVE -> s.add(new Extrusion(0, 0, 1, concat(poly(0, 0, 1, 0, 1, 1), arc(0, 1, 1, 0, -90, 8))));
			case ROUND_CORNER -> s.add(roundCorner());
			case CYLINDER -> s.add(new Extrusion(1, 0, 1, concat(arc(0.5, 0.5, 0.5, 0, 360, 16))));
			case HALF_ROUND -> s.add(new Extrusion(0, 0, 1, concat(arc(0.5, 0, 0.5, 0, 180, 8))));
			case HALF_PLATE -> s.add(box(0, 0, 0, 1, 0.5, 1));
			case QUARTER_PLATE -> s.add(box(0, 0, 0, 1, 0.25, 1));
			case ARMOR_PLATE -> s.add(new Extrusion(0, 0, 1, poly(0, 0, 0.25, 0, 1, 0.75, 1, 1)));
			case FIN -> s.add(new Extrusion(0, 7 / 16.0, 9 / 16.0, poly(0, 0, 1, 0, 1, 1, 0.6, 1)));
			case FRAME -> {
				for (double a : new double[] {0, 1 - T}) {
					for (double b : new double[] {0, 1 - T}) {
						s.add(box(a, 0, b, a + T, 1, b + T));           // vertical edges
						s.add(box(0, a, b, 1, a + T, b + T));           // edges along X
						s.add(box(a, b, 0, a + T, b + T, 1));           // edges along Z
					}
				}
			}
			case LATTICE -> {
				for (double a : new double[] {0, 1 - T}) {
					for (double b : new double[] {0, 1 - T}) s.add(box(a, b, 0, a + T, b + T, 1)); // rails along Z
				}
				double[][] band = poly(0, 0, 0.18, 0, 1, 0.82, 1, 1, 0.82, 1, 0, 0.18);
				s.add(new Extrusion(0, 0, T, band));       // west face brace (z, y)
				s.add(new Extrusion(0, 1 - T, 1, band));   // east face brace
				double[][] flat = poly(0, 0, 0.18, 0, 1, 0.82, 1, 1, 0.82, 1, 0, 0.18);
				s.add(new Extrusion(1, 0, T, flat));       // bottom brace (x, z)
				s.add(new Extrusion(1, 1 - T, 1, flat));   // top brace
			}
			case RIB -> s.add(new Extrusion(0, 6 / 16.0, 10 / 16.0, concat(arc(1, 0, 1, 90, 180, 8), arc(1, 0, 0.8, 180, 90, 8))));
		}
		return s;
	}

	/** An eighth of a sphere of radius 1 centred on the bottom south-east corner: two quarter-rounds meeting. */
	private static Solid roundCorner() {
		int n = 6;
		List<double[][]> faces = new ArrayList<>();
		double[][][] grid = new double[n + 1][n + 1][];
		for (int i = 0; i <= n; i++) {
			double th = Math.PI / 2 * i / n;
			for (int j = 0; j <= n; j++) {
				double ph = Math.PI / 2 * j / n;
				grid[i][j] = new double[] {1 - Math.cos(ph) * Math.cos(th), Math.sin(ph), 1 - Math.cos(ph) * Math.sin(th)};
			}
		}
		for (int i = 0; i < n; i++) {
			for (int j = 0; j < n; j++) {
				if (j == n - 1) faces.add(new double[][] {grid[i][j], grid[i + 1][j], grid[i][j + 1]});
				else faces.add(new double[][] {grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1]});
			}
		}
		double[] centre = {1, 0, 1};
		for (int i = 0; i < n; i++) faces.add(new double[][] {centre, grid[i][0], grid[i + 1][0]});   // bottom
		double[] axis = {1, 0, 1};
		for (int j = 0; j < n; j++) faces.add(new double[][] {axis, grid[0][j], grid[0][j + 1]});     // south face (z = 1)
		for (int j = 0; j < n; j++) faces.add(new double[][] {axis, grid[n][j], grid[n][j + 1]});     // east face (x = 1)
		return new Faceted((x, y, z) -> (x - 1) * (x - 1) + y * y + (z - 1) * (z - 1) <= 1, faces);
	}

	// ---------------------------------------------------------------------------------------------
	// Face building
	// ---------------------------------------------------------------------------------------------

	private static List<double[][]> buildFaces(List<Solid> solids) {
		List<double[][]> out = new ArrayList<>();
		for (Solid solid : solids) {
			for (double[][] face : solid.rawFaces()) {
				double[] n = normal(face);
				double len = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
				if (len < 1e-9) continue;
				double[] c = centroid(face);
				double e = 1e-4 / len;
				if (solid.inside(c[0] + n[0] * e, c[1] + n[1] * e, c[2] + n[2] * e)) {
					face = reversed(face);
					n = new double[] {-n[0], -n[1], -n[2]};
				}
				if (inside(solids, c[0] + n[0] * e, c[1] + n[1] * e, c[2] + n[2] * e)) continue; // buried in another part
				out.add(face);
			}
		}
		return List.copyOf(out);
	}

	/** Newell's normal (unnormalised). */
	public static double[] normal(double[][] face) {
		double nx = 0, ny = 0, nz = 0;
		for (int i = 0; i < face.length; i++) {
			double[] a = face[i], b = face[(i + 1) % face.length];
			nx += (a[1] - b[1]) * (a[2] + b[2]);
			ny += (a[2] - b[2]) * (a[0] + b[0]);
			nz += (a[0] - b[0]) * (a[1] + b[1]);
		}
		return new double[] {nx, ny, nz};
	}

	private static double[] centroid(double[][] face) {
		double x = 0, y = 0, z = 0;
		for (double[] p : face) {
			x += p[0];
			y += p[1];
			z += p[2];
		}
		return new double[] {x / face.length, y / face.length, z / face.length};
	}

	private static double[][] reversed(double[][] face) {
		double[][] out = new double[face.length][];
		for (int i = 0; i < face.length; i++) out[i] = face[face.length - 1 - i];
		return out;
	}

	private static boolean pointInPolygon(double[][] poly, double u, double v) {
		boolean in = false;
		for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
			double ui = poly[i][0], vi = poly[i][1], uj = poly[j][0], vj = poly[j][1];
			if ((vi > v) != (vj > v) && u < (uj - ui) * (v - vi) / (vj - vi) + ui) in = !in;
		}
		return in;
	}

	/** Ear-clipping triangulation of a simple polygon (either winding); returns index triples. */
	private static List<int[]> triangulate(double[][] poly) {
		List<Integer> idx = new ArrayList<>();
		double area = 0;
		for (int i = 0; i < poly.length; i++) {
			double[] a = poly[i], b = poly[(i + 1) % poly.length];
			area += a[0] * b[1] - b[0] * a[1];
		}
		for (int i = 0; i < poly.length; i++) idx.add(area >= 0 ? i : poly.length - 1 - i);
		List<int[]> out = new ArrayList<>();
		int guard = 0;
		while (idx.size() > 3 && guard++ < 10_000) {
			boolean clipped = false;
			for (int k = 0; k < idx.size(); k++) {
				int ia = idx.get((k + idx.size() - 1) % idx.size()), ib = idx.get(k), ic = idx.get((k + 1) % idx.size());
				double[] a = poly[ia], b = poly[ib], c = poly[ic];
				double cross = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
				if (cross <= 1e-12) continue; // reflex or degenerate
				boolean contains = false;
				for (int m : idx) {
					if (m == ia || m == ib || m == ic) continue;
					if (inTriangle(poly[m], a, b, c)) {
						contains = true;
						break;
					}
				}
				if (contains) continue;
				out.add(new int[] {ia, ib, ic});
				idx.remove(k);
				clipped = true;
				break;
			}
			if (!clipped) break;
		}
		if (idx.size() == 3) out.add(new int[] {idx.get(0), idx.get(1), idx.get(2)});
		return out;
	}

	private static boolean inTriangle(double[] p, double[] a, double[] b, double[] c) {
		double d1 = (p[0] - b[0]) * (a[1] - b[1]) - (a[0] - b[0]) * (p[1] - b[1]);
		double d2 = (p[0] - c[0]) * (b[1] - c[1]) - (b[0] - c[0]) * (p[1] - c[1]);
		double d3 = (p[0] - a[0]) * (c[1] - a[1]) - (c[0] - a[0]) * (p[1] - a[1]);
		boolean neg = d1 < 0 || d2 < 0 || d3 < 0, pos = d1 > 0 || d2 > 0 || d3 > 0;
		return !(neg && pos);
	}
}
