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

/**
 * Draws an assembled ship by submitting each of its blocks as a moving block at its offset
 * from the ship origin, lit by the world at its current position.
 *
 * Every block is re-tessellated each frame, which is fine for Milestone 3 ship sizes. A
 * cached mesh is a Milestone 9 performance item.
 */
public class ShipRenderer extends EntityRenderer<ShipEntity, ShipRenderState> {
	/** Render-state objects per ship structure (weak: dropped when the ship is gone). */
	private final Map<ShipStructure, ShipBlockRenderState.Frame> frames = new WeakHashMap<>();
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
		ShipBlockRenderState.Frame frame = frames.computeIfAbsent(structure, ShipBlockRenderState.Frame::new);
		List<ShipBlockRenderState> list = blocks.computeIfAbsent(structure, s -> {
			List<ShipBlockRenderState> created = new ArrayList<>();
			for (ShipStructure.Entry entry : s.entries()) {
				if (entry.state().getRenderShape() == RenderShape.MODEL) {
					created.add(new ShipBlockRenderState(frame, entry.pos(), entry.state()));
				}
			}
			return created;
		});

		Vec3 origin = ship.getPosition(partialTicks);
		frame.worldBase = BlockPos.containing(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5);
		if (ship.level() instanceof ClientLevel level) {
			for (ShipBlockRenderState block : list) {
				block.blockPos = frame.worldBase.offset(block.local);
				block.lightEngine = level.getLightEngine();
				block.cardinalLighting = level.cardinalLighting();
				block.biome = level.getBiome(block.blockPos);
			}
		}
		state.blocks = list;
	}

	@Override
	public void submit(ShipRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		for (ShipBlockRenderState block : state.blocks) {
			poseStack.pushPose();
			poseStack.translate(block.local.getX(), block.local.getY(), block.local.getZ());
			collector.submitMovingBlock(poseStack, block, 0);
			poseStack.popPose();
		}
		super.submit(state, poseStack, collector, camera);
	}
}
