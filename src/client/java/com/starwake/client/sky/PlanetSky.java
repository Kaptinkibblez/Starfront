package com.starwake.client.sky;

import com.starwake.client.space.SpaceRenderer;
import com.starwake.galaxy.SolarSystem;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3d;
import org.jspecify.annotations.Nullable;

/**
 * The real sky on a planet's surface (M9): the system's own star(s) in place of the vanilla sun, and the other planets
 * and moons in their true directions and sizes, lit from the star (so they show phases).
 *
 * Day and night stay vanilla's (the planet's timeline drives the sun angle and the light level), so the star is drawn
 * exactly where the vanilla sun would be. Everything else is placed by its true angle from the star: the planet's
 * orbital plane maps onto the sun's daily path across the sky (an observer on the equator), and the system's up axis
 * onto north. Moons are visible by day; planets fade into a daylit sky; the unlit side of a body is see-through. The
 * vanilla sun and moon are hidden while this runs ({@code SkyRendererMixin}).
 */
public final class PlanetSky {
	private PlanetSky() {}

	public static void init() {
		LevelRenderEvents.COLLECT_SUBMITS.register(PlanetSky::render);
	}

	/** The body of the planet (or moon) this level is the surface of, if its system has a star. */
	private static SolarSystem.@Nullable Entry here(ClientLevel level) {
		if (SolarSystem.isSpace(level.dimension())) return null;
		var entry = SolarSystem.forDimension(level.registryAccess(), level.dimension()).orElse(null);
		if (entry == null || entry.orbit() == null) return null;
		return entry;
	}

	/** True when this sky replaces the vanilla sun and moon. */
	public static boolean replacesVanilla(@Nullable ClientLevel level) {
		return level != null && here(level) != null;
	}

	private static void render(LevelRenderContext context) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null) return;
		SolarSystem.Entry home = here(level);
		if (home == null) return;
		CameraRenderState camera = context.levelState().cameraRenderState;
		float partial = camera.cameraEntityPartialTicks;
		double t = SpaceRenderer.time(partial);
		List<SolarSystem.Entry> bodies = SolarSystem.bodies(level);
		Vec3 eye = home.at(t);
		Vec3 star = bodies.stream().filter(e -> e.body().star()).map(e -> e.body().position()).findFirst().orElse(null);
		if (star == null) return;

		var probe = minecraft.gameRenderer.mainCamera().attributeProbe();
		float sunAngle = (float) Math.toRadians(probe.getValue(EnvironmentAttributes.SUN_ANGLE, partial));
		float starBrightness = probe.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, partial);
		float rain = level.getRainLevel(partial);
		// Daylight washes planets out (moons, being big, stay visible); rain hides everything but a dim star.
		float alpha = Mth.clamp(0.3f + starBrightness * 1.4f, 0.3f, 1f) * (1f - rain * 0.9f);

		Matrix3d sky = skyRotation(eye, star, sunAngle);
		double reach = Math.min(camera.depthFar * 0.6, 1400);
		var draws = SpaceRenderer.draws(bodies, eye, t, reach, sky, home.key(), alpha);
		if (rain > 0.2f) draws = draws.stream().filter(d -> d.body().star()).toList();
		SpaceRenderer.submit(context, draws);
	}

	/**
	 * System directions -> sky directions. The star direction becomes the vanilla sun's (-sin a, cos a, 0); the next
	 * direction along the orbital plane follows the sun's path; the system's up axis points north (-Z).
	 */
	static Matrix3d skyRotation(Vec3 eye, Vec3 star, float sunAngle) {
		Vec3 s = new Vec3(star.x - eye.x, 0, star.z - eye.z);
		s = s.lengthSqr() < 1e-9 ? new Vec3(1, 0, 0) : s.normalize();
		Vec3 up = new Vec3(0, 1, 0);
		Vec3 along = up.cross(s); // in the orbital plane, 90 degrees from the star
		double sa = Math.sin(sunAngle), ca = Math.cos(sunAngle);
		// Columns: where the system basis (s, along, up) lands in the sky.
		Matrix3d toSky = new Matrix3d(-sa, ca, 0, -ca, -sa, 0, 0, 0, -1);
		Matrix3d fromSystem = new Matrix3d(s.x, along.x, up.x, s.y, along.y, up.y, s.z, along.z, up.z); // rows = basis
		return toSky.mul(fromSystem);
	}
}
