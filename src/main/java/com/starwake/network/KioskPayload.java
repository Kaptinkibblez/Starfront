package com.starwake.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/** Server -> client (M9): a Trade Kiosk's stock and prices (opens or refreshes the kiosk screen). */
public record KioskPayload(BlockPos pos, String owner, boolean isOwner, long credits, List<Line> lines) implements CustomPacketPayload {
	public static final Type<KioskPayload> TYPE = new Type<>(StarwakeMod.id("kiosk"));

	public record Line(int slot, ItemStack stack, long price) {
		static final Codec<Line> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.INT.fieldOf("slot").forGetter(Line::slot),
				ItemStack.CODEC.fieldOf("stack").forGetter(Line::stack),
				Codec.LONG.fieldOf("price").forGetter(Line::price)).apply(i, Line::new));
	}

	private static final Codec<KioskPayload> CODEC_ALL = RecordCodecBuilder.create(i -> i.group(
			BlockPos.CODEC.fieldOf("pos").forGetter(KioskPayload::pos),
			Codec.STRING.fieldOf("owner").forGetter(KioskPayload::owner),
			Codec.BOOL.fieldOf("is_owner").forGetter(KioskPayload::isOwner),
			Codec.LONG.fieldOf("credits").forGetter(KioskPayload::credits),
			Line.CODEC.listOf().fieldOf("lines").forGetter(KioskPayload::lines)
	).apply(i, KioskPayload::new));
	public static final StreamCodec<RegistryFriendlyByteBuf, KioskPayload> CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC_ALL);

	/** Client -> server: buy {@code value} items from a slot, or (owner) set a slot's price to {@code value}. */
	public record Action(BlockPos pos, String action, int slot, long value) implements CustomPacketPayload {
		public static final Type<Action> TYPE = new Type<>(StarwakeMod.id("kiosk_action"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = StreamCodec.composite(
				BlockPos.STREAM_CODEC, Action::pos,
				ByteBufCodecs.stringUtf8(16), Action::action,
				ByteBufCodecs.VAR_INT, Action::slot,
				ByteBufCodecs.VAR_LONG, Action::value,
				Action::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
