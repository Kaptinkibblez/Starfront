package com.starwake.network;

import com.starwake.StarwakeMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server -> client on join (M6): the galaxy seed, derived from (but not revealing) the world
 * seed. With the synced star class registry, the client generates the galaxy map itself.
 */
public record GalaxyInfoPayload(long seed) implements CustomPacketPayload {
	public static final Type<GalaxyInfoPayload> TYPE = new Type<>(StarwakeMod.id("galaxy_info"));
	public static final StreamCodec<RegistryFriendlyByteBuf, GalaxyInfoPayload> CODEC =
			ByteBufCodecs.VAR_LONG.map(GalaxyInfoPayload::new, GalaxyInfoPayload::seed).cast();

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
