package com.starwake.network;

import com.starwake.StarwakeMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client -> server (M6): the pilot picked a star system on the galaxy map as the jump target. */
public record JumpTargetPayload(String system) implements CustomPacketPayload {
	public static final Type<JumpTargetPayload> TYPE = new Type<>(StarwakeMod.id("jump_target"));
	public static final StreamCodec<RegistryFriendlyByteBuf, JumpTargetPayload> CODEC =
			ByteBufCodecs.STRING_UTF8.map(JumpTargetPayload::new, JumpTargetPayload::system).cast();

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
