package com.starwake.client.ship;

import com.mojang.blaze3d.vertex.PoseStack;
import com.starwake.ship.ShipEntity;
import com.starwake.ship.ShipStructure;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Draws an assembled ship: rotates by the ship's (interpolated) orientation about its pivot and
 * replays its cached {@link ShipMesh}, one draw per render layer, each block lit from the world
 * cell under its rotated centre.
 *
 * The mesh is tessellated once per structure (3j); before that, every block was re-tessellated
 * every frame through vanilla's moving-block path.
 */
public class ShipRenderer extends EntityRenderer<ShipEntity, ShipRenderState> {
	/** Cached meshes per ship structure (weak: dropped when the structure is replaced or gone). */
	private final Map<ShipStructure, ShipMesh> meshes = new WeakHashMap<>();

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
		if (structure == null || !(ship.level() instanceof ClientLevel level)) {
			state.mesh = null;
			return;
		}
		ShipMesh mesh = meshes.computeIfAbsent(structure, s -> ShipMesh.build(s, level, BlockPos.containing(ship.position())));
		state.mesh = mesh;
		state.orientation.set(ship.orientation(partialTicks));
		state.pivot = ship.pivot();

		// Light each block from the world cell under its rotated centre.
		if (state.light.length != mesh.blocks.size()) state.light = new int[mesh.blocks.size()];
		Vec3 origin = ship.getPosition(partialTicks);
		Vector3f c = new Vector3f();
		BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
		for (int i = 0; i < mesh.blocks.size(); i++) {
			BlockPos local = mesh.blocks.get(i);
			c.set((float) (local.getX() + 0.5 - state.pivot.x), (float) (local.getY() + 0.5 - state.pivot.y),
					(float) (local.getZ() + 0.5 - state.pivot.z));
			state.orientation.transform(c);
			cell.set(origin.x + c.x, origin.y + c.y, origin.z + c.z);
			state.light[i] = LightCoordsUtil.pack(level.getBrightness(LightLayer.BLOCK, cell), level.getBrightness(LightLayer.SKY, cell));
		}
	}

	@Override
	public void submit(ShipRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		ShipMesh mesh = state.mesh;
		if (mesh != null) {
			// The pose starts at the entity position (the pivot): rotate, then shift to local space.
			poseStack.pushPose();
			poseStack.rotate(state.orientation);
			poseStack.translate(-state.pivot.x, -state.pivot.y, -state.pivot.z);
			int[] light = state.light;
			for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
				if (!mesh.has(layer)) continue;
				collector.submitCustomGeometry(poseStack, renderType(layer), (pose, buffer) -> mesh.emit(layer, pose, buffer, light));
			}
			poseStack.popPose();
		}
		super.submit(state, poseStack, collector, camera);
	}

	private static RenderType renderType(ChunkSectionLayer layer) {
		return switch (layer) {
			case SOLID -> RenderTypes.solidMovingBlock();
			case CUTOUT -> RenderTypes.cutoutMovingBlock();
			case TRANSLUCENT -> RenderTypes.translucentMovingBlock();
		};
	}
}
