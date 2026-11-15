package com.starwake.ship;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

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
	/** One stored block. {@code blockEntity}: full NBT on the server; the client update tag on clients (see STREAM_CODEC). */
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
	/**
	 * Network form. Clients get every block plus, for blocks with a block entity, what vanilla would send them with a
	 * chunk: the entity's {@code getUpdateTag} (sign text, banner patterns, ... never inventories), so block-entity
	 * renderers can draw them aboard (M9 pass 2). On the client an entry's {@code blockEntity} holds that tag (empty
	 * when the entity shares nothing, e.g. a chest).
	 */
	public static final StreamCodec<RegistryFriendlyByteBuf, ShipStructure> STREAM_CODEC = StreamCodec.of((buf, s) -> {
		List<Entry> list = s.entries;
		BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, list.stream().map(Entry::pos).toList());
		ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY).apply(ByteBufCodecs.list()).encode(buf, list.stream().map(Entry::state).toList());
		net.minecraft.world.phys.Vec3.STREAM_CODEC.encode(buf, s.pivot);
		var tags = s.clientTags(buf.registryAccess());
		buf.writeVarInt(tags.size());
		for (var tag : tags.int2ObjectEntrySet()) {
			buf.writeVarInt(tag.getIntKey());
			ByteBufCodecs.TRUSTED_COMPOUND_TAG.encode(buf, tag.getValue());
		}
	}, buf -> {
		List<BlockPos> positions = BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf);
		List<BlockState> states = ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY).apply(ByteBufCodecs.list()).decode(buf);
		net.minecraft.world.phys.Vec3 pivot = net.minecraft.world.phys.Vec3.STREAM_CODEC.decode(buf);
		it.unimi.dsi.fastutil.ints.Int2ObjectMap<CompoundTag> tags = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
		int n = buf.readVarInt();
		for (int i = 0; i < n; i++) tags.put(buf.readVarInt(), ByteBufCodecs.TRUSTED_COMPOUND_TAG.decode(buf));
		List<Entry> entries = new ArrayList<>(positions.size());
		for (int i = 0; i < positions.size(); i++) {
			entries.add(new Entry(positions.get(i), states.get(i), Optional.ofNullable(tags.get(i))));
		}
		return new ShipStructure(entries, pivot);
	});

	/** Client tags by entry index, computed once per structure (it's immutable) on first send. */
	private it.unimi.dsi.fastutil.ints.@Nullable Int2ObjectMap<CompoundTag> clientTags;

	private it.unimi.dsi.fastutil.ints.Int2ObjectMap<CompoundTag> clientTags(net.minecraft.core.HolderLookup.Provider registries) {
		if (clientTags != null) return clientTags;
		var tags = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<CompoundTag>();
		for (int i = 0; i < entries.size(); i++) {
			CompoundTag tag = clientTag(entries.get(i), registries);
			if (tag != null) tags.put(i, tag);
		}
		clientTags = tags;
		return tags;
	}

	private final List<Entry> entries;
	private final Long2ObjectOpenHashMap<BlockState> states;
	private final Vec3i size;
	private final BlockPos min, max;
	private final net.minecraft.world.phys.Vec3 pivot;
	/** Blocks with collision whose neighbour in that direction is empty: the ship's outer skin (built on first use). */
	private final Map<Direction, List<BlockPos>> outerFaces = new EnumMap<>(Direction.class);

	/** A fresh structure (assembly): the pivot is the centre of the blocks' box. */
	public ShipStructure(List<Entry> entries) {
		this(entries, null);
	}

	public ShipStructure(List<Entry> entries, net.minecraft.world.phys.@Nullable Vec3 pivot) {
		this(List.copyOf(entries), pivot, null, null);
	}

	/**
	 * With {@code states} (and optionally {@code index}) already built for {@code entries} (M9 pass 3: an edit patches
	 * copies of the previous structure's maps instead of rebuilding them from every block).
	 */
	private ShipStructure(List<Entry> entries, net.minecraft.world.phys.@Nullable Vec3 pivot, @Nullable Long2ObjectOpenHashMap<BlockState> states,
			it.unimi.dsi.fastutil.longs.@Nullable Long2IntOpenHashMap index) {
		this.entries = entries;
		this.states = states != null ? states : new Long2ObjectOpenHashMap<>(entries.size());
		this.index = index;
		boolean fill = states == null;
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (Entry entry : this.entries) {
			if (fill) this.states.put(entry.pos().asLong(), entry.state());
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
		// M9 pass 3: patch copies of the entry list and both maps (array copies) instead of rebuilding them. Removals
		// swap the last entry into the hole; changes apply in position order so server and clients (which apply the
		// same delta) end up with identical entry orders (synced throttles refer to entry indices).
		indexOf(BlockPos.ZERO); // make sure our index exists
		ArrayList<Entry> updated = new ArrayList<>(entries.size() + changes.size());
		updated.addAll(entries);
		var idx = index.clone();
		var st = states.clone();
		long[] keys = new long[changes.size()];
		int n = 0;
		for (var change : changes.entrySet()) keys[n++] = change.getKey().asLong();
		java.util.Arrays.sort(keys);
		Map<Long, @Nullable Entry> byKey = new java.util.HashMap<>();
		for (var change : changes.entrySet()) byKey.put(change.getKey().asLong(), change.getValue());
		for (long key : keys) {
			Entry entry = byKey.get(key);
			int at = idx.get(key);
			if (entry == null) {
				if (at < 0) continue;
				int last = updated.size() - 1;
				Entry moved = updated.remove(last);
				if (at != last) {
					updated.set(at, moved);
					idx.put(moved.pos().asLong(), at);
				}
				idx.remove(key);
				st.remove(key);
			} else {
				if (at >= 0) updated.set(at, entry);
				else {
					idx.put(key, updated.size());
					updated.add(entry);
				}
				st.put(key, entry.state());
			}
		}
		return new ShipStructure(java.util.Collections.unmodifiableList(updated), pivot, st, idx);
	}

	public Optional<CompoundTag> blockEntity(BlockPos local) {
		Entry entry = entry(local);
		return entry == null ? Optional.empty() : entry.blockEntity();
	}

	/** Entry index per cell, built on first use (structures are immutable). */
	private it.unimi.dsi.fastutil.longs.@Nullable Long2IntOpenHashMap index;

	/** The entry at a local cell, or null (M9 pass 3: constant time; was a scan). */
	public @Nullable Entry entry(BlockPos local) {
		int i = indexOf(local);
		return i < 0 ? null : entries.get(i);
	}

	/** Index of the entry at a local cell in {@link #entries()}, or -1. */
	public int indexOf(BlockPos local) {
		var map = index;
		if (map == null) {
			map = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(entries.size());
			map.defaultReturnValue(-1);
			for (int i = 0; i < entries.size(); i++) map.put(entries.get(i).pos().asLong(), i);
			index = map;
		}
		return map.get(local.asLong());
	}

	/**
	 * What clients may know about a block entity aboard (M9 pass 2): vanilla's {@code getUpdateTag} (sign text, banner
	 * patterns; never inventories). Empty for block entities that share nothing; null for blocks without one.
	 */
	public static @Nullable CompoundTag clientTag(Entry entry, net.minecraft.core.HolderLookup.Provider registries) {
		if (!(entry.state().getBlock() instanceof net.minecraft.world.level.block.EntityBlock)) return null;
		if (entry.blockEntity().isEmpty()) return new CompoundTag();
		var be = net.minecraft.world.level.block.entity.BlockEntity.loadStatic(entry.pos(), entry.state(), entry.blockEntity().get(), registries);
		return be == null ? new CompoundTag() : be.getUpdateTag(registries);
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
	public synchronized List<BlockPos> outerFace(Direction direction) {
		return outerFaces.computeIfAbsent(direction, dir -> {
			List<BlockPos> face = new ArrayList<>();
			for (Entry entry : entries) {
				if (hasCollision(entry.pos()) && !hasCollision(entry.pos().relative(dir))) face.add(entry.pos());
			}
			return List.copyOf(face);
		});
	}
}
