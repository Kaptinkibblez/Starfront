package com.starwake.network;

import com.starwake.StarwakeMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client -> server (M9 pass 3): a structure delta didn't fit what this client holds; please send the whole ship. */
public record ShipResyncPayload(int entityId) implements CustomPacketPayload {
	public static final Type<ShipResyncPayload> TYPE = new Type<>(StarwakeMod.id("ship_resync"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShipResyncPayload> CODEC =
			StreamCodec.composite(ByteBufCodecs.VAR_INT, ShipResyncPayload::entityId, ShipResyncPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
