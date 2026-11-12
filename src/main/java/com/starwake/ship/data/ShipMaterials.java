package com.starwake.ship.data;

import com.starwake.fab.MaterialBlockEntity;
import com.starwake.metal.Composition;
import com.starwake.metal.MaterialProperties;
import com.starwake.metal.MetalForm;
import com.starwake.metal.Metallurgy;
import com.starwake.metal.Tempers;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * What a ship block is made of (M8e-1), and what that means for the ship: blocks that remember an
 * alloy (hull plating on a {@link MaterialBlockEntity}) weigh their JSON mass x density / mild steel's,
 * survive re-entry up to the alloy's service temperature, and soak crashes in proportion to its
 * toughness (UTS x elongation). Other blocks use {@code heat_limit} and {@code armor} from ship_stats.
 * Works on world blocks (pre-flight) and on assembled ships' stored block-entity NBT.
 */
public final class ShipMaterials {
	public record Material(Composition composition, Tempers.Temper temper, float quality) {
		public MaterialProperties properties() {
			return Tempers.properties(Metallurgy.analyze(composition), temper, MetalForm.PLATE);
		}
	}

	/** Per-block ratings used by the flying ship. */
	public record Rating(double massFactor, double heatLimit, double armor) {}

	private ShipMaterials() {}

	public static @Nullable Material of(@Nullable BlockEntity be) {
		if (!(be instanceof MaterialBlockEntity m) || m.composition() == null) return null;
		return new Material(m.composition(), m.temper(), m.quality());
	}

	/** Reads the components saved in a block entity's NBT (assembled ships). */
	public static @Nullable Material of(Optional<CompoundTag> data) {
		if (data.isEmpty()) return null;
		CompoundTag components = data.get().getCompound("components").orElse(null);
		if (components == null) return null;
		Tag comp = components.get("starwake:composition");
		if (comp == null) return null;
		Composition c = Composition.CODEC.parse(NbtOps.INSTANCE, comp).result().orElse(null);
		if (c == null || c.isEmpty()) return null;
		Tag temperTag = components.get("starwake:temper");
		Tempers.Temper t = temperTag == null ? Tempers.Temper.REFERENCE
				: Tempers.Temper.CODEC.parse(NbtOps.INSTANCE, temperTag).result().orElse(Tempers.Temper.REFERENCE);
		float q = components.getFloat("starwake:quality").orElse(1f);
		return new Material(c, t, q);
	}

	public static Rating rate(BlockState state, @Nullable Material material) {
		ShipStats.BlockStats stats = ShipStats.get(state);
		if (material == null) return new Rating(1, stats.heatLimit(), stats.armor());
		HullTuning tuning = HullTuning.get();
		MaterialProperties p = material.properties();
		double toughness = p.tensileStrength() * Math.min(40, p.elongation());
		double armor = stats.armor() * Math.max(0.1, Math.min(5, toughness / tuning.referenceToughness()));
		return new Rating(p.density() / tuning.referenceDensity(), p.serviceTemp(), armor);
	}
}
