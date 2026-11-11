package com.starwake.client.ship;

import com.starwake.ship.ShipCollisions;
import com.starwake.ship.ShipEntity;
import com.starwake.ship.SpaceTravel;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Star Citizen-style "local grid" movement for the local player.
 *
 * <h2>Aboard a ship</h2>
 * While you stand on or inside a ship, your movement is simulated in the ship's own frame:
 * position, velocity and look are ship-local, gravity pulls toward the deck (artificial
 * gravity), and collision is exact against the ship's blocks. Each tick the result is mapped
 * to world space with the ship's current transform. So you walk normally on a deck that is
 * pitched, rolled or upside down, and the ship carries you through every maneuver. The
 * camera rolls with the ship (CameraMixin), and mouse look turns you relative to the ship.
 *
 * <h2>EVA</h2>
 * Off-ship in space there's no gravity: suit thrusters move you along your view (WASD),
 * Space up, Shift down, with light damping.
 *
 * The server stays authoritative over everything else. It accepts client positions near
 * ships (ServerGamePacketListenerImplMixin), exactly like normal player movement.
 */
public final class ShipLocalMotion {
	/** Artificial gravity aboard (vanilla's 0.08 blocks/tick²). */
	private static final double DECK_GRAVITY = 0.08;
	/** How far outside the hull's box you can be before you leave the ship's frame. */
	private static final double EXIT_MARGIN = 1.5;
	private static final double EVA_ACCEL = 0.012, EVA_DRAG = 0.985, EVA_MAX_SPEED = 0.6;

	private static @Nullable ShipEntity ship;
	private static Vec3 localPos = Vec3.ZERO;
	private static Vec3 localVel = Vec3.ZERO;
	private static float localYaw, localPitch;
	private static boolean localOnGround;
	/** The world look we last applied; if the player's look differs, something else (a /tp,
	 * the server) rotated them, and we re-read their ship-local look from it. */
	private static float appliedYaw = Float.NaN, appliedPitch = Float.NaN;
	/** Where we last put the player in the world; a mismatch means a /tp or server correction. */
	private static Vec3 appliedPos = Vec3.ZERO;

	private ShipLocalMotion() {}

	/** The ship whose frame the local player is moving in, if any. */
	public static @Nullable ShipEntity activeShip() {
		return ship;
	}

	// =========================================================================================
	// Tick hooks (LocalPlayerMixin)
	// =========================================================================================

	/** Start of the player's tick: re-anchor to the ship's new transform. */
	public static void beforeTick(LocalPlayer player) {
		if (ship == null) return;
		if (!canUseShipFrame(player, ship)) {
			exit(player);
			return;
		}
		if (player.position().distanceToSqr(appliedPos) > 1.0e-6) {
			// Moved by something else (teleport, server correction): adopt the new position.
			localPos = ship.toLocal(player.position());
			localVel = Vec3.ZERO;
			if (!ship.isNearLocal(localPos, EXIT_MARGIN)) {
				exit(player);
				return;
			}
		}
		player.setPos(ship.toWorld(localPos));
		appliedPos = player.position();
		if (Math.abs(Mth.wrapDegrees(player.getYRot() - appliedYaw)) > 0.01f || Math.abs(player.getXRot() - appliedPitch) > 0.01f) {
			readLocalLook(player, ship);
		}
		applyLook(player);
	}

	private static void readLocalLook(LocalPlayer player, ShipEntity s) {
		Vec3 lookLocal = rotateToLocal(s, player.getViewVector(1.0f));
		localYaw = (float) Math.toDegrees(Math.atan2(-lookLocal.x, lookLocal.z));
		localPitch = (float) Math.toDegrees(Math.asin(Mth.clamp(-lookLocal.y, -1, 1)));
	}

	/** End of the player's tick: enter a ship's frame when standing on or inside one. */
	public static void afterTick(LocalPlayer player) {
		if (ship != null || player.isPassenger() || player.getAbilities().flying || player.isSpectator()) return;
		for (ShipEntity candidate : ShipCollisions.shipsIn(player.level())) {
			if (candidate.structure() != null && candidate.isSupporting(player)) {
				enter(player, candidate);
				return;
			}
		}
	}

	private static boolean canUseShipFrame(LocalPlayer player, ShipEntity s) {
		return !s.isRemoved() && s.level() == player.level() && !player.isPassenger()
				&& !player.getAbilities().flying && !player.isSpectator() && player.isAlive();
	}

	private static void enter(LocalPlayer player, ShipEntity s) {
		ship = s;
		localPos = s.toLocal(player.position());
		Vec3 relative = player.getDeltaMovement().subtract(s.pointVelocity(localPos));
		localVel = rotateToLocal(s, relative);
		readLocalLook(player, s);
		appliedPos = player.position();
		localOnGround = player.onGround();
	}

	private static void exit(LocalPlayer player) {
		ShipEntity s = ship;
		ship = null;
		if (s != null && !s.isRemoved()) {
			// Keep momentum: your velocity relative to the ship plus the ship's own motion here.
			player.setDeltaMovement(rotateToWorld(s, localVel).add(s.pointVelocity(localPos)));
		}
	}

	// =========================================================================================
	// Movement (PlayerTravelMixin)
	// =========================================================================================

	/** Replaces vanilla travel when aboard a ship or on EVA. Returns true if handled. */
	public static boolean travel(LocalPlayer player) {
		if (ship != null) {
			travelAboard(player, ship);
			return true;
		}
		if (SpaceTravel.isSpace(player.level()) && !player.isPassenger() && !player.getAbilities().flying
				&& !player.isSpectator()) {
			travelEva(player);
			return true;
		}
		return false;
	}

	private static void travelAboard(LocalPlayer player, ShipEntity s) {
		// Vanilla-style ground/air movement, but in the ship's frame.
		float friction = localOnGround ? 0.6f * 0.91f : 0.91f;
		float speed = localOnGround
				? player.getSpeed() * (0.21600002f / (friction * friction * friction))
				: (player.isSprinting() ? 0.026f : 0.02f);
		Vec3 wish = new Vec3(player.xxa, 0, player.zza);
		if (wish.lengthSqr() > 1.0e-7) {
			if (wish.lengthSqr() > 1) wish = wish.normalize();
			wish = wish.scale(speed);
			float sin = Mth.sin(localYaw * Mth.DEG_TO_RAD), cos = Mth.cos(localYaw * Mth.DEG_TO_RAD);
			localVel = localVel.add(wish.x * cos - wish.z * sin, 0, wish.z * cos + wish.x * sin);
		}
		if (player.input.keyPresses.jump() && localOnGround) {
			localVel = new Vec3(localVel.x, player.getAttributeValue(Attributes.JUMP_STRENGTH), localVel.z);
		}

		Vec3 moved = collideLocal(player, s, localVel);
		boolean hitX = moved.x != localVel.x, hitZ = moved.z != localVel.z;
		localOnGround = localVel.y < 0 && moved.y > localVel.y;
		localPos = localPos.add(moved);
		localVel = new Vec3(hitX ? 0 : moved.x * friction, (moved.y != localVel.y ? 0 : localVel.y - DECK_GRAVITY) * 0.98,
				hitZ ? 0 : moved.z * friction);

		player.setPos(s.toWorld(localPos));
		appliedPos = player.position();
		player.setOnGround(localOnGround);
		if (localOnGround) player.resetFallDistance();
		player.setDeltaMovement(rotateToWorld(s, localVel).add(s.pointVelocity(localPos)));
		applyLook(player);

		// Walked off the hull? Leave the ship's frame and fall/float in the world again.
		if (!s.isNearLocal(localPos, EXIT_MARGIN) || (!localOnGround && !s.isNearLocal(localPos, 0) && !s.isSupporting(player))) {
			exit(player);
		}
	}

	/** Axis-by-axis collision against the ship's blocks in local space, with a 0.6 step-up. */
	private static Vec3 collideLocal(LocalPlayer player, ShipEntity s, Vec3 motion) {
		double w = player.getBbWidth() / 2, h = player.getBbHeight();
		AABB box = new AABB(localPos.x - w, localPos.y, localPos.z - w, localPos.x + w, localPos.y + h, localPos.z + w);
		List<VoxelShape> shapes = new ArrayList<>();
		s.collectLocalShapes(box.expandTowards(motion).inflate(1.0), shapes);
		Vec3 result = collideAxes(box, motion, shapes);
		boolean blockedHorizontally = result.x != motion.x || result.z != motion.z;
		if (blockedHorizontally && localOnGround) {
			// Step up stairs/slabs like vanilla: rise, move, settle.
			double step = player.maxUpStep();
			Vec3 up = collideAxes(box, new Vec3(0, step, 0), shapes);
			Vec3 across = collideAxes(box.move(up), new Vec3(motion.x, 0, motion.z), shapes);
			Vec3 down = collideAxes(box.move(up).move(across), new Vec3(0, -up.y + Math.min(0, motion.y), 0), shapes);
			Vec3 stepped = up.add(across).add(down);
			if (stepped.horizontalDistanceSqr() > result.horizontalDistanceSqr()) return stepped;
		}
		return result;
	}

	private static Vec3 collideAxes(AABB box, Vec3 motion, List<VoxelShape> shapes) {
		double dy = Shapes.collide(Direction.Axis.Y, box, shapes, motion.y);
		box = box.move(0, dy, 0);
		double dx = Shapes.collide(Direction.Axis.X, box, shapes, motion.x);
		box = box.move(dx, 0, 0);
		double dz = Shapes.collide(Direction.Axis.Z, box, shapes, motion.z);
		return new Vec3(dx, dy, dz);
	}

	/** Zero-g suit thrusters: accelerate along your view; Space up, Shift down; light damping. */
	private static void travelEva(LocalPlayer player) {
		Vec3 look = player.getViewVector(1.0f);
		Vec3 right = look.cross(new Vec3(0, 1, 0)).normalize();
		Vec3 accel = look.scale(player.zza).add(right.scale(-player.xxa));
		if (player.input.keyPresses.jump()) accel = accel.add(0, 1, 0);
		if (player.input.keyPresses.shift()) accel = accel.add(0, -1, 0);
		if (accel.lengthSqr() > 1) accel = accel.normalize();
		Vec3 velocity = player.getDeltaMovement().add(accel.scale(EVA_ACCEL)).scale(EVA_DRAG);
		if (velocity.lengthSqr() > EVA_MAX_SPEED * EVA_MAX_SPEED) velocity = velocity.normalize().scale(EVA_MAX_SPEED);
		player.setDeltaMovement(velocity);
		player.move(net.minecraft.world.entity.MoverType.SELF, velocity);
		player.resetFallDistance();
	}

	// =========================================================================================
	// Look (EntityTurnMixin) and camera roll (CameraMixin)
	// =========================================================================================

	/** Mouse look aboard turns you relative to the ship. Returns true if handled. */
	public static boolean turn(LocalPlayer player, double dx, double dy) {
		if (ship == null && player.getVehicle() instanceof ShipEntity seatedOn) {
			return turnSeated(player, seatedOn, dx, dy);
		}
		if (ship == null) return false;
		localYaw += (float) dx * 0.15f;
		localPitch = Mth.clamp(localPitch + (float) dy * 0.15f, -90f, 90f);
		float oldYaw = player.getYRot(), oldPitch = player.getXRot();
		applyLook(player);
		// Keep the interpolation baseline consistent, like vanilla's turn().
		player.yRotO += Mth.wrapDegrees(player.getYRot() - oldYaw);
		player.xRotO += player.getXRot() - oldPitch;
		return true;
	}

	/**
	 * Seated (pilot or crew): mouse movement turns the view in the ship's frame, so "mouse
	 * right" always means "toward the ship's right" on screen, even when rolled. The pilot's
	 * mouse-aim steering then chases that look direction.
	 */
	private static boolean turnSeated(LocalPlayer player, ShipEntity s, double dx, double dy) {
		Vec3 lookLocal = rotateToLocal(s, player.getViewVector(1.0f));
		float yaw = (float) Math.toDegrees(Math.atan2(-lookLocal.x, lookLocal.z)) + (float) dx * 0.15f;
		float pitch = Mth.clamp((float) Math.toDegrees(Math.asin(Mth.clamp(-lookLocal.y, -1, 1))) + (float) dy * 0.15f, -89f, 89f);
		float y = yaw * Mth.DEG_TO_RAD, p = pitch * Mth.DEG_TO_RAD;
		Vec3 d = rotateToWorld(s, new Vec3(-Mth.sin(y) * Mth.cos(p), -Mth.sin(p), Mth.cos(y) * Mth.cos(p)));
		float oldYaw = player.getYRot(), oldPitch = player.getXRot();
		float worldYaw = oldYaw + Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-d.x, d.z)) - oldYaw);
		float worldPitch = (float) Math.toDegrees(Math.asin(Mth.clamp(-d.y, -1, 1)));
		player.setYRot(worldYaw);
		player.setXRot(worldPitch);
		player.setYHeadRot(worldYaw);
		player.yRotO += worldYaw - oldYaw;
		player.xRotO += worldPitch - oldPitch;
		return true;
	}

		/** Sets the player's world yaw/pitch to point along their ship-local look. */
	private static void applyLook(LocalPlayer player) {
		ShipEntity s = ship;
		if (s == null) return;
		float yaw = localYaw * Mth.DEG_TO_RAD, pitch = localPitch * Mth.DEG_TO_RAD;
		Vec3 dirLocal = new Vec3(-Mth.sin(yaw) * Mth.cos(pitch), -Mth.sin(pitch), Mth.cos(yaw) * Mth.cos(pitch));
		Vec3 d = rotateToWorld(s, dirLocal);
		float worldYaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float worldPitch = (float) Math.toDegrees(Math.asin(Mth.clamp(-d.y, -1, 1)));
		// Unwrap so yaw doesn't jump by 360 (keeps interpolation smooth).
		worldYaw = player.getYRot() + Mth.wrapDegrees(worldYaw - player.getYRot());
		player.setYRot(worldYaw);
		player.setXRot(worldPitch);
		player.setYHeadRot(worldYaw);
		appliedYaw = player.getYRot();
		appliedPitch = player.getXRot();
	}

	/**
	 * Camera roll (radians) so the view's "up" matches the ship's deck when you're aboard or
	 * seated. Zero otherwise.
	 */
	public static float cameraRoll(float yRotDeg, float xRotDeg) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) return 0;
		ShipEntity s = ship != null ? ship : minecraft.player.getVehicle() instanceof ShipEntity v ? v : null;
		if (s == null) return 0;
		float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		Quaternionf rotation = s.orientation(partial);
		Vector3f shipUp = rotation.transform(new Vector3f(0, 1, 0));

		float yaw = yRotDeg * Mth.DEG_TO_RAD, pitch = xRotDeg * Mth.DEG_TO_RAD;
		Vector3f look = new Vector3f(-Mth.sin(yaw) * Mth.cos(pitch), -Mth.sin(pitch), Mth.cos(yaw) * Mth.cos(pitch));
		// Vanilla's camera up (no roll): right = look x worldUp, up = right x look.
		Vector3f right = new Vector3f(look).cross(0, 1, 0);
		if (right.lengthSquared() < 1.0e-6f) return 0; // looking straight up/down: roll undefined
		Vector3f vanillaUp = new Vector3f(right).cross(look).normalize();
		// Ship up, projected onto the plane perpendicular to the view.
		Vector3f target = new Vector3f(shipUp).sub(new Vector3f(look).mul(shipUp.dot(look)));
		if (target.lengthSquared() < 1.0e-4f) return 0;
		target.normalize();
		Vector3f cross = new Vector3f(vanillaUp).cross(target);
		return (float) Math.atan2(cross.dot(look), vanillaUp.dot(target));
	}

	/**
	 * Camera position for the local player aboard (walking or seated) a ship: feet plus eye
	 * height along the ship's up axis. Null when not aboard, so vanilla's placement applies.
	 */
	public static @Nullable Vec3 eyePosition(float partialTicks, float eyeHeight) {
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null) return null;
		ShipEntity s = ship != null ? ship : player.getVehicle() instanceof ShipEntity v ? v : null;
		if (s == null) return null;
		Vector3f up = s.orientation(partialTicks).transform(new Vector3f(0, 1, 0));
		Vec3 feet = player.getPosition(partialTicks);
		return feet.add(up.x * eyeHeight, up.y * eyeHeight, up.z * eyeHeight);
	}

	/** Eye position for raycasts (same rule as the camera, at the current tick). */
	public static Vec3 eyePosition(LocalPlayer player) {
		Vec3 eye = eyePosition(1.0f, player.getEyeHeight());
		return eye != null ? eye : player.getEyePosition();
	}

		// =========================================================================================
	// Helpers
	// =========================================================================================

	private static Vec3 rotateToLocal(ShipEntity s, Vec3 v) {
		Vector3f out = s.orientation().transformInverse(new Vector3f((float) v.x, (float) v.y, (float) v.z));
		return new Vec3(out.x, out.y, out.z);
	}

	private static Vec3 rotateToWorld(ShipEntity s, Vec3 v) {
		Vector3f out = s.orientation().transform(new Vector3f((float) v.x, (float) v.y, (float) v.z));
		return new Vec3(out.x, out.y, out.z);
	}
}
