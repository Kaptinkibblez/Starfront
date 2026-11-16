package com.starwake.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> client (M9): what this player knows about hub markets (Wristcom Trade app): every hub whose terminal they've
 * used or hailed, with the prices as they were then and how long ago that was, the hub's tier and health now, plus the
 * price history of one item at one hub if asked for ({@link Request}).
 */
public record TradeIntelPayload(List<Hub> hubs, String historyHub, String historyItem, List<Float> history) implements CustomPacketPayload {
	public static final Type<TradeIntelPayload> TYPE = new Type<>(StarwakeMod.id("trade_intel"));

	public record Row(Identifier item, long buy, long sell, int stock) {
		static final Codec<Row> CODEC = RecordCodecBuilder.create(i -> i.group(
				Identifier.CODEC.fieldOf("item").forGetter(Row::item),
				Codec.LONG.fieldOf("buy").forGetter(Row::buy),
				Codec.LONG.fieldOf("sell").forGetter(Row::sell),
				Codec.INT.fieldOf("stock").forGetter(Row::stock)).apply(i, Row::new));
	}

	public record Hub(String id, String name, long age, int tier, float health, List<Row> rows) {
		static final Codec<Hub> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("id").forGetter(Hub::id),
				Codec.STRING.fieldOf("name").forGetter(Hub::name),
				Codec.LONG.fieldOf("age").forGetter(Hub::age),
				Codec.INT.fieldOf("tier").forGetter(Hub::tier),
				Codec.FLOAT.fieldOf("health").forGetter(Hub::health),
				Row.CODEC.listOf().fieldOf("rows").forGetter(Hub::rows)).apply(i, Hub::new));
	}

	private static final Codec<TradeIntelPayload> CODEC_ALL = RecordCodecBuilder.create(i -> i.group(
			Hub.CODEC.listOf().fieldOf("hubs").forGetter(TradeIntelPayload::hubs),
			Codec.STRING.fieldOf("history_hub").forGetter(TradeIntelPayload::historyHub),
			Codec.STRING.fieldOf("history_item").forGetter(TradeIntelPayload::historyItem),
			Codec.FLOAT.listOf().fieldOf("history").forGetter(TradeIntelPayload::history)
	).apply(i, TradeIntelPayload::new));
	public static final StreamCodec<RegistryFriendlyByteBuf, TradeIntelPayload> CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC_ALL);

	/** Client -> server: send me my market intel (and this item's history at this hub, if both are set). */
	public record Request(String hub, String item) implements CustomPacketPayload {
		public static final Type<Request> TYPE = new Type<>(StarwakeMod.id("trade_intel_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(128), Request::hub, ByteBufCodecs.stringUtf8(128), Request::item, Request::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
