package com.starwake.client.space;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.starwake.client.ship.ShipLocalMotion;
import com.starwake.galaxy.Body;
import com.starwake.galaxy.SolarSystem;
import com.starwake.ship.ShipEntity;
import com.starwake.ship.SpaceTravel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Draws space as a place (3g): the system's star, every planet as a lit sphere with an
 * atmosphere glow, and a fixed starfield, all in the {@code starwake:space} dimension (whose
 * vanilla skybox is off).
 *
 * <h2>Scale trick</h2>
 * Planets are millions of blocks away, far beyond the camera's far plane. Each body is drawn
 * scaled toward the camera by {@code k = min(1, reach / distance)}, keeping its direction and
 * apparent size exact, so a planet grows smoothly as you fly at it. The geometry doesn't write
 * depth, so ships and blocks always draw over it.
 *
 * <h2>Quantum travel</h2>
 * During a jump the hull doesn't move; the camera is placed at the ship's "true" position
 * ({@link ShipEntity#quantumOffset}), so the target swells ahead while the stars stretch into
 * streaks away from the direction of travel.
 */
public final class SpaceRenderer {
	private static final int LAT = 24, LON = 48, STARS = 1400;
	/** Unit sphere grid [lat][lon] -> xyz. */
	private static final float[][][] SPHERE = new float[LAT + 1][LON + 1][];
	private static final float[][] STAR_DIRS = new float[STARS][];
	private static final int[] STAR_COLORS = new int[STARS];
	/** Per-body surface colour per grid vertex (computed once). */
	private static final Map<ResourceKey<?>, int[][]> SURFACE = new HashMap<>();

	static {
		for (int i = 0; i <= LAT; i++) {
			double lat = Math.PI * i / LAT - Math.PI / 2;
			for (int j = 0; j <= LON; j++) {
				double lon = 2 * Math.PI * j / LON;
				SPHERE[i][j] = new float[] {(float) (Math.cos(lat) * Math.cos(lon)), (float) Math.sin(lat),
						(float) (Math.cos(lat) * Math.sin(lon))};
			}
		}
		RandomSource random = RandomSource.create(0x5741_4B45L);
		for (int i = 0; i < STARS; i++) {
			double z = random.nextDouble() * 2 - 1, a = random.nextDouble() * Math.PI * 2, r = Math.sqrt(1 - z * z);
			STAR_DIRS[i] = new float[] {(float) (r * Math.cos(a)), (float) z, (float) (r * Math.sin(a))};
			int brightness = 120 + random.nextInt(136);
			int tint = random.nextInt(3); // a few warm and cool stars
			STAR_COLORS[i] = ARGB.color(brightness, tint == 0 ? 255 : 220, 230, tint == 2 ? 255 : 210);
		}
	}

	private static final boolean DEBUG = Boolean.getBoolean("starwake.debugSpace");
	private static int frames;

	private SpaceRenderer() {}

	public static void init() {
		LevelRenderEvents.COLLECT_SUBMITS.register(SpaceRenderer::render);
	}

	/** The ship whose frame the local player is in (seated or walking aboard). */
	public static @Nullable ShipEntity localShip() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) return null;
		return minecraft.player.getVehicle() instanceof ShipEntity seated ? seated : ShipLocalMotion.activeShip();
	}

	/** Where the camera "really" is in the system: camera + any quantum travel offset. */
	public static Vec3 systemPosition(Vec3 camera, float partialTick) {
		ShipEntity ship = localShip();
		return ship == null || !ship.isQuantumTravelling() ? camera : camera.add(ship.quantumOffset(partialTick));
	}

	private record Draw(Body body, int[][] surface, double scale, Vec3 centre, Vec3 toStar) {}

	private static void render(LevelRenderContext context) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || !SpaceTravel.isSpace(minecraft.level)) return;
		CameraRenderState camera = context.levelState().cameraRenderState;
		float partial = camera.cameraEntityPartialTicks;
		Vec3 eye = systemPosition(camera.pos, partial);
		double reach = Math.min(camera.depthFar * 0.6, 1400);

		List<SolarSystem.Entry> bodies = SolarSystem.bodies(minecraft.level.registryAccess());
		Vec3 starPos = bodies.stream().filter(e -> e.body().star()).map(e -> e.body().position()).findFirst().orElse(Vec3.ZERO);

		// Far to near, so nearer bodies paint over farther ones.
		List<Draw> draws = new ArrayList<>();
		for (SolarSystem.Entry entry : bodies) {
			Body body = entry.body();
			Vec3 rel = body.position().subtract(eye);
			double distance = rel.length();
			if (distance <= body.radius() * 1.001) continue; // inside it (you'd be on the surface by now)
			double k = Math.min(1.0, reach / distance);
			int[][] surface = SURFACE.computeIfAbsent(entry.key(), key -> surfaceColors(body, key.identifier().hashCode()));
			draws.add(new Draw(body, surface, k, rel.scale(k), body.star() ? Vec3.ZERO : starPos.subtract(body.position()).normalize()));
		}
		draws.sort((a, b) -> Double.compare(b.centre.lengthSqr() / (b.scale * b.scale), a.centre.lengthSqr() / (a.scale * a.scale)));

		// Quantum streaks: direction and strength of travel.
		ShipEntity ship = localShip();
		Vec3 travel = Vec3.ZERO;
		if (ship != null && ship.isQuantumTravelling()) {
			Vec3 step = ship.quantumStep();
			double speed = step.length(); // blocks per tick
			if (speed > 1.0e-3) travel = step.normalize().scale(Mth.clamp(speed / 1500.0, 0.05, 1.0));
		}
		final Vec3 streak = travel;
		final float starDistance = (float) (reach * 1.25); // behind every (scaled) body

		if (DEBUG && frames++ % 200 == 0) {
			com.starwake.StarwakeMod.LOGGER.info("[space] draws={} reach={} depthFar={} eye={} first={}", draws.size(), reach,
					camera.depthFar, eye, draws.isEmpty() ? "-" : draws.get(0).centre + " r=" + draws.get(0).body.radius() * draws.get(0).scale);
		}
		context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.debugQuads(), (pose, buffer) -> {
			drawStars(pose, buffer, starDistance, streak);
			for (Draw draw : draws) drawBody(pose, buffer, draw);
		});
	}

	// ---------------------------------------------------------------------------------------

	private static void drawStars(PoseStack.Pose pose, VertexConsumer buffer, float distance, Vec3 travel) {
		float size = distance * 0.0022f;
		boolean streaking = travel.lengthSqr() > 0;
		float tx = (float) travel.x, ty = (float) travel.y, tz = (float) travel.z;
		float strength = (float) travel.length();
		for (int i = 0; i < STARS; i++) {
			float[] d = STAR_DIRS[i];
			float x = d[0] * distance, y = d[1] * distance, z = d[2] * distance;
			if (!streaking) {
				billboard(pose, buffer, x, y, z, size, STAR_COLORS[i]);
				continue;
			}
			// Streak: stretch the star away from the point we're travelling toward.
			float along = (d[0] * tx + d[1] * ty + d[2] * tz) / strength;
			float px = d[0] - along * tx / strength, py = d[1] - along * ty / strength, pz = d[2] - along * tz / strength;
			float pl = (float) Math.sqrt(px * px + py * py + pz * pz);
			if (pl < 1.0e-4f) {
				billboard(pose, buffer, x, y, z, size, STAR_COLORS[i]);
				continue;
			}
			float len = distance * 0.25f * strength * pl;
			float ex = x + px / pl * len, ey = y + py / pl * len, ez = z + pz / pl * len;
			int color = ARGB.color(ARGB.alpha(STAR_COLORS[i]), 190, 225, 255); // blue-shifted
			ribbon(pose, buffer, x, y, z, ex, ey, ez, size * 0.8f, color, ARGB.color(0, 120, 180, 255));
		}
	}

	private static void drawBody(PoseStack.Pose pose, VertexConsumer buffer, Draw draw) {
		Body body = draw.body;
		double k = draw.scale;
		float r = (float) (body.radius() * k);
		float cx = (float) draw.centre.x, cy = (float) draw.centre.y, cz = (float) draw.centre.z;
		double distance = draw.centre.length();

		// Too far to resolve: a point of light, like a planet in the night sky (stars stay bigger).
		float minRadius = (float) (distance * (body.star() ? 0.006 : 0.0025));
		if (r < minRadius) {
			int dot = body.star() ? body.color() : shade(body, ARGB.srgbLerp(0.5f, body.color(), body.secondaryColor()),
					new float[] {(float) (-cx / distance), (float) (-cy / distance), (float) (-cz / distance)}, draw.toStar);
			billboard(pose, buffer, cx, cy, cz, minRadius, ARGB.opaque(dot));
			if (body.star()) ring(pose, buffer, cx, cy, cz, minRadius, minRadius * 3, ARGB.color(160, body.atmosphereColor()));
			return;
		}

		// Atmosphere / corona: a glowing ring just outside the limb, facing the camera.
		double atmosphere = body.star() ? body.radius() * 1.8 : Math.max(body.atmosphere(), body.radius() * 1.04);
		float limb = (float) (r * distance / Math.sqrt(Math.max(1.0e-6, distance * distance - (double) r * r)));
		float glowOuter = (float) (limb * atmosphere / body.radius());
		ring(pose, buffer, cx, cy, cz, limb * 0.985f, glowOuter, ARGB.color(body.star() ? 230 : 150, body.atmosphereColor()));

		// The sphere: only the hemisphere facing the camera.
		int[][] surface = draw.surface;
		Vec3 sun = draw.toStar;
		for (int i = 0; i < LAT; i++) {
			for (int j = 0; j < LON; j++) {
				float[] a = SPHERE[i][j], b = SPHERE[i][j + 1], c = SPHERE[i + 1][j + 1], d = SPHERE[i + 1][j];
				float nx = a[0] + b[0] + c[0] + d[0], ny = a[1] + b[1] + c[1] + d[1], nz = a[2] + b[2] + c[2] + d[2];
				// Facing test: normal against the view ray to the quad.
				if (nx * (cx + nx * r * 0.25f) + ny * (cy + ny * r * 0.25f) + nz * (cz + nz * r * 0.25f) >= 0) continue;
				vertex(pose, buffer, cx, cy, cz, r, a, shade(body, surface[i][j], a, sun));
				vertex(pose, buffer, cx, cy, cz, r, b, shade(body, surface[i][j + 1], b, sun));
				vertex(pose, buffer, cx, cy, cz, r, c, shade(body, surface[i + 1][j + 1], c, sun));
				vertex(pose, buffer, cx, cy, cz, r, d, shade(body, surface[i + 1][j], d, sun));
			}
		}
	}

	private static int shade(Body body, int color, float[] n, Vec3 sun) {
		if (body.star()) return color;
		double light = (n[0] * sun.x + n[1] * sun.y + n[2] * sun.z + 0.15) / 1.15;
		float f = (float) (0.18 + 0.82 * Mth.clamp(light, 0, 1)); // night side stays faintly visible
		return ARGB.color(255, (int) (ARGB.red(color) * f), (int) (ARGB.green(color) * f), (int) (ARGB.blue(color) * f));
	}

	/**
	 * Surface colours: the two body colours mixed by a smooth pseudo-noise of direction, so
	 * every planet gets its own fixed continents (or bands, for the star's granulation).
	 */
	private static int[][] surfaceColors(Body body, int seed) {
		RandomSource random = RandomSource.create(seed);
		double[] f = new double[9];
		for (int i = 0; i < f.length; i++) f[i] = random.nextDouble() * 6.28;
		int[][] colors = new int[LAT + 1][LON + 1];
		for (int i = 0; i <= LAT; i++) {
			for (int j = 0; j <= LON; j++) {
				float[] p = SPHERE[i][j];
				double n = Math.sin(p[0] * 3.1 + f[0]) * Math.sin(p[1] * 2.7 + f[1]) * Math.sin(p[2] * 3.3 + f[2])
						+ 0.5 * Math.sin(p[0] * 7.3 + f[3]) * Math.sin(p[1] * 6.1 + f[4]) * Math.sin(p[2] * 6.7 + f[5])
						+ 0.25 * Math.sin(p[0] * 13.1 + f[6]) * Math.sin(p[1] * 14.3 + f[7]) * Math.sin(p[2] * 12.7 + f[8]);
				float t = (float) Mth.clamp((n + 0.05) * 3.0, 0, 1);
				int c = ARGB.srgbLerp(t, body.color(), body.secondaryColor());
				if (!body.star() && Math.abs(p[1]) > 0.93) c = ARGB.srgbLerp(0.7f, c, 0xFFF4F8FF); // polar caps
				colors[i][j] = c;
			}
		}
		return colors;
	}

	// --- primitives ---------------------------------------------------------------------------

	private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, float cx, float cy, float cz, float r, float[] n, int color) {
		buffer.addVertex(pose, cx + n[0] * r, cy + n[1] * r, cz + n[2] * r).setColor(color);
	}

	/** A camera-facing square at a camera-relative point. */
	private static void billboard(PoseStack.Pose pose, VertexConsumer buffer, float x, float y, float z, float size, int color) {
		float[] u = new float[3], v = new float[3];
		basis(x, y, z, u, v);
		buffer.addVertex(pose, x - u[0] * size - v[0] * size, y - u[1] * size - v[1] * size, z - u[2] * size - v[2] * size).setColor(color);
		buffer.addVertex(pose, x + u[0] * size - v[0] * size, y + u[1] * size - v[1] * size, z + u[2] * size - v[2] * size).setColor(color);
		buffer.addVertex(pose, x + u[0] * size + v[0] * size, y + u[1] * size + v[1] * size, z + u[2] * size + v[2] * size).setColor(color);
		buffer.addVertex(pose, x - u[0] * size + v[0] * size, y - u[1] * size + v[1] * size, z - u[2] * size + v[2] * size).setColor(color);
	}

	/** A camera-facing strip from (x,y,z) to (ex,ey,ez), fading from {@code head} to {@code tail}. */
	private static void ribbon(PoseStack.Pose pose, VertexConsumer buffer, float x, float y, float z,
			float ex, float ey, float ez, float width, int head, int tail) {
		float dx = ex - x, dy = ey - y, dz = ez - z;
		// Side vector = direction x view ray (view ray to the head = (x,y,z)).
		float sx = dy * z - dz * y, sy = dz * x - dx * z, sz = dx * y - dy * x;
		float sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
		if (sl < 1.0e-6f) return;
		sx = sx / sl * width;
		sy = sy / sl * width;
		sz = sz / sl * width;
		buffer.addVertex(pose, x - sx, y - sy, z - sz).setColor(head);
		buffer.addVertex(pose, x + sx, y + sy, z + sz).setColor(head);
		buffer.addVertex(pose, ex + sx, ey + sy, ez + sz).setColor(tail);
		buffer.addVertex(pose, ex - sx, ey - sy, ez - sz).setColor(tail);
	}

	/** A camera-facing annulus centred on (cx,cy,cz), fading from {@code color} to clear. */
	private static void ring(PoseStack.Pose pose, VertexConsumer buffer, float cx, float cy, float cz,
			float inner, float outer, int color) {
		float[] u = new float[3], v = new float[3];
		basis(cx, cy, cz, u, v);
		int clear = ARGB.color(0, color);
		int segments = 64;
		for (int s = 0; s < segments; s++) {
			double a0 = 2 * Math.PI * s / segments, a1 = 2 * Math.PI * (s + 1) / segments;
			float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
			ringVertex(pose, buffer, cx, cy, cz, u, v, c0, s0, inner, color);
			ringVertex(pose, buffer, cx, cy, cz, u, v, c1, s1, inner, color);
			ringVertex(pose, buffer, cx, cy, cz, u, v, c1, s1, outer, clear);
			ringVertex(pose, buffer, cx, cy, cz, u, v, c0, s0, outer, clear);
		}
	}

	private static void ringVertex(PoseStack.Pose pose, VertexConsumer buffer, float cx, float cy, float cz,
			float[] u, float[] v, float c, float s, float radius, int color) {
		buffer.addVertex(pose, cx + (u[0] * c + v[0] * s) * radius, cy + (u[1] * c + v[1] * s) * radius,
				cz + (u[2] * c + v[2] * s) * radius).setColor(color);
	}

	/** Two unit vectors perpendicular to the view ray (x,y,z) and to each other. */
	private static void basis(float x, float y, float z, float[] u, float[] v) {
		float len = (float) Math.sqrt(x * x + y * y + z * z);
		float fx = x / len, fy = y / len, fz = z / len;
		// u = f x up (or f x east when looking straight up/down)
		float ux, uy, uz;
		if (Math.abs(fy) < 0.99f) {
			ux = -fz;
			uy = 0;
			uz = fx;
		} else {
			ux = 0;
			uy = fz;
			uz = -fy;
		}
		float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
		u[0] = ux / ul;
		u[1] = uy / ul;
		u[2] = uz / ul;
		v[0] = fy * u[2] - fz * u[1];
		v[1] = fz * u[0] - fx * u[2];
		v[2] = fx * u[1] - fy * u[0];
	}
}
