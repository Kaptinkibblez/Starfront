package com.starwake.network;

import com.starwake.StarwakeMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> server: the player right-clicked a block on an assembled ship (found by a
 * client-side raycast in ship space). The server re-validates reach and the block, then
 * either uses it (helm, seat, door, chest, fuel tank) or places the held block against
 * {@code face} (Milestone 4).
 *
 * @param face the clicked face (ship-local)
 * @param hit  where the ray hit, in ship-local coordinates
 */
public record ShipInteractPayload(int shipId, BlockPos local, net.minecraft.core.Direction face, net.minecraft.world.phys.Vec3 hit)
		implements CustomPacketPayload {
	public static final Type<ShipInteractPayload> TYPE = new Type<>(StarwakeMod.id("ship_interact"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShipInteractPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, ShipInteractPayload::shipId,
			BlockPos.STREAM_CODEC, ShipInteractPayload::local,
			net.minecraft.core.Direction.STREAM_CODEC, ShipInteractPayload::face,
			net.minecraft.world.phys.Vec3.STREAM_CODEC, ShipInteractPayload::hit,
			ShipInteractPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
