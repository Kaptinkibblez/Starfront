package com.starwake.ship.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Per-block ship statistics, loaded from datapack JSON at
 * {@code data/<namespace>/starwake/ship_stats/<name>.json}:
 *
 * <pre>{@code
 * { "blocks": ["#minecraft:wool", "minecraft:white_carpet"],
 *   "mass": 0.2,            // tonnes per block (default 1.0)
 *   "thrust": 40,           // thrusters only: force at full throttle
 *   "fuel_burn": 0.05,      // thrusters only: fuel units per tick at full throttle
 *   "fuel_capacity": 2000 } // fuel tanks only
 * }</pre>
 *
 * When several entries match a block, the most specific wins (exact id > tag > "*").
 * Unset fields fall through to the next matching entry, then to defaults. Reloads with /reload.
 */
public final class ShipStats {
	/** Resolved stats for one block. */
	public record BlockStats(double mass, double thrust, double fuelBurn, int fuelCapacity) {
		public static final BlockStats DEFAULT = new BlockStats(1.0, 0.0, 0.0, 0);
	}

	/** One JSON file. */
	public record Entry(IdMatcher blocks, Optional<Double> mass, Optional<Double> thrust,
			Optional<Double> fuelBurn, Optional<Integer> fuelCapacity) {
		public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
				IdMatcher.CODEC.fieldOf("blocks").forGetter(Entry::blocks),
				Codec.doubleRange(0.0, 1000.0).optionalFieldOf("mass").forGetter(Entry::mass),
				Codec.doubleRange(0.0, 100000.0).optionalFieldOf("thrust").forGetter(Entry::thrust),
				Codec.doubleRange(0.0, 1000.0).optionalFieldOf("fuel_burn").forGetter(Entry::fuelBurn),
				Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("fuel_capacity").forGetter(Entry::fuelCapacity)
		).apply(i, Entry::new));
	}

	private static List<Entry> entries = List.of();
	/** Lazily filled per block; cleared on reload. Main-thread only. */
	private static final Map<Block, BlockStats> CACHE = new IdentityHashMap<>();

	private ShipStats() {}

	public static void init() {
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(StarwakeMod.id("ship_stats"),
				new SimpleJsonResourceReloadListener<>(Entry.CODEC, FileToIdConverter.json("starwake/ship_stats")) {
					@Override
					protected void apply(Map<Identifier, Entry> loaded, ResourceManager manager, ProfilerFiller profiler) {
						entries = List.copyOf(loaded.values());
						CACHE.clear();
						StarwakeMod.LOGGER.info("Loaded {} ship stat entries", entries.size());
					}
				});
	}

	public static BlockStats get(BlockState state) {
		return CACHE.computeIfAbsent(state.getBlock(), ShipStats::resolve);
	}

	private static BlockStats resolve(Block block) {
		var holder = block.builtInRegistryHolder();
		// Collect matching entries, most specific first.
		List<Entry> matches = new ArrayList<>();
		List<Integer> scores = new ArrayList<>();
		for (Entry entry : entries) {
			int score = entry.blocks().blockSpecificity(holder);
			if (score > 0) {
				int at = 0;
				while (at < scores.size() && scores.get(at) >= score) at++;
				matches.add(at, entry);
				scores.add(at, score);
			}
		}
		BlockStats d = BlockStats.DEFAULT;
		return new BlockStats(
				first(matches, Entry::mass).orElse(d.mass()),
				first(matches, Entry::thrust).orElse(d.thrust()),
				first(matches, Entry::fuelBurn).orElse(d.fuelBurn()),
				first(matches, Entry::fuelCapacity).orElse(d.fuelCapacity()));
	}

	private static <T> Optional<T> first(List<Entry> matches, java.util.function.Function<Entry, Optional<T>> field) {
		for (Entry entry : matches) {
			Optional<T> value = field.apply(entry);
			if (value.isPresent()) return value;
		}
		return Optional.empty();
	}
}
