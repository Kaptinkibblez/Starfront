package com.starwake.trade;

import com.starwake.StarwakeMod;
import com.starwake.galaxy.Galaxy;
import com.starwake.galaxy.SolarSystem;
import com.starwake.network.ContractBoardPayload;
import com.starwake.registry.ModHubBlocks;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.levelgen.RandomSupport;

/**
 * Contracts (M7). Every hub's Contract Board offers four contracts a day, seeded by (hub, day)
 * and drawn from the types its faction issues:
 * <ul>
 *   <li><b>delivery</b>: bring goods another hub in the system consumes to that hub;</li>
 *   <li><b>mining</b>: bring a quota of a raw mineral back to this hub;</li>
 *   <li><b>courier</b>: carry a sealed package (given on accepting) to another hub;</li>
 *   <li><b>exploration</b>: reach a star system 4-12 ly away (completes on arrival).</li>
 * </ul>
 * Rewards are credits and reputation with the issuing faction. Up to three at once; they expire
 * after three in-game days, and abandoning or letting one expire costs a little reputation.
 */
public final class Contracts {
	public static final long DAY = 24000;
	private static final int OFFERS = 4;
	private static final long DURATION = 3 * DAY;

	private Contracts() {}

	public static void init() {
		// Exploration contracts complete when you arrive in the target system.
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) -> arrived(player, SolarSystem.systemOf(to)));
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 200 != 0) return;
			for (ServerPlayer p : server.getPlayerList().getPlayers()) expire(p);
		});
	}

	// ---------------------------------------------------------------------------------------
	// Offers
	// ---------------------------------------------------------------------------------------

	public static List<Contract> offers(MinecraftServer server, TradeHubs.Hub hub) {
		long now = server.overworld().getGameTime();
		long day = now / DAY;
		var faction = server.registryAccess().lookupOrThrow(Faction.REGISTRY_KEY).get(StarwakeMod.id(hub.faction())).orElse(null);
		if (faction == null || faction.value().contracts().isEmpty()) return List.of();
		List<String> types = faction.value().contracts();
		List<Contract> out = new ArrayList<>();
		for (int slot = 0; slot < OFFERS; slot++) {
			RandomSource r = RandomSource.create(RandomSupport.mixStafford13(hub.id().hashCode() * 31L + day * 0x9E3779B97F4A7C15L + slot));
			String type = types.get(r.nextInt(types.size()));
			Contract c = make(server, hub, type, r, hub.id() + "#" + day + "#" + slot, (day + 1) * DAY + DURATION);
			if (c != null) out.add(c);
		}
		return out;
	}

	private static Contract make(MinecraftServer server, TradeHubs.Hub hub, String type, RandomSource r, String id, long expires) {
		List<TradeHubs.Hub> others = new ArrayList<>(TradeHubs.plan(server, hub.system()).stream().filter(h -> !h.id().equals(hub.id())).toList());
		switch (type) {
			case "delivery" -> {
				if (others.isEmpty()) return null;
				TradeHubs.Hub target = others.get(r.nextInt(others.size()));
				List<Markets.Listing> wants = Markets.listings(server, target).stream().filter(l -> !l.producer()).toList();
				if (wants.isEmpty()) return null;
				Markets.Listing want = wants.get(r.nextInt(wants.size()));
				int count = countFor(want.base(), r, 300, 1400);
				long reward = Math.round(want.base() * count * 0.7 + 150);
				return new Contract(id, type, hub.faction(), hub.id(), target.id(), target.name(), want.item(), count, reward, 15, expires);
			}
			case "mining" -> {
				var raw = TradeData.group("raw_minerals").orElse(null);
				if (raw == null || raw.items().isEmpty()) return null;
				List<Identifier> items = raw.items().keySet().stream().sorted().toList();
				Identifier item = items.get(r.nextInt(items.size()));
				double base = raw.items().get(item);
				int count = countFor(base, r, 300, 1600);
				long reward = Math.round(base * count * 1.6 + 100);
				return new Contract(id, type, hub.faction(), hub.id(), hub.id(), hub.name(), item, count, reward, 12, expires);
			}
			case "courier" -> {
				if (others.isEmpty()) return null;
				TradeHubs.Hub target = others.get(r.nextInt(others.size()));
				long reward = 350 + r.nextInt(250) + (target.station() != hub.station() ? 150 : 0);
				return new Contract(id, type, hub.faction(), hub.id(), target.id(), target.name(),
						BuiltInRegistries.ITEM.getKey(ModHubBlocks.SEALED_CARGO), 1, reward, 20, expires);
			}
			case "exploration" -> {
				var here = Galaxy.star(hub.system()).orElse(null);
				if (here == null) return null;
				List<Galaxy.Star> near = Galaxy.within(here.position(), 12).stream()
						.filter(s -> !s.id().equals(here.id()) && s.distanceTo(here) >= 3).sorted(java.util.Comparator.comparing(Galaxy.Star::id)).toList();
				if (near.isEmpty()) return null;
				Galaxy.Star target = near.get(r.nextInt(near.size()));
				double ly = target.distanceTo(here);
				long reward = Math.round(300 + 140 * ly);
				return new Contract(id, type, hub.faction(), hub.id(), target.id(), target.name(), Identifier.withDefaultNamespace("air"), 0,
						reward, 25, expires);
			}
			default -> {
				return null;
			}
		}
	}

	/** A count that makes the job worth roughly between {@code min} and {@code max} credits. */
	private static int countFor(double unit, RandomSource r, int min, int max) {
		double value = min + r.nextDouble() * (max - min);
		return Math.clamp((int) Math.round(value / Math.max(0.5, unit)), 4, 64);
	}

	// ---------------------------------------------------------------------------------------
	// Board
	// ---------------------------------------------------------------------------------------

	public static ContractBoardPayload board(ServerPlayer player, TradeHubs.Hub hub) {
		MinecraftServer server = player.level().getServer();
		var active = Economy.account(player).contracts();
		List<Contract> offers = new ArrayList<>();
		for (Contract c : offers(server, hub)) {
			if (Economy.isDone(player, c.id()) || active.stream().anyMatch(a -> a.id().equals(c.id()))) continue;
			offers.add(c);
		}
		List<Contract> completable = active.stream().filter(c -> c.target().equals(hub.id())).toList();
		return new ContractBoardPayload(hub.id(), Component.literal(hub.name()), hub.faction(), Economy.credits(player), offers, completable);
	}

	public static Component accept(ServerPlayer player, TradeHubs.Hub hub, String id) {
		var account = Economy.account(player);
		if (account.contracts().size() >= Economy.MAX_CONTRACTS) return Component.translatable("message.starwake.contract.too_many");
		Contract offer = offers(player.level().getServer(), hub).stream().filter(c -> c.id().equals(id)).findFirst().orElse(null);
		if (offer == null || Economy.isDone(player, id) || account.contracts().stream().anyMatch(c -> c.id().equals(id))) {
			return Component.translatable("message.starwake.contract.gone");
		}
		if (offer.type().equals("courier")) {
			ItemStack cargo = new ItemStack(ModHubBlocks.SEALED_CARGO);
			CompoundTag tag = new CompoundTag();
			tag.putString("contract", offer.id());
			cargo.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
			cargo.set(DataComponents.CUSTOM_NAME, Component.translatable("item.starwake.sealed_cargo.for", offer.targetName()));
			if (!player.getInventory().add(cargo)) return Component.translatable("message.starwake.contract.no_room");
		}
		Economy.addContract(player, offer);
		return Component.translatable("message.starwake.contract.accepted", describe(offer));
	}

	public static Component complete(ServerPlayer player, TradeHubs.Hub hub, String id) {
		Contract c = Economy.account(player).contracts().stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
		if (c == null || !c.target().equals(hub.id())) return Component.translatable("message.starwake.contract.not_here");
		switch (c.type()) {
			case "courier" -> {
				int slot = cargoSlot(player, c.id());
				if (slot < 0) return Component.translatable("message.starwake.contract.no_cargo");
				player.getInventory().getItem(slot).shrink(1);
			}
			case "delivery", "mining" -> {
				Item item = BuiltInRegistries.ITEM.getValue(c.item());
				if (player.getInventory().countItem(item) < c.count()) {
					return Component.translatable("message.starwake.contract.need", c.count(), new ItemStack(item).getHoverName());
				}
				Markets.removeItems(player, item, c.count());
			}
			default -> {
				return Component.translatable("message.starwake.contract.not_here");
			}
		}
		finish(player, c);
		return Component.translatable("message.starwake.contract.completed", c.reward());
	}

	public static Component abandon(ServerPlayer player, String id) {
		Contract c = Economy.account(player).contracts().stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
		if (c == null) return Component.translatable("message.starwake.contract.gone");
		Economy.removeContract(player, id);
		Economy.markDone(player, id);
		Economy.addRep(player, c.faction(), -5);
		return Component.translatable("message.starwake.contract.abandoned");
	}

	private static void finish(ServerPlayer player, Contract c) {
		Economy.removeContract(player, c.id());
		Economy.markDone(player, c.id());
		Economy.pay(player, c.reward());
		Economy.addRep(player, c.faction(), c.rep());
	}

	private static int cargoSlot(ServerPlayer player, String contract) {
		var inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!s.is(ModHubBlocks.SEALED_CARGO)) continue;
			CustomData data = s.get(DataComponents.CUSTOM_DATA);
			if (data != null && contract.equals(data.copyTag().getStringOr("contract", ""))) return i;
		}
		return -1;
	}

	private static void arrived(ServerPlayer player, String system) {
		Economy.visit(player, system);
		for (Contract c : List.copyOf(Economy.account(player).contracts())) {
			if (c.type().equals("exploration") && c.target().equals(system)) {
				finish(player, c);
				player.sendSystemMessage(Component.translatable("message.starwake.contract.explored", c.targetName(), c.reward()));
			}
		}
	}

	private static void expire(ServerPlayer player) {
		long now = player.level().getServer().overworld().getGameTime();
		for (Contract c : List.copyOf(Economy.account(player).contracts())) {
			if (now < c.expires()) continue;
			Economy.removeContract(player, c.id());
			Economy.markDone(player, c.id());
			Economy.addRep(player, c.faction(), -5);
			player.sendSystemMessage(Component.translatable("message.starwake.contract.expired", describe(c)));
		}
	}

	/** "Deliver 24 Bread to Isorraix Mining Camp" style summary. */
	public static Component describe(Contract c) {
		Component item = new ItemStack(BuiltInRegistries.ITEM.getValue(c.item())).getHoverName();
		return switch (c.type()) {
			case "delivery" -> Component.translatable("contract.starwake.delivery", c.count(), item, c.targetName());
			case "mining" -> Component.translatable("contract.starwake.mining", c.count(), item, c.targetName());
			case "courier" -> Component.translatable("contract.starwake.courier", c.targetName());
			case "exploration" -> Component.translatable("contract.starwake.exploration", c.targetName());
			default -> Component.literal(c.type());
		};
	}
}
