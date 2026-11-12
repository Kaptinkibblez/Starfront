package com.starwake.network;

import com.starwake.StarwakeMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> server, every tick while walking aboard a ship (M9): where the player stands in the ship's own frame.
 * The server then places the player from its own copy of the ship each tick, so a walker never lags behind, falls
 * through a deck or jitters against it on other players' screens. {@code shipId} -1 = no longer aboard.
 */
public record ShipFootingPayload(int shipId, double x, double y, double z, boolean onGround) implements CustomPacketPayload {
	public static final Type<ShipFootingPayload> TYPE = new Type<>(StarwakeMod.id("ship_footing"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShipFootingPayload> CODEC = StreamCodec.of((buf, p) -> {
		buf.writeVarInt(p.shipId);
		buf.writeDouble(p.x).writeDouble(p.y).writeDouble(p.z);
		buf.writeBoolean(p.onGround);
	}, buf -> new ShipFootingPayload(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readBoolean()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
