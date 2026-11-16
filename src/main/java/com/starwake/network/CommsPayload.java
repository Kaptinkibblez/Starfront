package com.starwake.network;

import com.starwake.StarwakeMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> server (M9): a Wristcom Comms action. {@code msg}: text to the player named {@code target}; {@code hail_hub}:
 * ask hub {@code target} for docking information; {@code hail_ship}: hail the ship with entity id {@code target}.
 */
public record CommsPayload(String action, String target, String text) implements CustomPacketPayload {
	public static final Type<CommsPayload> TYPE = new Type<>(StarwakeMod.id("comms"));
	public static final StreamCodec<RegistryFriendlyByteBuf, CommsPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(16), CommsPayload::action,
			ByteBufCodecs.stringUtf8(64), CommsPayload::target,
			ByteBufCodecs.stringUtf8(256), CommsPayload::text,
			CommsPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
