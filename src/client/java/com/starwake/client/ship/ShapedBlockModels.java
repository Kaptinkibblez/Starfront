package com.starwake.client.ship;

import com.starwake.StarwakeMod;
import com.starwake.registry.ModBlocks;
import com.starwake.ship.block.GreebleBlock;
import com.starwake.ship.block.HullPanelBlock;
import com.starwake.ship.block.ShipLightBlock;
import java.util.HashMap;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import net.minecraft.world.level.block.Block;
import com.starwake.ship.panel.PanelGeometry;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Renders blocks built from {@link PanelGeometry} solids (M9 pass 3): Hull Panels, greebles and ship lights. A Fabric
 * block-state resolver gives every state a model baked straight from the solids' polygons (true slopes and curves;
 * block-model JSON only has axis-aligned boxes). Each face's texture comes from its solid's texture key; lenses of lit
 * lights are emissive. Faces on the cube's boundary are cull faces, so these blocks hide against neighbours like
 * ordinary ones. Ships use the same baked models through vanilla's block renderer.
 */
public final class ShapedBlockModels {
	private ShapedBlockModels() {}

	/** How one block maps its states to geometry, textures and emission. */
	private record Spec(Function<BlockState, PanelGeometry.Shape> shape, ToIntFunction<BlockState> orient,
			BiFunction<BlockState, String, Identifier> texture, BiPredicate<BlockState, String> emissive) {}

	public static void init() {
		Identifier hull = StarwakeMod.id("block/hull_plating");
		register(ModBlocks.HULL_PANEL, new Spec(s -> s.getValue(HullPanelBlock.SHAPE), s -> s.getValue(HullPanelBlock.ORIENT),
				(s, key) -> hull, (s, key) -> false));
		register(ModBlocks.GREEBLE, new Spec(s -> s.getValue(GreebleBlock.SHAPE), s -> s.getValue(GreebleBlock.ORIENT),
				(s, key) -> key.equals("hull") ? hull : StarwakeMod.id("block/greeble_" + key), (s, key) -> false));
		register(ModBlocks.SHIP_LIGHT, new Spec(s -> s.getValue(ShipLightBlock.TYPE), s -> s.getValue(ShipLightBlock.ORIENT),
				(s, key) -> !key.equals("lens") ? StarwakeMod.id("block/greeble_" + key)
						: StarwakeMod.id("block/ship_light_" + (ShipLightBlock.lit(s) ? s.getValue(ShipLightBlock.COLOR).getSerializedName() : "off")),
				(s, key) -> key.equals("lens") && ShipLightBlock.lit(s)));
	}

	private static void register(Block block, Spec spec) {
		ModelLoadingPlugin.register(context -> context.registerBlockStateResolver(block, resolver -> {
			Root root = new Root(spec);
			for (BlockState state : resolver.block().getStateDefinition().getPossibleStates()) resolver.setModel(state, root);
		}));
	}

	private record Root(Spec spec) implements BlockStateModel.UnbakedRoot {
		@Override
		public void resolveDependencies(ResolvableModel.Resolver resolver) {
		}

		@Override
		public BlockStateModel bake(BlockState state, ModelBaker baker) {
			return new Baked(bakePart(state, spec, baker));
		}

		@Override
		public Object visualEqualityGroup(BlockState state) {
			return state;
		}
	}

	private static Part bakePart(BlockState state, Spec spec, ModelBaker baker) {
		Map<Direction, List<BakedQuad>> culled = new EnumMap<>(Direction.class);
		for (Direction d : Direction.values()) culled.put(d, new ArrayList<>());
		List<BakedQuad> free = new ArrayList<>();
		int orient = spec.orient().applyAsInt(state);
		Map<String, Material.Baked> materials = new HashMap<>();
		Map<String, BakedQuad.MaterialInfo> infos = new HashMap<>();
		Material.Baked particle = null;
		for (PanelGeometry.Face f : PanelGeometry.faces(spec.shape().apply(state))) {
			String key = f.tex();
			Material.Baked material = materials.computeIfAbsent(key,
					k -> baker.materials().get(new Material(spec.texture().apply(state, k)), () -> "starwake:shaped/" + k));
			if (particle == null) particle = material;
			BakedQuad.MaterialInfo info = infos.computeIfAbsent(key, k -> baker.interner().materialInfo(BakedQuad.MaterialInfo.of(material,
					material.sprite().transparency(), -1, null, spec.emissive().test(state, k) ? 15 : 0)));
			double[][] face = f.v();
			var sprite = material.sprite();
			// UVs project the model-space face onto its dominant axis plane (the texture follows the shape).
			double[] n = PanelGeometry.normal(face);
			int axis = Math.abs(n[0]) >= Math.abs(n[1]) && Math.abs(n[0]) >= Math.abs(n[2]) ? 0 : Math.abs(n[1]) >= Math.abs(n[2]) ? 1 : 2;
			int count = face.length;
			Vector3f[] pos = new Vector3f[count];
			long[] uv = new long[count];
			for (int i = 0; i < count; i++) {
				double[] p = face[i];
				pos[i] = PanelGeometry.transform(orient, p[0], p[1], p[2]);
				double u = axis == 0 ? p[2] : p[0], v = axis == 1 ? p[2] : 1 - p[1];
				uv[i] = UVPair.pack(sprite.getU((float) clamp(u)), sprite.getV((float) clamp(v)));
			}
			// Faces with more than four corners become a fan (each piece a quad, triangles repeat a corner).
			for (int k = 1; k + 1 < count; k += 2) {
				int a = 0, b = k, c = k + 1, d = Math.min(k + 2, count - 1);
				if (count == 4) d = 3;
				BakedQuad quad = quad(pos[a], pos[b], pos[c], pos[d], uv[a], uv[b], uv[c], uv[d], info);
				Direction cull = boundary(pos[a], pos[b], pos[c], pos[d], quad.direction());
				(cull == null ? free : culled.get(cull)).add(quad);
				if (count == 4) break;
			}
		}
		if (particle == null) particle = baker.materials().get(new Material(StarwakeMod.id("block/hull_plating")), () -> "starwake:shaped");
		return new Part(culled, free, particle);
	}

	private static double clamp(double v) {
		return Math.max(0, Math.min(1, v));
	}

	private static BakedQuad quad(Vector3f a, Vector3f b, Vector3f c, Vector3f d, long ua, long ub, long uc, long ud, BakedQuad.MaterialInfo info) {
		Vector3f n = new Vector3f(c).sub(a).cross(new Vector3f(d).sub(b));
		if (n.lengthSquared() < 1e-12f) n = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
		Direction dir = Direction.getApproximateNearest(n.x, n.y, n.z);
		return new BakedQuad(a, b, c, d, ua, ub, uc, ud, dir, info);
	}

	/** The cube face a quad lies on (all corners on that boundary plane, facing out), or null. */
	private static @Nullable Direction boundary(Vector3f a, Vector3f b, Vector3f c, Vector3f d, Direction dir) {
		float target = dir.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
		for (Vector3f p : new Vector3f[] {a, b, c, d}) {
			float v = (float) dir.getAxis().choose(p.x, p.y, p.z);
			if (Math.abs(v - target) > 1e-4f) return null;
		}
		return dir;
	}

	private record Part(Map<Direction, List<BakedQuad>> culled, List<BakedQuad> free, Material.Baked material) implements BlockStateModelPart {
		@Override
		public List<BakedQuad> getQuads(@Nullable Direction direction) {
			return direction == null ? free : culled.get(direction);
		}

		@Override
		public boolean useAmbientOcclusion() {
			return true;
		}

		@Override
		public Material.Baked particleMaterial() {
			return material;
		}

		@Override
		public int materialFlags() {
			return 0;
		}
	}

	private record Baked(Part part) implements BlockStateModel {
		@Override
		public void collectParts(RandomSource random, List<BlockStateModelPart> output) {
			output.add(part);
		}

		@Override
		public Material.Baked particleMaterial() {
			return part.material();
		}

		@Override
		public int materialFlags() {
			return 0;
		}
	}
}
