package com.starwake.client.ship;

import com.mojang.blaze3d.vertex.PoseStack;
import com.starwake.StarwakeMod;
import com.starwake.network.ShipEditPayload;
import com.starwake.ship.ShipEntity;
import com.starwake.ship.edit.ShipBlockView;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Client half of ship editing (Milestone 4): mining blocks on assembled ships, and the block
 * outline for whatever ship block is under the crosshair.
 *
 * <p>Mining follows vanilla's rules: progress per tick is {@code BlockState.getDestroyProgress}
 * for your tool against the ship's blocks ({@link ShipBlockView}), with hit sounds every 4 ticks
 * and a 5-tick pause between blocks. Creative breaks instantly. The client reports START and
 * FINISH; the server checks permission and that enough time passed. A progress bar under the
 * crosshair stands in for vanilla's crack overlay.
 */
public final class ShipEditingClient {
	private static int miningShip = -1;
	private static @Nullable BlockPos miningLocal;
	private static float progress;
	private static int ticks;
	private static int delay;

	private ShipEditingClient() {}

	public static void init() {
		LevelRenderEvents.COLLECT_SUBMITS.register(ShipEditingClient::renderOutline);
		// Hide vanilla's outline when the ship block in front of it is the real target.
		LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register((context, state) -> ShipClient.targetedShipBlock(Minecraft.getInstance()) == null);
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, StarwakeMod.id("ship_mining"), ShipEditingClient::renderProgress);
	}

	/** Left-click pressed. Returns true if it hit a ship block (vanilla then does nothing). */
	public static boolean startAttack(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.missTime > 0 || player.isHandsBusy()) return false;
		ShipClient.ShipHit hit = ShipClient.targetedShipBlock(minecraft);
		if (hit == null) return false;
		if (minecraft.gameMode != null) minecraft.gameMode.stopDestroyBlock();
		player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
		if (delay > 0) return true;
		if (player.isCreative()) {
			finish(hit);
		} else {
			begin(hit);
		}
		return true;
	}

	/** Every tick while the attack key is (or was) held. Returns true if a ship block took it. */
	public static boolean continueAttack(Minecraft minecraft, boolean down) {
		if (delay > 0) delay--;
		LocalPlayer player = minecraft.player;
		if (!down || player == null || player.isUsingItem()) {
			abort();
			return false;
		}
		ShipClient.ShipHit hit = ShipClient.targetedShipBlock(minecraft);
		if (hit == null) {
			abort();
			return false; // vanilla mines world blocks as usual
		}
		if (minecraft.missTime > 0) return true;
		if (minecraft.gameMode != null) minecraft.gameMode.stopDestroyBlock();
		player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
		if (delay > 0) return true;
		if (player.isCreative()) {
			finish(hit);
			return true;
		}
		if (hit.ship().getId() != miningShip || !hit.local().equals(miningLocal)) {
			abort();
			begin(hit);
			return true;
		}
		BlockState state = hit.ship().structure() == null ? null : hit.ship().structure().get(hit.local());
		if (state == null) {
			abort();
			return true;
		}
		progress += state.getDestroyProgress(player, new ShipBlockView(hit.ship().structure()), hit.local());
		if (ticks++ % 4 == 0) {
			SoundType sound = state.getSoundType();
			Vec3 at = hit.ship().blockCenter(hit.local());
			minecraft.getSoundManager().play(new SimpleSoundInstance(sound.getHitSound(), SoundSource.BLOCKS,
					(sound.getVolume() + 1) / 8f, sound.getPitch() * 0.5f, RandomSource.create(), at.x, at.y, at.z));
		}
		if (progress >= 1) finish(hit);
		return true;
	}

	private static void begin(ShipClient.ShipHit hit) {
		miningShip = hit.ship().getId();
		miningLocal = hit.local().immutable();
		progress = 0;
		ticks = 0;
		ClientPlayNetworking.send(new ShipEditPayload(miningShip, ShipEditPayload.START, miningLocal));
	}

	private static void finish(ShipClient.ShipHit hit) {
		ClientPlayNetworking.send(new ShipEditPayload(hit.ship().getId(), ShipEditPayload.FINISH, hit.local()));
		miningShip = -1;
		miningLocal = null;
		progress = 0;
		delay = 5;
	}

	private static void abort() {
		if (miningLocal != null) ClientPlayNetworking.send(new ShipEditPayload(miningShip, ShipEditPayload.ABORT, miningLocal));
		miningShip = -1;
		miningLocal = null;
		progress = 0;
	}

	// ---------------------------------------------------------------------------------------

	/** The targeted ship block's outline, drawn in the ship's (rotated) frame. */
	private static void renderOutline(LevelRenderContext context) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gameRenderer.gameRenderState().guiRenderState.isHudHidden) return;
		ShipClient.ShipHit hit = ShipClient.targetedShipBlock(minecraft);
		if (hit == null || hit.ship().structure() == null) return;
		BlockState state = hit.ship().structure().get(hit.local());
		if (state == null) return;
		VoxelShape shape = state.getShape(new ShipBlockView(hit.ship().structure()), hit.local());
		if (shape.isEmpty()) return;
		ShipEntity ship = hit.ship();
		var camera = context.levelState().cameraRenderState;
		float partial = camera.cameraEntityPartialTicks;
		Vec3 pos = ship.getPosition(partial);
		Vec3 pivot = ship.pivot();
		PoseStack poseStack = context.poseStack();
		poseStack.pushPose();
		poseStack.translate(pos.x - camera.pos.x, pos.y - camera.pos.y, pos.z - camera.pos.z);
		poseStack.rotate(ship.orientation(partial));
		poseStack.translate(hit.local().getX() - pivot.x, hit.local().getY() - pivot.y, hit.local().getZ() - pivot.z);
		float width = minecraft.gameRenderer.gameRenderState().windowRenderState.appropriateLineWidth;
		context.submitNodeCollector().submitShapeOutline(poseStack, shape, RenderTypes.linesTranslucent(), ARGB.black(102), width, false);
		poseStack.popPose();
	}

	/** Mining progress under the crosshair. */
	private static void renderProgress(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (miningLocal == null || progress <= 0) return;
		int cx = graphics.guiWidth() / 2, y = graphics.guiHeight() / 2 + 10;
		int half = 16;
		graphics.fill(cx - half - 1, y - 1, cx + half + 1, y + 3, 0x90000000);
		graphics.fill(cx - half, y, cx - half + Math.round(2 * half * Math.min(1, progress)), y + 2, 0xFFE6F2FF);
	}
}
