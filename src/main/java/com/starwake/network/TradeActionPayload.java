package com.starwake.network;

import com.starwake.StarwakeMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> server (M7): a terminal action. The server checks the player is inside that hub.
 *
 * @param action {@code buy}/{@code sell} (market: {@code arg} = item id, {@code count}),
 *               {@code shop} (outfitter: {@code count} = line index),
 *               {@code accept}/{@code complete}/{@code abandon} (contracts: {@code arg} = contract id)
 */
public record TradeActionPayload(String hub, String action, String arg, int count) implements CustomPacketPayload {
	public static final Type<TradeActionPayload> TYPE = new Type<>(StarwakeMod.id("trade_action"));
	public static final StreamCodec<RegistryFriendlyByteBuf, TradeActionPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, TradeActionPayload::hub,
			ByteBufCodecs.STRING_UTF8, TradeActionPayload::action,
			ByteBufCodecs.STRING_UTF8, TradeActionPayload::arg,
			ByteBufCodecs.VAR_INT, TradeActionPayload::count,
			TradeActionPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
