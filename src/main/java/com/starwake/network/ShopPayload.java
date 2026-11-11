package com.starwake.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server -> client (M7): a hub outfitter's stock and your prices (opens or refreshes the shop). */
public record ShopPayload(String hub, Component title, String faction, long credits, List<Line> lines) implements CustomPacketPayload {
	public static final Type<ShopPayload> TYPE = new Type<>(StarwakeMod.id("shop"));

	/** One item for sale. {@code locked}: your reputation is below {@code rep}. */
	public record Line(Identifier item, int count, long price, int rep, boolean locked) {
		public static final Codec<Line> CODEC = RecordCodecBuilder.create(i -> i.group(
				Identifier.CODEC.fieldOf("item").forGetter(Line::item),
				Codec.INT.fieldOf("count").forGetter(Line::count),
				Codec.LONG.fieldOf("price").forGetter(Line::price),
				Codec.INT.fieldOf("rep").forGetter(Line::rep),
				Codec.BOOL.fieldOf("locked").forGetter(Line::locked)
		).apply(i, Line::new));
	}

	private static final Codec<ShopPayload> CODEC_ALL = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("hub").forGetter(ShopPayload::hub),
			ComponentSerialization.CODEC.fieldOf("title").forGetter(ShopPayload::title),
			Codec.STRING.fieldOf("faction").forGetter(ShopPayload::faction),
			Codec.LONG.fieldOf("credits").forGetter(ShopPayload::credits),
			Line.CODEC.listOf().fieldOf("lines").forGetter(ShopPayload::lines)
	).apply(i, ShopPayload::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShopPayload> CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC_ALL);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
