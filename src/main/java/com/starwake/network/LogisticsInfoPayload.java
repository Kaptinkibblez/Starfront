package com.starwake.network;

import com.starwake.StarwakeMod;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server -> client (M9): the readout lines for a cable or pipe the player looks at. */
public record LogisticsInfoPayload(BlockPos pos, List<Component> lines) implements CustomPacketPayload {
	public static final Type<LogisticsInfoPayload> TYPE = new Type<>(StarwakeMod.id("logistics_info"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LogisticsInfoPayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, LogisticsInfoPayload::pos,
			ComponentSerialization.TRUSTED_STREAM_CODEC.apply(ByteBufCodecs.list(8)), LogisticsInfoPayload::lines,
			LogisticsInfoPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
