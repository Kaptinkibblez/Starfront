package com.starwake.network;

import com.starwake.StarwakeMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client -> server (M9): the player is looking at a cable or pipe; asks for its readout. */
public record LogisticsProbePayload(BlockPos pos) implements CustomPacketPayload {
	public static final Type<LogisticsProbePayload> TYPE = new Type<>(StarwakeMod.id("logistics_probe"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LogisticsProbePayload> CODEC =
			BlockPos.STREAM_CODEC.map(LogisticsProbePayload::new, LogisticsProbePayload::pos).cast();

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
