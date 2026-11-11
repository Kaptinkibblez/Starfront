package com.starwake.trade;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import com.starwake.network.EconomyPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Player accounts (M7): digital credits, reputation with every faction and active contracts,
 * kept by the server ({@link State}, SavedData {@code starwake:economy}) and synced to the owner
 * ({@link EconomyPayload}) whenever they change. Credits can't be lost on death.
 *
 * <p>Reputation runs from -1000 to 1000. Ranks: Hostile (< -500: no trade, guards attack),
 * Unfriendly (< -100), Neutral, Friendly (>= 200), Trusted (>= 500), Honored (>= 800). Prices move
 * up to 10% with reputation ({@link #priceFactor}).
 */
public final class Economy {
	public static final long STARTING_CREDITS = 1000;
	public static final int MAX_REP = 1000;
	public static final int MAX_CONTRACTS = 3;

	/** One player's account. */
	public static final class Account {
		long credits = STARTING_CREDITS;
		final Map<String, Integer> rep = new HashMap<>();
		final List<Contract> contracts = new ArrayList<>();
		final List<String> visited = new ArrayList<>(); // systems reached, for exploration contracts
		final List<String> done = new ArrayList<>(); // recently finished contract offers (no repeats)

		public long credits() { return credits; }
		public Map<String, Integer> rep() { return rep; }
		public List<Contract> contracts() { return contracts; }

		static final Codec<Account> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.LONG.fieldOf("credits").forGetter(a -> a.credits),
				Codec.unboundedMap(Codec.STRING, Codec.INT).fieldOf("rep").forGetter(a -> a.rep),
				Contract.CODEC.listOf().fieldOf("contracts").forGetter(a -> a.contracts),
				Codec.STRING.listOf().optionalFieldOf("visited", List.of()).forGetter(a -> a.visited),
				Codec.STRING.listOf().optionalFieldOf("done", List.of()).forGetter(a -> a.done)
		).apply(i, (credits, rep, contracts, visited, done) -> {
			Account a = new Account();
			a.credits = credits;
			a.rep.putAll(rep);
			a.contracts.addAll(contracts);
			a.visited.addAll(visited);
			a.done.addAll(done);
			return a;
		}));
	}

	/** Every account in the world. */
	public static final class State extends SavedData {
		final Map<UUID, Account> accounts = new HashMap<>();
		static final Codec<State> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, Account.CODEC).xmap(map -> {
			State s = new State();
			s.accounts.putAll(map);
			return s;
		}, s -> s.accounts);
		static final SavedDataType<State> TYPE = new SavedDataType<>(StarwakeMod.id("economy"), State::new, CODEC,
				DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
	}

	private Economy() {}

	public static void init() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sync(handler.getPlayer()));
	}

	private static State state(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(State.TYPE);
	}

	public static Account account(ServerPlayer player) {
		State state = state(player.level().getServer());
		return state.accounts.computeIfAbsent(player.getUUID(), u -> {
			state.setDirty();
			return new Account();
		});
	}

	private static void changed(ServerPlayer player) {
		state(player.level().getServer()).setDirty();
		sync(player);
	}

	public static void sync(ServerPlayer player) {
		Account a = account(player);
		ServerPlayNetworking.send(player, new EconomyPayload(a.credits, Map.copyOf(a.rep), List.copyOf(a.contracts)));
	}

	// ---------------------------------------------------------------------------------------

	public static long credits(ServerPlayer player) {
		return account(player).credits;
	}

	/** Takes credits if the player has them. */
	public static boolean spend(ServerPlayer player, long amount) {
		Account a = account(player);
		if (amount < 0 || a.credits < amount) return false;
		a.credits -= amount;
		changed(player);
		return true;
	}

	public static void pay(ServerPlayer player, long amount) {
		account(player).credits += Math.max(0, amount);
		changed(player);
	}

	public static int rep(ServerPlayer player, String faction) {
		return account(player).rep.getOrDefault(faction, 0);
	}

	public static void addRep(ServerPlayer player, String faction, int delta) {
		Account a = account(player);
		a.rep.put(faction, Mth.clamp(a.rep.getOrDefault(faction, 0) + delta, -MAX_REP, MAX_REP));
		changed(player);
	}

	public static void addContract(ServerPlayer player, Contract contract) {
		account(player).contracts.add(contract);
		changed(player);
	}

	public static void removeContract(ServerPlayer player, String id) {
		account(player).contracts.removeIf(c -> c.id().equals(id));
		changed(player);
	}

	/** Remembers a finished or abandoned offer so the board doesn't offer it again. */
	public static void markDone(ServerPlayer player, String id) {
		Account a = account(player);
		a.done.add(id);
		while (a.done.size() > 64) a.done.removeFirst();
		state(player.level().getServer()).setDirty();
	}

	public static boolean isDone(ServerPlayer player, String id) {
		return account(player).done.contains(id);
	}

	/** Records a visited system; true the first time. */
	public static boolean visit(ServerPlayer player, String system) {
		Account a = account(player);
		if (a.visited.contains(system)) return false;
		a.visited.add(system);
		state(player.level().getServer()).setDirty();
		return true;
	}

	// ---------------------------------------------------------------------------------------

	/** Price multiplier for buying from a faction (selling to it uses the inverse). */
	public static double priceFactor(int rep) {
		return 1.0 - Mth.clamp(rep, -MAX_REP, MAX_REP) / 10_000.0;
	}

	/** Rank translation key suffix for a reputation value. */
	public static String rank(int rep) {
		if (rep < -500) return "hostile";
		if (rep < -100) return "unfriendly";
		if (rep < 200) return "neutral";
		if (rep < 500) return "friendly";
		if (rep < 800) return "trusted";
		return "honored";
	}

	public static boolean hostile(int rep) {
		return rep < -500;
	}
}
