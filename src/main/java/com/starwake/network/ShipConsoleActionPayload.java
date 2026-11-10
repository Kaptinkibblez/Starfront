package com.starwake.network;

import com.starwake.StarwakeMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client -> server: a button press in the Ship Console. The server re-validates everything. */
public record ShipConsoleActionPayload(BlockPos helm, int shipId, Action action) implements CustomPacketPayload {
	/** RESCAN/ASSEMBLE target a world Helm; OPEN_SHIP/DISASSEMBLE target the ship id. */
	public enum Action { RESCAN, ASSEMBLE, OPEN_SHIP, DISASSEMBLE }

	public static final Type<ShipConsoleActionPayload> TYPE = new Type<>(StarwakeMod.id("ship_console_action"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShipConsoleActionPayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, ShipConsoleActionPayload::helm,
			ByteBufCodecs.VAR_INT, ShipConsoleActionPayload::shipId,
			ByteBufCodecs.idMapper(i -> Action.values()[i], Action::ordinal), ShipConsoleActionPayload::action,
			ShipConsoleActionPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
