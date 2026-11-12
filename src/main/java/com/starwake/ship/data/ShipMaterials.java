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

	/** Per-block ratings used by the flying ship (stats include the part's quality bonus). */
	public record Rating(double massFactor, double heatLimit, double armor, ShipStats.BlockStats stats) {}

	/** Performance gain and mass/draw saving per 1.0 of quality above the design (quality is capped at 1.5). */
	public static final double PERFORMANCE_PER_QUALITY = 0.4, SAVING_PER_QUALITY = 0.3;

	/**
	 * A part's stats with its quality bonus (M8e-2): output-type stats (thrust, power, cooling, shields,
	 * jump range, fuel capacity) grow by 0.4 x (q - 1), mass, fuel burn, power draw, heat and spool times
	 * shrink by 0.3 x (q - 1): a part fabricated from materials 50% over its gates is 20% better and 15% lighter.
	 */
	public static ShipStats.BlockStats withQuality(ShipStats.BlockStats s, double quality) {
		double b = Math.max(0, Math.min(0.5, quality - 1));
		if (b <= 0) return s;
		double up = 1 + PERFORMANCE_PER_QUALITY * b, down = 1 - SAVING_PER_QUALITY * b;
		return new ShipStats.BlockStats(s.mass() * down, s.thrust() * up, s.fuelBurn() * down, (int) Math.round(s.fuelCapacity() * up),
				s.powerOutput() * up, s.heat() * down, s.cooling() * up, s.powerDraw() * down, s.shieldCapacity() * up, s.shieldRegen() * up,
				s.quantumSpool() * down, s.jumpRange() * up, s.jumpSpool() * down, s.heatLimit(), s.armor());
	}

	/** Quality stored on any block entity (parts, fuel tanks), 1 when absent. */
	public static double quality(@Nullable BlockEntity be) {
		Float q = be == null ? null : be.components().get(com.starwake.registry.ModComponents.QUALITY);
		return q == null ? 1 : q;
	}

	/** Quality read from saved block-entity NBT (assembled ships). */
	public static double quality(Optional<CompoundTag> data) {
		return data.flatMap(t -> t.getCompound("components")).flatMap(c -> c.getFloat("starwake:quality")).map(Float::doubleValue).orElse(1.0);
	}

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
		return rate(state, material, material == null ? 1 : material.quality());
	}

	/**
	 * Hull plating's alloy sets its mass, heat limit and armour; a part's quality sets its stats (and so its mass);
	 * everything else uses ship_stats.
	 */
	public static Rating rate(BlockState state, @Nullable Material material, double quality) {
		ShipStats.BlockStats base = ShipStats.get(state);
		if (!(state.getBlock() instanceof com.starwake.ship.block.HullPlatingBlock)) {
			ShipStats.BlockStats stats = withQuality(base, quality);
			return new Rating(base.mass() <= 0 ? 1 : stats.mass() / base.mass(), base.heatLimit(), base.armor(), stats);
		}
		ShipStats.BlockStats stats = base;
		if (material == null) return new Rating(1, stats.heatLimit(), stats.armor(), stats);
		HullTuning tuning = HullTuning.get();
		MaterialProperties p = material.properties();
		double toughness = p.tensileStrength() * Math.min(40, p.elongation());
		double armor = stats.armor() * Math.max(0.1, Math.min(5, toughness / tuning.referenceToughness()));
		return new Rating(p.density() / tuning.referenceDensity(), p.serviceTemp(), armor, stats);
	}
}
