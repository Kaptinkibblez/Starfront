package com.starwake.ship.edit;

import com.starwake.ship.ShipEntity;
import com.starwake.ship.ShipStructure;
import com.starwake.ship.SpaceTravel;
import com.starwake.ship.block.HelmBlock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Milestone 4: building and breaking on an assembled ship, any time (parked, flying, in
 * space), with survival rules. Server side; every request is re-validated here.
 *
 * <ul>
 *   <li><b>Who:</b> the owner and crew ({@link ShipEntity#canPilot}); strangers can't edit.</li>
 *   <li><b>Breaking:</b> mining takes the vanilla time for your tool (the client reports start
 *       and finish; the server checks enough time passed). Drops go into your inventory. The
 *       Helm can't be broken while assembled (disassemble first).</li>
 *   <li><b>Detached parts:</b> blocks no longer connected to the Helm leave the ship. If the
 *       ship is parked level on a planet they stay put as world blocks; otherwise they drop
 *       as items.</li>
 *   <li><b>Placing:</b> a block item against a ship face, oriented in the ship's frame
 *       ({@link ShipPlaceContext}); doors take two cells; beds aren't supported aboard.</li>
 * </ul>
 * Each edit makes a new {@link ShipStructure}: the ship recomputes mass, thrusters, seal and
 * systems, and clients get the new structure.
 */
public final class ShipEditing {
	/** Same cap as the scanner: ships stay under this many blocks. */
	public static final int MAX_BLOCKS = 4096;

	/** A mining job a player started: which ship and block, and when. */
	private record Mining(int shipId, BlockPos local, long startTick) {}

	private static final Map<UUID, Mining> MINING = new HashMap<>();

	private ShipEditing() {}

	public static boolean mayEdit(ShipEntity ship, ServerPlayer player) {
		return ship.canPilot(player) && !player.isSpectator();
	}

	private static boolean inReach(ShipEntity ship, ServerPlayer player, BlockPos local) {
		double reach = player.blockInteractionRange() + 2.5;
		return player.getEyePosition().distanceToSqr(ship.blockCenter(local)) <= reach * reach;
	}

	/** The world cell a local block is over right now (for sounds, particles and loot). */
	private static BlockPos worldCell(ShipEntity ship, BlockPos local) {
		return BlockPos.containing(ship.blockCenter(local));
	}

	// =========================================================================================
	// Breaking
	// =========================================================================================

	public static void startBreak(ServerPlayer player, ShipEntity ship, BlockPos local) {
		if (ship.structure() == null || ship.structure().get(local) == null || !inReach(ship, player, local)) return;
		if (!mayEdit(ship, player)) {
			player.sendOverlayMessage(Component.translatable("message.starwake.edit.not_allowed", ship.ownerName()));
			return;
		}
		MINING.put(player.getUUID(), new Mining(ship.getId(), local.immutable(), player.level().getGameTime()));
	}

	public static void abortBreak(ServerPlayer player) {
		MINING.remove(player.getUUID());
	}

	public static void finishBreak(ServerPlayer player, ShipEntity ship, BlockPos local) {
		ShipStructure structure = ship.structure();
		Mining job = MINING.remove(player.getUUID());
		if (structure == null || !(player.level() instanceof ServerLevel level)) return;
		BlockState state = structure.get(local);
		if (state == null || !inReach(ship, player, local) || !mayEdit(ship, player)) return;
		if (state.getBlock() instanceof HelmBlock) {
			player.sendOverlayMessage(Component.translatable("message.starwake.edit.helm"));
			return;
		}
		boolean creative = player.isCreative();
		if (!creative) {
			// Mining time: the same per-tick progress vanilla uses, with slack for latency.
			float perTick = state.getDestroyProgress(player, new ShipBlockView(structure), local);
			if (perTick <= 0) return; // unbreakable
			long needed = (long) Math.ceil(1.0 / perTick);
			if (job == null || job.shipId() != ship.getId() || !job.local().equals(local)
					|| level.getGameTime() - job.startTick() < needed * 0.7 - 2) {
				return;
			}
		}

		BlockPos cell = worldCell(ship, local);
		ItemStack tool = player.getMainHandItem();
		if (!creative) {
			List<ItemStack> drops = new ArrayList<>(Block.getDrops(state, level, cell, null, player, tool));
			drops.addAll(containerContents(ship, local));
			tool.mineBlock(level, state, cell, player);
			for (ItemStack drop : drops) give(player, drop);
		}
		level.levelEvent(2001, cell, Block.getId(state)); // break particles + sound for everyone

		Set<BlockPos> removed = new HashSet<>();
		removed.add(local.immutable());
		// A door is two blocks: take the other half too.
		if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
			BlockPos other = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? local.above() : local.below();
			BlockState otherState = structure.get(other);
			if (otherState != null && otherState.is(state.getBlock())) removed.add(other);
		}
		ShipStructure next = structure.without(removed);
		Set<BlockPos> detached = detached(next, ship.helmLocal());
		if (!detached.isEmpty()) {
			dropDebris(ship, level, next, detached, player);
			next = next.without(detached);
		}
		ship.applyEdit(next);
	}

	/** Contents of a container block aboard (chest, barrel), as items. */
	private static List<ItemStack> containerContents(ShipEntity ship, BlockPos local) {
		List<ItemStack> items = new ArrayList<>();
		if (ship.structure() == null || ship.structure().blockEntity(local).isEmpty()) return items;
		var container = ship.container(local, 27);
		for (int i = 0; i < container.getContainerSize(); i++) {
			if (!container.getItem(i).isEmpty()) items.add(container.getItem(i).copy());
		}
		return items;
	}

	private static void give(ServerPlayer player, ItemStack stack) {
		if (!player.getInventory().add(stack) && !stack.isEmpty()) player.drop(stack, false, net.minecraft.util.Prediction.PREDICTED);
	}

	/** Blocks no longer connected (6-neighbour) to the Helm. */
	private static Set<BlockPos> detached(ShipStructure structure, BlockPos helm) {
		Set<BlockPos> reached = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		if (structure.get(helm) != null) {
			reached.add(helm);
			queue.add(helm);
		}
		while (!queue.isEmpty()) {
			BlockPos pos = queue.poll();
			for (Direction d : Direction.values()) {
				BlockPos next = pos.relative(d);
				if (structure.get(next) != null && reached.add(next)) queue.add(next);
			}
		}
		Set<BlockPos> detached = new HashSet<>();
		for (ShipStructure.Entry entry : structure.entries()) {
			if (!reached.contains(entry.pos())) detached.add(entry.pos());
		}
		return detached;
	}

	/**
	 * Detached blocks leave the ship: placed into the world where they are if the ship is parked
	 * level on a planet and the cells are free; otherwise dropped as items there.
	 */
	private static void dropDebris(ShipEntity ship, ServerLevel level, ShipStructure structure, Set<BlockPos> detached, ServerPlayer player) {
		boolean parked = !SpaceTravel.isSpace(level) && ship.isAxisAligned() && ship.lastMove().lengthSqr() < 0.0025;
		Rotation rotation = parked ? ship.cardinalRotation() : Rotation.NONE;
		for (BlockPos local : detached) {
			BlockState state = structure.get(local);
			if (state == null) continue;
			BlockPos cell = worldCell(ship, local);
			Optional<CompoundTag> data = structure.blockEntity(local);
			if (parked && level.getBlockState(cell).canBeReplaced()) {
				level.setBlock(cell, state.rotate(rotation), Block.UPDATE_ALL);
				data.ifPresent(tag -> {
					BlockEntity blockEntity = level.getBlockEntity(cell);
					if (blockEntity != null) {
						blockEntity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
						blockEntity.setChanged();
					}
				});
			} else {
				List<ItemStack> drops = new ArrayList<>(Block.getDrops(state, level, cell, null));
				drops.addAll(containerContents(ship, local));
				Vec3 at = ship.blockCenter(local);
				for (ItemStack drop : drops) {
					ItemEntity item = new ItemEntity(level, at.x, at.y, at.z, drop);
					item.setDeltaMovement(ship.lastMove());
					level.addFreshEntity(item);
				}
			}
		}
		player.sendOverlayMessage(Component.translatable("message.starwake.edit.detached", detached.size()));
	}

	// =========================================================================================
	// Placing
	// =========================================================================================

	/**
	 * Places the held block against {@code face} of ship block {@code clicked}.
	 *
	 * @return true if the click was a placement attempt (handled), false if the item isn't a block
	 */
	public static boolean place(ServerPlayer player, ShipEntity ship, InteractionHand hand, BlockPos clicked, Direction face, Vec3 hitLocal) {
		ItemStack stack = player.getItemInHand(hand);
		if (!(stack.getItem() instanceof BlockItem blockItem)) return false;
		ShipStructure structure = ship.structure();
		if (structure == null || structure.get(clicked) == null || !inReach(ship, player, clicked)) return true;
		if (!mayEdit(ship, player)) {
			player.sendOverlayMessage(Component.translatable("message.starwake.edit.not_allowed", ship.ownerName()));
			return true;
		}
		Block block = blockItem.getBlock();
		if (block instanceof BedBlock) {
			player.sendOverlayMessage(Component.translatable("message.starwake.edit.unsupported"));
			return true;
		}
		BlockPos target = clicked.relative(face);
		if (structure.get(target) != null) return true;
		if (structure.entries().size() >= MAX_BLOCKS) {
			player.sendOverlayMessage(Component.translatable("message.starwake.edit.too_big", MAX_BLOCKS));
			return true;
		}

		BlockPos offset = BlockPos.containing(ship.position()).subtract(BlockPos.containing(ship.pivot()));
		ShipPlaceContext context = ShipPlaceContext.create(ship, player, hand, clicked, face, hitLocal, offset);
		BlockState state = block.getStateForPlacement(context);
		if (state == null) return true;

		List<BlockPos> cells = new ArrayList<>(List.of(target));
		List<BlockState> states = new ArrayList<>(List.of(state));
		if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
			BlockPos upper = target.above();
			if (structure.get(upper) != null) return true;
			cells.add(upper);
			states.add(state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
		}
		// Don't place a solid block into someone.
		for (int i = 0; i < cells.size(); i++) {
			if (states.get(i).getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) continue;
			AABB box = ship.worldBoxOf(cells.get(i)).deflate(0.05);
			List<Entity> inside = player.level().getEntities(ship, box, e -> !e.isSpectator() && e.isPickable() && !(e instanceof ShipEntity));
			if (!inside.isEmpty()) return true;
		}

		ShipStructure next = structure;
		for (int i = 0; i < cells.size(); i++) next = next.plus(cells.get(i), states.get(i), Optional.empty());
		ship.applyEdit(next);
		if (!player.isCreative()) stack.shrink(1);
		var sound = state.getSoundType();
		player.level().playSound(null, worldCell(ship, target), sound.getPlaceSound(), SoundSource.BLOCKS,
				(sound.getVolume() + 1.0f) / 2.0f, sound.getPitch() * 0.8f);
		return true;
	}

	/** Forget a player's mining job (logout). */
	public static void forget(@Nullable ServerPlayer player) {
		if (player != null) MINING.remove(player.getUUID());
	}
}
