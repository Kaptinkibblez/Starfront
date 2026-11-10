package com.starwake.network;

import com.starwake.StarwakeMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> server: the player right-clicked a block on an assembled ship (found by a
 * client-side raycast in ship space). The server re-validates reach and the block.
 */
public record ShipInteractPayload(int shipId, BlockPos local) implements CustomPacketPayload {
	public static final Type<ShipInteractPayload> TYPE = new Type<>(StarwakeMod.id("ship_interact"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShipInteractPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, ShipInteractPayload::shipId,
			BlockPos.STREAM_CODEC, ShipInteractPayload::local,
			ShipInteractPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
