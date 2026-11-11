package com.starwake.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import com.starwake.trade.Contract;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server -> client (M7): a hub's Contract Board: today's offers and your contracts you can hand in here. */
public record ContractBoardPayload(String hub, Component title, String faction, long credits, List<Contract> offers,
		List<Contract> completable) implements CustomPacketPayload {
	public static final Type<ContractBoardPayload> TYPE = new Type<>(StarwakeMod.id("contract_board"));

	private static final Codec<ContractBoardPayload> CODEC_ALL = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("hub").forGetter(ContractBoardPayload::hub),
			ComponentSerialization.CODEC.fieldOf("title").forGetter(ContractBoardPayload::title),
			Codec.STRING.fieldOf("faction").forGetter(ContractBoardPayload::faction),
			Codec.LONG.fieldOf("credits").forGetter(ContractBoardPayload::credits),
			Contract.CODEC.listOf().fieldOf("offers").forGetter(ContractBoardPayload::offers),
			Contract.CODEC.listOf().fieldOf("completable").forGetter(ContractBoardPayload::completable)
	).apply(i, ContractBoardPayload::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, ContractBoardPayload> CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC_ALL);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
