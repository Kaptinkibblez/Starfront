package com.starwake.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Server -> client (M7): the trade hubs of the system you're in (markers, pressurized zones, Wristcom). */
public record HubListPayload(List<HubView> hubs) implements CustomPacketPayload {
	public static final Type<HubListPayload> TYPE = new Type<>(StarwakeMod.id("hub_list"));

	private static final Codec<AABB> BOX = Codec.DOUBLE.listOf(6, 6).xmap(l -> new AABB(l.get(0), l.get(1), l.get(2), l.get(3), l.get(4), l.get(5)),
			b -> List.of(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ));

	/** One hub as the client sees it. Zones are empty until it's built. */
	public record HubView(String id, String name, String faction, String kind, ResourceKey<Level> dimension, Vec3 beacon, String planet,
			List<AABB> zones) {
		public boolean station() {
			return kind.equals("station");
		}

		public static final Codec<HubView> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("id").forGetter(HubView::id),
				Codec.STRING.fieldOf("name").forGetter(HubView::name),
				Codec.STRING.fieldOf("faction").forGetter(HubView::faction),
				Codec.STRING.fieldOf("kind").forGetter(HubView::kind),
				Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(HubView::dimension),
				Vec3.CODEC.fieldOf("beacon").forGetter(HubView::beacon),
				Codec.STRING.fieldOf("planet").forGetter(HubView::planet),
				BOX.listOf().fieldOf("zones").forGetter(HubView::zones)
		).apply(i, HubView::new));
	}

	public static final StreamCodec<RegistryFriendlyByteBuf, HubListPayload> CODEC =
			ByteBufCodecs.fromCodecWithRegistries(HubView.CODEC.listOf()).map(HubListPayload::new, HubListPayload::hubs);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
