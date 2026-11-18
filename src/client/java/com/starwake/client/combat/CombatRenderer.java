package com.starwake.client.combat;

import com.starwake.registry.ModEntities;
import com.starwake.ship.weapon.ShipProjectile;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Ship-gun shots (M11) drawn as tracers: a bright core and a soft glow, stretched back about two ticks along the shot's
 * path (never behind its muzzle) and turned to face the camera. Slugs are thinner and amber; laser bolts are longer, wider and
 * coloured by tier. The entities themselves use a no-op renderer.
 */
public final class CombatRenderer {
	private CombatRenderer() {}

	public static void init() {
		EntityRendererRegistry.register(ModEntities.SHIP_PROJECTILE, NoopRenderer::new);
		LevelRenderEvents.COLLECT_SUBMITS.register(CombatRenderer::render);
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(CombatRenderer::tick);
	}

	/**
	 * A tracer the client keeps on its own: shots often hit within a tick or two, so the entity alone would barely be
	 * seen. It starts at the muzzle (where the shot first appeared), follows the shot, stops at its end point, and fades
	 * for a few ticks after the shot is gone.
	 */
	private static final class Tracer {
		Vec3 origin;
		Vec3 dir;
		double speed;
		int color;
		boolean energy;
		Vec3 head, prevHead;
		boolean ended;
		int fade = -1; // ticks left once the shot has ended

		Tracer(ShipProjectile shot) {
			origin = shot.position();
			dir = new Vec3(0, 0, 1);
			head = prevHead = origin;
			read(shot);
		}

		/** The shot's synced data can arrive a moment after the shot itself. */
		void read(ShipProjectile shot) {
			Vec3 muzzle = shot.muzzle();
			if (muzzle != null) origin = muzzle;
			Vec3 v = shot.velocity();
			if (v.lengthSqr() > 1e-8) {
				speed = v.length();
				dir = v.scale(1 / speed);
			}
			color = shot.color();
			energy = shot.energy();
		}
	}

	private static final java.util.Map<Integer, Tracer> TRACERS = new java.util.HashMap<>();
	private static final int FADE_TICKS = 4;

	/** Client tick: follow live shots, start new tracers, fade finished ones. */
	private static void tick(Minecraft minecraft) {
		if (minecraft.level == null) {
			TRACERS.clear();
			return;
		}
		java.util.Set<Integer> seen = new java.util.HashSet<>();
		for (Entity e : minecraft.level.entitiesForRendering()) {
			if (!(e instanceof ShipProjectile shot)) continue;
			seen.add(shot.getId());
			Tracer t = TRACERS.get(shot.getId());
			if (t == null) {
				t = new Tracer(shot);
				TRACERS.put(shot.getId(), t);
				// Muzzle flash.
				for (int i = 0; i < 3; i++) {
					minecraft.level.addParticle(t.energy ? net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK : net.minecraft.core.particles.ParticleTypes.SMALL_FLAME,
							t.origin.x, t.origin.y, t.origin.z, t.dir.x * 0.08 * i, t.dir.y * 0.08 * i, t.dir.z * 0.08 * i);
				}
			}
			t.prevHead = t.head;
			t.read(shot);
			Vec3 end = shot.end();
			if (end != null) {
				t.head = end;
				if (!t.ended) {
					t.ended = true;
					t.fade = FADE_TICKS;
				}
			} else {
				t.head = shot.position();
			}
		}
		var it = TRACERS.entrySet().iterator();
		while (it.hasNext()) {
			var entry = it.next();
			Tracer t = entry.getValue();
			if (!seen.contains(entry.getKey()) && !t.ended) {
				// Removed without an end point reaching us: end where it was heading this tick.
				t.ended = true;
				t.fade = FADE_TICKS;
				t.prevHead = t.head;
				t.head = t.head.add(t.dir.scale(t.speed));
			}
			if (t.ended) {
				t.prevHead = t.head;
				if (--t.fade < 0) it.remove();
			}
		}
	}

	private static void render(LevelRenderContext context) {
		if (TRACERS.isEmpty()) return;
		CameraRenderState camera = context.levelState().cameraRenderState;
		float partial = camera.cameraEntityPartialTicks;
		Vec3 eye = camera.pos;
		// Order -1: drawn before ships and other entities (order 0). Tracers don't write depth, so hulls drawn after still
		// hide the ones behind them, and canopy glass (which writes depth) blends over them instead of hiding them.
		context.submitNodeCollector().order(-1).submitCustomGeometry(context.poseStack(), RenderTypes.debugQuads(), (pose, buffer) -> {
			for (Tracer t : TRACERS.values()) {
				Vec3 headWorld = t.prevHead.add(t.head.subtract(t.prevHead).scale(partial));
				double travelled = headWorld.subtract(t.origin).dot(t.dir);
				// About two ticks of travel: fast shots read as streaks from the guns rather than dots far ahead.
				double length = Math.min(Math.max(2, t.speed * (t.energy ? 2.5 : 2.0)), Math.max(0, travelled));
				if (length < 0.05) continue;
				float alpha = t.ended ? Math.max(0, (t.fade + 1 - partial) / (FADE_TICKS + 1f)) : 1f;
				if (alpha <= 0) continue;
				Vec3 head = headWorld.subtract(eye);
				Vec3 tail = head.subtract(t.dir.scale(length));
				Vec3 side = t.dir.cross(head).normalize();
				if (!Double.isFinite(side.x)) continue;
				double scale = 1 + head.length() / 80; // far shots stay visible
				int core = (int) (255 * alpha) << 24 | mix(t.color, 0xFFFFFF, 0.6);
				int glow = (int) ((t.energy ? 0xC0 : 0xA0) * alpha) << 24 | t.color & 0xFFFFFF;
				quad(pose, buffer, head, tail, side.scale((t.energy ? 0.07 : 0.05) * scale), core);
				quad(pose, buffer, head, tail, side.scale((t.energy ? 0.2 : 0.14) * scale), glow);
			}
		});
	}

	private static void quad(com.mojang.blaze3d.vertex.PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer buffer, Vec3 head, Vec3 tail,
			Vec3 side, int argb) {
		int faded = argb & 0x00FFFFFF; // the tail fades out
		float[][] v = {
				{(float) (head.x + side.x), (float) (head.y + side.y), (float) (head.z + side.z)},
				{(float) (head.x - side.x), (float) (head.y - side.y), (float) (head.z - side.z)},
				{(float) (tail.x - side.x), (float) (tail.y - side.y), (float) (tail.z - side.z)},
				{(float) (tail.x + side.x), (float) (tail.y + side.y), (float) (tail.z + side.z)}};
		int[] c = {argb, argb, faded, faded};
		// Both windings: the render type culls back faces and a billboard can face either way.
		for (int i : new int[] {0, 1, 2, 3}) buffer.addVertex(pose, v[i][0], v[i][1], v[i][2]).setColor(c[i]);
		for (int i : new int[] {3, 2, 1, 0}) buffer.addVertex(pose, v[i][0], v[i][1], v[i][2]).setColor(c[i]);
	}

	private static int mix(int a, int b, double t) {
		int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
		return r << 16 | g << 8 | bl;
	}
}
