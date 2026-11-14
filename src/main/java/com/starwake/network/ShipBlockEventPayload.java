package com.starwake.network;

import com.starwake.StarwakeMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server -> clients tracking a ship (M9 pass 2): a block event ran aboard (a chest lid opening or closing, a bell),
 * at a ship-local position. Vanilla only sends block events to players in the shipyard dimension, which is nobody,
 * so the client replays it on its copy of that block entity.
 */
public record ShipBlockEventPayload(int shipId, BlockPos local, int a, int b) implements CustomPacketPayload {
	public static final Type<ShipBlockEventPayload> TYPE = new Type<>(StarwakeMod.id("ship_block_event"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShipBlockEventPayload> CODEC = StreamCodec.of((buf, p) -> {
		buf.writeVarInt(p.shipId);
		buf.writeBlockPos(p.local);
		buf.writeVarInt(p.a);
		buf.writeVarInt(p.b);
	}, buf -> new ShipBlockEventPayload(buf.readVarInt(), buf.readBlockPos(), buf.readVarInt(), buf.readVarInt()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
