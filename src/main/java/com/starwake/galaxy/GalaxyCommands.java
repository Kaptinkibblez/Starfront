package com.starwake.galaxy;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Admin/testing commands for the galaxy (M6):
 * <ul>
 *   <li>{@code /starwake galaxy near [ly]}: stars around your current system, with ids, class
 *       and distance;</li>
 *   <li>{@code /starwake galaxy tp <id>}: opens a system and puts you in its space, 3 Mm from
 *       its star;</li>
 *   <li>{@code /starwake galaxy here}: which system you're in;</li>
 *   <li>{@code /starwake galaxy target <id>}: sets the nearest ship's jump target.</li>
 * </ul>
 */
public final class GalaxyCommands {
	private GalaxyCommands() {}

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(Commands.literal("starwake")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("galaxy")
						.then(Commands.literal("here").executes(GalaxyCommands::here))
						.then(Commands.literal("near")
								.executes(ctx -> near(ctx, 10))
								.then(Commands.argument("ly", DoubleArgumentType.doubleArg(1, 60))
										.executes(ctx -> near(ctx, DoubleArgumentType.getDouble(ctx, "ly")))))
						.then(Commands.literal("target")
								.then(Commands.argument("system", StringArgumentType.word())
										.executes(GalaxyCommands::target)))
						.then(Commands.literal("tp")
								.then(Commands.argument("system", StringArgumentType.word())
										.executes(GalaxyCommands::tp))))));
	}

	private static Galaxy.Star current(CommandSourceStack source) {
		return Galaxy.star(SolarSystem.systemOf(source.getLevel())).orElse(Galaxy.home());
	}

	private static int here(CommandContext<CommandSourceStack> ctx) {
		Galaxy.Star s = current(ctx.getSource());
		ctx.getSource().sendSuccess(() -> Component.literal(s.name() + " [" + s.id() + "] at " + format(s.position())), false);
		return 1;
	}

	private static int near(CommandContext<CommandSourceStack> ctx, double radius) {
		Galaxy.Star here = current(ctx.getSource());
		List<Galaxy.Star> stars = Galaxy.within(here.position(), radius).stream()
				.filter(s -> !s.id().equals(here.id()))
				.sorted(Comparator.comparingDouble(s -> s.distanceTo(here))).toList();
		ctx.getSource().sendSuccess(() -> Component.literal(stars.size() + " stars within " + radius + " ly of " + here.name()), false);
		for (Galaxy.Star s : stars.subList(0, Math.min(12, stars.size()))) {
			StarClass c = Galaxy.starClass(s.starClass());
			Vec3 d = s.position().subtract(here.position()).normalize();
			float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z)), pitch = (float) Math.toDegrees(-Math.asin(d.y));
			ctx.getSource().sendSuccess(() -> Component.literal(String.format("%s [%s] %s, %.2f ly, %d planets%s, look %.1f %.1f", s.name(), s.id(),
					c == null ? "?" : c.symbol(), s.distanceTo(here), s.planets(), s.companion().isPresent() ? ", binary" : "", yaw, pitch)), false);
		}
		return stars.size();
	}

	private static int tp(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String id = StringArgumentType.getString(ctx, "system");
		var server = ctx.getSource().getServer();
		if (!StarSystems.open(server, id)) {
			ctx.getSource().sendFailure(Component.literal("Unknown system " + id));
			return 0;
		}
		ServerLevel space = server.getLevel(SolarSystem.spaceOf(id));
		if (space == null) return 0;
		player.teleport(new TeleportTransition(space, new Vec3(3_000_000, 0, 0), Vec3.ZERO, 90, 0, Set.<Relative>of(),
				TeleportTransition.DO_NOTHING));
		ctx.getSource().sendSuccess(() -> Component.literal("Sent to " + id), true);
		return 1;
	}

	/** Testing: sets the nearest ship's jump target, as the galaxy map's button does for its pilot. */
	private static int target(CommandContext<CommandSourceStack> ctx) {
		String id = StringArgumentType.getString(ctx, "system");
		var source = ctx.getSource();
		var ships = source.getLevel().getEntitiesOfClass(com.starwake.ship.ShipEntity.class,
				new net.minecraft.world.phys.AABB(source.getPosition(), source.getPosition()).inflate(64));
		if (ships.isEmpty()) {
			source.sendFailure(Component.literal("No ship within 64 blocks"));
			return 0;
		}
		var ship = ships.stream().min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(source.getPosition()))).orElseThrow();
		Component error = ship.setJumpTarget(id);
		if (error != null) source.sendFailure(error);
		else source.sendSuccess(() -> Component.literal("Jump target set: " + id), false);
		return error == null ? 1 : 0;
	}

	private static String format(Vec3 v) {
		return String.format("(%.1f, %.1f, %.1f) ly", v.x, v.y, v.z);
	}
}
