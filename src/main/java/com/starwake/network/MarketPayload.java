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

/** Server -> client (M7): a hub's commodity market (opens or refreshes the trade screen). */
public record MarketPayload(String hub, Component title, String faction, long credits, List<Quote> quotes, List<Offer> industrial)
		implements CustomPacketPayload {
	public static final Type<MarketPayload> TYPE = new Type<>(StarwakeMod.id("market"));

	/** One commodity: stock, capacity, your buy and sell prices, whether the hub produces it, how many you carry. */
	public record Quote(Identifier item, int stock, int capacity, long buy, long sell, boolean producer, int have) {
		public static final Codec<Quote> CODEC = RecordCodecBuilder.create(i -> i.group(
				Identifier.CODEC.fieldOf("item").forGetter(Quote::item),
				Codec.INT.fieldOf("stock").forGetter(Quote::stock),
				Codec.INT.fieldOf("capacity").forGetter(Quote::capacity),
				Codec.LONG.fieldOf("buy").forGetter(Quote::buy),
				Codec.LONG.fieldOf("sell").forGetter(Quote::sell),
				Codec.BOOL.fieldOf("producer").forGetter(Quote::producer),
				Codec.INT.fieldOf("have").forGetter(Quote::have)
		).apply(i, Quote::new));
	}

	/** M9: an industrial product in the player's inventory the hub will buy: slot, the stack, credits per item, category. */
	public record Offer(int slot, net.minecraft.world.item.ItemStack stack, long price, String category) {
		public static final Codec<Offer> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.INT.fieldOf("slot").forGetter(Offer::slot),
				net.minecraft.world.item.ItemStack.CODEC.fieldOf("stack").forGetter(Offer::stack),
				Codec.LONG.fieldOf("price").forGetter(Offer::price),
				Codec.STRING.fieldOf("category").forGetter(Offer::category)
		).apply(i, Offer::new));
	}

	private static final Codec<MarketPayload> CODEC_ALL = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("hub").forGetter(MarketPayload::hub),
			ComponentSerialization.CODEC.fieldOf("title").forGetter(MarketPayload::title),
			Codec.STRING.fieldOf("faction").forGetter(MarketPayload::faction),
			Codec.LONG.fieldOf("credits").forGetter(MarketPayload::credits),
			Quote.CODEC.listOf().fieldOf("quotes").forGetter(MarketPayload::quotes),
			Offer.CODEC.listOf().fieldOf("industrial").forGetter(MarketPayload::industrial)
	).apply(i, MarketPayload::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, MarketPayload> CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC_ALL);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
