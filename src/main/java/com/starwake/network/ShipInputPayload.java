package com.starwake.network;

import com.starwake.StarwakeMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Input;

/**
 * Client -> server, every tick while piloting (M9 prediction): the pilot's input for tick {@code seq}, which the
 * pilot's client has already applied to its own predicted ship. The server flies the ship with exactly this input
 * and echoes {@code seq} back in {@link ShipStatePayload} so the client can compare and correct.
 *
 * @param controls roll/free-look/joystick bits ({@code ShipEntity.CONTROL_*})
 * @param look     the pilot's view direction (world), for mouse-aim steering
 */
public record ShipInputPayload(int shipId, int seq, Input input, int controls, float stickX, float stickY,
		float lookX, float lookY, float lookZ) implements CustomPacketPayload {
	public static final Type<ShipInputPayload> TYPE = new Type<>(StarwakeMod.id("ship_input"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShipInputPayload> CODEC = StreamCodec.of((buf, p) -> {
		buf.writeVarInt(p.shipId);
		buf.writeVarInt(p.seq);
		Input.STREAM_CODEC.encode(buf, p.input);
		buf.writeVarInt(p.controls);
		buf.writeFloat(p.stickX).writeFloat(p.stickY);
		buf.writeFloat(p.lookX).writeFloat(p.lookY).writeFloat(p.lookZ);
	}, buf -> new ShipInputPayload(buf.readVarInt(), buf.readVarInt(), Input.STREAM_CODEC.decode(buf), buf.readVarInt(),
			buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
