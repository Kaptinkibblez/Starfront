package com.starwake.ship;

import com.starwake.network.ShipConsoleActionPayload;
import com.starwake.network.ShipConsolePayload;
import com.starwake.registry.ModBlocks;
import com.starwake.ship.scan.PreflightReport;
import com.starwake.ship.scan.ShipScanner;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Server side of the Ship Console: scans the ship around a Helm, runs the pre-flight check,
 * and sends the report to the player's screen.
 */
public final class ShipConsole {
	/** Max distance (squared) between player and Helm for console actions. */
	private static final double MAX_DISTANCE_SQ = 10 * 10;

	private ShipConsole() {}

	public static void open(ServerPlayer player, BlockPos helm) {
		var scan = ShipScanner.scan(player.level(), helm);
		var report = PreflightReport.analyze(player.level(), scan);
		ServerPlayNetworking.send(player, new ShipConsolePayload(
				helm,
				report.lines().stream().map(line -> line.status().ordinal()).toList(),
				report.lines().stream().map(PreflightReport.Line::text).toList(),
				report.canFly()));
	}

	public static void handleAction(ServerPlayer player, ShipConsoleActionPayload payload) {
		BlockPos helm = payload.helm();
		// Never trust the client: re-check reach and that the Helm is still there.
		if (player.distanceToSqr(Vec3.atCenterOf(helm)) > MAX_DISTANCE_SQ
				|| !player.level().getBlockState(helm).is(ModBlocks.HELM)) {
			return;
		}
		switch (payload.action()) {
			case RESCAN -> open(player, helm);
			case ASSEMBLE -> player.sendOverlayMessage(Component.translatable("message.starwake.assemble_soon"));
		}
	}
}
