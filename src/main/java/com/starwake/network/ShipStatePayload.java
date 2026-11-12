package com.starwake.network;

import com.starwake.StarwakeMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server -> pilot, every tick (M9 prediction): the ship's authoritative state after applying input {@code seq}:
 * position, orientation (quaternion), velocity (b/t) and angular velocity (ship frame, rad/t).
 */
public record ShipStatePayload(int shipId, int seq, double x, double y, double z, float qx, float qy, float qz, float qw,
		double vx, double vy, double vz, float wx, float wy, float wz) implements CustomPacketPayload {
	public static final Type<ShipStatePayload> TYPE = new Type<>(StarwakeMod.id("ship_state"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShipStatePayload> CODEC = StreamCodec.of((buf, p) -> {
		buf.writeVarInt(p.shipId).writeVarInt(p.seq);
		buf.writeDouble(p.x).writeDouble(p.y).writeDouble(p.z);
		buf.writeFloat(p.qx).writeFloat(p.qy).writeFloat(p.qz).writeFloat(p.qw);
		buf.writeDouble(p.vx).writeDouble(p.vy).writeDouble(p.vz);
		buf.writeFloat(p.wx).writeFloat(p.wy).writeFloat(p.wz);
	}, buf -> new ShipStatePayload(buf.readVarInt(), buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
			buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
			buf.readFloat(), buf.readFloat(), buf.readFloat()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
