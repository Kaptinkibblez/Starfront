package com.starwake.ship;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The blocks of an assembled ship, in ship-local coordinates. At assembly (0,0,0) is the min
 * corner; editing (Milestone 4) never moves existing blocks, so later the bounds can extend
 * into negative coordinates ({@link #min()}/{@link #max()}). The {@link #pivot()} (the
 * entity's position in local space) is fixed at assembly and kept through edits, so the ship
 * doesn't shift when its shape changes. Immutable: edits make a new structure.
 *
 * Also keeps each block's block-entity data (chest contents and so on) so disassembly can
 * restore it, plus a precomputed "outer face" list per direction for cheap terrain collision.
 */
public final class ShipStructure {
	/** One stored block. {@code blockEntity} is only kept server-side (never sent to clients). */
	public record Entry(BlockPos pos, BlockState state, Optional<CompoundTag> blockEntity) {
		public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
				BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos),
				BlockState.CODEC.fieldOf("state").forGetter(Entry::state),
				CompoundTag.CODEC.optionalFieldOf("block_entity").forGetter(Entry::blockEntity)
		).apply(i, Entry::new));
	}

	/** Saved form: {entries, pivot}. Older saves are a bare entry list (pivot = box centre). */
	private static final Codec<ShipStructure> WITH_PIVOT = RecordCodecBuilder.create(i -> i.group(
			Entry.CODEC.listOf().fieldOf("entries").forGetter(ShipStructure::entries),
			net.minecraft.world.phys.Vec3.CODEC.fieldOf("pivot").forGetter(ShipStructure::pivot)
	).apply(i, ShipStructure::new));
	public static final Codec<ShipStructure> CODEC = Codec.withAlternative(WITH_PIVOT,
			Entry.CODEC.listOf().xmap(ShipStructure::new, ShipStructure::entries));

	/** Network form: positions, block states and the pivot. */
	public static final StreamCodec<RegistryFriendlyByteBuf, ShipStructure> STREAM_CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()), s -> s.entries.stream().map(Entry::pos).toList(),
			ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY).apply(ByteBufCodecs.list()), s -> s.entries.stream().map(Entry::state).toList(),
			net.minecraft.world.phys.Vec3.STREAM_CODEC, ShipStructure::pivot,
			(positions, states, pivot) -> {
				List<Entry> entries = new ArrayList<>(positions.size());
				for (int i = 0; i < positions.size(); i++) {
					entries.add(new Entry(positions.get(i), states.get(i), Optional.empty()));
				}
				return new ShipStructure(entries, pivot);
			});

	private final List<Entry> entries;
	private final Long2ObjectMap<BlockState> states = new Long2ObjectOpenHashMap<>();
	private final Vec3i size;
	private final BlockPos min, max;
	private final net.minecraft.world.phys.Vec3 pivot;
	/** Blocks with collision whose neighbour in that direction is empty: the ship's outer skin. */
	private final Map<Direction, List<BlockPos>> outerFaces = new EnumMap<>(Direction.class);

	/** A fresh structure (assembly): the pivot is the centre of the blocks' box. */
	public ShipStructure(List<Entry> entries) {
		this(entries, null);
	}

	public ShipStructure(List<Entry> entries, net.minecraft.world.phys.@Nullable Vec3 pivot) {
		this.entries = List.copyOf(entries);
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (Entry entry : this.entries) {
			states.put(entry.pos().asLong(), entry.state());
			minX = Math.min(minX, entry.pos().getX());
			minY = Math.min(minY, entry.pos().getY());
			minZ = Math.min(minZ, entry.pos().getZ());
			maxX = Math.max(maxX, entry.pos().getX());
			maxY = Math.max(maxY, entry.pos().getY());
			maxZ = Math.max(maxZ, entry.pos().getZ());
		}
		if (this.entries.isEmpty()) {
			minX = minY = minZ = 0;
			maxX = maxY = maxZ = 0;
		}
		this.min = new BlockPos(minX, minY, minZ);
		this.max = new BlockPos(maxX, maxY, maxZ);
		this.size = new Vec3i(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
		this.pivot = pivot != null ? pivot
				: new net.minecraft.world.phys.Vec3((minX + maxX + 1) / 2.0, (minY + maxY + 1) / 2.0, (minZ + maxZ + 1) / 2.0);
		for (Direction direction : Direction.values()) {
			List<BlockPos> face = new ArrayList<>();
			for (Entry entry : this.entries) {
				if (!hasCollision(entry.pos()) ) continue;
				if (!hasCollision(entry.pos().relative(direction))) face.add(entry.pos());
			}
			outerFaces.put(direction, List.copyOf(face));
		}
	}

	public List<Entry> entries() {
		return entries;
	}

	/** Lowest occupied corner (inclusive). */
	public BlockPos min() {
		return min;
	}

	/** Highest occupied corner (inclusive). */
	public BlockPos max() {
		return max;
	}

	/** The entity's position in local space; fixed at assembly. */
	public net.minecraft.world.phys.Vec3 pivot() {
		return pivot;
	}

	/** A copy with a block added (or replaced), keeping the pivot. */
	public ShipStructure plus(BlockPos local, BlockState state, Optional<CompoundTag> blockEntity) {
		List<Entry> updated = new ArrayList<>(entries.size() + 1);
		for (Entry entry : entries) if (!entry.pos().equals(local)) updated.add(entry);
		updated.add(new Entry(local.immutable(), state, blockEntity));
		return new ShipStructure(updated, pivot);
	}

	/** A copy without the given blocks, keeping the pivot. */
	public ShipStructure without(java.util.Collection<BlockPos> removed) {
		java.util.Set<BlockPos> gone = new java.util.HashSet<>(removed);
		List<Entry> updated = new ArrayList<>(entries.size());
		for (Entry entry : entries) if (!gone.contains(entry.pos())) updated.add(entry);
		return new ShipStructure(updated, pivot);
	}

	/** True if a local position is inside the bounds (grown by {@code margin}). */
	public boolean inBounds(double x, double y, double z, double margin) {
		return x > min.getX() - margin && y > min.getY() - margin && z > min.getZ() - margin
				&& x < max.getX() + 1 + margin && y < max.getY() + 1 + margin && z < max.getZ() + 1 + margin;
	}

	/** A copy with one block's state and/or block-entity data replaced (structures are immutable). */
	public ShipStructure with(BlockPos local, BlockState state, Optional<CompoundTag> blockEntity) {
		List<Entry> updated = new ArrayList<>(entries.size());
		for (Entry entry : entries) {
			updated.add(entry.pos().equals(local) ? new Entry(local, state, blockEntity) : entry);
		}
		return new ShipStructure(updated, pivot);
	}

	/**
	 * M9: a copy with many cells changed at once (a tick's worth of shipyard changes): a null entry removes the
	 * cell, anything else adds or replaces it. Keeps the pivot.
	 */
	public ShipStructure withChanges(Map<BlockPos, @Nullable Entry> changes) {
		List<Entry> updated = new ArrayList<>(entries.size() + changes.size());
		for (Entry entry : entries) if (!changes.containsKey(entry.pos())) updated.add(entry);
		for (Entry entry : changes.values()) if (entry != null) updated.add(entry);
		return new ShipStructure(updated, pivot);
	}

	public Optional<CompoundTag> blockEntity(BlockPos local) {
		for (Entry entry : entries) {
			if (entry.pos().equals(local)) return entry.blockEntity();
		}
		return Optional.empty();
	}

	/** Size of the bounding box in blocks. */
	public Vec3i size() {
		return size;
	}

	public @Nullable BlockState get(BlockPos local) {
		return states.get(local.asLong());
	}

	public @Nullable BlockState get(int x, int y, int z) {
		return states.get(BlockPos.asLong(x, y, z));
	}

	public boolean hasCollision(BlockPos local) {
		BlockState state = get(local);
		return state != null && !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
	}

	/** Blocks on the ship's outer skin facing {@code direction}. Used for terrain collision. */
	public List<BlockPos> outerFace(Direction direction) {
		return outerFaces.get(direction);
	}
}
