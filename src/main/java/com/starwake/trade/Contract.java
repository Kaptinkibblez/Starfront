package com.starwake.trade;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * A contract (M7) a player took from a hub's Contract Board.
 *
 * @param id        unique id (hub + day + slot)
 * @param type      {@code delivery} (bring items to a hub), {@code mining} (bring raw minerals to the
 *                  issuing hub), {@code courier} (carry a sealed package to a hub) or
 *                  {@code exploration} (reach a star system)
 * @param faction   issuing faction id
 * @param hub       issuing hub id
 * @param target    destination hub id (delivery/courier/mining) or system id (exploration)
 * @param targetName display name of the destination
 * @param item      item to bring (delivery/mining; the package for courier)
 * @param count     how many
 * @param reward    credits paid on completion
 * @param rep       reputation gained on completion
 * @param expires   game time it expires
 */
public record Contract(String id, String type, String faction, String hub, String target, String targetName, Identifier item,
		int count, long reward, int rep, long expires) {
	public static final Codec<Contract> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("id").forGetter(Contract::id),
			Codec.STRING.fieldOf("type").forGetter(Contract::type),
			Codec.STRING.fieldOf("faction").forGetter(Contract::faction),
			Codec.STRING.fieldOf("hub").forGetter(Contract::hub),
			Codec.STRING.fieldOf("target").forGetter(Contract::target),
			Codec.STRING.fieldOf("target_name").forGetter(Contract::targetName),
			Identifier.CODEC.fieldOf("item").forGetter(Contract::item),
			Codec.INT.fieldOf("count").forGetter(Contract::count),
			Codec.LONG.fieldOf("reward").forGetter(Contract::reward),
			Codec.INT.fieldOf("rep").forGetter(Contract::rep),
			Codec.LONG.fieldOf("expires").forGetter(Contract::expires)
	).apply(i, Contract::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, Contract> STREAM_CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC);
}
