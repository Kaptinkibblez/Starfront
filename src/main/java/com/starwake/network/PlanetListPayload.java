package com.starwake.network;

import com.starwake.StarwakeMod;
import com.starwake.planet.PlanetRef;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server -> client on join: the procedural planets of this world (M5). Clients have neither the
 * world seed nor the archetype data, so the server sends the finished definitions; the client
 * merges them with the synced planet registry ({@code Planets.all}).
 */
public record PlanetListPayload(List<PlanetRef> planets) implements CustomPacketPayload {
	public static final Type<PlanetListPayload> TYPE = new Type<>(StarwakeMod.id("planet_list"));

	public static final StreamCodec<RegistryFriendlyByteBuf, PlanetListPayload> CODEC =
			ByteBufCodecs.fromCodecWithRegistries(PlanetRef.CODEC.listOf()).map(PlanetListPayload::new, PlanetListPayload::planets);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
