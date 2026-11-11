package com.starwake.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;

/**
 * An impact crater (M5), for airless worlds where nothing erodes them: a parabolic bowl
 * ({@code depth} x radius deep) with a raised rim of ejecta that falls off outward, lined with
 * whatever the surface was made of. Configured in feature JSON ({@code "type": "starwake:crater"}).
 * Radius is capped at 14 so a crater stays within the chunks a feature may write to.
 *
 * @param radius bowl radius in blocks
 * @param depth  bowl depth as a fraction of the radius (simple lunar craters are ~0.2)
 * @param rim    rim height as a fraction of the radius
 */
public record CraterFeature(IntProvider radius, float depth, float rim) implements Feature {
	public static final MapCodec<CraterFeature> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			net.minecraft.util.valueproviders.IntProviders.codec(2, 14).fieldOf("radius").forGetter(CraterFeature::radius),
			Codec.floatRange(0.05f, 0.6f).optionalFieldOf("depth", 0.22f).forGetter(CraterFeature::depth),
			Codec.floatRange(0f, 0.3f).optionalFieldOf("rim", 0.08f).forGetter(CraterFeature::rim)
	).apply(i, CraterFeature::new));

	public static void init() {
		Registry.register(BuiltInRegistries.FEATURE_TYPE, StarwakeMod.id("crater"), CODEC);
	}

	@Override
	public MapCodec<CraterFeature> codec() {
		return CODEC;
	}

	@Override
	public boolean place(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin) {
		int r = radius.sample(random);
		double bowl = r * depth, rimHeight = Math.max(1, r * rim);
		int outer = (int) Math.ceil(r * 1.35);
		int baseY = origin.getY();
		BlockState surface = level.getBlockState(origin.below());
		if (surface.isAir() || !surface.getFluidState().isEmpty()) return false;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -outer; dx <= outer; dx++) {
			for (int dz = -outer; dz <= outer; dz++) {
				double d = Math.sqrt(dx * dx + dz * dz) + random.nextFloat() * 0.6;
				if (d > outer) continue;
				int x = origin.getX() + dx, z = origin.getZ() + dz;
				int top = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
				pos.set(x, top, z);
				BlockState cover = level.getBlockState(pos);
				if (cover.isAir() || !cover.getFluidState().isEmpty()) continue;
				if (d < r) {
					// Bowl: carve down to the parabola, then line the floor with the surface block.
					int floor = (int) Math.round(baseY - 1 - bowl * (1 - (d / r) * (d / r)));
					for (int y = top; y > floor; y--) level.setBlock(pos.set(x, y, z), Blocks.AIR.defaultBlockState(), 2);
					level.setBlock(pos.set(x, floor, z), cover, 2);
				} else {
					// Rim and ejecta: pile surface material, highest at the bowl's edge.
					int pile = (int) Math.round(rimHeight * (1 - (d - r) / (outer - r)));
					for (int y = 1; y <= pile; y++) level.setBlock(pos.set(x, top + y, z), cover, 2);
				}
			}
		}
		return true;
	}
}
