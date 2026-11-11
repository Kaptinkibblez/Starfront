package com.starwake.client.ship;

import com.mojang.blaze3d.platform.InputConstants;
import com.starwake.StarwakeMod;
import com.starwake.network.ShipConsoleActionPayload;
import com.starwake.network.ShipControlPayload;
import com.starwake.network.ShipQuantumPayload;
import com.starwake.network.ShipInteractPayload;
import com.starwake.network.ShipStructurePayload;
import com.starwake.registry.ModEntities;
import com.starwake.ship.ShipCollisions;
import com.starwake.ship.ShipEntity;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Client half of ships:
 * - renderer and structure sync
 * - keeps the client's ship index (for collision prediction)
 * - (walking aboard lives in ShipLocalMotion + its mixins)
 * - right-click raycasts against ship blocks (ships aren't pickable; see MinecraftMixin)
 * - the "Ship Console" key (default R) for the pilot
 */
public final class ShipClient {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(StarwakeMod.id("starwake"));
	private static final KeyMapping SHIP_CONSOLE = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.starwake.ship_console", InputConstants.KEY_R, CATEGORY));
	private static final KeyMapping ROLL_LEFT = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.starwake.roll_left", InputConstants.KEY_Z, CATEGORY));
	private static final KeyMapping ROLL_RIGHT = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.starwake.roll_right", InputConstants.KEY_C, CATEGORY));
	private static final KeyMapping FREE_LOOK = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.starwake.free_look", InputConstants.KEY_LALT, CATEGORY));
	private static final KeyMapping LEAVE_SEAT = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.starwake.leave_seat", InputConstants.KEY_X, CATEGORY));
	/** Star Citizen's quantum key: tap to toggle quantum mode, hold to engage. */
	private static final KeyMapping QUANTUM = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.starwake.quantum", InputConstants.KEY_B, CATEGORY));
	/** Cockpit / chase camera. */
	private static final KeyMapping CHASE = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.starwake.chase_camera", InputConstants.KEY_F4, CATEGORY));
	/** Landing gear up/down. */
	private static final KeyMapping GEAR = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.starwake.gear", InputConstants.KEY_N, CATEGORY));
	/** The Wristcom (wrist computer). */
	private static final KeyMapping WRISTCOM = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.starwake.wristcom", InputConstants.KEY_G, CATEGORY));
	/** Star Citizen's coupled/decoupled toggle. */
	private static final KeyMapping DECOUPLE = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.starwake.decouple", InputConstants.KEY_V, CATEGORY));
	/** Jump drive (M6): tap to arm, hold to jump. */
	private static final KeyMapping JUMP = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.starwake.jump", InputConstants.KEY_J, CATEGORY));
	/** The galaxy map (M6). */
	private static final KeyMapping GALAXY_MAP = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.starwake.galaxy_map", InputConstants.KEY_M, CATEGORY));
	private static int jumpHeld;
	/** Ticks B must be held to engage (0.75 s). */
	private static final int QUANTUM_HOLD_TICKS = 15;
	private static int quantumHeld;

	/** Last control bits sent, so we only send changes. */
	private static int sentControls = -1;

	private ShipClient() {}

	public static void init() {
		EntityRendererRegistry.register(ModEntities.SHIP, ShipRenderer::new);

		ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof ShipEntity ship) ShipCollisions.add(ship);
		});
		ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof ShipEntity ship) ShipCollisions.remove(ship);
		});

		ClientPlayNetworking.registerGlobalReceiver(ShipStructurePayload.TYPE, (payload, context) -> {
			if (context.client().level != null && context.client().level.getEntity(payload.entityId()) instanceof ShipEntity ship) {
				ship.setStructure(payload.structure());
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			sendControls(client);
			quantumKey(client);
			jumpKey(client);
			while (GALAXY_MAP.consumeClick()) {
				if (client.player != null && client.gui.screen() == null) client.gui.setScreen(new com.starwake.client.galaxy.GalaxyMapScreen());
			}
			decoupleKey(client);
			gearKey(client);
			while (WRISTCOM.consumeClick()) {
				if (client.player != null && client.gui.screen() == null) client.gui.setScreen(new com.starwake.client.wristcom.WristcomScreen());
			}
			ThrusterEffects.tick(client);
			facePilotForward(client);
			ShipSounds.tick(client);
			ChaseCamera.tick(client);
			ReentryEffects.tick(client);
			while (CHASE.consumeClick()) ChaseCamera.toggle(client);
			while (SHIP_CONSOLE.consumeClick()) {
				if (client.player != null && client.player.getVehicle() instanceof ShipEntity ship) {
					ClientPlayNetworking.send(new ShipConsoleActionPayload(BlockPos.ZERO, ship.getId(),
							ShipConsoleActionPayload.Action.OPEN_SHIP));
				}
			}
		});
	}

	/** Sends roll/free-look changes while seated on a ship, and "leave seat" presses. */
	private static void sendControls(Minecraft client) {
		if (client.player == null || !(client.player.getVehicle() instanceof ShipEntity)) {
			sentControls = -1;
			while (LEAVE_SEAT.consumeClick()) { /* discard */ }
			return;
		}
		int controls = (ROLL_LEFT.isDown() ? ShipEntity.CONTROL_ROLL_LEFT : 0)
				| (ROLL_RIGHT.isDown() ? ShipEntity.CONTROL_ROLL_RIGHT : 0)
				| (FREE_LOOK.isDown() ? ShipEntity.CONTROL_FREE_LOOK : 0);
		if (controls != sentControls) {
			ClientPlayNetworking.send(new ShipControlPayload(controls, false));
			sentControls = controls;
		}
		while (LEAVE_SEAT.consumeClick()) {
			ClientPlayNetworking.send(new ShipControlPayload(0, true));
		}
	}

	private static int pilotOf = -1;

	/**
	 * The moment you become a ship's pilot, look along its nose (mouse-aim steers toward your
	 * view, so the downward look you had while clicking the Helm would pitch the ship). The
	 * server does the same; doing it here too avoids a race between that and the seat packet.
	 */
	private static void facePilotForward(Minecraft client) {
		ShipEntity ship = client.player != null && client.player.getVehicle() instanceof ShipEntity s && s.pilot() == client.player ? s : null;
		int id = ship == null ? -1 : ship.getId();
		if (ship != null && id != pilotOf) {
			var nose = ship.worldDirection(ship.forward());
			float yaw = (float) Math.toDegrees(Math.atan2(-nose.x, nose.z));
			float pitch = (float) Math.toDegrees(-Math.asin(Math.clamp(nose.y, -1, 1)));
			client.player.setYRot(yaw);
			client.player.setXRot(pitch);
			client.player.yRotO = yaw;
			client.player.xRotO = pitch;
			client.player.setYHeadRot(yaw);
		}
		pilotOf = id;
	}

	/** N: landing gear (pilot only). */
	private static void gearKey(Minecraft client) {
		while (GEAR.consumeClick()) {
			if (client.player != null && client.player.getVehicle() instanceof ShipEntity ship && ship.pilot() == client.player) {
				ClientPlayNetworking.send(new ShipQuantumPayload(ShipQuantumPayload.ACTION_GEAR));
			}
		}
	}

	/** V: toggle decoupled flight (pilot only). */
	private static void decoupleKey(Minecraft client) {
		while (DECOUPLE.consumeClick()) {
			if (client.player != null && client.player.getVehicle() instanceof ShipEntity ship && ship.pilot() == client.player) {
				ClientPlayNetworking.send(new ShipQuantumPayload(ShipQuantumPayload.ACTION_DECOUPLE));
			}
		}
	}

	/** J: a tap arms/disarms the jump drive; holding past {@link #QUANTUM_HOLD_TICKS} jumps once. */
	private static void jumpKey(Minecraft client) {
		while (JUMP.consumeClick()) { /* edge presses are handled through isDown below */ }
		boolean piloting = client.player != null && client.player.getVehicle() instanceof ShipEntity ship && ship.pilot() == client.player;
		if (!piloting) {
			jumpHeld = 0;
			return;
		}
		if (JUMP.isDown()) {
			jumpHeld++;
			if (jumpHeld == QUANTUM_HOLD_TICKS) ClientPlayNetworking.send(new ShipQuantumPayload(ShipQuantumPayload.ACTION_JUMP_ENGAGE));
		} else {
			if (jumpHeld > 0 && jumpHeld < QUANTUM_HOLD_TICKS) ClientPlayNetworking.send(new ShipQuantumPayload(ShipQuantumPayload.ACTION_JUMP_TOGGLE));
			jumpHeld = 0;
		}
	}

	/** B: a tap toggles quantum mode; holding past {@link #QUANTUM_HOLD_TICKS} engages once. */
	private static void quantumKey(Minecraft client) {
		while (QUANTUM.consumeClick()) { /* edge presses are handled through isDown below */ }
		boolean piloting = client.player != null && client.player.getVehicle() instanceof ShipEntity ship
				&& ship.pilot() == client.player;
		if (!piloting) {
			quantumHeld = 0;
			return;
		}
		if (QUANTUM.isDown()) {
			quantumHeld++;
			if (quantumHeld == QUANTUM_HOLD_TICKS) ClientPlayNetworking.send(new ShipQuantumPayload(ShipQuantumPayload.ACTION_QUANTUM_ENGAGE));
		} else {
			if (quantumHeld > 0 && quantumHeld < QUANTUM_HOLD_TICKS) ClientPlayNetworking.send(new ShipQuantumPayload(ShipQuantumPayload.ACTION_QUANTUM_TOGGLE));
			quantumHeld = 0;
		}
	}

	/** Result of a ray hitting a ship block. */
	public record ShipHit(ShipEntity ship, BlockPos local, net.minecraft.core.Direction face, Vec3 hitLocal, double distanceSqr) {}

	/**
	 * Called at the start of every right-click (MinecraftMixin). Returns true if the click hit
	 * a ship block (closer than anything vanilla targeted) and was handled.
	 */
	public static boolean tryInteract() {
		Minecraft minecraft = Minecraft.getInstance();
		Player player = minecraft.player;
		if (player == null || minecraft.gameMode == null || player.isSpectator()) return false;
		ShipHit hit = targetedShipBlock(minecraft);
		if (hit == null) return false;
		ClientPlayNetworking.send(new ShipInteractPayload(hit.ship().getId(), hit.local(), hit.face(), hit.hitLocal()));
		player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
		return true;
	}

	/**
	 * The ship block under the crosshair, if it's closer than whatever vanilla targets in the
	 * world (block or entity); null otherwise.
	 */
	public static @Nullable ShipHit targetedShipBlock(Minecraft minecraft) {
		Player player = minecraft.player;
		if (player == null || player.isSpectator()) return null;
		ShipHit hit = raycastShips(player);
		if (hit == null) return null;
		HitResult vanilla = minecraft.hitResult;
		if (vanilla != null && vanilla.getType() != HitResult.Type.MISS
				&& vanilla.getLocation().distanceToSqr(player.getEyePosition()) < hit.distanceSqr()) {
			return null; // something in the world is closer
		}
		return hit;
	}

	/** Raycasts the player's view against every nearby ship, in each ship's local space. */
	public static @Nullable ShipHit raycastShips(Player player) {
		Vec3 eye = player instanceof net.minecraft.client.player.LocalPlayer local ? ShipLocalMotion.eyePosition(local) : player.getEyePosition();
		Vec3 end = eye.add(player.getViewVector(1.0f).scale(player.blockInteractionRange()));
		ShipHit best = null;
		for (ShipEntity ship : ShipCollisions.shipsIn(player.level())) {
			if (ship.structure() == null || !ship.getBoundingBox().inflate(player.blockInteractionRange()).contains(eye)) {
				continue;
			}
			// Raycast in the ship's own frame, where its blocks are axis-aligned.
			Vec3 from = ship.toLocal(eye);
			Vec3 to = ship.toLocal(end);
			BlockHitResult local = BlockGetter.traverseBlocks(from, to, ship, (s, pos) -> {
				BlockState state = s.structure().get(pos);
				if (state == null) return null;
				// traverseBlocks reuses a mutable position: hand the hit an immutable copy.
				return state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).clip(from, to, pos.immutable());
			}, s -> null);
			if (local != null) {
				double distanceSqr = local.getLocation().distanceToSqr(from);
				if (best == null || distanceSqr < best.distanceSqr()) {
					best = new ShipHit(ship, local.getBlockPos(), local.getDirection(), local.getLocation(), distanceSqr);
				}
			}
		}
		return best;
	}
}
