package com.starwake.client.logistics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.starwake.logistics.ConveyorBlock;
import com.starwake.logistics.ConveyorBlockEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Draws a belt's cargo (M9): every stack lying flat where its interpolated progress puts it, turned to the belt's
 * direction, and the filter item hovering over a filter belt.
 */
public class ConveyorRenderer implements BlockEntityRenderer<ConveyorBlockEntity, ConveyorRenderer.State> {
	public static final class State extends BlockEntityRenderState {
		final List<ItemStackRenderState> items = new ArrayList<>();
		final List<Vec3> positions = new ArrayList<>();
		final ItemStackRenderState filter = new ItemStackRenderState();
		boolean lift, hasFilter;
		float yaw;
	}

	private final ItemModelResolver resolver;

	public ConveyorRenderer(BlockEntityRendererProvider.Context context) {
		this.resolver = context.itemModelResolver();
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(ConveyorBlockEntity belt, State state, float partial, Vec3 camera, ModelFeatureRenderer.@Nullable CrumblingOverlay breaking) {
		BlockEntityRenderer.super.extractRenderState(belt, state, partial, camera, breaking);
		state.items.clear();
		state.positions.clear();
		var block = belt.getBlockState();
		state.lift = block.getBlock() instanceof ConveyorBlock b && b.kind == ConveyorBlock.Kind.LIFT;
		Direction facing = block.getValue(ConveyorBlock.FACING);
		state.yaw = -facing.toYRot();
		int seed = (int) belt.getBlockPos().asLong();
		int i = 0;
		for (ConveyorBlockEntity.Entry e : belt.items()) {
			ItemStackRenderState item = new ItemStackRenderState();
			resolver.updateForTopItem(item, e.stack, ItemDisplayContext.FIXED, belt.getLevel(), null, seed + i++);
			state.items.add(item);
			double p = Mth.lerp(partial, e.previous, e.progress);
			state.positions.add(ConveyorBlockEntity.localPosition(block, Math.min(1, p), e.exit));
		}
		state.hasFilter = !belt.filter().isEmpty();
		if (state.hasFilter) resolver.updateForTopItem(state.filter, belt.filter(), ItemDisplayContext.FIXED, belt.getLevel(), null, seed);
	}

	@Override
	public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		for (int i = 0; i < state.items.size(); i++) {
			Vec3 p = state.positions.get(i);
			pose.pushPose();
			pose.translate(p.x, p.y + 0.03, p.z);
			pose.rotateDegrees(Axis.YP, state.yaw);
			if (!state.lift) pose.rotateDegrees(Axis.XP, 90);
			pose.scale(0.4f, 0.4f, 0.4f);
			state.items.get(i).submit(pose, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
			pose.popPose();
		}
		if (state.hasFilter) {
			pose.pushPose();
			pose.translate(0.5, 0.55, 0.5);
			pose.rotateDegrees(Axis.YP, state.yaw);
			pose.scale(0.3f, 0.3f, 0.3f);
			state.filter.submit(pose, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
			pose.popPose();
		}
	}
}
