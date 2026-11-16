package com.starwake.trade;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.starwake.StarwakeMod;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Player-posted contracts (M9): a player at a hub's Contract Board posts a supply request for the item in their hand
 * ("deliver 64 of these to this hub"), paying the reward up front into escrow. It shows on every Contract Board in the
 * system; one player takes it (it then counts as one of their active contracts) and hands the goods in at the hub,
 * collecting the reward; the goods wait for the poster at any Contract Board ("Collect"). Unfinished requests expire after
 * three days and refund the poster; a taker who abandons or runs out of time frees it for others. Matching is exact
 * (item and components), so a request for 304 plate wants 304 plate.
 */
public final class PlayerContracts {
	public static final String PREFIX = "p-";
	public static final int MAX_OPEN = 5;
	public static final long LIFETIME = 3 * Contracts.DAY;

	/** A posted request: the contract shown to players, the exact goods wanted, who posted it, who took it. */
	public record Entry(Contract contract, ItemStack template, UUID poster, String posterName, Optional<UUID> taker) {
		static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
				Contract.CODEC.fieldOf("contract").forGetter(Entry::contract),
				ItemStack.CODEC.fieldOf("template").forGetter(Entry::template),
				UUIDUtil.STRING_CODEC.fieldOf("poster").forGetter(Entry::poster),
				Codec.STRING.fieldOf("poster_name").forGetter(Entry::posterName),
				UUIDUtil.STRING_CODEC.optionalFieldOf("taker").forGetter(Entry::taker)
		).apply(i, Entry::new));

		Entry withTaker(Optional<UUID> taker) {
			return new Entry(contract, template, poster, posterName, taker);
		}
	}

	private PlayerContracts() {}

	public static boolean isPlayer(String id) {
		return id.startsWith(PREFIX);
	}

	/** Open requests in a system (not taken, not expired). */
	public static List<Entry> open(MinecraftServer server, String system) {
		long now = server.overworld().getGameTime();
		List<Entry> out = new ArrayList<>();
		for (Entry e : state(server).entries.values()) {
			if (e.taker().isEmpty() && now < e.contract().expires() && e.contract().hub().startsWith(system + "/")) out.add(e);
		}
		return out;
	}

	public static List<Entry> postedBy(MinecraftServer server, UUID poster) {
		return state(server).entries.values().stream().filter(e -> e.poster().equals(poster)).toList();
	}

	public static Optional<Entry> get(MinecraftServer server, String id) {
		return Optional.ofNullable(state(server).entries.get(id));
	}

	public static int pickups(MinecraftServer server, UUID player) {
		return state(server).pickups.getOrDefault(player, List.of()).stream().mapToInt(ItemStack::getCount).sum();
	}

	// --- Actions ----------------------------------------------------------------------------------------

	public static Component post(ServerPlayer player, TradeHubs.Hub hub, int count, long reward) {
		MinecraftServer server = player.level().getServer();
		ItemStack held = player.getMainHandItem();
		if (held.isEmpty()) return Component.translatable("message.starwake.pcontract.hold_item");
		if (count <= 0 || count > 4096 || reward <= 0) return Component.translatable("message.starwake.pcontract.bad_terms");
		if (postedBy(server, player.getUUID()).size() >= MAX_OPEN) return Component.translatable("message.starwake.pcontract.too_many", MAX_OPEN);
		if (!Economy.spend(player, reward)) return Component.translatable("message.starwake.trade.no_credits", reward);
		long now = server.overworld().getGameTime();
		String id = PREFIX + UUID.randomUUID().toString().substring(0, 8);
		Contract c = new Contract(id, "player", "player:" + player.getGameProfile().name(), hub.id(), hub.id(), hub.name(), BuiltInRegistries.ITEM.getKey(held.getItem()), count, reward, 0,
				now + LIFETIME);
		State st = state(server);
		st.entries.put(id, new Entry(c, held.copyWithCount(1), player.getUUID(), player.getGameProfile().name(), Optional.empty()));
		st.setDirty();
		return Component.translatable("message.starwake.pcontract.posted", count, held.getHoverName(), reward);
	}

	public static Component accept(ServerPlayer player, String id) {
		MinecraftServer server = player.level().getServer();
		State st = state(server);
		Entry e = st.entries.get(id);
		if (e == null || e.taker().isPresent() || server.overworld().getGameTime() >= e.contract().expires()) {
			return Component.translatable("message.starwake.contract.gone");
		}
		if (e.poster().equals(player.getUUID())) return Component.translatable("message.starwake.pcontract.own");
		if (Economy.account(player).contracts().size() >= Economy.MAX_CONTRACTS) return Component.translatable("message.starwake.contract.too_many");
		st.entries.put(id, e.withTaker(Optional.of(player.getUUID())));
		st.setDirty();
		Economy.addContract(player, e.contract());
		return Component.translatable("message.starwake.contract.accepted", Contracts.describe(e.contract()));
	}

	public static Component complete(ServerPlayer player, TradeHubs.Hub hub, String id) {
		MinecraftServer server = player.level().getServer();
		State st = state(server);
		Entry e = st.entries.get(id);
		if (e == null || !e.taker().map(player.getUUID()::equals).orElse(false)) return Component.translatable("message.starwake.contract.gone");
		if (!e.contract().target().equals(hub.id())) return Component.translatable("message.starwake.contract.not_here");
		var inv = player.getInventory();
		int have = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) if (ItemStack.isSameItemSameComponents(inv.getItem(i), e.template())) have += inv.getItem(i).getCount();
		if (have < e.contract().count()) {
			return Component.translatable("message.starwake.contract.need", e.contract().count(), e.template().getHoverName());
		}
		int left = e.contract().count();
		List<ItemStack> goods = st.pickups.computeIfAbsent(e.poster(), k -> new ArrayList<>());
		for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
			ItemStack s = inv.getItem(i);
			if (!ItemStack.isSameItemSameComponents(s, e.template())) continue;
			int n = Math.min(left, s.getCount());
			goods.add(s.copyWithCount(n));
			s.shrink(n);
			left -= n;
		}
		inv.setChanged();
		st.entries.remove(id);
		st.setDirty();
		Economy.removeContract(player, id);
		Economy.markDone(player, id);
		Economy.pay(player, e.contract().reward());
		ServerPlayer poster = server.getPlayerList().getPlayer(e.poster());
		if (poster != null) {
			poster.sendSystemMessage(Component.translatable("message.starwake.pcontract.filled", player.getName(), e.contract().count(),
					e.template().getHoverName()));
		}
		return Component.translatable("message.starwake.contract.completed", e.contract().reward());
	}

	/** The taker gave up (or ran out of time): the request opens again. */
	public static void release(MinecraftServer server, String id) {
		State st = state(server);
		Entry e = st.entries.get(id);
		if (e == null) return;
		st.entries.put(id, e.withTaker(Optional.empty()));
		st.setDirty();
	}

	public static Component cancel(ServerPlayer player, String id) {
		MinecraftServer server = player.level().getServer();
		State st = state(server);
		Entry e = st.entries.get(id);
		if (e == null || !e.poster().equals(player.getUUID())) return Component.translatable("message.starwake.contract.gone");
		if (e.taker().isPresent()) return Component.translatable("message.starwake.pcontract.taken");
		st.entries.remove(id);
		st.setDirty();
		Economy.pay(player, e.contract().reward());
		return Component.translatable("message.starwake.pcontract.cancelled", e.contract().reward());
	}

	public static Component collect(ServerPlayer player) {
		State st = state(player.level().getServer());
		List<ItemStack> goods = st.pickups.remove(player.getUUID());
		if (goods == null || goods.isEmpty()) return Component.translatable("message.starwake.pcontract.nothing");
		int n = 0;
		for (ItemStack s : goods) {
			n += s.getCount();
			if (!player.getInventory().add(s)) player.drop(s, false, net.minecraft.util.Prediction.PREDICTED);
		}
		st.setDirty();
		return Component.translatable("message.starwake.pcontract.collected", n);
	}

	/** Expired requests refund their poster (and drop out of a taker's list). Called with the contract sweep. */
	public static void expire(MinecraftServer server) {
		State st = state(server);
		long now = server.overworld().getGameTime();
		for (Entry e : List.copyOf(st.entries.values())) {
			if (now < e.contract().expires()) continue;
			st.entries.remove(e.contract().id());
			Economy.payTo(server, e.poster(), e.contract().reward());
			e.taker().ifPresent(t -> {
				ServerPlayer taker = server.getPlayerList().getPlayer(t);
				if (taker != null) Economy.removeContract(taker, e.contract().id());
			});
			st.setDirty();
		}
	}

	// --- Saved state ------------------------------------------------------------------------------------

	public static final class State extends SavedData {
		final Map<String, Entry> entries = new LinkedHashMap<>();
		final Map<UUID, List<ItemStack>> pickups = new HashMap<>();

		private record Data(List<Entry> entries, Map<UUID, List<ItemStack>> pickups) {}

		static final Codec<State> CODEC = RecordCodecBuilder.<Data>create(i -> i.group(
				Entry.CODEC.listOf().optionalFieldOf("entries", List.of()).forGetter(Data::entries),
				Codec.unboundedMap(UUIDUtil.STRING_CODEC, ItemStack.CODEC.listOf()).optionalFieldOf("pickups", Map.of()).forGetter(Data::pickups)
		).apply(i, Data::new)).xmap(d -> {
			State s = new State();
			d.entries().forEach(e -> s.entries.put(e.contract().id(), e));
			d.pickups().forEach((k, v) -> s.pickups.put(k, new ArrayList<>(v)));
			return s;
		}, s -> new Data(List.copyOf(s.entries.values()), s.pickups));

		static final SavedDataType<State> TYPE = new SavedDataType<>(StarwakeMod.id("player_contracts"), State::new, CODEC,
				DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
	}

	static State state(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(State.TYPE);
	}
}
