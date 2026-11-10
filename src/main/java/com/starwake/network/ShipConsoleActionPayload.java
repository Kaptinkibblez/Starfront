package com.starwake.network;

import com.starwake.StarwakeMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client -> server: a button press in the Ship Console. The server re-validates everything. */
public record ShipConsoleActionPayload(BlockPos helm, Action action) implements CustomPacketPayload {
	public enum Action { RESCAN, ASSEMBLE }

	public static final Type<ShipConsoleActionPayload> TYPE = new Type<>(StarwakeMod.id("ship_console_action"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShipConsoleActionPayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, ShipConsoleActionPayload::helm,
			ByteBufCodecs.idMapper(i -> Action.values()[i], Action::ordinal), ShipConsoleActionPayload::action,
			ShipConsoleActionPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
