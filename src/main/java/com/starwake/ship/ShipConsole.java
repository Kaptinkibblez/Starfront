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
				report.canFly()));
	}

	/** Status console for a flying ship. */
	public static void openShip(ServerPlayer player, ShipEntity ship) {
		List<Integer> status = new ArrayList<>();
		List<Component> lines = new ArrayList<>();
		double gravity = Planets.gravityOf(ship.level().dimension());
		add(status, lines, PreflightReport.Status.INFO, Component.translatable("console.starwake.ship.mass",
				ship.structure() == null ? 0 : ship.structure().entries().size(), String.format("%.1f", ship.mass()),
				String.format("%.2f", gravity)));
		add(status, lines, ship.fuel() > 0 ? PreflightReport.Status.PASS : PreflightReport.Status.FAIL,
				Component.translatable("console.starwake.ship.fuel", ship.fuel(), ship.fuelCapacity()));
		boolean stationary = ship.getDeltaMovement().lengthSqr() < 1.0e-4;
		add(status, lines, PreflightReport.Status.INFO, Component.translatable("console.starwake.ship.controls"));
		add(status, lines, stationary ? PreflightReport.Status.INFO : PreflightReport.Status.WARN,
				Component.translatable(stationary ? "console.starwake.ship.can_disassemble" : "console.starwake.ship.moving"));
		ServerPlayNetworking.send(player, new ShipConsolePayload(
				BlockPos.containing(ship.toWorld(ship.helmLocal())), ship.getId(), status, lines, stationary));
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
			case OPEN_SHIP, DISASSEMBLE -> {
				// Only the pilot can use the ship console.
				if (!(player.getVehicle() instanceof ShipEntity ship) || ship.getId() != payload.shipId()) {
					return;
				}
				if (payload.action() == ShipConsoleActionPayload.Action.OPEN_SHIP) {
					openShip(player, ship);
				} else {
					Component error = ShipAssembler.disassemble(ship);
					player.sendOverlayMessage(error != null ? error : Component.translatable("message.starwake.disassembled"));
				}
			}
		}
	}
}
