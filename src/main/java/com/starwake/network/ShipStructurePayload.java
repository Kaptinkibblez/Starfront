package com.starwake.network;

import com.starwake.StarwakeMod;
import com.starwake.ship.ShipStructure;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server -> client: all the blocks of a ship entity, sent when a player starts tracking it, after big edits, and when
 * a client asks for a resync. {@code version} is the structure's edit count; later {@link ShipDeltaPayload}s build on it.
 */
public record ShipStructurePayload(int entityId, int version, ShipStructure structure) implements CustomPacketPayload {
	public static final Type<ShipStructurePayload> TYPE = new Type<>(StarwakeMod.id("ship_structure"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShipStructurePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, ShipStructurePayload::entityId,
			ByteBufCodecs.VAR_INT, ShipStructurePayload::version,
			ShipStructure.STREAM_CODEC, ShipStructurePayload::structure,
			ShipStructurePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
