package com.starwake.ship;

import com.starwake.StarwakeMod;
import com.starwake.planet.Planet;
import com.starwake.planet.Planets;
import com.starwake.ship.data.ShipPhysics;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Moving ships between planets and space.
 *
 * - Climb above a planet's build height (plus a margin) in a sealed ship: the ship and
 *   everyone aboard move into {@code starwake:space} at the same X/Z, orbiting that planet.
 * - Descend below the bottom of space: re-enter the orbited planet from above.
 * - "Set course" (ship console, in space) changes the orbited planet for a fuel cost. The
 *   Milestone 6 star map will replace this list.
 */
public final class SpaceTravel {
	public static final ResourceKey<Level> SPACE = ResourceKey.create(Registries.DIMENSION, StarwakeMod.id("space"));

	/** Blocks above a planet's build limit at which a ship leaves the atmosphere. */
	public static final int EXIT_MARGIN = 32;
	/** Altitude (in space) at which arriving ships appear. */
	public static final double SPACE_ARRIVAL_Y = 192;
	/** Below this altitude in space, a ship re-enters its orbited planet. */
	public static final double SPACE_REENTRY_Y = 16;
	/** Blocks above the planet's build limit at which re-entering ships appear. */
	public static final int REENTRY_HEIGHT = 8;

	private SpaceTravel() {}

	public static boolean isSpace(Level level) {
		return level.dimension().equals(SPACE);
	}

	/**
	 * Players who fall out of the bottom of space (e.g. stepping off a ship) are returned to
	 * their return point or world spawn instead of dying in the void. Players only: cheap.
	 */
	public static void init() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_LEVEL_TICK.register(level -> {
			if (!isSpace(level)) return;
			for (ServerPlayer player : List.copyOf(level.players())) {
				if (player.getY() < 0 && !player.isPassenger()) {
					com.starwake.planet.PlanetTravel.returnHome(player);
					player.sendSystemMessage(Component.translatable("message.starwake.lost_in_space"));
				}
			}
		});
	}

	/**
	 * Called every server tick for moving ships. Returns true if the ship was transferred (the
	 * caller must stop ticking it: it has been replaced by a copy in another level).
	 */
	public static boolean checkAltitude(ShipEntity ship, ServerLevel level) {
		if (!isSpace(level)) {
			double ceiling = level.getMaxY() + EXIT_MARGIN;
			if (ship.getY() <= ceiling) return false;
			if (!ship.isSealed()) {
				// Unsealed hulls can't leave the atmosphere: hold them at the ceiling.
				ship.setPos(ship.getX(), ceiling, ship.getZ());
				ship.setDeltaMovement(ship.getDeltaMovement().multiply(1, 0, 1));
				if (ship.getFirstPassenger() instanceof ServerPlayer pilot && ship.tickCount % 40 == 0) {
					pilot.sendOverlayMessage(Component.translatable("message.starwake.not_sealed_for_space"));
				}
				return false;
			}
			ship.setOrbiting(level.dimension());
			return transfer(ship, level.getServer().getLevel(SPACE), new Vec3(ship.getX(), SPACE_ARRIVAL_Y, ship.getZ()));
		}
		// Space has a ceiling too: hold ships below the top of the dimension.
		double top = level.getMaxY() - (ship.structure() == null ? 1 : ship.structure().size().getY());
		if (ship.getY() > top) {
			ship.setPos(ship.getX(), top, ship.getZ());
			ship.setDeltaMovement(ship.getDeltaMovement().multiply(1, 0, 1));
		}
		if (ship.getY() >= SPACE_REENTRY_Y) return false;
		ResourceKey<Level> planet = ship.orbiting() != null ? ship.orbiting() : Level.OVERWORLD;
		ServerLevel destination = level.getServer().getLevel(planet);
		if (destination == null) destination = level.getServer().overworld();
		return transfer(ship, destination, new Vec3(ship.getX(), destination.getMaxY() + REENTRY_HEIGHT, ship.getZ()));
	}

	/** Moves the ship and everyone on board (pilot included) to {@code origin} in {@code to}. */
	private static boolean transfer(ShipEntity ship, @Nullable ServerLevel to, Vec3 origin) {
		if (to == null || !(ship.level() instanceof ServerLevel from)) return false;

		// Everyone standing on or inside the ship (the pilot rides along via vanilla). Riders
		// haven't been carried for this tick's move yet, so measure in the ship's old frame.
		Vec3 pending = ship.movementThisTick();
		List<Entity> aboard = new ArrayList<>();
		List<Vec3> locals = new ArrayList<>();
		for (Entity entity : from.getEntities(ship, ship.getBoundingBox().expandTowards(pending.reverse()).inflate(0.5, 1.5, 0.5),
				e -> !(e instanceof ShipEntity) && !e.isPassenger())) {
			Vec3 local = ship.toLocalAtLastTick(entity.position());
			if (ship.isAboard(entity, local)) {
				aboard.add(entity);
				locals.add(local);
			}
		}
		Vec3 shift = origin.subtract(ship.position());

		Entity moved = ship.teleport(new TeleportTransition(to, origin, ship.getDeltaMovement(), 0, 0,
				TeleportTransition.PLACE_PORTAL_TICKET));
		if (moved == null) return false;
		for (int i = 0; i < aboard.size(); i++) {
			Entity entity = aboard.get(i);
			Vec3 target = ship.toWorld(locals.get(i)).add(shift); // same local spot on the moved ship
			entity.teleport(new TeleportTransition(to, target, entity.getDeltaMovement(),
					entity.getYRot(), entity.getXRot(), TeleportTransition.DO_NOTHING));
		}
		if (moved.getFirstPassenger() instanceof ServerPlayer pilot) {
			pilot.sendOverlayMessage(Component.translatable(isSpace(to)
					? "message.starwake.entered_space" : "message.starwake.entered_atmosphere", planetName(to, to.dimension())));
		}
		return true;
	}

	/** Planets a ship in space can set a course for (excluding the one it orbits). */
	public static List<Holder.Reference<Planet>> destinations(ShipEntity ship) {
		List<Holder.Reference<Planet>> result = new ArrayList<>();
		Planets.registry(ship.level().getServer()).listElements().forEach(planet -> {
			if (planet.value().destination() && !planet.value().dimension().equals(ship.orbiting())) {
				result.add(planet);
			}
		});
		return result;
	}

	/** @return null on success, or why the course can't be set */
	public static @Nullable Component setCourse(ShipEntity ship, ResourceKey<Planet> target) {
		if (!isSpace(ship.level())) return Component.translatable("message.starwake.course.not_in_space");
		var planet = Planets.registry(ship.level().getServer()).get(target).orElse(null);
		if (planet == null || !planet.value().destination()) return Component.translatable("message.starwake.course.unknown");
		int cost = ShipPhysics.get().courseFuelCost();
		if (ship.fuel() < cost) return Component.translatable("message.starwake.course.no_fuel", cost);
		ship.useFuel(cost);
		ship.setOrbiting(planet.value().dimension());
		return null;
	}

	public static Component planetName(Level level, @Nullable ResourceKey<Level> dimension) {
		if (dimension == null) return Component.literal("?");
		return Planets.forDimension(dimension).map(p -> p.value().name())
				.orElse(Component.literal(dimension.identifier().toString()));
	}
}
