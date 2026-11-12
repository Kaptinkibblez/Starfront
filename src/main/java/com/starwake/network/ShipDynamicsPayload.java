package com.starwake.network;

import com.starwake.StarwakeMod;
import com.starwake.ship.systems.ShipDynamics;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server -> clients tracking a ship (M9): its rigid-body model (mass, centre of mass, inertia, thrusters, wings) as the
 * server computed it from ship stats and part materials, so a pilot's client can predict the flight exactly.
 */
public record ShipDynamicsPayload(int shipId, byte[] model) implements CustomPacketPayload {
	public static final Type<ShipDynamicsPayload> TYPE = new Type<>(StarwakeMod.id("ship_dynamics"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShipDynamicsPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, ShipDynamicsPayload::shipId,
			ByteBufCodecs.BYTE_ARRAY, ShipDynamicsPayload::model,
			ShipDynamicsPayload::new);

	public static ShipDynamicsPayload of(int shipId, ShipDynamics dynamics) {
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		dynamics.write(buf);
		byte[] bytes = new byte[buf.readableBytes()];
		buf.readBytes(bytes);
		return new ShipDynamicsPayload(shipId, bytes);
	}

	public ShipDynamics read() {
		return ShipDynamics.read(new FriendlyByteBuf(Unpooled.wrappedBuffer(model)));
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
