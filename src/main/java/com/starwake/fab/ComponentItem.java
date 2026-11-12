package com.starwake.fab;

import net.minecraft.world.item.Item;

/**
 * A fabricated sub-component (M8e-1): nozzle, combustion chamber, turbopump, heat exchanger, magnet
 * coil, control board, pressure vessel, structural frame. Made in the {@link FabricatorBlockEntity};
 * carries the main input's {@code starwake:composition} and {@code starwake:temper} (so later
 * blueprints can gate on the component's material) and its {@code starwake:quality}. Tooltips come
 * from the metal tooltip (material) plus the fabrication tooltip (quality).
 */
public class ComponentItem extends Item {
	public ComponentItem(Properties properties) {
		super(properties);
	}
}
