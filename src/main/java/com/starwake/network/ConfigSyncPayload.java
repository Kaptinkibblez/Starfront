package com.starwake.network;

import com.starwake.StarwakeMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server -> client (M9): the server config values clients need (the orbit speed, for placing planets). */
public record ConfigSyncPayload(double orbitSpeed) implements CustomPacketPayload {
	public static final Type<ConfigSyncPayload> TYPE = new Type<>(StarwakeMod.id("config_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ConfigSyncPayload> CODEC =
			ByteBufCodecs.DOUBLE.map(ConfigSyncPayload::new, ConfigSyncPayload::orbitSpeed).cast();

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
