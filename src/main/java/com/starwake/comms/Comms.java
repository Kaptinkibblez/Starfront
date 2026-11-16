package com.starwake.comms;

import com.starwake.galaxy.SolarSystem;
import com.starwake.network.CommsMessagePayload;
import com.starwake.network.CommsPayload;
import com.starwake.ship.ShipEntity;
import com.starwake.trade.Economy;
import com.starwake.trade.TradeHubs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Comms (M9), the Wristcom radio: player-to-player messages (any online player), hails to trade hubs in your star
 * system (the hub answers with docking information: where it is, who runs it, your standing, its services and how to
 * come aboard) and hails to ships within 2 km (delivered to the pilot and the owner). Messages go to both ends' inboxes
 * ({@link CommsMessagePayload}); nothing is stored on the server.
 */
public final class Comms {
	private static final double SHIP_RANGE = 2000;

	private Comms() {}

	public static void handle(ServerPlayer player, CommsPayload p) {
		MinecraftServer server = player.level().getServer();
		String text = p.text().strip();
		switch (p.action()) {
			case "msg" -> {
				ServerPlayer to = server.getPlayerList().getPlayerByName(p.target());
				if (to == null) {
					reply(player, p.target(), Component.translatable("comms.starwake.offline", p.target()), "error");
					return;
				}
				if (text.isEmpty()) return;
				send(to, player.getGameProfile().name(), player.getName(), Component.literal(text), "msg");
				send(player, p.target(), player.getName(), Component.literal(text), "sent");
			}
			case "hail_hub" -> {
				var hub = TradeHubs.hub(server, p.target()).orElse(null);
				String channel = "hub:" + p.target();
				if (hub == null || !hub.system().equals(SolarSystem.systemOf(player.level()))) {
					reply(player, channel, Component.translatable("comms.starwake.no_answer"), "error");
					return;
				}
				Component name = Component.literal(hub.name());
				BlockPos origin = TradeHubs.origin(server, hub);
				BlockPos at = origin != null ? origin : hub.anchor();
				Component where = hub.dimension().equals(player.level().dimension())
						? Component.translatable("comms.starwake.hub.distance", String.format("%.1f", Math.sqrt(player.blockPosition().distSqr(at)) / 1000.0))
						: Component.translatable(hub.station() ? "comms.starwake.hub.orbit" : "comms.starwake.hub.on", hub.planet());
				int rep = Economy.rep(player, hub.faction());
				send(player, channel, name, Component.translatable("comms.starwake.hub.greeting", name,
						TradeHubs.factionName(server, hub.faction())), "hub");
				send(player, channel, name, Component.translatable("comms.starwake.hub.where", where, at.getX(), at.getY(), at.getZ()), "hub");
				send(player, channel, name, Component.translatable("comms.starwake.hub.standing",
						Component.translatable("rank.starwake." + Economy.rank(rep)), rep), "hub");
				send(player, channel, name, Component.translatable(Economy.hostile(rep) ? "comms.starwake.hub.refused"
						: hub.station() ? "comms.starwake.hub.dock_station" : "comms.starwake.hub.dock_outpost"), "hub");
				send(player, channel, name, Component.translatable("comms.starwake.hub.services"), "hub");
			}
			case "hail_ship" -> {
				String channel = "ship:" + p.target();
				int id;
				try {
					id = Integer.parseInt(p.target());
				} catch (NumberFormatException e) {
					return;
				}
				if (!(player.level().getEntity(id) instanceof ShipEntity ship) || ship.distanceTo(player) > SHIP_RANGE) {
					reply(player, channel, Component.translatable("comms.starwake.no_answer"), "error");
					return;
				}
				Component line = text.isEmpty() ? Component.translatable("comms.starwake.ship.hail", player.getName()) : Component.literal(text);
				boolean delivered = false;
				java.util.Set<ServerPlayer> crew = new java.util.LinkedHashSet<>();
				if (ship.pilot() instanceof ServerPlayer pilot) crew.add(pilot);
				if (ship.owner() != null && server.getPlayerList().getPlayer(ship.owner()) instanceof ServerPlayer owner) crew.add(owner);
				for (ServerPlayer to : crew) {
					if (to == player) continue;
					send(to, player.getGameProfile().name(), player.getName(), line, "hail");
					delivered = true;
				}
				send(player, channel, player.getName(), line, "sent");
				if (!delivered) reply(player, channel, Component.translatable("comms.starwake.ship.nobody"), "error");
			}
			default -> {}
		}
	}

	private static void reply(ServerPlayer player, String channel, Component text, String kind) {
		send(player, channel, Component.translatable("comms.starwake.system"), text, kind);
	}

	private static void send(ServerPlayer to, String channel, Component from, Component text, String kind) {
		ServerPlayNetworking.send(to, new CommsMessagePayload(channel, from, text, kind));
	}
}
