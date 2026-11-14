package com.starwake.client.ship;

import com.starwake.ship.ShipEntity;
import com.starwake.ship.ShipStructure;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import org.jspecify.annotations.Nullable;

/**
 * Client copies ("ghosts") of the block entities aboard each ship (M9 pass 2), so vanilla's block-entity renderers
 * draw chests, signs, banners, beds, skulls, shulker boxes and lecterns on ships. Each ghost is built from the ship
 * snapshot (the client tag the server sends with it) at its ship-local position, and kept across snapshot changes
 * so animation state survives: chest lids replay the block events the server relays ({@code ShipBlockEventPayload})
 * and tick here. {@code ShipRenderer} draws them in the ship's frame.
 */
public final class ShipBlockEntities {
	/** One ship's ghosts and the snapshot they were built from. */
	static final class Ghosts {
		@Nullable ShipStructure built;
		final Map<BlockPos, BlockEntity> byPos = new HashMap<>();
	}

	private static final Map<ShipEntity, Ghosts> SHIPS = new WeakHashMap<>();

	private ShipBlockEntities() {}

	/** The ship's ghosts, rebuilt if its snapshot changed. Only blocks with a block-entity renderer get one. */
	static Map<BlockPos, BlockEntity> of(ShipEntity ship, ClientLevel level) {
		Ghosts ghosts = SHIPS.computeIfAbsent(ship, s -> new Ghosts());
		ShipStructure structure = ship.structure();
		if (structure == null) {
			ghosts.byPos.clear();
			ghosts.built = null;
			return ghosts.byPos;
		}
		if (ghosts.built == structure) return ghosts.byPos;
		ghosts.built = structure;
		var dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
		Map<BlockPos, BlockEntity> next = new HashMap<>();
		for (ShipStructure.Entry entry : structure.entries()) {
			if (!(entry.state().getBlock() instanceof EntityBlock block)) continue;
			BlockEntity ghost = ghosts.byPos.get(entry.pos());
			if (ghost == null || !ghost.getType().isValid(entry.state())) {
				ghost = block.newBlockEntity(entry.pos(), entry.state());
				if (ghost == null || dispatcher.getRenderer(ghost) == null) continue;
				ghost.setLevel(level);
			} else {
				ghost.setBlockState(entry.state());
			}
			CompoundTag tag = entry.blockEntity().orElse(null);
			if (tag != null && !tag.isEmpty()) {
				try (var reporter = new ProblemReporter.ScopedCollector(ghost.problemPath(), com.starwake.StarwakeMod.LOGGER)) {
					ghost.loadWithComponents(TagValueInput.create(reporter, level.registryAccess(), tag));
				}
			}
			next.put(entry.pos(), ghost);
		}
		ghosts.byPos.clear();
		ghosts.byPos.putAll(next);
		return ghosts.byPos;
	}

	/** A block event aboard (chest lid open/close, ...), relayed by the server. */
	public static void blockEvent(ShipEntity ship, BlockPos local, int a, int b) {
		if (!(ship.level() instanceof ClientLevel level)) return;
		BlockEntity ghost = of(ship, level).get(local);
		if (ghost != null) ghost.triggerEvent(a, b);
	}

	/**
	 * The ghost a client-side editor should use for the plot position {@code plotPos} (sign editing aboard): a fresh
	 * copy at the plot position, so the edit the screen sends goes to the real block.
	 */
	public static @Nullable BlockEntity forPlot(ClientLevel level, BlockPos plotPos) {
		int index = com.starwake.ship.yard.Shipyard.indexAt(plotPos);
		for (ShipEntity ship : SHIPS.keySet()) {
			if (ship.isRemoved() || ship.level() != level || ship.syncedPlot() != index) continue;
			BlockPos local = com.starwake.ship.yard.Shipyard.toLocal(index, plotPos);
			BlockEntity ghost = of(ship, level).get(local);
			ShipStructure structure = ship.structure();
			if (ghost == null || structure == null || !(ghost.getBlockState().getBlock() instanceof EntityBlock block)) return null;
			BlockEntity copy = block.newBlockEntity(plotPos, ghost.getBlockState());
			if (copy == null) return null;
			copy.setLevel(level);
			structure.blockEntity(local).filter(t -> !t.isEmpty()).ifPresent(tag -> {
				try (var reporter = new ProblemReporter.ScopedCollector(copy.problemPath(), com.starwake.StarwakeMod.LOGGER)) {
					copy.loadWithComponents(TagValueInput.create(reporter, level.registryAccess(), tag));
				}
			});
			return copy;
		}
		return null;
	}

	/** A sign editor waiting for its sign to appear in the ship's snapshot (plot position, front or back). */
	private static @Nullable BlockPos pendingSign;
	private static net.minecraft.world.level.block.entity.SignTextSlot pendingFront = net.minecraft.world.level.block.entity.SignTextSlot.FRONT;
	private static int pendingTicks;

	/** Opens the editor for the sign at {@code plotPos} as soon as the ship snapshot has it (up to 2 s). */
	public static void openSignEditor(BlockPos plotPos, net.minecraft.world.level.block.entity.SignTextSlot front) {
		pendingSign = plotPos.immutable();
		pendingFront = front;
		pendingTicks = 40;
		tryOpenSign();
	}

	private static void tryOpenSign() {
		Minecraft minecraft = Minecraft.getInstance();
		if (pendingSign == null || minecraft.level == null || minecraft.player == null) return;
		if (forPlot(minecraft.level, pendingSign) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
			pendingSign = null;
			minecraft.player.openTextEdit(sign, pendingFront);
		} else if (--pendingTicks <= 0) {
			pendingSign = null;
		}
	}

	/** Client tick: chest lids animate (the only ghost tickers we run; others would act at the local coordinates). */
	public static void tick(ClientLevel level) {
		tryOpenSign();
		for (var entry : SHIPS.entrySet()) {
			if (entry.getKey().level() != level) continue;
			for (BlockEntity ghost : entry.getValue().byPos.values()) {
				if (ghost instanceof ChestBlockEntity chest) {
					ChestBlockEntity.lidAnimateTick(level, chest.getBlockPos(), chest.getBlockState(), chest);
				} else if (ghost instanceof EnderChestBlockEntity chest) {
					EnderChestBlockEntity.lidAnimateTick(level, chest.getBlockPos(), chest.getBlockState(), chest);
				}
			}
		}
		SHIPS.keySet().removeIf(ShipEntity::isRemoved);
	}
}
