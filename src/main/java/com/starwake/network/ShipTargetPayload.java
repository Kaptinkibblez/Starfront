package com.starwake.network;

import com.starwake.StarwakeMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client -> server (M11): the pilot locks a target (entity id) or clears it (-1). The server checks it's a ship or a living thing. */
public record ShipTargetPayload(int target) implements CustomPacketPayload {
	public static final Type<ShipTargetPayload> TYPE = new Type<>(StarwakeMod.id("ship_target"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShipTargetPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, ShipTargetPayload::target,
			ShipTargetPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
