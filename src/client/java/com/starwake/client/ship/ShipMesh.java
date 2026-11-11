package com.starwake.client.ship;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.starwake.ship.ShipStructure;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.RenderShape;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * A ship's tessellated geometry, built once per structure (3j performance pass). Vanilla's
 * block renderer runs over every block a single time (models, tints, ambient occlusion, faces
 * culled between neighbouring ship blocks); the resulting quads are kept per render layer and
 * replayed each frame with only the lighting refreshed per block. A structure change (a door,
 * landing gear) builds a new mesh, since structures are immutable.
 */
final class ShipMesh {
	/** One quad: which block it belongs to (for light), its offset, the model quad and its per-vertex colours. */
	private record Quad(int block, float x, float y, float z, BakedQuad quad, int c0, int c1, int c2, int c3, int overlay) {}

	final List<BlockPos> blocks = new ArrayList<>();
	final Map<ChunkSectionLayer, List<Quad>> layers = new EnumMap<>(ChunkSectionLayer.class);

	private ShipMesh() {}

	/** Tessellates every block of the structure once. {@code reference} is a world cell for AO lookups. */
	static ShipMesh build(ShipStructure structure, ClientLevel level, BlockPos reference) {
		ShipMesh mesh = new ShipMesh();
		Minecraft minecraft = Minecraft.getInstance();
		ModelBlockRenderer renderer = new ModelBlockRenderer(minecraft.options.ambientOcclusion().get(), false, minecraft.getBlockColors());
		var models = minecraft.getModelManager().getBlockStateModelSet();
		boolean cutoutLeaves = minecraft.options.cutoutLeaves().get();
		for (ShipStructure.Entry entry : structure.entries()) {
			if (entry.state().getRenderShape() != RenderShape.MODEL) continue;
			int index = mesh.blocks.size();
			mesh.blocks.add(entry.pos());
			ShipBlockRenderState getter = new ShipBlockRenderState(structure, entry.pos(), entry.state());
			getter.blockPos = reference;
			getter.lightEngine = level.getLightEngine();
			getter.cardinalLighting = level.cardinalLighting();
			getter.biome = level.getBiome(reference);
			boolean opaque = ModelBlockRenderer.forceOpaque(cutoutLeaves, entry.state());
			BlockPos local = entry.pos();
			renderer.tesselateBlock((x, y, z, quad, instance) -> mesh.layers
							.computeIfAbsent(opaque ? ChunkSectionLayer.SOLID : quad.materialInfo().layer(), l -> new ArrayList<>())
							.add(new Quad(index, local.getX() + x, local.getY() + y, local.getZ() + z, quad,
									instance.getColor(0), instance.getColor(1), instance.getColor(2), instance.getColor(3), instance.overlayCoords())),
					0, 0, 0, getter, reference, entry.state(), models.get(entry.state()), entry.state().getSeed(local));
		}
		return mesh;
	}

	/** Replays one layer's quads with per-block light ({@code light[i]} for block i). */
	void emit(ChunkSectionLayer layer, PoseStack.Pose pose, VertexConsumer buffer, int[] light) {
		List<Quad> quads = layers.get(layer);
		if (quads == null) return;
		Matrix4f matrix = pose.pose();
		Vector3f p = new Vector3f(), normal = new Vector3f();
		for (Quad q : quads) {
			BakedQuad quad = q.quad();
			pose.transformNormal(quad.direction().getUnitVec3f(), normal);
			int emission = quad.materialInfo().lightEmission();
			int base = q.block() < light.length ? light[q.block()] : LightCoordsUtil.FULL_BRIGHT;
			int lit = emission > 0 ? LightCoordsUtil.withBlock(base, Math.max(LightCoordsUtil.block(base), emission)) : base;
			for (int v = 0; v < 4; v++) {
				Vector3fc pos = quad.position(v);
				matrix.transformPosition(pos.x() + q.x(), pos.y() + q.y(), pos.z() + q.z(), p);
				long uv = quad.packedUV(v);
				int color = v == 0 ? q.c0() : v == 1 ? q.c1() : v == 2 ? q.c2() : q.c3();
				buffer.addVertex(p.x, p.y, p.z, color, UVPair.unpackU(uv), UVPair.unpackV(uv), q.overlay(), lit,
						normal.x, normal.y, normal.z);
			}
		}
	}

	boolean has(ChunkSectionLayer layer) {
		List<Quad> quads = layers.get(layer);
		return quads != null && !quads.isEmpty();
	}

	int quadCount() {
		int n = 0;
		for (List<Quad> quads : layers.values()) n += quads.size();
		return n;
	}
}
