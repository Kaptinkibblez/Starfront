package com.starwake.planet.proc;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.List;
import net.minecraft.util.RandomSource;

/**
 * A designed range a procedural value is picked from: {@code [min, max]} in JSON, or a single
 * number for a fixed value. Part of the archetype format ({@link PlanetArchetype}).
 */
public record Range(double min, double max) {
	public static final Codec<Range> CODEC = Codec.either(Codec.DOUBLE, Codec.DOUBLE.listOf().comapFlatMap(
			list -> list.size() == 2 ? DataResult.success(new Range(list.get(0), list.get(1)))
					: DataResult.error(() -> "range must be [min, max]"),
			r -> List.of(r.min, r.max))).xmap(e -> e.map(v -> new Range(v, v), r -> r), r -> r.min == r.max
					? com.mojang.datafixers.util.Either.left(r.min) : com.mojang.datafixers.util.Either.right(r));

	public double pick(RandomSource random) {
		return min + (max - min) * random.nextDouble();
	}

	public int pickInt(RandomSource random) {
		return (int) Math.round(pick(random));
	}
}
