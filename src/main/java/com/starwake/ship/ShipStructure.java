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
 * The blocks of an assembled ship, in ship-local coordinates (0,0,0 = the min corner of the
 * ship's bounding box). Immutable after creation (in-flight editing is Milestone 4).
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

	public static final Codec<ShipStructure> CODEC = Entry.CODEC.listOf().xmap(ShipStructure::new, ShipStructure::entries);

	/** Network form: positions and block states only. */
	public static final StreamCodec<RegistryFriendlyByteBuf, ShipStructure> STREAM_CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()), s -> s.entries.stream().map(Entry::pos).toList(),
			ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY).apply(ByteBufCodecs.list()), s -> s.entries.stream().map(Entry::state).toList(),
			(positions, states) -> {
				List<Entry> entries = new ArrayList<>(positions.size());
				for (int i = 0; i < positions.size(); i++) {
					entries.add(new Entry(positions.get(i), states.get(i), Optional.empty()));
				}
				return new ShipStructure(entries);
			});

	private final List<Entry> entries;
	private final Long2ObjectMap<BlockState> states = new Long2ObjectOpenHashMap<>();
	private final Vec3i size;
	/** Blocks with collision whose neighbour in that direction is empty: the ship's outer skin. */
	private final Map<Direction, List<BlockPos>> outerFaces = new EnumMap<>(Direction.class);

	public ShipStructure(List<Entry> entries) {
		this.entries = List.copyOf(entries);
		int maxX = 0, maxY = 0, maxZ = 0;
		for (Entry entry : this.entries) {
			states.put(entry.pos().asLong(), entry.state());
			maxX = Math.max(maxX, entry.pos().getX());
			maxY = Math.max(maxY, entry.pos().getY());
			maxZ = Math.max(maxZ, entry.pos().getZ());
		}
		this.size = new Vec3i(maxX + 1, maxY + 1, maxZ + 1);
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

	/** A copy with one block's state and/or block-entity data replaced (structures are immutable). */
	public ShipStructure with(BlockPos local, BlockState state, Optional<CompoundTag> blockEntity) {
		List<Entry> updated = new ArrayList<>(entries.size());
		for (Entry entry : entries) {
			updated.add(entry.pos().equals(local) ? new Entry(local, state, blockEntity) : entry);
		}
		return new ShipStructure(updated);
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
