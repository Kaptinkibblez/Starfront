package com.starwake.client.mixin;

import com.starwake.client.ship.ShipLocalMotion;
import net.minecraft.client.Camera;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rolls the camera with the ship when you're aboard or seated, so the deck/cockpit stays
 * level on screen and the world outside tilts (Star Citizen-style). Vanilla cameras have no
 * roll axis; we add it after vanilla sets yaw and pitch.
 */
@Mixin(Camera.class)
abstract class CameraMixin {
	@Shadow @Final private static Vector3fc FORWARDS;
	@Shadow @Final private static Vector3fc UP;
	@Shadow @Final private static Vector3fc LEFT;
	@Shadow @Final private Quaternionf rotation;
	@Shadow @Final private Vector3f forwards;
	@Shadow @Final private Vector3f up;
	@Shadow @Final private Vector3f left;

	@Shadow private float eyeHeight;
	@Shadow private float eyeHeightOld;

	@Shadow
	protected abstract void setPosition(net.minecraft.world.phys.Vec3 position);

	/**
	 * Aboard a rotated ship, your eyes sit along the deck's "up", not the world's. Vanilla
	 * adds eye height on world Y, which puts the camera inside the floor when the ship rolls.
	 */
	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void starwake$shipEyeHeight(float partialTicks, CallbackInfo ci) {
		if (((Camera) (Object) this).isDetached()) return;
		var eye = ShipLocalMotion.eyePosition(partialTicks,
				net.minecraft.util.Mth.lerp(partialTicks, eyeHeightOld, eyeHeight));
		if (eye != null) setPosition(eye);
	}

	@Inject(method = "setRotation", at = @At("TAIL"))
	private void starwake$shipRoll(float yRot, float xRot, CallbackInfo ci) {
		float roll = ShipLocalMotion.cameraRoll(yRot, xRot);
		if (roll == 0) return;
		rotation.rotateZ(-roll);
		FORWARDS.rotate(rotation, forwards);
		UP.rotate(rotation, up);
		LEFT.rotate(rotation, left);
	}
}
