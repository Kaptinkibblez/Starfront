package com.starwake.network;

import com.starwake.StarwakeMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> server: mining a block on an assembled ship (Milestone 4). The client times the
 * mining like vanilla does and reports start, abort and finish; the server checks permission,
 * reach and that enough time passed ({@code ShipEditing}).
 *
 * @param action {@code START}, {@code ABORT} or {@code FINISH}
 */
public record ShipEditPayload(int shipId, int action, BlockPos local) implements CustomPacketPayload {
	public static final int START = 0, ABORT = 1, FINISH = 2;
	public static final Type<ShipEditPayload> TYPE = new Type<>(StarwakeMod.id("ship_edit"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShipEditPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, ShipEditPayload::shipId,
			ByteBufCodecs.VAR_INT, ShipEditPayload::action,
			BlockPos.STREAM_CODEC, ShipEditPayload::local,
			ShipEditPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
