package com.starwake.network;

import com.starwake.StarwakeMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client -> server: the pilot's power split (percent to engines, shields, quantum). */
public record ShipPowerPayload(int engines, int shields, int quantum) implements CustomPacketPayload {
	public static final Type<ShipPowerPayload> TYPE = new Type<>(StarwakeMod.id("ship_power"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShipPowerPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, ShipPowerPayload::engines,
			ByteBufCodecs.VAR_INT, ShipPowerPayload::shields,
			ByteBufCodecs.VAR_INT, ShipPowerPayload::quantum,
			ShipPowerPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
