package com.starwake.trade;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import com.starwake.galaxy.Galaxy;
import com.starwake.galaxy.StarSystems;
import com.starwake.network.MarketPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The background economy (M9, after Star Citizen's simulated economy, with no entities anywhere): every 30 s, for every
 * hub of every opened system,
 * <ul>
 * <li><b>production</b>: what a hub sells refills toward full, at its tier's rate times its <b>supply</b> (how well the
 * groups its production runs on, {@code inputs}, are stocked: a refinery without ore or fuel slows to a crawl);</li>
 * <li><b>consumption</b>: what a hub buys drains toward empty;</li>
 * <li><b>virtual haulers</b>: per item, the fullest producer ships to the emptiest consumer when the gap is worth it,
 * backing off on items players have been hauling recently (so player trade takes over a route, and a solo player still
 * finds a living market);</li>
 * <li><b>health and tiers</b>: a hub whose consumed goods stay stocked gains growth points and rises a tier (more
 * capacity, faster production, a physical expansion: {@link TradeHubs#onTier}); a starved one declines.</li>
 * </ul>
 * Market <b>depth</b> = sqrt(1 + k x (active traders - 1)) scales every listing's capacity, so 1000 traders don't swing
 * prices harder than one. The state also keeps what each player has seen at each hub and a short price history per
 * hub (the Wristcom Trade app's data). Tuning: {@code data/starwake/starwake/economy/default.json}.
 */
public final class EconomySim {
	public record Tuning(int stepTicks, double produce, double consume, double haul, double haulGap, double playerBackoff,
			double playerTau, double traderWindow, double depthPerTrader, List<Double> tierCapacity, List<Double> tierRate,
			double healthEma, int growPoints, int declinePoints, double growHealth, double declineHealth) {
		public static final Codec<Tuning> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.INT.fieldOf("step_ticks").forGetter(Tuning::stepTicks),
				Codec.DOUBLE.fieldOf("produce").forGetter(Tuning::produce),
				Codec.DOUBLE.fieldOf("consume").forGetter(Tuning::consume),
				Codec.DOUBLE.fieldOf("haul").forGetter(Tuning::haul),
				Codec.DOUBLE.fieldOf("haul_gap").forGetter(Tuning::haulGap),
				Codec.DOUBLE.fieldOf("player_backoff").forGetter(Tuning::playerBackoff),
				Codec.DOUBLE.fieldOf("player_tau").forGetter(Tuning::playerTau),
				Codec.DOUBLE.fieldOf("trader_window").forGetter(Tuning::traderWindow),
				Codec.DOUBLE.fieldOf("depth_per_trader").forGetter(Tuning::depthPerTrader),
				Codec.DOUBLE.listOf().fieldOf("tier_capacity").forGetter(Tuning::tierCapacity),
				Codec.DOUBLE.listOf().fieldOf("tier_rate").forGetter(Tuning::tierRate),
				Codec.DOUBLE.fieldOf("health_ema").forGetter(Tuning::healthEma),
				Codec.INT.fieldOf("grow_points").forGetter(Tuning::growPoints),
				Codec.INT.fieldOf("decline_points").forGetter(Tuning::declinePoints),
				Codec.DOUBLE.fieldOf("grow_health").forGetter(Tuning::growHealth),
				Codec.DOUBLE.fieldOf("decline_health").forGetter(Tuning::declineHealth)
		).apply(i, Tuning::new));

		static final Tuning DEFAULT = new Tuning(600, 0.02, 0.012, 0.03, 0.2, 2.0, 72000, 144000, 1.0, List.of(1.0, 1.6, 2.5),
				List.of(1.0, 1.3, 1.6), 0.02, 240, 480, 0.7, 0.3);
	}

	public static final int MAX_TIER = 2, HISTORY = 48, HISTORY_EVERY = 10;
	/** Fill a consumed group counts as fully supplied at. */
	private static final double SUPPLIED = 0.4;

	private static Tuning tuning = Tuning.DEFAULT;
	private static double depth = 1;
	private static int steps;

	private EconomySim() {}

	static void set(Map<Identifier, Tuning> loaded) {
		tuning = loaded.getOrDefault(StarwakeMod.id("default"), loaded.values().stream().findFirst().orElse(Tuning.DEFAULT));
	}

	public static Tuning tuning() {
		return tuning;
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % Math.max(20, tuning.stepTicks()) == 0) step(server);
		});
	}

	/** Market depth from active traders (shared by every hub). */
	public static double depth() {
		return depth;
	}

	public static int tier(MinecraftServer server, TradeHubs.Hub hub) {
		return state(server).hub(hub.id()).tier;
	}

	/** Admin: puts a hub on a tier (its modules follow once nobody is near). */
	public static void setTier(MinecraftServer server, TradeHubs.Hub hub, int tier) {
		State st = state(server);
		HubState hs = st.hub(hub.id());
		boolean up = tier > hs.tier;
		hs.tier = Math.clamp(tier, 0, MAX_TIER);
		hs.points = 0;
		st.setDirty();
		TradeHubs.onTier(server, hub, hs.tier, up);
	}

	public static int points(MinecraftServer server, TradeHubs.Hub hub) {
		return state(server).hub(hub.id()).points;
	}

	public static double health(MinecraftServer server, TradeHubs.Hub hub) {
		return state(server).hub(hub.id()).health;
	}

	/** Capacity multiplier of a hub's listings: its tier times market depth. */
	static double capacityFactor(MinecraftServer server, TradeHubs.Hub hub) {
		List<Double> caps = tuning.tierCapacity();
		int tier = Math.clamp(tier(server, hub), 0, caps.size() - 1);
		return caps.get(tier) * depth;
	}

	// --- The step ---------------------------------------------------------------------------------------

	static void step(MinecraftServer server) {
		Tuning t = tuning;
		State st = state(server);
		Markets.State market = Markets.state(server);
		long now = server.overworld().getGameTime();
		st.traders.values().removeIf(last -> now - last > t.traderWindow());
		depth = Math.sqrt(1 + t.depthPerTrader() * Math.max(0, st.traders.size() - 1));
		boolean sample = steps++ % HISTORY_EVERY == 0;
		Set<String> systems = new LinkedHashSet<>();
		systems.add(Galaxy.HOME);
		systems.addAll(StarSystems.opened(server));
		for (String system : systems) {
			List<TradeHubs.Hub> hubs = TradeHubs.plan(server, system);
			// item -> producer/consumer listings in this system (for haulers)
			Map<Identifier, List<Slot>> producers = new HashMap<>(), consumers = new HashMap<>();
			for (TradeHubs.Hub hub : hubs) {
				var economy = TradeData.economy(hub.economy()).orElse(null);
				if (economy == null) continue;
				HubState hs = st.hub(hub.id());
				List<Markets.Listing> listings = Markets.listings(server, hub);
				double rate = t.tierRate().get(Math.clamp(hs.tier, 0, t.tierRate().size() - 1));
				// Supply: how well the groups production runs on are stocked.
				double supply = 1, supplied = 0;
				int inputs = 0, consumed = 0;
				double healthSum = 0;
				for (Markets.Listing l : listings) {
					if (l.producer()) continue;
					double sat = Math.min(1, Markets.fill(market, hub.id(), l) / SUPPLIED);
					healthSum += sat;
					consumed++;
					if (economy.inputs().contains(l.group())) {
						supplied += sat;
						inputs++;
					}
				}
				if (inputs > 0) supply = Math.max(0.1, supplied / inputs);
				double cap = capacityFactor(server, hub);
				for (Markets.Listing l : listings) {
					double f = Markets.fill(market, hub.id(), l);
					double next = l.producer() ? (f < 1 ? Math.min(1, f + t.produce() * supply * rate) : f) : Math.max(0, f - t.consume() * rate);
					if (next != f) market.setFill(hub.id(), l.item().toString(), next, now);
					Slot slot = new Slot(hub, l, l.capacity() * cap);
					(l.producer() ? producers : consumers).computeIfAbsent(l.item(), k -> new ArrayList<>()).add(slot);
					if (sample) st.sample(hub.id(), l.item().toString(), Markets.midPrice(l, next));
				}
				// Health and tiers.
				double health = consumed == 0 ? 1 : healthSum / consumed;
				hs.health += (health - hs.health) * t.healthEma();
				if (hs.health >= t.growHealth()) hs.points = Math.max(0, hs.points) + 1;
				else if (hs.health <= t.declineHealth()) hs.points = Math.min(0, hs.points) - 1;
				if (hs.points >= t.growPoints() && hs.tier < MAX_TIER) {
					hs.tier++;
					hs.points = 0;
					TradeHubs.onTier(server, hub, hs.tier, true);
				} else if (hs.points <= -t.declinePoints() && hs.tier > 0) {
					hs.tier--;
					hs.points = 0;
					TradeHubs.onTier(server, hub, hs.tier, false);
				}
			}
			// Virtual haulers: one run per item per step, fullest producer -> emptiest consumer.
			for (var e : producers.entrySet()) {
				List<Slot> to = consumers.get(e.getKey());
				if (to == null) continue;
				Slot from = null, dest = null;
				double fromFill = -1, destFill = 2;
				for (Slot s : e.getValue()) {
					double f = Markets.fill(market, s.hub.id(), s.listing);
					if (f > fromFill) {
						fromFill = f;
						from = s;
					}
				}
				for (Slot s : to) {
					double f = Markets.fill(market, s.hub.id(), s.listing);
					if (f < destFill) {
						destFill = f;
						dest = s;
					}
				}
				if (from == null || dest == null || fromFill - destFill < t.haulGap()) continue;
				double played = st.played(system + "|" + e.getKey(), now, t.playerTau());
				double backoff = 1 / (1 + t.playerBackoff() * played / Math.max(1, dest.capacity));
				double units = Math.min(t.haul() * backoff * Math.min(from.capacity, dest.capacity), (fromFill - destFill) / 2 * Math.min(from.capacity, dest.capacity));
				if (units < 0.5) continue;
				market.setFill(from.hub.id(), e.getKey().toString(), fromFill - units / from.capacity, now);
				market.setFill(dest.hub.id(), e.getKey().toString(), destFill + units / dest.capacity, now);
			}
		}
		st.setDirty();
	}

	private record Slot(TradeHubs.Hub hub, Markets.Listing listing, double capacity) {}

	// --- Player activity --------------------------------------------------------------------------------

	/** A player traded {@code count} of an item at a hub (+ sold to it, - bought): they count as an active trader, and the
	 * volume displaces virtual haulers on that item in the system for a while. */
	static void recordTrade(MinecraftServer server, ServerPlayer player, TradeHubs.Hub hub, Identifier item, int count) {
		State st = state(server);
		long now = server.overworld().getGameTime();
		st.traders.put(player.getStringUUID(), now);
		st.addPlayed(hub.system() + "|" + item, Math.abs(count), now, tuning.playerTau());
		st.setDirty();
	}

	static void recordIndustrial(MinecraftServer server, ServerPlayer player, TradeHubs.Hub hub, long credits) {
		State st = state(server);
		st.traders.put(player.getStringUUID(), server.overworld().getGameTime());
		st.setDirty();
	}

	/** What a player saw at a hub's terminal (the Trade app shows it with its age). */
	static void remember(ServerPlayer player, TradeHubs.Hub hub, List<MarketPayload.Quote> quotes, long now) {
		State st = state(player.level().getServer());
		Map<String, List<Double>> items = new HashMap<>();
		for (MarketPayload.Quote q : quotes) items.put(q.item().toString(), List.of((double) q.buy(), (double) q.sell(), (double) q.stock()));
		st.seen.computeIfAbsent(player.getStringUUID(), k -> new HashMap<>()).put(hub.id(), new Seen(now, items));
		st.setDirty();
	}

	public static Map<String, Seen> seen(ServerPlayer player) {
		return state(player.level().getServer()).seen.getOrDefault(player.getStringUUID(), Map.of());
	}

	/** The Wristcom Trade app's data for a player. */
	public static com.starwake.network.TradeIntelPayload intel(ServerPlayer player, String historyHub, String historyItem) {
		MinecraftServer server = player.level().getServer();
		long now = server.overworld().getGameTime();
		List<com.starwake.network.TradeIntelPayload.Hub> hubs = new ArrayList<>();
		for (var e : seen(player).entrySet()) {
			var hub = TradeHubs.hub(server, e.getKey()).orElse(null);
			if (hub == null) continue;
			List<com.starwake.network.TradeIntelPayload.Row> rows = new ArrayList<>();
			e.getValue().items().forEach((item, v) -> {
				if (v.size() >= 3) rows.add(new com.starwake.network.TradeIntelPayload.Row(Identifier.parse(item), v.get(0).longValue(),
						v.get(1).longValue(), v.get(2).intValue()));
			});
			rows.sort(java.util.Comparator.comparing(r -> r.item().toString()));
			HubState hs = state(server).hub(hub.id());
			hubs.add(new com.starwake.network.TradeIntelPayload.Hub(hub.id(), hub.name(), now - e.getValue().time(), hs.tier, (float) hs.health, rows));
		}
		hubs.sort(java.util.Comparator.comparingLong(com.starwake.network.TradeIntelPayload.Hub::age));
		List<Float> history = historyHub.isEmpty() || historyItem.isEmpty() || !seen(player).containsKey(historyHub) ? List.of()
				: history(server, historyHub, historyItem);
		return new com.starwake.network.TradeIntelPayload(hubs, historyHub, historyItem, history);
	}

	public static List<Float> history(MinecraftServer server, String hub, String item) {
		return state(server).history.getOrDefault(hub, Map.of()).getOrDefault(item, List.of());
	}

	// --- Saved state ------------------------------------------------------------------------------------

	static final class HubState {
		int tier;
		int points;
		double health = 0.5;

		List<Double> save() {
			return List.of((double) tier, (double) points, health);
		}

		static HubState load(List<Double> v) {
			HubState h = new HubState();
			if (v.size() >= 3) {
				h.tier = (int) Math.round(v.get(0));
				h.points = (int) Math.round(v.get(1));
				h.health = v.get(2);
			}
			return h;
		}
	}

	/** One terminal visit: game time and item -> [buy, sell, stock]. */
	public record Seen(long time, Map<String, List<Double>> items) {
		static final Codec<Seen> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.LONG.fieldOf("time").forGetter(Seen::time),
				Codec.unboundedMap(Codec.STRING, Codec.DOUBLE.listOf()).fieldOf("items").forGetter(Seen::items)
		).apply(i, Seen::new));
	}

	public static final class State extends SavedData {
		final Map<String, HubState> hubs = new HashMap<>();
		final Map<String, List<Double>> played = new HashMap<>();
		final Map<String, Long> traders = new HashMap<>();
		final Map<String, Map<String, Seen>> seen = new HashMap<>();
		final Map<String, Map<String, List<Float>>> history = new HashMap<>();

		private record Data(Map<String, List<Double>> hubs, Map<String, List<Double>> played, Map<String, Long> traders,
				Map<String, Map<String, Seen>> seen, Map<String, Map<String, List<Float>>> history) {}

		static final Codec<State> CODEC = RecordCodecBuilder.<Data>create(i -> i.group(
				Codec.unboundedMap(Codec.STRING, Codec.DOUBLE.listOf()).optionalFieldOf("hubs", Map.of()).forGetter(Data::hubs),
				Codec.unboundedMap(Codec.STRING, Codec.DOUBLE.listOf()).optionalFieldOf("played", Map.of()).forGetter(Data::played),
				Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("traders", Map.of()).forGetter(Data::traders),
				Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(Codec.STRING, Seen.CODEC)).optionalFieldOf("seen", Map.of()).forGetter(Data::seen),
				Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(Codec.STRING, Codec.FLOAT.listOf())).optionalFieldOf("history", Map.of())
						.forGetter(Data::history)
		).apply(i, Data::new)).xmap(d -> {
			State s = new State();
			d.hubs().forEach((k, v) -> s.hubs.put(k, HubState.load(v)));
			s.played.putAll(d.played());
			s.traders.putAll(d.traders());
			d.seen().forEach((k, v) -> s.seen.put(k, new HashMap<>(v)));
			d.history().forEach((k, v) -> {
				Map<String, List<Float>> m = new HashMap<>();
				v.forEach((item, list) -> m.put(item, new ArrayList<>(list)));
				s.history.put(k, m);
			});
			return s;
		}, s -> {
			Map<String, List<Double>> hubs = new HashMap<>();
			s.hubs.forEach((k, v) -> hubs.put(k, v.save()));
			return new Data(hubs, s.played, s.traders, s.seen, s.history);
		});

		static final SavedDataType<State> TYPE = new SavedDataType<>(StarwakeMod.id("economy_sim"), State::new, CODEC,
				DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

		HubState hub(String id) {
			return hubs.computeIfAbsent(id, k -> new HubState());
		}

		double played(String key, long now, double tau) {
			List<Double> v = played.get(key);
			if (v == null || v.size() < 2) return 0;
			return v.get(0) * Math.exp(-Math.max(0, now - v.get(1)) / tau);
		}

		void addPlayed(String key, double units, long now, double tau) {
			played.put(key, List.of(played(key, now, tau) + units, (double) now));
		}

		void sample(String hub, String item, double price) {
			List<Float> list = history.computeIfAbsent(hub, k -> new HashMap<>()).computeIfAbsent(item, k -> new ArrayList<>());
			list.add((float) price);
			while (list.size() > HISTORY) list.removeFirst();
		}
	}

	static State state(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(State.TYPE);
	}
}
