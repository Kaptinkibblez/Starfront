package com.starwake.ship.scan;

import com.starwake.StarwakeMod;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Finds the blocks that make up a ship: everything connected (face-to-face) to the Helm,
 * except air, replaceable blocks (grass, snow layers, fluids) and terrain
 * ({@code #starwake:ship_excluded}). So you can build any shape, and the ship's boundary is
 * wherever your build stops touching the ground.
 */
public final class ShipScanner {
	/** Hard cap on scanned blocks, to stop runaway scans (e.g. a ship touching a stone house). */
	public static final int MAX_BLOCKS = 65536;

	public static final TagKey<Block> EXCLUDED = TagKey.create(Registries.BLOCK, StarwakeMod.id("ship_excluded"));

	private ShipScanner() {}

	/** Result of a scan. {@code blocks} is partial if {@code tooBig} is set. */
	public record ShipScan(BlockPos helm, Set<BlockPos> blocks, boolean tooBig) {}

	public static ShipScan scan(Level level, BlockPos helm) {
		Set<BlockPos> blocks = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		blocks.add(helm.immutable());
		queue.add(helm.immutable());

		while (!queue.isEmpty()) {
			BlockPos pos = queue.poll();
			for (Direction direction : Direction.values()) {
				BlockPos next = pos.relative(direction);
				if (blocks.contains(next) || !isShipBlock(level.getBlockState(next))) {
					continue;
				}
				if (blocks.size() >= MAX_BLOCKS) {
					return new ShipScan(helm, blocks, true);
				}
				blocks.add(next);
				queue.add(next);
			}
		}
		return new ShipScan(helm, blocks, false);
	}

	public static boolean isShipBlock(BlockState state) {
		return !state.isAir() && !state.canBeReplaced() && !state.is(EXCLUDED);
	}
}
