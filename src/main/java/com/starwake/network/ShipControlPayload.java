package com.starwake.network;

import com.starwake.StarwakeMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> server: ship controls that vanilla's input packet doesn't carry (roll, free-look,
 * leave seat). Sent only when they change. WASD/Space/Ctrl/Shift come from vanilla input.
 *
 * @param controls bit set of {@code ShipEntity.CONTROL_*}
 * @param leave    true to leave the seat
 * @param stickX   joystick mode (M9): yaw deflection -1..1 (right positive)
 * @param stickY   joystick mode: pitch deflection -1..1 (nose down positive)
 */
public record ShipControlPayload(int controls, boolean leave, float stickX, float stickY) implements CustomPacketPayload {
	public ShipControlPayload(int controls, boolean leave) {
		this(controls, leave, 0, 0);
	}

	public static final Type<ShipControlPayload> TYPE = new Type<>(StarwakeMod.id("ship_control"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShipControlPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, ShipControlPayload::controls,
			ByteBufCodecs.BOOL, ShipControlPayload::leave,
			ByteBufCodecs.FLOAT, ShipControlPayload::stickX,
			ByteBufCodecs.FLOAT, ShipControlPayload::stickY,
			ShipControlPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
