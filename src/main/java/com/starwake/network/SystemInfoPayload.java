package com.starwake.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import com.starwake.planet.Planet;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.util.ExtraCodecs;

/**
 * Server -> client (M6): a star system's planets for the galaxy map, answering a
 * {@link SystemInfoRequestPayload}. Planets are generated from archetype data only the server has.
 */
public record SystemInfoPayload(String system, List<PlanetInfo> planets) implements CustomPacketPayload {
	public static final Type<SystemInfoPayload> TYPE = new Type<>(StarwakeMod.id("system_info"));

	/** One planet: name, type, orbit (blocks), temperature, gravity, air, colour. */
	public record PlanetInfo(Component name, Optional<Component> archetype, double orbit, float temperature, double gravity,
			boolean breathable, int color) {
		public static final Codec<PlanetInfo> CODEC = RecordCodecBuilder.create(i -> i.group(
				ComponentSerialization.CODEC.fieldOf("name").forGetter(PlanetInfo::name),
				ComponentSerialization.CODEC.optionalFieldOf("archetype").forGetter(PlanetInfo::archetype),
				Codec.DOUBLE.fieldOf("orbit").forGetter(PlanetInfo::orbit),
				Codec.FLOAT.fieldOf("temperature").forGetter(PlanetInfo::temperature),
				Codec.DOUBLE.fieldOf("gravity").forGetter(PlanetInfo::gravity),
				Codec.BOOL.fieldOf("breathable").forGetter(PlanetInfo::breathable),
				ExtraCodecs.ARGB_COLOR_CODEC.fieldOf("color").forGetter(PlanetInfo::color)
		).apply(i, PlanetInfo::new));

		public static PlanetInfo of(Planet p) {
			var body = p.body().orElseThrow();
			return new PlanetInfo(p.name(), p.archetype(), body.position().length(), p.hazards().temperature(), p.gravity(),
					p.hazards().breathable() && !p.hazards().toxic(), body.color());
		}
	}

	public static final StreamCodec<RegistryFriendlyByteBuf, SystemInfoPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, SystemInfoPayload::system,
			ByteBufCodecs.fromCodecWithRegistries(PlanetInfo.CODEC.listOf()), SystemInfoPayload::planets,
			SystemInfoPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
