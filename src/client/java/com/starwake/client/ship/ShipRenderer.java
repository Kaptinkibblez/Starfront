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
 * replays its cached {@link ShipMesh} sections (16-block cubes, M9 pass 3: an edit re-tessellates only the sections it
 * touches; sections behind the camera are skipped), one draw per render layer, each block lit from the world cell under
 * its rotated centre (big ships refresh light round-robin).
 *
 * The mesh is tessellated once per structure (3j); before that, every block was re-tessellated
 * every frame through vanilla's moving-block path.
 */
public class ShipRenderer extends EntityRenderer<ShipEntity, ShipRenderState> {
	/** M9 pass 3: per ship, its 16-block section meshes and their light (weak: dropped with the entity). */
	private final Map<ShipEntity, Sections> sections = new WeakHashMap<>();
	/** Blocks whose light is re-sampled per frame per ship; bigger ships refresh round-robin over several frames. */
	private static final int LIGHT_BUDGET = 16_384;

	private static final class Section {
		final ShipMesh mesh;
		final int[] light;
		final Vec3 centre;
		/** Articulation group (-1 = the rigid hull). */
		final int group;

		Section(ShipMesh.Key k, ShipMesh mesh) {
			long key = k.section();
			this.group = k.group();
			this.mesh = mesh;
			this.light = new int[mesh.blocks.size()];
			java.util.Arrays.fill(light, -1);
			this.centre = new Vec3((BlockPos.getX(key) << 4) + 8, (BlockPos.getY(key) << 4) + 8, (BlockPos.getZ(key) << 4) + 8);
		}
	}

	private static final class Sections {
		ShipStructure structure;
		final Map<ShipMesh.Key, Section> map = new java.util.HashMap<>();
		final java.util.List<Section> order = new java.util.ArrayList<>();
		int cursor;
	}

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
		state.meshes.clear();
		state.lights.clear();
		state.groups.clear();
		ShipStructure structure = ship.structure();
		if (structure == null || !(ship.level() instanceof ClientLevel level)) return;
		Sections cache = sections.computeIfAbsent(ship, s -> new Sections());
		if (cache.structure != structure) rebuild(ship, structure, level, cache);
		state.orientation.set(ship.orientation(partialTicks));
		state.pivot = ship.pivot();

		Vec3 origin = ship.getPosition(partialTicks);
		Vector3f c = new Vector3f();
		BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
		// Light: new sections first (never lit), then a round-robin share of the rest.
		int budget = LIGHT_BUDGET;
		int n = cache.order.size();
		for (int k = 0; k < n && budget > 0; k++) {
			Section section = cache.order.get((cache.cursor + k) % n);
			light(section, state, origin, level, c, cell);
			budget -= section.light.length;
			if (k == n - 1 || budget <= 0) cache.cursor = (cache.cursor + k + 1) % n;
		}
		// Sections well behind the camera aren't drawn (a section's half-diagonal is ~14 blocks).
		var camera = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera();
		Vec3 eye = camera.position();
		Vector3f forward = new Vector3f(camera.forwardVector());
		for (Section section : cache.order) {
			if (section.light.length > 0 && section.light[0] == -1) light(section, state, origin, level, c, cell);
			c.set((float) (section.centre.x - state.pivot.x), (float) (section.centre.y - state.pivot.y), (float) (section.centre.z - state.pivot.z));
			state.orientation.transform(c);
			float rx = (float) (origin.x + c.x - eye.x), ry = (float) (origin.y + c.y - eye.y), rz = (float) (origin.z + c.z - eye.z);
			if (section.group < 0 && rx * forward.x + ry * forward.y + rz * forward.z < -16) continue;
			state.meshes.add(section.mesh);
			state.lights.add(section.light);
			state.groups.add(section.group);
		}
		// Articulated groups (M9 pass 3): each turns about its joint's pivot.
		var joints = ship.articulations();
		state.jointPivots.clear();
		state.jointRotations.clear();
		for (int i = 0; i < joints.count(); i++) {
			var q = joints.rotation(i, ship.level().getGameTime(), partialTicks);
			state.jointPivots.add(joints.joint(i).pivot());
			state.jointRotations.add(new org.joml.Quaternionf((float) q.x, (float) q.y, (float) q.z, (float) q.w));
		}
		extractBlockEntities(ship, level, state, partialTicks, origin, c, cell);
	}

	/** Lights each block of a section from the world cell under its rotated centre. */
	private static void light(Section section, ShipRenderState state, Vec3 origin, ClientLevel level, Vector3f c, BlockPos.MutableBlockPos cell) {
		var blocks = section.mesh.blocks;
		for (int i = 0; i < blocks.size(); i++) {
			BlockPos local = blocks.get(i);
			c.set((float) (local.getX() + 0.5 - state.pivot.x), (float) (local.getY() + 0.5 - state.pivot.y),
					(float) (local.getZ() + 0.5 - state.pivot.z));
			state.orientation.transform(c);
			cell.set(origin.x + c.x, origin.y + c.y, origin.z + c.z);
			section.light[i] = LightCoordsUtil.pack(level.getBrightness(LightLayer.BLOCK, cell), level.getBrightness(LightLayer.SKY, cell));
		}
	}

	/**
	 * Re-tessellates what changed: everything for a new snapshot, otherwise the sections of each changed cell and of
	 * its six neighbours (their faces against it may have been culled or uncovered).
	 */
	private static void rebuild(ShipEntity ship, ShipStructure structure, ClientLevel level, Sections cache) {
		long t0 = System.nanoTime();
		var changed = ship.takeMeshChanges();
		it.unimi.dsi.fastutil.longs.LongSet only = null;
		if (changed != null && cache.structure != null) {
			only = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
			for (BlockPos pos : changed) {
				only.add(ShipMesh.sectionOf(pos));
				for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) only.add(ShipMesh.sectionOf(pos.relative(d)));
			}
		}
		var built = ShipMesh.buildSections(structure, level, BlockPos.containing(ship.position()), only, ship.articulations());
		if (only == null) cache.map.clear();
		else {
			final var gone = only;
			cache.map.keySet().removeIf(key -> gone.contains(key.section()));
		}
		for (var e : built.entrySet()) cache.map.put(e.getKey(), new Section(e.getKey(), e.getValue()));
		cache.order.clear();
		cache.order.addAll(cache.map.values());
		cache.cursor = 0;
		cache.structure = structure;
		long ms = (System.nanoTime() - t0) / 1_000_000;
		if (structure.entries().size() >= 10_000) {
			com.starwake.StarwakeMod.LOGGER.info("Big ship mesh: {} blocks, {} of {} sections rebuilt in {} ms", structure.entries().size(),
					built.size(), cache.map.size(), ms);
		}
	}

	/**
	 * Block entities aboard (M9 pass 2): each ghost's renderer extracts its state with the camera moved into the
	 * ship's frame (renderers judge distance and facing against it), then gets the light of its world cell.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void extractBlockEntities(ShipEntity ship, ClientLevel level, ShipRenderState state, float partialTicks, Vec3 origin,
			Vector3f c, BlockPos.MutableBlockPos cell) {
		state.blockEntityPos.clear();
		state.blockEntities.clear();
		var ghosts = ShipBlockEntities.of(ship, level);
		if (ghosts.isEmpty()) return;
		var dispatcher = net.minecraft.client.Minecraft.getInstance().getBlockEntityRenderDispatcher();
		Vec3 camera = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera().position();
		Vector3f rel = new Vector3f((float) (camera.x - origin.x), (float) (camera.y - origin.y), (float) (camera.z - origin.z));
		new org.joml.Quaternionf(state.orientation).conjugate().transform(rel);
		Vec3 localCamera = new Vec3(rel.x + state.pivot.x, rel.y + state.pivot.y, rel.z + state.pivot.z);
		for (var ghost : ghosts.entrySet()) {
			BlockPos local = ghost.getKey();
			if (Vec3.atCenterOf(local).distanceToSqr(localCamera) > 64 * 64) continue;
			net.minecraft.client.renderer.blockentity.BlockEntityRenderer renderer = dispatcher.getRenderer(ghost.getValue());
			if (renderer == null) continue;
			var beState = (net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState) renderer.createRenderState();
			try {
				renderer.extractRenderState(ghost.getValue(), beState, partialTicks, localCamera, null);
			} catch (RuntimeException e) {
				continue; // a renderer that needs real world neighbours: skip it rather than crash the frame
			}
			c.set((float) (local.getX() + 0.5 - state.pivot.x), (float) (local.getY() + 0.5 - state.pivot.y), (float) (local.getZ() + 0.5 - state.pivot.z));
			state.orientation.transform(c);
			cell.set(origin.x + c.x, origin.y + c.y, origin.z + c.z);
			beState.lightCoords = LightCoordsUtil.pack(level.getBrightness(LightLayer.BLOCK, cell), level.getBrightness(LightLayer.SKY, cell));
			state.blockEntityPos.add(local);
			state.blockEntities.add(beState);
		}
	}

	@Override
	public void submit(ShipRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		if (!state.meshes.isEmpty() || !state.blockEntities.isEmpty()) {
			// The pose starts at the entity position (the pivot): rotate, then shift to local space.
			poseStack.pushPose();
			poseStack.rotate(state.orientation);
			poseStack.translate(-state.pivot.x, -state.pivot.y, -state.pivot.z);
			var meshes = java.util.List.copyOf(state.meshes);
			var lights = java.util.List.copyOf(state.lights);
			var groups = java.util.List.copyOf(state.groups);
			for (int g = -1; g < state.jointRotations.size(); g++) {
				final int group = g;
				poseStack.pushPose();
				if (g >= 0) {
					// Turn the sub-assembly about its joint's pivot (ship-local coordinates).
					var p = state.jointPivots.get(g);
					poseStack.translate(p.x, p.y, p.z);
					poseStack.rotate(state.jointRotations.get(g));
					poseStack.translate(-p.x, -p.y, -p.z);
				}
				for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
					boolean any = false;
					for (int i = 0; i < meshes.size(); i++) any |= groups.get(i) == group && meshes.get(i).has(layer);
					if (!any) continue;
					collector.submitCustomGeometry(poseStack, renderType(layer), (pose, buffer) -> {
						for (int i = 0; i < meshes.size(); i++) if (groups.get(i) == group) meshes.get(i).emit(layer, pose, buffer, lights.get(i));
					});
				}
				poseStack.popPose();
			}
			var dispatcher = net.minecraft.client.Minecraft.getInstance().getBlockEntityRenderDispatcher();
			for (int i = 0; i < state.blockEntities.size(); i++) {
				BlockPos local = state.blockEntityPos.get(i);
				poseStack.pushPose();
				poseStack.translate(local.getX(), local.getY(), local.getZ());
				dispatcher.submit(state.blockEntities.get(i), poseStack, collector, camera);
				poseStack.popPose();
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
