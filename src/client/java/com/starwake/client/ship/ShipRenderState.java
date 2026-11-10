package com.starwake.client.ship;

import java.util.List;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** Per-frame render state for a ship: the block states to draw (cached per ship). */
public final class ShipRenderState extends EntityRenderState {
	List<ShipBlockRenderState> blocks = List.of();
}
