package com.starwake.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/**
 * Both ways (M9): the Wristcom Cargo app. Client -> server with {@code ship} set and everything else empty asks for
 * that ship's manifest; server -> client answers with every item stored aboard (totals), every fluid in its tanks,
 * fuel and fusion, and how many containers were counted ({@code containers} = -1: not allowed or no ship).
 */
public record CargoPayload(int ship, int containers, List<Line> items, List<Tank> tanks, int fuel, int fuelCapacity, int fusion,
		int fusionCapacity) implements CustomPacketPayload {
	public static final Type<CargoPayload> TYPE = new Type<>(StarwakeMod.id("cargo"));

	/** One kind of item and how many of it are aboard (the stack is a 1-count template). */
	public record Line(ItemStack item, long count) {
		static final Codec<Line> CODEC = RecordCodecBuilder.create(i -> i.group(
				ItemStack.CODEC.fieldOf("item").forGetter(Line::item),
				Codec.LONG.fieldOf("count").forGetter(Line::count)).apply(i, Line::new));
	}

	/** One fluid aboard, summed over tanks, in kg (1 bucket = 1 kg). */
	public record Tank(Component name, double kg) {
		static final Codec<Tank> CODEC = RecordCodecBuilder.create(i -> i.group(
				ComponentSerialization.CODEC.fieldOf("name").forGetter(Tank::name),
				Codec.DOUBLE.fieldOf("kg").forGetter(Tank::kg)).apply(i, Tank::new));
	}

	private static final Codec<CargoPayload> CODEC_ALL = RecordCodecBuilder.create(i -> i.group(
			Codec.INT.fieldOf("ship").forGetter(CargoPayload::ship),
			Codec.INT.fieldOf("containers").forGetter(CargoPayload::containers),
			Line.CODEC.listOf().fieldOf("items").forGetter(CargoPayload::items),
			Tank.CODEC.listOf().fieldOf("tanks").forGetter(CargoPayload::tanks),
			Codec.INT.fieldOf("fuel").forGetter(CargoPayload::fuel),
			Codec.INT.fieldOf("fuel_capacity").forGetter(CargoPayload::fuelCapacity),
			Codec.INT.fieldOf("fusion").forGetter(CargoPayload::fusion),
			Codec.INT.fieldOf("fusion_capacity").forGetter(CargoPayload::fusionCapacity)
	).apply(i, CargoPayload::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, CargoPayload> CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC_ALL);

	public static CargoPayload request(int ship) {
		return new CargoPayload(ship, 0, List.of(), List.of(), 0, 0, 0, 0);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
