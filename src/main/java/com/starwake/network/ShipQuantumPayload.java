package com.starwake.network;

import com.starwake.StarwakeMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> server: the pilot's quantum drive key (B). A tap toggles quantum mode; holding it
 * engages the jump. The server checks spool, alignment, obstruction and fuel itself.
 *
 * @param engage false = toggle quantum mode, true = engage
 */
public record ShipQuantumPayload(boolean engage) implements CustomPacketPayload {
	public static final Type<ShipQuantumPayload> TYPE = new Type<>(StarwakeMod.id("ship_quantum"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShipQuantumPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, ShipQuantumPayload::engage,
			ShipQuantumPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
