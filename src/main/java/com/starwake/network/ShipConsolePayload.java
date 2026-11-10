package com.starwake.network;

import com.starwake.StarwakeMod;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server -> client: open (or refresh) the Ship Console for a Helm, carrying the pre-flight
 * report. The server formats every line, so the client screen is a dumb display.
 *
 * @param status per line: ordinal of {@link com.starwake.ship.scan.PreflightReport.Status}
 */
public record ShipConsolePayload(BlockPos helm, List<Integer> status, List<Component> lines,
		boolean canAssemble) implements CustomPacketPayload {
	public static final Type<ShipConsolePayload> TYPE = new Type<>(StarwakeMod.id("ship_console"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShipConsolePayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, ShipConsolePayload::helm,
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(64)), ShipConsolePayload::status,
			ComponentSerialization.TRUSTED_STREAM_CODEC.apply(ByteBufCodecs.list(64)), ShipConsolePayload::lines,
			ByteBufCodecs.BOOL, ShipConsolePayload::canAssemble,
			ShipConsolePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
