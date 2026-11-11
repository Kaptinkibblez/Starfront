package com.starwake.ship.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import java.util.Map;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Jump drive tuning (M6), from {@code data/<ns>/starwake/jump_drive/default.json}. Range and
 * spool time are per drive block ({@code ship_stats}); these are the rules of a jump.
 *
 * @param fuelPerLy     fuel units per light-year
 * @param alignDegrees  how closely the nose must point at the target star to engage
 * @param planetWell    clearance from a planet, in multiples of its atmosphere radius
 * @param starWell      clearance from a star, in multiples of its radius
 * @param baseTicks     jump tunnel time for any jump
 * @param ticksPerLy    extra tunnel time per light-year
 * @param maxTicks      longest tunnel
 * @param arrival       arrival distance from the target star at 1 solar luminosity (scales with
 *                      the square root of its luminosity, like its habitable zone)
 */
public record JumpTuning(double fuelPerLy, double alignDegrees, double planetWell, double starWell, int baseTicks, int ticksPerLy,
		int maxTicks, double arrival) {
	public static final JumpTuning DEFAULT = new JumpTuning(60, 5, 12, 25, 200, 40, 1200, 2_500_000);

	public static final Codec<JumpTuning> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.doubleRange(0, 1.0e6).optionalFieldOf("fuel_per_ly", DEFAULT.fuelPerLy).forGetter(JumpTuning::fuelPerLy),
			Codec.doubleRange(0.1, 45).optionalFieldOf("align", DEFAULT.alignDegrees).forGetter(JumpTuning::alignDegrees),
			Codec.doubleRange(1, 1000).optionalFieldOf("planet_well", DEFAULT.planetWell).forGetter(JumpTuning::planetWell),
			Codec.doubleRange(1, 1000).optionalFieldOf("star_well", DEFAULT.starWell).forGetter(JumpTuning::starWell),
			Codec.intRange(20, 72000).optionalFieldOf("base_ticks", DEFAULT.baseTicks).forGetter(JumpTuning::baseTicks),
			Codec.intRange(0, 72000).optionalFieldOf("ticks_per_ly", DEFAULT.ticksPerLy).forGetter(JumpTuning::ticksPerLy),
			Codec.intRange(20, 72000).optionalFieldOf("max_ticks", DEFAULT.maxTicks).forGetter(JumpTuning::maxTicks),
			Codec.doubleRange(10_000, 2.0e7).optionalFieldOf("arrival", DEFAULT.arrival).forGetter(JumpTuning::arrival)
	).apply(i, JumpTuning::new));

	private static JumpTuning current = DEFAULT;

	public static JumpTuning get() {
		return current;
	}

	public int fuel(double lightYears) {
		return (int) Math.ceil(lightYears * fuelPerLy);
	}

	public int ticks(double lightYears) {
		return (int) Math.min(maxTicks, baseTicks + lightYears * ticksPerLy);
	}

	public static void init() {
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(StarwakeMod.id("jump_drive"),
				new SimpleJsonResourceReloadListener<>(CODEC, FileToIdConverter.json("starwake/jump_drive")) {
					@Override
					protected void apply(Map<Identifier, JumpTuning> loaded, ResourceManager manager, ProfilerFiller profiler) {
						current = loaded.getOrDefault(StarwakeMod.id("default"), DEFAULT);
					}
				});
	}
}
