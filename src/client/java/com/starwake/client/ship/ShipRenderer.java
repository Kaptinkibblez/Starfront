package com.starwake.client.ship;

import com.mojang.blaze3d.vertex.PoseStack;
import com.starwake.ship.ShipEntity;
import com.starwake.ship.ShipStructure;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Draws an assembled ship: rotates by the ship's (interpolated) orientation about its pivot,
 * then submits each block as a moving block at its local offset, lit from the world cell
 * under its rotated centre.
 *
 * Every block is re-tessellated each frame, which is fine for Milestone 3 ship sizes. A
 * cached mesh is a Milestone 9 performance item.
 */
public class ShipRenderer extends EntityRenderer<ShipEntity, ShipRenderState> {
	/** Render-state objects per ship structure (weak: dropped when the ship is gone). */
	private final Map<ShipStructure, List<ShipBlockRenderState>> blocks = new WeakHashMap<>();

	public ShipRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0;
	}

	@Override
	public ShipRenderState createRenderState() {
		return new ShipRenderState();
	}

	@Override
	public void extractRenderState(ShipEntity ship, ShipRenderState state, float partialTicks) {
		super.extractRenderState(ship, state, partialTicks);
		ShipStructure structure = ship.structure();
		if (structure == null) {
			state.blocks = List.of();
			return;
		}
		List<ShipBlockRenderState> list = blocks.computeIfAbsent(structure, s -> {
			List<ShipBlockRenderState> created = new ArrayList<>();
			for (ShipStructure.Entry entry : s.entries()) {
				if (entry.state().getRenderShape() == RenderShape.MODEL) {
					created.add(new ShipBlockRenderState(s, entry.pos(), entry.state()));
				}
			}
			return created;
		});

		state.orientation.set(ship.orientation(partialTicks));
		state.pivot = ship.pivot();
		if (ship.level() instanceof ClientLevel level) {
			// Light each block from the world cell under its rotated centre.
			Vec3 origin = ship.getPosition(partialTicks);
			for (ShipBlockRenderState block : list) {
				Vector3f c = new Vector3f((float) (block.local.getX() + 0.5 - state.pivot.x),
						(float) (block.local.getY() + 0.5 - state.pivot.y), (float) (block.local.getZ() + 0.5 - state.pivot.z));
				state.orientation.transform(c);
				block.blockPos = BlockPos.containing(origin.x + c.x, origin.y + c.y, origin.z + c.z);
				block.lightEngine = level.getLightEngine();
				block.cardinalLighting = level.cardinalLighting();
				block.biome = level.getBiome(block.blockPos);
			}
		}
		state.blocks = list;
	}

	@Override
	public void submit(ShipRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		// The pose starts at the entity position (the pivot): rotate, then shift to local space.
		poseStack.pushPose();
		poseStack.rotate(state.orientation);
		poseStack.translate(-state.pivot.x, -state.pivot.y, -state.pivot.z);
		for (ShipBlockRenderState block : state.blocks) {
			poseStack.pushPose();
			poseStack.translate(block.local.getX(), block.local.getY(), block.local.getZ());
			collector.submitMovingBlock(poseStack, block, 0);
			poseStack.popPose();
		}
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}
}
