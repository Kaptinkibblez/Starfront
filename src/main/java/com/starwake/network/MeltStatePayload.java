package com.starwake.network;

import com.starwake.StarwakeMod;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server -> client (M8d-1): a melting furnace's bath for the player looking at its screen: element
 * kg, slag species kg, liquidus and furnace limit (C), target grade id, tapping, vacuum, status.
 */
public record MeltStatePayload(BlockPos pos, Map<String, Double> metal, Map<String, Double> slag, double liquidus, double maxTemperature,
		String target, boolean tapping, boolean vacuum, int status) implements CustomPacketPayload {
	public static final Type<MeltStatePayload> TYPE = new Type<>(StarwakeMod.id("melt_state"));
	private static final StreamCodec<RegistryFriendlyByteBuf, Map<String, Double>> MAP = ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8,
			ByteBufCodecs.DOUBLE);
	public static final StreamCodec<RegistryFriendlyByteBuf, MeltStatePayload> CODEC = StreamCodec.of((buf, p) -> {
		BlockPos.STREAM_CODEC.encode(buf, p.pos);
		MAP.encode(buf, p.metal);
		MAP.encode(buf, p.slag);
		buf.writeDouble(p.liquidus);
		buf.writeDouble(p.maxTemperature);
		buf.writeUtf(p.target);
		buf.writeBoolean(p.tapping);
		buf.writeBoolean(p.vacuum);
		buf.writeVarInt(p.status);
	}, buf -> new MeltStatePayload(BlockPos.STREAM_CODEC.decode(buf), MAP.decode(buf), MAP.decode(buf), buf.readDouble(), buf.readDouble(),
			buf.readUtf(), buf.readBoolean(), buf.readBoolean(), buf.readVarInt()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
