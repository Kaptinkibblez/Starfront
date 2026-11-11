package com.starwake.client.trade;

import com.starwake.StarwakeMod;
import com.starwake.trade.HubNpcEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;

/**
 * Hub guards and crew (M7): the player-shaped humanoid model with a faction/role/variant skin
 * ({@code textures/entity/npc/<faction>_<role>_<n>.png}, from {@code tools/npc_skins.py}).
 */
public class HubNpcRenderer extends HumanoidMobRenderer<HubNpcEntity, HubNpcRenderer.State, HumanoidModel<HubNpcRenderer.State>> {
	/** Render state with the skin to use. */
	public static class State extends HumanoidRenderState {
		Identifier texture = StarwakeMod.id("textures/entity/npc/solace_accord_crew_0.png");
	}

	public HubNpcRenderer(EntityRendererProvider.Context context) {
		super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5f);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(HubNpcEntity entity, State state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		String faction = entity.faction().isEmpty() ? "solace_accord" : entity.faction();
		state.texture = StarwakeMod.id("textures/entity/npc/" + faction + "_" + entity.role() + "_" + entity.variant() + ".png");
	}

	@Override
	public Identifier getTextureLocation(State state) {
		return state.texture;
	}
}
