package com.starwake.network;

import com.starwake.StarwakeMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> server: the ship owner manages access from the Wristcom's Crew app (3j). The
 * server checks the sender owns the ship and is near it.
 *
 * @param action one of {@code ADD}, {@code REMOVE} (crew, by player name), {@code LOCK}, {@code UNLOCK}
 */
public record ShipCrewPayload(int shipId, int action, String name) implements CustomPacketPayload {
	public static final int ADD = 0, REMOVE = 1, LOCK = 2, UNLOCK = 3;
	public static final Type<ShipCrewPayload> TYPE = new Type<>(StarwakeMod.id("ship_crew"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShipCrewPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, ShipCrewPayload::shipId,
			ByteBufCodecs.VAR_INT, ShipCrewPayload::action,
			ByteBufCodecs.stringUtf8(32), ShipCrewPayload::name,
			ShipCrewPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
