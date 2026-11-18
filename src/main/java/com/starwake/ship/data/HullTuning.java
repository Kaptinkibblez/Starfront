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
 * Hull numbers (M8e-1), {@code data/starwake/starwake/hull/default.json}: how alloys turn into mass and
 * armour.
 *
 * @param referenceDensity  g/cm3 of the alloy a block's JSON mass assumes (mild steel)
 * @param referenceToughness UTS (MPa) x elongation (%, capped at 40) of that steel: armour scales with it
 * @param crumple           share of a crash's energy (after shields) the struck hull can soak up
 */
public record HullTuning(double referenceDensity, double referenceToughness, double crumple) {
	public static final HullTuning DEFAULT = new HullTuning(7.87, 10220, 0.5);
	static final Codec<HullTuning> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.DOUBLE.optionalFieldOf("reference_density", DEFAULT.referenceDensity).forGetter(HullTuning::referenceDensity),
			Codec.DOUBLE.optionalFieldOf("reference_toughness", DEFAULT.referenceToughness).forGetter(HullTuning::referenceToughness),
			Codec.DOUBLE.optionalFieldOf("crumple", DEFAULT.crumple).forGetter(HullTuning::crumple)
	).apply(i, HullTuning::new));

	private static HullTuning current = DEFAULT;

	public static HullTuning get() {
		return current;
	}

	public static void init() {
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(StarwakeMod.id("hull"),
				new SimpleJsonResourceReloadListener<>(CODEC, FileToIdConverter.json("starwake/hull")) {
					@Override
					protected void apply(Map<Identifier, HullTuning> loaded, ResourceManager manager, ProfilerFiller profiler) {
						current = loaded.getOrDefault(StarwakeMod.id("default"), DEFAULT);
					}
				});
	}
}
