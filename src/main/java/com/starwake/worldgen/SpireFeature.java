package com.starwake.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * A tapered rock spire (M9 alien pass): desert hoodoos, crag spires on airless worlds, sulfur chimneys on toxic ones,
 * ice penitentes on frozen ones. A cone from {@code radius} at the base to a point over {@code height}, with a lumpy
 * outline and a lean, built of {@code block} (with an optional {@code cap} of a different rock on top, like a hoodoo's
 * hard caprock). Its base is sunk a few blocks into the ground so it stands on slopes too. Configured in feature JSON
 * ({@code "type": "starwake:spire"}); radius is capped at 6 so a spire stays inside the chunks a feature may write.
 */
public record SpireFeature(BlockState block, Optional<BlockState> cap, IntProvider height, IntProvider radius, float lean)
		implements Feature {
	public static final MapCodec<SpireFeature> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			BlockState.CODEC.fieldOf("block").forGetter(SpireFeature::block),
			BlockState.CODEC.optionalFieldOf("cap").forGetter(SpireFeature::cap),
			net.minecraft.util.valueproviders.IntProviders.codec(3, 64).fieldOf("height").forGetter(SpireFeature::height),
			net.minecraft.util.valueproviders.IntProviders.codec(1, 6).fieldOf("radius").forGetter(SpireFeature::radius),
			Codec.floatRange(0f, 0.5f).optionalFieldOf("lean", 0.12f).forGetter(SpireFeature::lean)
	).apply(i, SpireFeature::new));

	public static void init() {
		Registry.register(BuiltInRegistries.FEATURE_TYPE, StarwakeMod.id("spire"), CODEC);
	}

	@Override
	public MapCodec<SpireFeature> codec() {
		return CODEC;
	}

	@Override
	public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
		BlockState ground = level.getBlockState(origin.below());
		if (ground.isAir() || !ground.getFluidState().isEmpty() || !level.getBlockState(origin).isAir()) return false;
		int h = height.sample(random);
		double r0 = radius.sample(random) + random.nextDouble() * 0.5;
		double leanX = (random.nextDouble() * 2 - 1) * lean, leanZ = (random.nextDouble() * 2 - 1) * lean;
		double phase = random.nextDouble() * Math.PI * 2;
		int capFrom = cap.isPresent() ? h - Math.max(1, h / 6) : Integer.MAX_VALUE;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = -3; y < h; y++) {
			double t = Math.max(0, y) / (double) h;
			double r = r0 * Math.pow(1 - t, 0.8) + 0.35;
			if (cap.isPresent() && y >= capFrom) r = Math.max(r, r0 * 0.6); // caprock: a wider hat on a hoodoo
			double cx = leanX * y, cz = leanZ * y;
			int reach = (int) Math.ceil(r + 1);
			for (int dx = -reach; dx <= reach; dx++) {
				for (int dz = -reach; dz <= reach; dz++) {
					double ax = dx - cx, az = dz - cz;
					double angle = Math.atan2(az, ax);
					double edge = r * (1 + 0.18 * Math.sin(angle * 3 + phase + y * 0.3));
					if (ax * ax + az * az > edge * edge) continue;
					pos.set(origin.getX() + dx, origin.getY() + y, origin.getZ() + dz);
					BlockState here = level.getBlockState(pos);
					if (y >= 0 && !here.isAir() && here.getFluidState().isEmpty()) continue; // don't carve into terrain
					level.setBlock(pos, y >= capFrom ? cap.get() : block, 2);
				}
			}
		}
		return true;
	}
}
