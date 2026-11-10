package com.starwake.ship.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Which items can be loaded into fuel tanks, and how many fuel units each is worth. Loaded
 * from {@code data/<namespace>/starwake/ship_fuel/<name>.json}:
 * {@code { "items": ["minecraft:coal", "#minecraft:coals"], "units": 100 }}
 */
public final class ShipFuels {
	public record Entry(IdMatcher items, int units) {
		public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
				IdMatcher.CODEC.fieldOf("items").forGetter(Entry::items),
				Codec.intRange(1, 1_000_000).fieldOf("units").forGetter(Entry::units)
		).apply(i, Entry::new));
	}

	private static List<Entry> entries = List.of();
	private static final Map<Item, Integer> CACHE = new IdentityHashMap<>();

	private ShipFuels() {}

	public static void init() {
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(StarwakeMod.id("ship_fuel"),
				new SimpleJsonResourceReloadListener<>(Entry.CODEC, FileToIdConverter.json("starwake/ship_fuel")) {
					@Override
					protected void apply(Map<Identifier, Entry> loaded, ResourceManager manager, ProfilerFiller profiler) {
						entries = List.copyOf(loaded.values());
						CACHE.clear();
					}
				});
	}

	/** Fuel units one of this item is worth, or 0 if it isn't fuel. */
	public static int unitsFor(ItemStack stack) {
		if (stack.isEmpty()) return 0;
		return CACHE.computeIfAbsent(stack.getItem(), item -> {
			int best = 0, bestScore = 0;
			for (Entry entry : entries) {
				int score = entry.items().specificity(item.builtInRegistryHolder(), Registries.ITEM);
				if (score > bestScore) {
					bestScore = score;
					best = entry.units();
				}
			}
			return best;
		});
	}
}
