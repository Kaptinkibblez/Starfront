package com.starwake.ship;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Index of loaded ships per level, and the hook that makes ships solid for every other entity.
 *
 * {@link com.starwake.mixin.EntityCollideMixin} calls {@link #append} whenever an entity
 * gathers collision shapes for its movement, so players and mobs collide with ship blocks
 * exactly like world blocks, on both client (prediction) and server. With no ships loaded,
 * this costs one map lookup.
 */
public final class ShipCollisions {
	/** Keyed by level instance (client and integrated server levels are distinct objects). */
	private static final Map<Level, List<ShipEntity>> SHIPS = new ConcurrentHashMap<>();

	private ShipCollisions() {}

	/** Server side: track ships as they load/unload. The client registers its own listeners. */
	public static void init() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof ShipEntity ship) add(ship);
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof ShipEntity ship) remove(ship);
		});
	}

	public static void add(ShipEntity ship) {
		SHIPS.computeIfAbsent(ship.level(), level -> new CopyOnWriteArrayList<>()).add(ship);
	}

	public static void remove(ShipEntity ship) {
		List<ShipEntity> ships = SHIPS.get(ship.level());
		if (ships != null) {
			ships.remove(ship);
			if (ships.isEmpty()) {
				SHIPS.remove(ship.level());
			}
		}
	}

	public static List<ShipEntity> shipsIn(Level level) {
		return SHIPS.getOrDefault(level, List.of());
	}

	/** True if any ship's bounding box intersects {@code area}. */
	public static boolean isNearShip(Level level, AABB area) {
		for (ShipEntity ship : SHIPS.getOrDefault(level, List.of())) {
			if (ship.getBoundingBox().intersects(area)) return true;
		}
		return false;
	}

	/** Returns {@code base} plus the collision shapes of every ship intersecting {@code area}. */
	public static List<VoxelShape> append(Level level, @Nullable Entity source, AABB area, List<VoxelShape> base) {
		List<ShipEntity> ships = SHIPS.get(level);
		if (ships == null) {
			return base;
		}
		List<VoxelShape> result = null;
		for (ShipEntity ship : ships) {
			// A ship never collides with itself, and its pilot is positioned, not moved.
			if (ship == source || (source != null && source.getVehicle() == ship)) continue;
			if (!ship.getBoundingBox().intersects(area)) continue;
			if (result == null) result = new ArrayList<>(base);
			ship.collectShapes(area, result);
		}
		return result == null ? base : result;
	}
}
