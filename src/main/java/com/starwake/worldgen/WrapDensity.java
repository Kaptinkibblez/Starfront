package com.starwake.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * Makes terrain repeat every {@code period} blocks in X and Z (M9 finite planets), with no seam: the tile is
 * [-period/2, period/2) in both axes and repeats outward, and over the last {@code blend} blocks before the tile's
 * upper edge the input cross-fades into its own values one period back, so the upper edge meets the lower one
 * exactly (g(u) = lerp(f(u), f(u - period), smoothstep)).
 *
 * Volumes (how 26.3 samples terrain) wholly inside one copy of the tile are handed to the input shifted by a whole
 * number of periods, so interpolation and caches inside keep working (the period is a multiple of 16, so cell
 * alignment holds). In the blend band the input is sampled as whole volumes here and one period back, then cross-faded
 * per point. Only a volume straddling a tile edge (never a chunk: edges fall on chunk boundaries) goes point by point.
 */
public record WrapDensity(DensityFunction input, int period, int blend) implements DensityFunction {
	public static final MapCodec<WrapDensity> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			DensityFunction.CODEC.fieldOf("input").forGetter(WrapDensity::input),
			ExtraCodecs.POSITIVE_INT.fieldOf("period").forGetter(WrapDensity::period),
			ExtraCodecs.POSITIVE_INT.optionalFieldOf("blend", 96).forGetter(WrapDensity::blend)
	).apply(i, WrapDensity::new));

	public static void init() {
		Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, StarwakeMod.id("wrap"), CODEC);
	}

	/** Every function of a noise router wrapped (terrain and the climate that picks biomes). */
	public static NoiseRouter wrap(NoiseRouter r, int period, int blend) {
		return new NoiseRouter(w(r.temperature(), period, blend), w(r.vegetation(), period, blend), w(r.continents(), period, blend),
				w(r.erosion(), period, blend), w(r.depth(), period, blend), w(r.ridges(), period, blend),
				w(r.chunkSurfaceLevel(), period, blend), w(r.finalDensity(), period, blend));
	}

	private static DensityFunction w(DensityFunction f, int period, int blend) {
		return (f.domainAxes() & (AXIS_X | AXIS_Z)) == 0 ? f : new WrapDensity(f, period, blend);
	}

	@Override
	public DensitySampler compileSampler(DensityFunction.CompileContext context) {
		return new Sampler(input.compileSampler(context), period, Math.min(blend, period / 4));
	}

	@Override
	public DensityFunction rewriteChildren(DfRewriteRule rule) {
		DensityFunction rewritten = rule.rewrite(input);
		return rewritten == input ? this : new WrapDensity(rewritten, period, blend);
	}

	@Override
	public Interval range() {
		return input.range();
	}

	@Override
	public @DensityFunction.Axes int domainAxes() {
		return input.domainAxes();
	}

	@Override
	public MapCodec<WrapDensity> codec() {
		return CODEC;
	}

	private record Sampler(DensitySampler input, int period, int blend) implements DensitySampler {
		/** Coordinate inside the tile, [-period/2, period/2). */
		private int tile(int c) {
			return Math.floorMod(c + period / 2, period) - period / 2;
		}

		/** Cross-fade weight toward the value one period back: 0 away from the upper edge, 1 at it. */
		private float weight(int u) {
			float t = (u - (period / 2f - blend)) / blend;
			if (t <= 0) return 0;
			t = Math.min(1, t);
			return t * t * (3 - 2 * t);
		}

		@Override
		public float sampleValue(SamplerContext context, int x, int y, int z) {
			int u = tile(x), v = tile(z);
			float wx = weight(u), wz = weight(v);
			float a = input.sampleValue(context, u, y, v);
			if (wx == 0 && wz == 0) return a;
			float b = wx == 0 ? a : input.sampleValue(context, u - period, y, v);
			float low = a + (b - a) * wx;
			if (wz == 0) return low;
			float c = input.sampleValue(context, u, y, v - period);
			float d = wx == 0 ? c : input.sampleValue(context, u - period, y, v - period);
			float high = c + (d - c) * wx;
			return low + (high - low) * wz;
		}

		@Override
		public void sampleVolume(SamplerContext context, DensityBuffer output, DensityVolume volume) {
			int x0 = volume.minBlockX(), x1 = volume.maxBlockX(), z0 = volume.minBlockZ(), z1 = volume.maxBlockZ();
			int u0 = tile(x0), v0 = tile(z0);
			boolean sameX = x1 - x0 == tile(x1) - u0, sameZ = z1 - z0 == tile(z1) - v0; // no wrap inside the volume
			if (!sameX || !sameZ) {
				DensitySampler.sampleVolumeNaive(context, output, volume, this); // odd volumes across an edge (rare)
				return;
			}
			DensityVolume base = at(volume, u0, v0);
			boolean bandX = weight(tile(x1)) > 0, bandZ = weight(tile(z1)) > 0;
			if (!bandX && !bandZ) {
				input.sampleVolume(context, output, base);
				return;
			}
			// In the blend band: sample whole volumes (fast, interpolated) here and one period back, then cross-fade.
			input.sampleVolume(context, output, base);
			DensityBuffer b = bandX ? sample(context, at(volume, u0 - period, v0)) : null;
			DensityBuffer c = bandZ ? sample(context, at(volume, u0, v0 - period)) : null;
			DensityBuffer d = bandX && bandZ ? sample(context, at(volume, u0 - period, v0 - period)) : null;
			int sy = volume.sizeY();
			for (int iz = 0; iz < volume.sizeZ(); iz++) {
				float wz = bandZ ? weight(v0 + iz * volume.stepBlockZ()) : 0;
				for (int ix = 0; ix < volume.sizeX(); ix++) {
					float wx = bandX ? weight(u0 + ix * volume.stepBlockX()) : 0;
					if (wx == 0 && wz == 0) continue;
					int start = volume.indexUnchecked(ix, 0, iz);
					for (int iy = 0; iy < sy; iy++) {
						int i = start + iy;
						float a = output.get(i);
						float low = b == null ? a : a + (b.get(i) - a) * wx;
						float high = low;
						if (c != null) {
							float cv = c.get(i);
							high = d == null ? cv : cv + (d.get(i) - cv) * wx;
						}
						output.set(i, low + (high - low) * wz);
					}
				}
			}
		}

		private DensityBuffer sample(SamplerContext context, DensityVolume volume) {
			DensityBuffer buffer = DensityBuffer.createUnpooled(volume.size());
			input.sampleVolume(context, buffer, volume);
			return buffer;
		}

		private static DensityVolume at(DensityVolume v, int minX, int minZ) {
			if (minX == v.minBlockX() && minZ == v.minBlockZ()) return v;
			return new DensityVolume(v.sizeX(), v.sizeY(), v.sizeZ(), minX, v.minBlockY(), minZ, v.stepBlockX(), v.stepBlockY(), v.stepBlockZ());
		}
	}
}
