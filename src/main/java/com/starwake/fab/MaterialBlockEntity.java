package com.starwake.fab;

import com.starwake.metal.Composition;
import com.starwake.metal.Tempers;
import com.starwake.registry.ModComponents;
import com.starwake.registry.ModFabrication;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * A placed block that remembers what it is made of (M8e-1): hull plating (and, in 8e-2, Mk parts). It
 * holds no state of its own; the item's {@code starwake:composition}, {@code starwake:temper} and
 * {@code starwake:quality} components stay on the block entity (vanilla keeps non-implicit item
 * components on placed block entities), are saved with it, ride along in assembled ships' block-entity
 * NBT, and are copied back onto the drop by the loot table.
 */
public class MaterialBlockEntity extends BlockEntity {
	public MaterialBlockEntity(BlockPos pos, BlockState state) {
		super(ModFabrication.MATERIAL_BE, pos, state);
	}

	public @Nullable Composition composition() {
		return components().get(ModComponents.COMPOSITION);
	}

	public Tempers.Temper temper() {
		Tempers.Temper t = components().get(ModComponents.TEMPER);
		return t == null ? Tempers.Temper.REFERENCE : t;
	}

	public float quality() {
		Float q = components().get(ModComponents.QUALITY);
		return q == null ? 1f : q;
	}
}
