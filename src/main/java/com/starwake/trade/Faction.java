package com.starwake.trade;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ExtraCodecs;

/**
 * A faction (M7), from {@code data/<ns>/starwake/faction/<id>.json}; a synced registry so the
 * Wristcom can show names and colours. Factions control trade hubs, set what those hubs buy and
 * sell, issue contracts, and keep a reputation with every player.
 *
 * @param name        display name
 * @param code        short tag shown on markers ("SAC")
 * @param color       the faction's colour (markers, terminals)
 * @param uniform     uniform main colour (guard and crew skins)
 * @param accent      uniform accent colour
 * @param description one-line description for the Wristcom
 * @param hubs        the kinds of hub this faction runs and where
 * @param contracts   contract types it offers ({@code delivery}, {@code mining}, {@code courier},
 *                    {@code exploration})
 */
public record Faction(Component name, String code, int color, int uniform, int accent, Component description, List<HubRule> hubs,
		List<String> contracts) {
	public static final ResourceKey<Registry<Faction>> REGISTRY_KEY = ResourceKey.createRegistryKey(StarwakeMod.id("faction"));

	/**
	 * One kind of hub a faction runs.
	 *
	 * @param kind       {@code station} (orbital, with a hangar) or {@code outpost} (on a planet)
	 * @param economy    hub economy id ({@code data/<ns>/starwake/hub_economy/<id>.json})
	 * @param template   layout id ({@code data/<ns>/starwake/hub_template/<id>.json})
	 * @param archetypes for outposts: the planet archetypes it settles (empty = any)
	 * @param weight     how often this rule is picked against other factions' rules
	 */
	public record HubRule(String kind, String economy, String template, List<String> archetypes, double weight) {
		public static final Codec<HubRule> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("kind").forGetter(HubRule::kind),
				Codec.STRING.fieldOf("economy").forGetter(HubRule::economy),
				Codec.STRING.fieldOf("template").forGetter(HubRule::template),
				Codec.STRING.listOf().optionalFieldOf("archetypes", List.of()).forGetter(HubRule::archetypes),
				Codec.doubleRange(0, 100).optionalFieldOf("weight", 1.0).forGetter(HubRule::weight)
		).apply(i, HubRule::new));
	}

	public static final Codec<Faction> CODEC = RecordCodecBuilder.create(i -> i.group(
			ComponentSerialization.CODEC.fieldOf("name").forGetter(Faction::name),
			Codec.STRING.fieldOf("code").forGetter(Faction::code),
			ExtraCodecs.STRING_RGB_COLOR.fieldOf("color").forGetter(Faction::color),
			ExtraCodecs.STRING_RGB_COLOR.fieldOf("uniform").forGetter(Faction::uniform),
			ExtraCodecs.STRING_RGB_COLOR.fieldOf("accent").forGetter(Faction::accent),
			ComponentSerialization.CODEC.fieldOf("description").forGetter(Faction::description),
			HubRule.CODEC.listOf().fieldOf("hubs").forGetter(Faction::hubs),
			Codec.STRING.listOf().optionalFieldOf("contracts", List.of()).forGetter(Faction::contracts)
	).apply(i, Faction::new));
}
