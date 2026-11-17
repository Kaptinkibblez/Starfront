package com.starwake.network;

import com.starwake.StarwakeMod;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> server: the pilot's power pips per subsystem (M10; {@code ShipSystems.Subsystem} order). The server refits
 * them to the ship ({@code PowerPips.normalise}).
 */
public record ShipPowerPayload(List<Integer> pips) implements CustomPacketPayload {
	public static final Type<ShipPowerPayload> TYPE = new Type<>(StarwakeMod.id("ship_power"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShipPowerPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(8)), ShipPowerPayload::pips,
			ShipPowerPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
