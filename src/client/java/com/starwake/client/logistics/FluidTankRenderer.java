package com.starwake.client.logistics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.starwake.logistics.FluidTankBlockEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Draws a Fluid Tank's contents (M9): a translucent box inside the glass up to the fill level, coloured by what it
 * holds (synced by the tank), shaded by the light at the tank and a little brighter on top.
 */
public class FluidTankRenderer implements BlockEntityRenderer<FluidTankBlockEntity, FluidTankRenderer.State> {
	public static final class State extends BlockEntityRenderState {
		float fill;
		int color;
	}

	public FluidTankRenderer(BlockEntityRendererProvider.Context context) {}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(FluidTankBlockEntity tank, State state, float partial, Vec3 camera, ModelFeatureRenderer.@Nullable CrumblingOverlay breaking) {
		BlockEntityRenderer.super.extractRenderState(tank, state, partial, camera, breaking);
		state.fill = tank.clientFill();
		state.color = tank.clientColor();
	}

	@Override
	public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		if (state.fill <= 0.002f) return;
		float light = Math.max(LightCoordsUtil.block(state.lightCoords), LightCoordsUtil.sky(state.lightCoords)) / 15f;
		float shade = 0.35f + 0.65f * light;
		float lo = 1.5f / 16, hi = 14.5f / 16;
		float top = lo + (hi - lo) * Math.min(1, state.fill);
		int side = argb(state.color, shade * 0.85f, 0xB0);
		int cap = argb(state.color, Math.min(1, shade * 1.1f), 0xC8);
		collector.submitCustomGeometry(pose, RenderTypes.debugQuads(), (p, b) -> {
			quad(p, b, lo, top, lo, hi, top, lo, hi, top, hi, lo, top, hi, cap); // top (seen from above)
			quad(p, b, lo, lo, hi, hi, lo, hi, hi, lo, lo, lo, lo, lo, side); // bottom
			quad(p, b, lo, lo, lo, hi, lo, lo, hi, top, lo, lo, top, lo, side); // north
			quad(p, b, hi, lo, hi, lo, lo, hi, lo, top, hi, hi, top, hi, side); // south
			quad(p, b, lo, lo, hi, lo, lo, lo, lo, top, lo, lo, top, hi, side); // west
			quad(p, b, hi, lo, lo, hi, lo, hi, hi, top, hi, hi, top, lo, side); // east
		});
	}

	private static int argb(int rgb, float shade, int alpha) {
		int r = (int) ((rgb >> 16 & 255) * shade), g = (int) ((rgb >> 8 & 255) * shade), bl = (int) ((rgb & 255) * shade);
		return alpha << 24 | r << 16 | g << 8 | bl;
	}

	private static void quad(PoseStack.Pose p, VertexConsumer b, float x0, float y0, float z0, float x1, float y1, float z1,
			float x2, float y2, float z2, float x3, float y3, float z3, int color) {
		b.addVertex(p, x0, y0, z0).setColor(color);
		b.addVertex(p, x1, y1, z1).setColor(color);
		b.addVertex(p, x2, y2, z2).setColor(color);
		b.addVertex(p, x3, y3, z3).setColor(color);
	}
}
