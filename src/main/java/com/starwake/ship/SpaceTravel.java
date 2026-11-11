package com.starwake.ship;

import com.starwake.StarwakeMod;
import com.starwake.galaxy.SolarSystem;
import com.starwake.planet.Planet;
import com.starwake.planet.Planets;
import com.starwake.ship.data.ShipPhysics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Moving ships between planets and space (3c, reworked in 3g: "space as a real place").
 *
 * - Planets are spheres at fixed places in {@code starwake:space} ({@link SolarSystem}).
 * - Climb above a planet's build height (plus a margin) in a sealed ship: the ship and
 *   everyone aboard appear just outside that planet's atmosphere in space, at the point of the
 *   sphere matching where they left, pointing away from the planet.
 * - Fly into a planet's atmosphere sphere in space: arrive above the matching spot on its
 *   surface, still moving in the same direction relative to the ground.
 * - Crossing the gulf between planets takes quantum travel (see {@link ShipEntity}'s quantum
 *   drive); {@link #jump} moves the ship when a jump completes.
 */
public final class SpaceTravel {
	public static final ResourceKey<Level> SPACE = ResourceKey.create(Registries.DIMENSION, StarwakeMod.id("space"));

	/** Blocks above a planet's build limit at which a ship leaves the atmosphere. */
	public static final int EXIT_MARGIN = 32;
	/** Blocks above the planet's build limit at which arriving ships appear. */
	public static final int REENTRY_HEIGHT = 8;
	/** How far outside the atmosphere sphere a ship leaving a planet appears. */
	public static final double ORBIT_EXIT_MARGIN = 96;
	/** Keep ships this far inside space's top and bottom (space is a slab around the ecliptic). */
	public static final int SLAB_MARGIN = 16;

	private SpaceTravel() {}

	public static boolean isSpace(Level level) {
		return level.dimension().equals(SPACE);
	}

	/**
	 * Players who fall out of the bottom of space (e.g. drifting away on EVA) are returned to
	 * their return point or world spawn instead of dying in the void. Players only: cheap.
	 */
	public static void init() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_LEVEL_TICK.register(level -> {
			if (!isSpace(level)) return;
			for (ServerPlayer player : List.copyOf(level.players())) {
				if (player.getY() < level.getMinY() && !player.isPassenger()) {
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
		if (!isSpace(level)) return checkLeavingPlanet(ship, level);

		// Space is a slab: hold ships inside its top and bottom.
		double height = ship.structure() == null ? 1 : ship.structure().size().getY();
		double top = level.getMaxY() - SLAB_MARGIN - height, bottom = level.getMinY() + SLAB_MARGIN + height;
		if (ship.getY() > top || ship.getY() < bottom) {
			ship.setPos(ship.getX(), Math.clamp(ship.getY(), bottom, top), ship.getZ());
			ship.setDeltaMovement(ship.getDeltaMovement().multiply(1, 0, 1));
		}

		// Flying into a planet's atmosphere takes you down to its surface.
		for (SolarSystem.Entry body : SolarSystem.bodies(level.registryAccess())) {
			double distance = ship.position().distanceTo(body.body().position());
			if (body.body().star() && distance < body.body().radius() * 1.2) {
				// Too close to the star: the hull is pushed back out (and the crew get the hint).
				Vec3 out = ship.position().subtract(body.body().position()).normalize();
				ship.setPos(body.body().position().add(out.scale(body.body().radius() * 1.2)));
				ship.setDeltaMovement(out.scale(0.5));
				continue;
			}
			if (!body.enterable() || distance >= body.body().entryRadius()) continue;
			ServerLevel destination = level.getServer().getLevel(body.dimension());
			if (destination == null) continue;
			double[] lonLat = SolarSystem.lonLat(body.body(), ship.position());
			double x = lonLat[0] * body.body().worldRadius(), z = lonLat[1] * body.body().worldRadius();
			// Space -> surface frame: undo the (east, up, north) rotation at the entry point.
			Quaternionf toPlanet = SolarSystem.planetToSpace(lonLat[0], lonLat[1]).conjugate();
			ship.setOrbiting(body.dimension());
			return transfer(ship, destination, new Vec3(x, destination.getMaxY() + REENTRY_HEIGHT, z), toPlanet);
		}
		return false;
	}

	private static boolean checkLeavingPlanet(ShipEntity ship, ServerLevel level) {
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
		ServerLevel space = level.getServer().getLevel(SPACE);
		if (space == null) return false;
		ship.setOrbiting(level.dimension());
		Optional<SolarSystem.Entry> planet = SolarSystem.forDimension(level.registryAccess(), level.dimension());
		if (planet.isEmpty()) {
			// A planet with no place in space (e.g. a datapack planet without a "body").
			return transfer(ship, space, new Vec3(ship.getX(), 0, ship.getZ()), new Quaternionf());
		}
		var body = planet.get().body();
		double distance = body.entryRadius() + ORBIT_EXIT_MARGIN;
		double lon = ship.getX() / body.worldRadius();
		double lat = SolarSystem.latitude(body, ship.getZ(), distance, space.getMaxY() - 128 - body.position().y);
		Vec3 origin = body.position().add(SolarSystem.radial(lon, lat).scale(distance));
		return transfer(ship, space, origin, SolarSystem.planetToSpace(lon, lat));
	}

	/**
	 * Moves the ship and everyone on board (pilot included) to {@code origin} in {@code to},
	 * turning the whole ship (and its velocity, and everyone's view) by {@code rotation}.
	 */
	private static boolean transfer(ShipEntity ship, @Nullable ServerLevel to, Vec3 origin, Quaternionfc rotation) {
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
		// Seated crew keep their seats (vanilla carries passengers); turn their view with the ship.
		for (Entity passenger : ship.getPassengers()) rotateLook(passenger, rotation);

		Quaternionf newOrientation = new Quaternionf(rotation).mul(ship.orientation()).normalize();
		Vec3 velocity = rotate(rotation, ship.getDeltaMovement());
		ship.setOrientation(newOrientation);
		Entity moved = ship.teleport(new TeleportTransition(to, origin, velocity, 0, 0,
				TeleportTransition.PLACE_PORTAL_TICKET));
		if (moved == null) return false;
		if (moved instanceof ShipEntity movedShip) {
			movedShip.setOrientation(newOrientation);
			if (isSpace(to) && !isSpace(from)) movedShip.startExitAssist();
		}
		for (int i = 0; i < aboard.size(); i++) {
			Entity entity = aboard.get(i);
			Vec3 target = ship.toWorldAt(locals.get(i), origin, newOrientation); // same spot on the moved ship
			float[] look = rotatedLook(entity, rotation);
			entity.teleport(new TeleportTransition(to, target, rotate(rotation, entity.getDeltaMovement()),
					look[0], look[1], TeleportTransition.DO_NOTHING));
		}
		if (moved.getFirstPassenger() instanceof ServerPlayer pilot && from != to) {
			pilot.sendOverlayMessage(Component.translatable(isSpace(to)
					? "message.starwake.entered_space" : "message.starwake.entered_atmosphere", planetName(to, to.dimension())));
		}
		return true;
	}

	/** Completes a quantum jump: moves the ship and crew to {@code arrival} in the same level. */
	public static boolean jump(ShipEntity ship, Vec3 arrival) {
		return ship.level() instanceof ServerLevel level && transfer(ship, level, arrival, new Quaternionf());
	}

	private static Vec3 rotate(Quaternionfc rotation, Vec3 v) {
		Vector3f r = rotation.transform(new Vector3f((float) v.x, (float) v.y, (float) v.z));
		return new Vec3(r.x, r.y, r.z);
	}

	private static float[] rotatedLook(Entity entity, Quaternionfc rotation) {
		Vec3 look = rotate(rotation, entity.getViewVector(1.0f));
		float yRot = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
		float xRot = (float) Math.toDegrees(-Math.asin(Math.clamp(look.y, -1, 1)));
		return new float[] {yRot, xRot};
	}

	private static void rotateLook(Entity entity, Quaternionfc rotation) {
		float[] look = rotatedLook(entity, rotation);
		entity.setYRot(look[0]);
		entity.setXRot(look[1]);
		entity.setYHeadRot(look[0]);
	}

	// =========================================================================================
	// Quantum routes
	// =========================================================================================

	/** A possible quantum jump from where the ship is now. */
	public record Route(SolarSystem.Entry target, Vec3 arrival, double distance, int fuel, double ticks,
			SolarSystem.@Nullable Entry obstruction, boolean tooClose) {}

	public static Route route(ShipEntity ship, SolarSystem.Entry target) {
		ShipPhysics physics = ShipPhysics.get();
		Vec3 from = ship.position();
		Vec3 arrival = SolarSystem.arrivalPoint(target, from, physics.quantumStandoff());
		double distance = from.distanceTo(arrival);
		boolean tooClose = from.distanceTo(target.body().position()) - target.body().entryRadius()
				< physics.quantumMinDistance();
		var obstruction = SolarSystem.obstruction(ship.level().registryAccess(), from, arrival, target.key()).orElse(null);
		return new Route(target, arrival, distance, physics.quantumFuel(distance),
				SolarSystem.travelTicks(distance, physics.quantumSpeed(), physics.quantumRamp()), obstruction, tooClose);
	}

	/** Quantum destinations from where the ship is, nearest first. */
	public static List<Route> destinations(ShipEntity ship) {
		if (!isSpace(ship.level())) return List.of();
		List<Route> result = new ArrayList<>();
		for (SolarSystem.Entry body : SolarSystem.bodies(ship.level().registryAccess())) {
			if (body.enterable()) result.add(route(ship, body));
		}
		result.sort(Comparator.comparingDouble(Route::distance));
		return result;
	}

	/** Console "Set course": select a quantum target and enter quantum mode. Null = OK, else why not. */
	public static @Nullable Component setCourse(ShipEntity ship, ResourceKey<Planet> target) {
		if (!isSpace(ship.level())) return Component.translatable("message.starwake.course.not_in_space");
		var entry = SolarSystem.get(ship.level().registryAccess(), target).orElse(null);
		if (entry == null || !entry.enterable()) return Component.translatable("message.starwake.course.unknown");
		return ship.enterQuantumMode(target);
	}

	public static Component planetName(Level level, @Nullable ResourceKey<Level> dimension) {
		if (dimension == null) return Component.literal("?");
		return Planets.forDimension(level.registryAccess(), dimension).map(p -> p.planet().name())
				.orElse(Component.literal(dimension.identifier().toString()));
	}
}
