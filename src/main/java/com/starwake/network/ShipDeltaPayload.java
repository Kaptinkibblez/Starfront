package com.starwake.network;

import com.starwake.StarwakeMod;
import com.starwake.ship.ShipStructure;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Server -> clients tracking a ship (M9 pass 3): only the cells an edit changed, so placing a block on a 262k-block
 * ship sends a few bytes instead of the whole ship. {@code version} is the structure version after the change; a
 * client that doesn't hold {@code version - 1} asks for a full resync ({@link ShipResyncPayload}). A removed cell has
 * no state. Block entities travel as their client tags, like {@link ShipStructurePayload}.
 */
public record ShipDeltaPayload(int entityId, int version, List<Cell> cells) implements CustomPacketPayload {
	public static final Type<ShipDeltaPayload> TYPE = new Type<>(StarwakeMod.id("ship_delta"));

	/** One changed cell: null state = removed; tag = client tag for a block entity (or null). */
	public record Cell(BlockPos pos, @Nullable BlockState state, @Nullable CompoundTag tag) {}

	public static final StreamCodec<RegistryFriendlyByteBuf, ShipDeltaPayload> CODEC = StreamCodec.of((buf, p) -> {
		buf.writeVarInt(p.entityId);
		buf.writeVarInt(p.version);
		buf.writeVarInt(p.cells.size());
		for (Cell cell : p.cells) {
			buf.writeBlockPos(cell.pos);
			buf.writeVarInt(cell.state == null ? 0 : Block.BLOCK_STATE_REGISTRY.getId(cell.state) + 1);
			buf.writeBoolean(cell.tag != null);
			if (cell.tag != null) ByteBufCodecs.TRUSTED_COMPOUND_TAG.encode(buf, cell.tag);
		}
	}, buf -> {
		int entity = buf.readVarInt(), version = buf.readVarInt(), n = buf.readVarInt();
		List<Cell> cells = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			BlockPos pos = buf.readBlockPos();
			int id = buf.readVarInt();
			CompoundTag tag = buf.readBoolean() ? ByteBufCodecs.TRUSTED_COMPOUND_TAG.decode(buf) : null;
			cells.add(new Cell(pos, id == 0 ? null : Block.BLOCK_STATE_REGISTRY.byId(id - 1), tag));
		}
		return new ShipDeltaPayload(entity, version, cells);
	});

	/** Server: the delta for these changed cells of {@code next}. */
	public static ShipDeltaPayload of(int entityId, int version, ShipStructure next, Map<BlockPos, ShipStructure.@Nullable Entry> changes,
			HolderLookup.Provider registries) {
		List<Cell> cells = new ArrayList<>(changes.size());
		for (var e : changes.entrySet()) {
			ShipStructure.Entry entry = e.getValue();
			cells.add(entry == null ? new Cell(e.getKey(), null, null)
					: new Cell(e.getKey(), entry.state(), ShipStructure.clientTag(entry, registries)));
		}
		return new ShipDeltaPayload(entityId, version, cells);
	}

	/** Client: the cells as structure changes (block entities carry their client tag, like a full structure). */
	public Map<BlockPos, ShipStructure.@Nullable Entry> changes() {
		Map<BlockPos, ShipStructure.@Nullable Entry> out = new LinkedHashMap<>();
		for (Cell cell : cells) {
			out.put(cell.pos, cell.state == null ? null : new ShipStructure.Entry(cell.pos, cell.state, Optional.ofNullable(cell.tag)));
		}
		return out;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
