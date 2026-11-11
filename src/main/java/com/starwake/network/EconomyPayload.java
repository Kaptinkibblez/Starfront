package com.starwake.network;

import com.starwake.StarwakeMod;
import com.starwake.trade.Contract;
import java.util.List;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server -> client (M7): your credits, reputation per faction and active contracts. */
public record EconomyPayload(long credits, Map<String, Integer> rep, List<Contract> contracts) implements CustomPacketPayload {
	public static final Type<EconomyPayload> TYPE = new Type<>(StarwakeMod.id("economy"));
	public static final StreamCodec<RegistryFriendlyByteBuf, EconomyPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_LONG, EconomyPayload::credits,
			ByteBufCodecs.map(java.util.HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_INT), EconomyPayload::rep,
			Contract.STREAM_CODEC.apply(ByteBufCodecs.list()), EconomyPayload::contracts,
			EconomyPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
