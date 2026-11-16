package com.starwake.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server -> client (M9): one line for the Comms inbox. {@code channel} = who the conversation is with (a player name,
 * "hub:&lt;id&gt;" or "ship:&lt;entity id&gt;"), {@code from} = the speaker, {@code kind} = msg / sent / hail / hub / error.
 */
public record CommsMessagePayload(String channel, Component from, Component text, String kind) implements CustomPacketPayload {
	public static final Type<CommsMessagePayload> TYPE = new Type<>(StarwakeMod.id("comms_message"));
	private static final Codec<CommsMessagePayload> CODEC_ALL = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("channel").forGetter(CommsMessagePayload::channel),
			ComponentSerialization.CODEC.fieldOf("from").forGetter(CommsMessagePayload::from),
			ComponentSerialization.CODEC.fieldOf("text").forGetter(CommsMessagePayload::text),
			Codec.STRING.fieldOf("kind").forGetter(CommsMessagePayload::kind)
	).apply(i, CommsMessagePayload::new));
	public static final StreamCodec<RegistryFriendlyByteBuf, CommsMessagePayload> CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC_ALL);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
