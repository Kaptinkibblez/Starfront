package com.starwake.trade;

import com.starwake.registry.ModEntities;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.AABB;

/**
 * Spawns and keeps up a hub's guards and crew (M7). A built hub spawns everyone in its template's
 * NPC list; every minute, hubs near a player get missing people replaced (so a cleared hub fills
 * up again, slowly).
 */
public final class HubNpcs {
	private HubNpcs() {}

	public static void spawnAll(ServerLevel level, TradeHubs.Hub hub, TradeData.Template template, BlockPos origin) {
		spawn(level, hub, template, origin, 0);
	}

	private static void spawn(ServerLevel level, TradeHubs.Hub hub, TradeData.Template template, BlockPos origin, int from) {
		List<TradeData.NpcSpawn> spawns = template.npcs();
		for (int i = from; i < spawns.size(); i++) {
			var s = spawns.get(i);
			if (s.pos().size() != 3) continue;
			BlockPos post = origin.offset(s.pos().get(0), s.pos().get(1), s.pos().get(2));
			HubNpcEntity npc = ModEntities.HUB_NPC.create(level, EntitySpawnReason.STRUCTURE);
			if (npc == null) continue;
			npc.snapTo(post.getX() + 0.5, post.getY(), post.getZ() + 0.5, level.getRandom().nextFloat() * 360, 0);
			boolean guard = s.role().equals("guard");
			npc.setup(hub.id(), hub.faction(), s.role(), level.getRandom().nextInt(HubNpcEntity.VARIANTS), post, guard ? 5 : 9);
			level.addFreshEntity(npc);
		}
	}

	/** Refills hubs within 96 blocks of players (called every 60 s). */
	public static void upkeep(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerLevel level = player.level();
			for (TradeHubs.Hub hub : TradeHubs.plan(server, com.starwake.galaxy.SolarSystem.systemOf(level))) {
				if (!hub.dimension().equals(level.dimension())) continue;
				BlockPos origin = TradeHubs.origin(server, hub);
				var t = TradeData.template(hub.template()).orElse(null);
				if (origin == null || t == null) continue;
				AABB box = new AABB(origin.getX(), origin.getY(), origin.getZ(), origin.getX() + t.sizeX(), origin.getY() + t.sizeY(),
						origin.getZ() + t.sizeZ()).inflate(16);
				if (!box.inflate(80).contains(player.position())) continue;
				int present = level.getEntitiesOfClass(HubNpcEntity.class, box, n -> n.hub().equals(hub.id())).size();
				if (present < t.npcs().size()) spawn(level, hub, t, origin, present);
			}
		}
	}
}
