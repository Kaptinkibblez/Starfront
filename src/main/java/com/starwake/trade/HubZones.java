package com.starwake.trade;

import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Pressurized hub interiors (M7): station hangars (held by a pressure field) and habitat
 * interiors are breathable at room temperature. The server fills its map from built hubs, the
 * client from {@code HubListPayload}; {@code PlanetHazards} and the environment HUD ask here.
 */
public final class HubZones {
	private static volatile Map<ResourceKey<Level>, List<AABB>> server = Map.of();
	private static volatile Map<ResourceKey<Level>, List<AABB>> client = Map.of();

	private HubZones() {}

	public static void set(boolean clientSide, Map<ResourceKey<Level>, List<AABB>> zones) {
		if (clientSide) client = Map.copyOf(zones);
		else server = Map.copyOf(zones);
	}

	public static void clear(boolean clientSide) {
		set(clientSide, Map.of());
	}

	public static boolean isPressurized(Level level, Vec3 pos) {
		List<AABB> zones = (level.isClientSide() ? client : server).get(level.dimension());
		if (zones == null) return false;
		for (AABB box : zones) if (box.contains(pos)) return true;
		return false;
	}
}
