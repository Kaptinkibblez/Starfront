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

	/** Hull paint (M10), or -1 for bare metal. */
	public int paint() {
		Integer p = components().get(ModComponents.PAINT);
		return p == null ? -1 : p;
	}

	/** Sets or clears the paint and tells clients (and a ship whose plot this is). */
	public void setPaint(int rgb) {
		var next = net.minecraft.core.component.DataComponentMap.builder().addAll(components());
		if (rgb < 0) next.set(ModComponents.PAINT, null);
		else next.set(ModComponents.PAINT, rgb);
		setComponents(next.build());
		setChanged();
		if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
	}

	// M10: clients see the components (paint), and repaint the block when they change.
	private int seenPaint = -2;

	@Override
	public net.minecraft.nbt.CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}

	@Override
	public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
		return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	protected void loadAdditional(net.minecraft.world.level.storage.ValueInput input) {
		super.loadAdditional(input);
		int paint = input.read("components", net.minecraft.core.component.DataComponentMap.CODEC)
				.map(c -> c.get(ModComponents.PAINT)).map(Integer::intValue).orElse(-1);
		if (level != null && level.isClientSide() && seenPaint != -2 && paint != seenPaint) {
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 8); // re-mesh with the new tint
		}
		seenPaint = paint;
	}

	public float quality() {
		Float q = components().get(ModComponents.QUALITY);
		return q == null ? 1f : q;
	}
}
