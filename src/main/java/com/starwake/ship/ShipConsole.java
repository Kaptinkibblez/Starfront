package com.starwake.ship;

import com.starwake.network.ShipConsoleActionPayload;
import com.starwake.network.ShipConsolePayload;
import com.starwake.planet.Planets;
import com.starwake.registry.ModBlocks;
import com.starwake.ship.scan.PreflightReport;
import com.starwake.ship.scan.ShipScanner;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Server side of the Ship Console.
 * - For a Helm in the world: scan + pre-flight report, with an Assemble button.
 * - For an assembled ship (opened by its pilot): status, with a Disassemble button.
 */
public final class ShipConsole {
	/** Max distance (squared) between player and Helm for console actions. */
	private static final double MAX_DISTANCE_SQ = 10 * 10;

	private ShipConsole() {}

	public static void open(ServerPlayer player, BlockPos helm) {
		var scan = ShipScanner.scan(player.level(), helm);
		var report = PreflightReport.analyze(player.level(), scan);
		ServerPlayNetworking.send(player, new ShipConsolePayload(
				helm, -1,
				report.lines().stream().map(line -> line.status().ordinal()).toList(),
				report.lines().stream().map(PreflightReport.Line::text).toList(),
				report.canFly(), List.of(), List.of(), List.of()));
	}

	/** Status console for a flying ship. */
	public static void openShip(ServerPlayer player, ShipEntity ship) {
		List<Integer> status = new ArrayList<>();
		List<Component> lines = new ArrayList<>();
		// Ships are weightless in space (see ShipEntity.applyThrust).
		double gravity = SpaceTravel.isSpace(ship.level()) ? 0 : Planets.gravityOf(ship.level().dimension());
		add(status, lines, PreflightReport.Status.INFO, Component.translatable("console.starwake.ship.mass",
				ship.structure() == null ? 0 : ship.structure().entries().size(), String.format("%.1f", ship.mass()),
				String.format("%.2f", gravity)));
		add(status, lines, ship.fuel() > 0 ? PreflightReport.Status.PASS : PreflightReport.Status.FAIL,
				Component.translatable("console.starwake.ship.fuel", ship.fuel(), ship.fuelCapacity()));
		boolean stationary = ship.getDeltaMovement().lengthSqr() < 1.0e-4;
		boolean inSpace = SpaceTravel.isSpace(ship.level());
		var systems = ship.systems();
		add(status, lines, systems.powerPlants() == 0 ? PreflightReport.Status.FAIL
						: systems.thermalFactor() < 1 ? PreflightReport.Status.WARN : PreflightReport.Status.PASS,
				Component.translatable("console.starwake.ship.power", String.format("%.0f", systems.availablePower()),
						String.format("%.0f/%.0f", systems.cooling(), systems.heat()),
						Math.round(systems.engineFactor(ship.allocation()) * 100) + "%", String.format("%.0f", ship.shield())));
		add(status, lines, ship.isSealed() ? PreflightReport.Status.PASS : PreflightReport.Status.WARN,
				Component.translatable(ship.isSealed() ? "console.starwake.ship.sealed" : "console.starwake.ship.unsealed"));
		List<Identifier> destinations = new ArrayList<>();
		List<Component> destinationNames = new ArrayList<>();
		if (inSpace) {
			add(status, lines, PreflightReport.Status.INFO, Component.translatable("console.starwake.ship.near",
					SpaceTravel.planetName(ship.level(), ship.orbiting())));
			// Quantum routes, nearest first: distance, travel time and fuel, like a nav computer.
			for (SpaceTravel.Route route : SpaceTravel.destinations(ship)) {
				var problem = route.tooClose() ? "too_close" : route.obstruction() != null ? "obstructed"
						: route.fuel() > ship.fuel() ? "no_fuel" : null;
				add(status, lines, problem == null ? PreflightReport.Status.PASS : PreflightReport.Status.WARN,
						Component.translatable("console.starwake.ship.route", route.target().displayName(),
								com.starwake.galaxy.SolarSystem.formatDistance(route.distance()),
								com.starwake.galaxy.SolarSystem.formatTime(route.ticks()), route.fuel(),
								problem == null ? Component.empty() : Component.translatable("console.starwake.ship.route." + problem)));
				if (!route.tooClose()) {
					destinations.add(route.target().key().identifier());
					destinationNames.add(route.target().displayName());
				}
			}
		} else {
			add(status, lines, PreflightReport.Status.INFO, Component.translatable("console.starwake.ship.to_space",
					ship.level().getMaxY() + SpaceTravel.EXIT_MARGIN));
		}
		String disassembly = inSpace ? "console.starwake.ship.space_no_disassemble"
				: stationary ? "console.starwake.ship.can_disassemble" : "console.starwake.ship.moving";
		add(status, lines, stationary && !inSpace ? PreflightReport.Status.INFO : PreflightReport.Status.WARN,
				Component.translatable(disassembly));
		ServerPlayNetworking.send(player, new ShipConsolePayload(
				BlockPos.containing(ship.blockCenter(ship.helmLocal())), ship.getId(), status, lines,
				stationary && !inSpace, destinations, destinationNames,
				java.util.Arrays.stream(ship.allocation().percents()).boxed().toList()));
	}

	private static void add(List<Integer> status, List<Component> lines, PreflightReport.Status s, Component line) {
		status.add(s.ordinal());
		lines.add(line);
	}

	public static void handleAction(ServerPlayer player, ShipConsoleActionPayload payload) {
		switch (payload.action()) {
			case RESCAN, ASSEMBLE -> {
				BlockPos helm = payload.helm();
				// Never trust the client: re-check reach and that the Helm is still there.
				if (player.distanceToSqr(Vec3.atCenterOf(helm)) > MAX_DISTANCE_SQ
						|| !player.level().getBlockState(helm).is(ModBlocks.HELM)) {
					return;
				}
				if (payload.action() == ShipConsoleActionPayload.Action.RESCAN) {
					open(player, helm);
				} else {
					ShipAssembler.assemble(player, helm);
				}
			}
			case OPEN_SHIP, DISASSEMBLE, SET_COURSE -> {
				// Only the pilot can use the ship console.
				if (!(player.getVehicle() instanceof ShipEntity ship) || ship.getId() != payload.shipId() || ship.pilot() != player) {
					return;
				}
				if (payload.action() == ShipConsoleActionPayload.Action.OPEN_SHIP) {
					openShip(player, ship);
				} else if (payload.action() == ShipConsoleActionPayload.Action.SET_COURSE) {
					Component error = SpaceTravel.setCourse(ship, ResourceKey.create(Planets.REGISTRY_KEY, payload.target()));
					player.sendOverlayMessage(error != null ? error : Component.translatable("message.starwake.quantum.course"));
					openShip(player, ship);
				} else {
					Component error = ShipAssembler.disassemble(ship);
					player.sendOverlayMessage(error != null ? error : Component.translatable("message.starwake.disassembled"));
				}
			}
		}
	}
}
