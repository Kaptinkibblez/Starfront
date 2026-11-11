package com.starwake.trade;

import com.mojang.serialization.Codec;
import com.starwake.StarwakeMod;
import com.starwake.network.MarketPayload;
import com.starwake.planet.Planets;
import com.starwake.planet.proc.PlanetArchetype;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Commodity markets (M7, Star Citizen style). Every hub trades the commodity groups its economy
 * lists ({@code hub_economy}): it <b>produces</b> what it sells (stock settles at 80% of its
 * capacity, so prices are low) and <b>consumes</b> what it buys (stock settles at 15%, so it
 * pays well). Buy at a mining camp, sell at a refinery.
 *
 * <p>Unit price = base x economy multiplier x 2^(1 - 2 x stock/capacity): buying drains stock and
 * raises the price unit by unit, selling fills it and lowers it. Stock drifts back to its
 * equilibrium with a 5-minute time constant, computed lazily when someone looks (no ticking).
 * The player pays 8% over the mid price and gets 8% under it, both moved by faction reputation
 * ({@link Economy#priceFactor}); hostile factions won't trade.
 */
public final class Markets {
	private static final double TAU = 6000; // ticks
	private static final double PRODUCER_EQ = 0.8, CONSUMER_EQ = 0.15, MAX_FILL = 1.5;
	private static final double SPREAD = 0.08;

	/** What a hub trades in one item. */
	public record Listing(Identifier item, double base, double mult, int capacity, boolean producer) {}

	private static final Map<String, List<Listing>> LISTINGS = new HashMap<>();
	private static int listingsFor = -1;

	private Markets() {}

	// ---------------------------------------------------------------------------------------

	public static synchronized List<Listing> listings(MinecraftServer server, TradeHubs.Hub hub) {
		if (listingsFor != TradeData.generation()) {
			LISTINGS.clear();
			listingsFor = TradeData.generation();
		}
		return LISTINGS.computeIfAbsent(hub.id(), id -> build(server, hub));
	}

	private static List<Listing> build(MinecraftServer server, TradeHubs.Hub hub) {
		var economy = TradeData.economy(hub.economy()).orElse(null);
		if (economy == null) return List.of();
		Map<Identifier, Listing> out = new LinkedHashMap<>();
		for (var t : economy.sells()) add(server, hub, t, true, out);
		for (var t : economy.buys()) add(server, hub, t, false, out);
		return List.copyOf(out.values());
	}

	private static void add(MinecraftServer server, TradeHubs.Hub hub, TradeData.Trade trade, boolean producer, Map<Identifier, Listing> out) {
		var group = TradeData.group(trade.group()).orElse(null);
		if (group == null) return;
		java.util.Set<Identifier> local = trade.local() ? localMinerals(server, hub) : null;
		group.items().entrySet().stream().sorted(Map.Entry.comparingByValue()).forEach(e -> {
			if (local != null && !local.contains(e.getKey())) return;
			if (!BuiltInRegistries.ITEM.containsKey(e.getKey())) return;
			out.putIfAbsent(e.getKey(), new Listing(e.getKey(), e.getValue(), trade.price(), trade.stock(), producer));
		});
	}

	/** Raw minerals that generate on the hub's planet (from its archetype). */
	private static java.util.Set<Identifier> localMinerals(MinecraftServer server, TradeHubs.Hub hub) {
		java.util.Set<Identifier> out = new java.util.HashSet<>();
		var planet = Planets.forDimension(hub.dimension()).map(p -> p.planet()).orElse(null);
		if (planet == null) return out;
		String archetype = TradeHubs.archetypeOf(planet);
		var registry = server.registryAccess().lookupOrThrow(PlanetArchetype.REGISTRY_KEY);
		registry.get(StarwakeMod.id(archetype)).ifPresent(a -> {
			for (String m : a.value().minerals()) {
				Identifier raw = switch (m) {
					case "iron" -> Identifier.withDefaultNamespace("raw_iron");
					case "copper" -> Identifier.withDefaultNamespace("raw_copper");
					case "gold" -> Identifier.withDefaultNamespace("raw_gold");
					case "coal" -> Identifier.withDefaultNamespace("coal");
					case "cinnabar" -> Identifier.withDefaultNamespace("cinnabar");
					case "sulfur" -> Identifier.withDefaultNamespace("sulfur");
					default -> StarwakeMod.id("raw_" + m);
				};
				out.add(raw);
			}
		});
		return out;
	}

	// ---------------------------------------------------------------------------------------

	private static double equilibrium(Listing l) {
		return l.capacity() * (l.producer() ? PRODUCER_EQ : CONSUMER_EQ);
	}

	/** Current stock (drifted toward equilibrium since it was last touched). */
	private static double stock(State state, String hub, Listing l, long now) {
		double[] s = state.stock(hub, l.item().toString(), equilibrium(l), now);
		double eq = equilibrium(l);
		double dt = Math.max(0, now - s[1]);
		return eq + (s[0] - eq) * Math.exp(-dt / TAU);
	}

	private static double mid(Listing l, double stock) {
		double fill = Math.clamp(stock / l.capacity(), 0, MAX_FILL);
		return l.base() * l.mult() * Math.pow(2, 1 - 2 * fill);
	}

	private static long buyUnit(Listing l, double stock, double rep) {
		return Math.max(1, (long) Math.ceil(mid(l, stock) * (1 + SPREAD) * rep));
	}

	private static long sellUnit(Listing l, double stock, double rep) {
		return Math.max(1, (long) Math.floor(mid(l, stock) * (1 - SPREAD) / rep));
	}

	/** Prices and stock for the market screen. */
	public static MarketPayload quote(ServerPlayer player, TradeHubs.Hub hub, Component title) {
		MinecraftServer server = player.level().getServer();
		State state = state(server);
		long now = server.overworld().getGameTime();
		double rep = Economy.priceFactor(Economy.rep(player, hub.faction()));
		List<MarketPayload.Quote> quotes = new ArrayList<>();
		for (Listing l : listings(server, hub)) {
			double s = stock(state, hub.id(), l, now);
			Item item = BuiltInRegistries.ITEM.getValue(l.item());
			int have = player.getInventory().countItem(item);
			quotes.add(new MarketPayload.Quote(l.item(), (int) Math.floor(s), (int) (l.capacity() * MAX_FILL), buyUnit(l, s, rep),
					sellUnit(l, s, rep), l.producer(), have));
		}
		return new MarketPayload(hub.id(), title, hub.faction(), Economy.credits(player), quotes);
	}

	/** Buys {@code count} of an item. Returns a message for the player. */
	public static Component buy(ServerPlayer player, TradeHubs.Hub hub, Identifier itemId, int count) {
		MinecraftServer server = player.level().getServer();
		Listing l = find(server, hub, itemId);
		if (l == null || count <= 0) return Component.translatable("message.starwake.trade.not_traded");
		State state = state(server);
		long now = server.overworld().getGameTime();
		double s = stock(state, hub.id(), l, now);
		if (s < count) return Component.translatable("message.starwake.trade.out_of_stock");
		double rep = Economy.priceFactor(Economy.rep(player, hub.faction()));
		long total = 0;
		for (int i = 0; i < count; i++) total += buyUnit(l, s - i, rep);
		if (!Economy.spend(player, total)) return Component.translatable("message.starwake.trade.no_credits", total);
		state.put(hub.id(), l.item().toString(), s - count, now);
		Item item = BuiltInRegistries.ITEM.getValue(itemId);
		int left = count;
		while (left > 0) {
			int n = Math.min(left, item.getDefaultMaxStackSize());
			ItemStack stack = new ItemStack(item, n);
			if (!player.getInventory().add(stack)) player.drop(stack, false, net.minecraft.util.Prediction.PREDICTED);
			left -= n;
		}
		repForVolume(player, hub, total);
		return Component.translatable("message.starwake.trade.bought", count, new ItemStack(item).getHoverName(), total);
	}

	/** Sells {@code count} of an item from the player's inventory. */
	public static Component sell(ServerPlayer player, TradeHubs.Hub hub, Identifier itemId, int count) {
		MinecraftServer server = player.level().getServer();
		Listing l = find(server, hub, itemId);
		if (l == null || count <= 0) return Component.translatable("message.starwake.trade.not_traded");
		Item item = BuiltInRegistries.ITEM.getValue(itemId);
		count = Math.min(count, player.getInventory().countItem(item));
		if (count <= 0) return Component.translatable("message.starwake.trade.none_to_sell");
		State state = state(server);
		long now = server.overworld().getGameTime();
		double s = stock(state, hub.id(), l, now);
		int room = (int) Math.floor(l.capacity() * MAX_FILL - s);
		count = Math.min(count, room);
		if (count <= 0) return Component.translatable("message.starwake.trade.full");
		double rep = Economy.priceFactor(Economy.rep(player, hub.faction()));
		long total = 0;
		for (int i = 0; i < count; i++) total += sellUnit(l, s + i, rep);
		removeItems(player, item, count);
		state.put(hub.id(), l.item().toString(), s + count, now);
		Economy.pay(player, total);
		repForVolume(player, hub, total);
		return Component.translatable("message.starwake.trade.sold", count, new ItemStack(item).getHoverName(), total);
	}

	/** A little reputation for business: +1 per 400 credits traded. */
	private static void repForVolume(ServerPlayer player, TradeHubs.Hub hub, long credits) {
		int rep = (int) (credits / 400);
		if (rep > 0) Economy.addRep(player, hub.faction(), rep);
	}

	public static void removeItems(ServerPlayer player, Item item, int count) {
		var inv = player.getInventory();
		for (int slot = 0; slot < inv.getContainerSize() && count > 0; slot++) {
			ItemStack stack = inv.getItem(slot);
			if (!stack.is(item)) continue;
			int n = Math.min(count, stack.getCount());
			stack.shrink(n);
			count -= n;
		}
		inv.setChanged();
	}

	private static Listing find(MinecraftServer server, TradeHubs.Hub hub, Identifier item) {
		for (Listing l : listings(server, hub)) if (l.item().equals(item)) return l;
		return null;
	}

	// ---------------------------------------------------------------------------------------

	/** Stock per hub per item: [stock, game time last set]. */
	public static final class State extends SavedData {
		final Map<String, Map<String, List<Double>>> stocks = new HashMap<>();
		static final Codec<State> CODEC = Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(Codec.STRING, Codec.DOUBLE.listOf()))
				.xmap(map -> {
					State s = new State();
					map.forEach((k, v) -> s.stocks.put(k, new HashMap<>(v)));
					return s;
				}, s -> s.stocks);
		static final SavedDataType<State> TYPE = new SavedDataType<>(StarwakeMod.id("markets"), State::new, CODEC,
				DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

		double[] stock(String hub, String item, double initial, long now) {
			List<Double> v = stocks.getOrDefault(hub, Map.of()).get(item);
			return v == null || v.size() < 2 ? new double[] {initial, now} : new double[] {v.get(0), v.get(1)};
		}

		void put(String hub, String item, double stock, long now) {
			stocks.computeIfAbsent(hub, k -> new HashMap<>()).put(item, List.of(stock, (double) now));
			setDirty();
		}
	}

	private static State state(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(State.TYPE);
	}
}
