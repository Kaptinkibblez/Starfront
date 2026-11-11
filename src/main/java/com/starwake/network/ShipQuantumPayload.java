package com.starwake.network;

import com.starwake.StarwakeMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> server: the pilot's mode keys. B: a tap toggles quantum mode, holding engages the
 * jump (the server checks spool, alignment, obstruction and fuel). V: coupled/decoupled.
 * N: landing gear.
 *
 * @param action one of the {@code ACTION_*} values
 */
public record ShipQuantumPayload(int action) implements CustomPacketPayload {
	public static final int ACTION_QUANTUM_TOGGLE = 0, ACTION_QUANTUM_ENGAGE = 1, ACTION_DECOUPLE = 2, ACTION_GEAR = 3;

	public static final Type<ShipQuantumPayload> TYPE = new Type<>(StarwakeMod.id("ship_quantum"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShipQuantumPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, ShipQuantumPayload::action,
			ShipQuantumPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
