package com.starwake.trade;

import com.starwake.network.TradeActionPayload;
import com.starwake.network.ShopPayload;
import com.starwake.trade.block.TerminalBlock;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Hub terminals (M7), server side: opening a terminal sends its screen's data, and every action
 * ({@link TradeActionPayload}) is re-validated (the player must be inside that hub, the faction
 * must not be hostile) before markets, the outfitter or contracts act on it; the screen is then
 * refreshed and the result shown on the action bar.
 */
public final class Terminals {
	private Terminals() {}

	public static void open(ServerPlayer player, BlockPos pos, TerminalBlock.Kind kind) {
		var hub = TradeHubs.hubAt(player.level(), pos).orElse(null);
		if (hub == null) {
			player.sendOverlayMessage(Component.translatable("message.starwake.trade.no_link"));
			return;
		}
		if (Economy.hostile(Economy.rep(player, hub.faction()))) {
			player.sendOverlayMessage(Component.translatable("message.starwake.trade.hostile"));
			return;
		}
		send(player, hub, kind);
	}

	private static void send(ServerPlayer player, TradeHubs.Hub hub, TerminalBlock.Kind kind) {
		switch (kind) {
			case COMMODITY -> ServerPlayNetworking.send(player, Markets.quote(player, hub, Component.literal(hub.name())));
			case OUTFITTER -> ServerPlayNetworking.send(player, shop(player, hub));
			case CONTRACTS -> ServerPlayNetworking.send(player, Contracts.board(player, hub));
		}
	}

	public static ShopPayload shop(ServerPlayer player, TradeHubs.Hub hub) {
		List<ShopPayload.Line> lines = new ArrayList<>();
		int rep = Economy.rep(player, hub.faction());
		double factor = Economy.priceFactor(rep);
		TradeData.economy(hub.economy()).flatMap(TradeData.Economy::outfitter).flatMap(TradeData::shop).ifPresent(shop -> {
			for (var item : shop.items()) {
				if (!BuiltInRegistries.ITEM.containsKey(item.item())) continue;
				lines.add(new ShopPayload.Line(item.item(), item.count(), Math.round(item.price() * factor), item.rep(), rep < item.rep()));
			}
		});
		return new ShopPayload(hub.id(), Component.literal(hub.name()), hub.faction(), Economy.credits(player), lines);
	}

	public static void action(ServerPlayer player, TradeActionPayload p) {
		MinecraftServer server = player.level().getServer();
		var hub = TradeHubs.hub(server, p.hub()).orElse(null);
		var at = TradeHubs.hubAt(player.level(), player.blockPosition()).orElse(null);
		if (hub == null || at == null || !at.id().equals(hub.id())) {
			player.sendOverlayMessage(Component.translatable("message.starwake.trade.too_far"));
			return;
		}
		if (Economy.hostile(Economy.rep(player, hub.faction()))) {
			player.sendOverlayMessage(Component.translatable("message.starwake.trade.hostile"));
			return;
		}
		Component result;
		TerminalBlock.Kind refresh;
		switch (p.action()) {
			case "buy" -> {
				result = Markets.buy(player, hub, net.minecraft.resources.Identifier.parse(p.arg()), p.count());
				refresh = TerminalBlock.Kind.COMMODITY;
			}
			case "sell" -> {
				result = Markets.sell(player, hub, net.minecraft.resources.Identifier.parse(p.arg()), p.count());
				refresh = TerminalBlock.Kind.COMMODITY;
			}
			case "shop" -> {
				result = buyFromShop(player, hub, p.count());
				refresh = TerminalBlock.Kind.OUTFITTER;
			}
			case "accept" -> {
				result = Contracts.accept(player, hub, p.arg());
				refresh = TerminalBlock.Kind.CONTRACTS;
			}
			case "complete" -> {
				result = Contracts.complete(player, hub, p.arg());
				refresh = TerminalBlock.Kind.CONTRACTS;
			}
			default -> {
				return;
			}
		}
		player.sendOverlayMessage(result);
		send(player, hub, refresh);
	}

	private static Component buyFromShop(ServerPlayer player, TradeHubs.Hub hub, int index) {
		var lines = shop(player, hub).lines();
		if (index < 0 || index >= lines.size()) return Component.translatable("message.starwake.trade.not_traded");
		var line = lines.get(index);
		if (line.locked()) return Component.translatable("message.starwake.trade.rep_needed", line.rep());
		if (!Economy.spend(player, line.price())) return Component.translatable("message.starwake.trade.no_credits", line.price());
		ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(line.item()), line.count());
		Component name = stack.getHoverName();
		if (!player.getInventory().add(stack)) player.drop(stack, false, net.minecraft.util.Prediction.PREDICTED);
		return Component.translatable("message.starwake.trade.bought", line.count(), name, line.price());
	}
}
