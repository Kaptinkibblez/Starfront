package com.starwake.client.hud;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** World direction -> GUI pixel, for HUD markers (shared by the flight and space HUDs). */
public final class HudProjection {
	private HudProjection() {}

	/**
	 * Screen position of a camera-relative direction, or null if it's behind the camera or well
	 * off screen. {@code mvp} = camera view-rotation-projection, {@code forward} = camera forward.
	 */
	public static int @Nullable [] project(Matrix4f mvp, Vector3f forward, Vec3 direction, int w, int h) {
		Vec3 dir = direction.normalize();
		if (dir.x * forward.x + dir.y * forward.y + dir.z * forward.z <= 0.05) return null;
		Vector3f ndc = mvp.transformProject(new Vector3f((float) dir.x * 10, (float) dir.y * 10, (float) dir.z * 10));
		if (Math.abs(ndc.x) > 1.2f || Math.abs(ndc.y) > 1.2f) return null;
		return new int[] {Mth.floor((ndc.x * 0.5f + 0.5f) * w), Mth.floor((0.5f - ndc.y * 0.5f) * h)};
	}
}
