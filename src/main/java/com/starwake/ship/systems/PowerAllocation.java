package com.starwake.ship.systems;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Mth;

/**
 * How the pilot splits available power between Engines, Shields and Quantum (Star Citizen's
 * power triangle). Fractions always sum to 1.
 */
public record PowerAllocation(float engines, float shields, float quantum) {
	public static final PowerAllocation DEFAULT = new PowerAllocation(0.6f, 0.2f, 0.2f);

	public static final Codec<PowerAllocation> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.FLOAT.fieldOf("engines").forGetter(PowerAllocation::engines),
			Codec.FLOAT.fieldOf("shields").forGetter(PowerAllocation::shields),
			Codec.FLOAT.fieldOf("quantum").forGetter(PowerAllocation::quantum)
	).apply(i, PowerAllocation::of));

	/** Builds a normalized allocation from any non-negative weights (all zero = DEFAULT). */
	public static PowerAllocation of(float engines, float shields, float quantum) {
		engines = Math.max(0, engines);
		shields = Math.max(0, shields);
		quantum = Math.max(0, quantum);
		float sum = engines + shields + quantum;
		if (sum <= 1.0e-6f) return DEFAULT;
		return new PowerAllocation(engines / sum, shields / sum, quantum / sum);
	}

	/** Percentages for display and the network (rounded, summing to ~100). */
	public int[] percents() {
		return new int[] {Math.round(engines * 100), Math.round(shields * 100), Math.round(quantum * 100)};
	}

	public static PowerAllocation fromPercents(int engines, int shields, int quantum) {
		return of(Mth.clamp(engines, 0, 100), Mth.clamp(shields, 0, 100), Mth.clamp(quantum, 0, 100));
	}
}
