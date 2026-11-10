package com.starwake.ship.data;

import com.mojang.serialization.Codec;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;

/**
 * A list of registry ids (blocks or items) and/or tags from JSON, e.g. {@code ["minecraft:glass", "#minecraft:wool"]},
 * or {@code ["*"]} to match everything. Kept as plain strings and resolved at lookup time, so
 * it works regardless of the order in which tags and our data are reloaded.
 */
public record IdMatcher(List<String> patterns) {
	public static final Codec<IdMatcher> CODEC = Codec.STRING.listOf().xmap(IdMatcher::new, IdMatcher::patterns);

	/** Match strength: 0 = no match, 1 = wildcard, 2 = tag, 3 = exact id (more specific wins). */
	public <T> int specificity(Holder<T> holder, net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<T>> registry) {
		int best = 0;
		for (String pattern : patterns) {
			if (pattern.equals("*")) {
				best = Math.max(best, 1);
			} else if (pattern.startsWith("#")) {
				TagKey<T> tag = TagKey.create(registry, Identifier.parse(pattern.substring(1)));
				if (holder.is(tag)) {
					best = Math.max(best, 2);
				}
			} else if (holder.is(Identifier.parse(pattern))) {
				return 3;
			}
		}
		return best;
	}

	/** Convenience for the block registry. */
	public int blockSpecificity(Holder<net.minecraft.world.level.block.Block> block) {
		return specificity(block, Registries.BLOCK);
	}
}
