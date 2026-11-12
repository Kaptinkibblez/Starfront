package com.starwake.ship;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.starwake.registry.ModBlocks;
import com.starwake.ship.scan.PreflightReport;
import com.starwake.ship.scan.ShipScanner;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Admin/debug ship commands (permission level 2).
 * <pre>
 * /starwake ship check &lt;helmPos&gt;   print the pre-flight report for the ship at a Helm
 * /starwake ship assemble &lt;helmPos&gt; assemble without seating anyone
 * /starwake ship spin &lt;yaw pitch roll&gt;  nearest unpiloted ship spins (degrees/second)
 * /starwake ship drift &lt;x y z&gt;     nearest unpiloted ship cruises at this velocity (blocks/tick); 0 0 0 stops
 * </pre>
 */
public final class ShipCommands {
	private ShipCommands() {}

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> dispatcher.register(
				Commands.literal("starwake")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.literal("ship")
								.then(Commands.literal("check")
										.then(Commands.argument("helm", BlockPosArgument.blockPos())
												.executes(ShipCommands::check)))
								.then(Commands.literal("assemble")
										.then(Commands.argument("helm", BlockPosArgument.blockPos())
												.executes(ShipCommands::assemble)))
								.then(Commands.literal("blueprint")
										.then(Commands.argument("id", net.minecraft.commands.arguments.IdentifierArgument.id())
												.suggests((c, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggestResource(
														com.starwake.ship.data.ShipBlueprints.all().keySet(), builder))
												.then(Commands.argument("pos", BlockPosArgument.blockPos())
														.executes(ShipCommands::blueprint))))
								.then(Commands.literal("owner")
										.then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
												.executes(ShipCommands::owner)))
								.then(Commands.literal("crew")
										.then(Commands.literal("add").then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
												.executes(ctx -> crew(ctx, true))))
										.then(Commands.literal("remove").then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player())
												.executes(ctx -> crew(ctx, false)))))
								.then(Commands.literal("refuel")
										.then(Commands.argument("fuel", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
												.then(Commands.argument("fusion", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
														.executes(ShipCommands::refuel))))
								.then(Commands.literal("drift")
										.then(Commands.argument("velocity", Vec3Argument.vec3(false))
												.executes(ShipCommands::drift)))
								.then(Commands.literal("spin")
										.then(Commands.argument("rates", Vec3Argument.vec3(false))
												.executes(ShipCommands::spin))))));
	}

	/** Admin: makes a player the owner of the nearest ship. */
	private static int owner(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		var player = net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "player");
		ShipEntity ship = nearest(ctx.getSource());
		if (ship == null) {
			ctx.getSource().sendFailure(Component.translatable("commands.starwake.ship.none_nearby"));
			return 0;
		}
		ship.setOwner(player);
		ctx.getSource().sendSuccess(() -> Component.literal("Ship " + ship.getId() + " now belongs to " + player.getGameProfile().name()), true);
		return 1;
	}

	/** Admin: adds or removes crew on the nearest ship. */
	private static int crew(CommandContext<CommandSourceStack> ctx, boolean add) throws CommandSyntaxException {
		var player = net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "player");
		ShipEntity ship = nearest(ctx.getSource());
		if (ship == null) {
			ctx.getSource().sendFailure(Component.translatable("commands.starwake.ship.none_nearby"));
			return 0;
		}
		if (add) ship.addCrew(player);
		else ship.removeCrew(player.getGameProfile().name());
		ctx.getSource().sendSuccess(() -> Component.literal((add ? "Added " : "Removed ") + player.getGameProfile().name()
				+ " as crew of ship " + ship.getId()), true);
		return 1;
	}

	/** Places a blueprint ship (nose north, min corner at pos) and reports its Helm. */
	private static int blueprint(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		var id = net.minecraft.commands.arguments.IdentifierArgument.getId(ctx, "id");
		var blueprint = com.starwake.ship.data.ShipBlueprints.all().get(id);
		if (blueprint == null) {
			ctx.getSource().sendFailure(Component.literal("Unknown blueprint " + id));
			return 0;
		}
		BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
		BlockPos helm = com.starwake.ship.data.ShipBlueprints.place(ctx.getSource().getLevel(), blueprint, pos);
		Component name = com.starwake.ship.data.ShipBlueprints.name(id, blueprint);
		ctx.getSource().sendSuccess(() -> Component.translatable("commands.starwake.ship.blueprint", name,
				helm == null ? "-" : helm.getX() + " " + helm.getY() + " " + helm.getZ()), true);
		return 1;
	}

	/** Makes the nearest unpiloted ship spin (yaw pitch roll, degrees/second). 0 0 0 stops. */
	private static int spin(CommandContext<CommandSourceStack> ctx) {
		Vec3 rates = Vec3Argument.getVec3(ctx, "rates");
		ShipEntity ship = nearest(ctx.getSource());
		if (ship == null) {
			ctx.getSource().sendFailure(Component.translatable("commands.starwake.ship.none_nearby"));
			return 0;
		}
		ship.setSpin(rates);
		ctx.getSource().sendSuccess(() -> Component.literal("Ship " + ship.getId() + " spinning " + rates), false);
		return 1;
	}

	private static ShipEntity nearest(CommandSourceStack source) {
		ShipEntity nearest = null;
		for (ShipEntity ship : ShipCollisions.shipsIn(source.getLevel())) {
			if (ship.distanceToSqr(source.getPosition()) < 64 * 64
					&& (nearest == null || ship.distanceToSqr(source.getPosition()) < nearest.distanceToSqr(source.getPosition()))) {
				nearest = ship;
			}
		}
		return nearest;
	}

	/** Assembles the ship at a Helm without seating anyone (testing and admin builds). */
	private static int assemble(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		BlockPos helm = BlockPosArgument.getLoadedBlockPos(ctx, "helm");
		CommandSourceStack source = ctx.getSource();
		if (!source.getLevel().getBlockState(helm).is(ModBlocks.HELM)) {
			source.sendFailure(Component.translatable("commands.starwake.ship.not_helm"));
			return 0;
		}
		ShipEntity ship = ShipAssembler.assemble(source.getLevel(), helm, null);
		if (ship == null) {
			source.sendFailure(Component.translatable("preflight.starwake.verdict_grounded"));
			return 0;
		}
		source.sendSuccess(() -> Component.translatable("commands.starwake.ship.assembled",
				ship.getId(), String.format("%.2f %.2f %.2f", ship.getX(), ship.getY(), ship.getZ())), true);
		return 1;
	}

	/** Makes the nearest ship (within 64 blocks) cruise at a fixed velocity while unpiloted. */
	/** Admin (M8f): adds propellant and fusion units to the nearest ship (up to capacity). */
	private static int refuel(CommandContext<CommandSourceStack> ctx) {
		int fuel = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "fuel");
		int fusion = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "fusion");
		CommandSourceStack source = ctx.getSource();
		ShipEntity nearest = null;
		for (ShipEntity ship : ShipCollisions.shipsIn(source.getLevel())) {
			if (ship.distanceToSqr(source.getPosition()) < 64 * 64
					&& (nearest == null || ship.distanceToSqr(source.getPosition()) < nearest.distanceToSqr(source.getPosition()))) {
				nearest = ship;
			}
		}
		if (nearest == null) {
			source.sendFailure(Component.translatable("commands.starwake.ship.none_nearby"));
			return 0;
		}
		nearest.addFuel(fuel);
		nearest.addFusion(fusion);
		ShipEntity ship = nearest;
		source.sendSuccess(() -> Component.literal("Ship " + ship.getId() + ": fuel " + ship.fuel() + "/" + ship.fuelCapacity() + ", fusion "
				+ ship.fusion() + "/" + ship.fusionCapacity()), false);
		return 1;
	}

	private static int drift(CommandContext<CommandSourceStack> ctx) {
		Vec3 velocity = Vec3Argument.getVec3(ctx, "velocity");
		CommandSourceStack source = ctx.getSource();
		ShipEntity nearest = null;
		for (ShipEntity ship : ShipCollisions.shipsIn(source.getLevel())) {
			if (ship.distanceToSqr(source.getPosition()) < 64 * 64
					&& (nearest == null || ship.distanceToSqr(source.getPosition()) < nearest.distanceToSqr(source.getPosition()))) {
				nearest = ship;
			}
		}
		if (nearest == null) {
			source.sendFailure(Component.translatable("commands.starwake.ship.none_nearby"));
			return 0;
		}
		nearest.setDrift(velocity);
		ShipEntity ship = nearest;
		source.sendSuccess(() -> Component.translatable("commands.starwake.ship.drift", ship.getId()), false);
		return 1;
	}

	private static int check(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		BlockPos helm = BlockPosArgument.getLoadedBlockPos(ctx, "helm");
		CommandSourceStack source = ctx.getSource();
		if (!source.getLevel().getBlockState(helm).is(ModBlocks.HELM)) {
			source.sendFailure(Component.translatable("commands.starwake.ship.not_helm"));
			return 0;
		}
		PreflightReport report = PreflightReport.analyze(source.getLevel(), ShipScanner.scan(source.getLevel(), helm));
		for (PreflightReport.Line line : report.lines()) {
			source.sendSuccess(() -> Component.literal(line.status().icon + " ").append(line.text()), false);
		}
		return report.canFly() ? 1 : 0;
	}
}
