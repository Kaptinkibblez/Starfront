package com.starwake.network;

import com.starwake.StarwakeMod;
import com.starwake.ship.data.ShipPhysics;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server -> client, on join and after a reload (M9): the ship physics tuning, so clients predict with the same numbers. */
public record ShipPhysicsPayload(ShipPhysics physics) implements CustomPacketPayload {
	public static final Type<ShipPhysicsPayload> TYPE = new Type<>(StarwakeMod.id("ship_physics"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShipPhysicsPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.fromCodec(ShipPhysics.CODEC), ShipPhysicsPayload::physics,
			ShipPhysicsPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
