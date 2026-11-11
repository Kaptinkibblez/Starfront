package com.starwake.client.trade;

import com.starwake.StarwakeMod;
import com.starwake.network.ContractBoardPayload;
import com.starwake.network.EconomyPayload;
import com.starwake.network.HubListPayload;
import com.starwake.network.MarketPayload;
import com.starwake.network.ShopPayload;
import com.starwake.registry.ModEntities;
import com.starwake.trade.Contract;
import com.starwake.trade.Contracts;
import com.starwake.trade.Economy;
import com.starwake.trade.Faction;
import com.starwake.trade.HubZones;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * Client side of trading (M7): your account (credits, reputation, contracts) and the hubs of the
 * system you're in, as the server sent them; opens and refreshes the terminal screens; registers
 * the hub NPC renderer.
 */
public final class TradeClient {
	private static long credits;
	private static Map<String, Integer> rep = Map.of();
	private static List<Contract> contracts = List.of();
	private static List<HubListPayload.HubView> hubs = List.of();

	private TradeClient() {}

	public static void init() {
		EntityRendererRegistry.register(ModEntities.HUB_NPC, HubNpcRenderer::new);
		ClientPlayNetworking.registerGlobalReceiver(EconomyPayload.TYPE, (p, c) -> {
			credits = p.credits();
			rep = Map.copyOf(p.rep());
			contracts = List.copyOf(p.contracts());
		});
		ClientPlayNetworking.registerGlobalReceiver(HubListPayload.TYPE, (p, c) -> {
			hubs = List.copyOf(p.hubs());
			Map<ResourceKey<Level>, List<AABB>> zones = new HashMap<>();
			for (var h : hubs) zones.computeIfAbsent(h.dimension(), k -> new java.util.ArrayList<>()).addAll(h.zones());
			HubZones.set(true, zones);
		});
		ClientPlayNetworking.registerGlobalReceiver(MarketPayload.TYPE, (p, c) -> {
			if (c.client().gui.screen() instanceof MarketScreen s && s.hub.equals(p.hub())) s.update(p);
			else c.client().gui.setScreen(new MarketScreen(p));
		});
		ClientPlayNetworking.registerGlobalReceiver(ShopPayload.TYPE, (p, c) -> {
			if (c.client().gui.screen() instanceof OutfitterScreen s && s.hub.equals(p.hub())) s.update(p);
			else c.client().gui.setScreen(new OutfitterScreen(p));
		});
		ClientPlayNetworking.registerGlobalReceiver(ContractBoardPayload.TYPE, (p, c) -> {
			if (c.client().gui.screen() instanceof ContractBoardScreen s && s.hub.equals(p.hub())) s.update(p);
			else c.client().gui.setScreen(new ContractBoardScreen(p));
		});
		ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> {
			hubs = List.of();
			contracts = List.of();
			HubZones.clear(true);
		});
	}

	public static long credits() { return credits; }
	public static Map<String, Integer> rep() { return rep; }
	public static List<Contract> contracts() { return contracts; }
	public static List<HubListPayload.HubView> hubs() { return hubs; }

	public static Component describe(Contract c) {
		return Contracts.describe(c);
	}

	public static Faction faction(String id) {
		var level = Minecraft.getInstance().level;
		if (level == null) return null;
		return level.registryAccess().lookup(Faction.REGISTRY_KEY).flatMap(r -> r.getOptional(StarwakeMod.id(id))).orElse(null);
	}

	public static Component factionName(String id) {
		Faction f = faction(id);
		return f == null ? Component.literal(id) : f.name();
	}

	public static int factionColor(String id) {
		Faction f = faction(id);
		return f == null ? 0xFF7F95AA : f.color() | 0xFF000000;
	}

	public static Component rankName(int rep) {
		return Component.translatable("rank.starwake." + Economy.rank(rep));
	}

	/** "Meridian Mining Collective · Friendly (230)". */
	public static Component standing(String faction) {
		int r = rep.getOrDefault(faction, 0);
		return Component.empty().append(factionName(faction)).append(" · ").append(rankName(r)).append(" (" + r + ")");
	}
}
