package com.starwake.ship.block;

import com.starwake.ship.panel.PanelGeometry;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * A block drawn and collided from {@link PanelGeometry} solids (M9 pass 3/4): hull pieces, greebles, lights, joints. The
 * client bakes {@link #shapeOf} in orientation {@link #orientOf} (`client/ship/ShapedBlockModels`); ships use its fill
 * for mass and {@link #seals} for pressurised cabins.
 */
public interface ShapedBlock {
	/** The 24 orientations (up x four fronts), see {@link PanelGeometry#orient}. */
	IntegerProperty ORIENT = IntegerProperty.create("orient", 0, PanelGeometry.ORIENTATIONS - 1);

	PanelGeometry.Shape shapeOf(BlockState state);

	int orientOf(BlockState state);

	/** Whether it closes its cell for a pressurised cabin. */
	default boolean seals(BlockState state) {
		return true;
	}

	/** Whether faces with this texture key glow (full-bright, light emission 15). */
	default boolean emissive(BlockState state, String key) {
		return false;
	}

	/** Texture of a solid's texture key ("hull" = the paintable hull panel, M10; others = block/greeble_<key>). */
	default String texture(BlockState state, String key) {
		return key.equals("hull") ? "block/hull_panel" : "block/greeble_" + key;
	}
}
