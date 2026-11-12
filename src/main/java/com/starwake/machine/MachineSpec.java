package com.starwake.machine;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import java.util.Map;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;

/**
 * Machine numbers (M8b), from {@code data/<ns>/starwake/machine/<block>.json} (reloadable):
 * the Java classes are behaviour, these are the numbers. Units are real: kW, kJ, kWh.
 *
 * @param power    kW: generator output at full tilt, or a consumer's draw while working
 * @param buffer   kJ: internal energy buffer
 * @param io       kW: most it moves to/from the grid
 * @param capacity kWh: storage (batteries)
 * @param speed    work-speed multiplier (an electric furnace cooks faster than a furnace)
 * @param fuel     fuel multiplier: generator burn time = vanilla burn time x fuel
 * @param rating   kW: what a cable carries (a network carries its weakest cable's rating)
 * @param rate     transfer rate: buckets/s for pipes, blocks/s for conveyors
 * @param temperature process temperature, C (roasters, smelters...)
 */
public record MachineSpec(double power, double buffer, double io, double capacity, double speed, double fuel, double rating, double rate, double temperature) {
	public static final MachineSpec DEFAULT = new MachineSpec(1, 20, 10, 0, 1, 1, 32, 1, 25);

	public static final Codec<MachineSpec> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.DOUBLE.optionalFieldOf("power", DEFAULT.power).forGetter(MachineSpec::power),
			Codec.DOUBLE.optionalFieldOf("buffer", DEFAULT.buffer).forGetter(MachineSpec::buffer),
			Codec.DOUBLE.optionalFieldOf("io", DEFAULT.io).forGetter(MachineSpec::io),
			Codec.DOUBLE.optionalFieldOf("capacity", DEFAULT.capacity).forGetter(MachineSpec::capacity),
			Codec.DOUBLE.optionalFieldOf("speed", DEFAULT.speed).forGetter(MachineSpec::speed),
			Codec.DOUBLE.optionalFieldOf("fuel", DEFAULT.fuel).forGetter(MachineSpec::fuel),
			Codec.DOUBLE.optionalFieldOf("rating", DEFAULT.rating).forGetter(MachineSpec::rating),
			Codec.DOUBLE.optionalFieldOf("rate", DEFAULT.rate).forGetter(MachineSpec::rate),
			Codec.DOUBLE.optionalFieldOf("temperature", DEFAULT.temperature).forGetter(MachineSpec::temperature)
	).apply(i, MachineSpec::new));

	private static Map<Identifier, MachineSpec> specs = Map.of();

	public static void init() {
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(StarwakeMod.id("machine"),
				new SimpleJsonResourceReloadListener<>(CODEC, FileToIdConverter.json("starwake/machine")) {
					@Override
					protected void apply(Map<Identifier, MachineSpec> loaded, ResourceManager manager, ProfilerFiller profiler) {
						specs = Map.copyOf(loaded);
						StarwakeMod.LOGGER.info("Loaded {} machine specs", loaded.size());
					}
				});
	}

	public static MachineSpec of(Block block) {
		return specs.getOrDefault(BuiltInRegistries.BLOCK.getKey(block), DEFAULT);
	}

	/** Buffer in joules. */
	public long bufferJ() {
		return Math.round(buffer * 1000);
	}

	/** Storage capacity in joules (kWh x 3.6 MJ). */
	public long capacityJ() {
		return Math.round(capacity * 3_600_000);
	}
}
