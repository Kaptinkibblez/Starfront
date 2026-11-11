package com.starwake.network;

import com.starwake.StarwakeMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client -> server (M6): "what planets does this system have?" for the galaxy map. */
public record SystemInfoRequestPayload(String system) implements CustomPacketPayload {
	public static final Type<SystemInfoRequestPayload> TYPE = new Type<>(StarwakeMod.id("system_info_request"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SystemInfoRequestPayload> CODEC =
			ByteBufCodecs.STRING_UTF8.map(SystemInfoRequestPayload::new, SystemInfoRequestPayload::system).cast();

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
