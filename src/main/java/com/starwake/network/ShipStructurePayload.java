package com.starwake.network;

import com.starwake.StarwakeMod;
import com.starwake.ship.ShipStructure;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server -> client: the blocks of a ship entity, sent when a player starts tracking it. */
public record ShipStructurePayload(int entityId, ShipStructure structure) implements CustomPacketPayload {
	public static final Type<ShipStructurePayload> TYPE = new Type<>(StarwakeMod.id("ship_structure"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShipStructurePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, ShipStructurePayload::entityId,
			ShipStructure.STREAM_CODEC, ShipStructurePayload::structure,
			ShipStructurePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
