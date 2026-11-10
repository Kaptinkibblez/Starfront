package com.starwake.planet;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import java.util.Collection;
import java.util.List;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;

/**
 * Debug and admin commands for planets (permission level 2, like /tp).
 *
 * <pre>
 * /starwake planet list                      list every loaded planet
 * /starwake planet tp &lt;planet&gt; [players]     travel to a planet's landing site
 * /starwake planet leave [players]           return to where you were before travelling
 * </pre>
 */
public final class PlanetCommands {
	private static final DynamicCommandExceptionType UNKNOWN_PLANET = new DynamicCommandExceptionType(
			id -> Component.translatable("commands.starwake.planet.unknown", id.toString()));

	private PlanetCommands() {}

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> register(dispatcher));
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("starwake")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("planet")
						.then(Commands.literal("list").executes(PlanetCommands::list))
						.then(Commands.literal("tp")
								.then(Commands.argument("planet", IdentifierArgument.id())
										.suggests((ctx, builder) -> SharedSuggestionProvider.suggestResource(
												Planets.registry(ctx.getSource().getServer()).keySet(), builder))
										.executes(ctx -> travel(ctx, List.of(ctx.getSource().getPlayerOrException())))
										.then(Commands.argument("players", EntityArgument.players())
												.executes(ctx -> travel(ctx, EntityArgument.getPlayers(ctx, "players"))))))
						.then(Commands.literal("leave")
								.executes(ctx -> leave(ctx, List.of(ctx.getSource().getPlayerOrException())))
								.then(Commands.argument("players", EntityArgument.players())
										.executes(ctx -> leave(ctx, EntityArgument.getPlayers(ctx, "players")))))));
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		var registry = Planets.registry(ctx.getSource().getServer());
		ctx.getSource().sendSuccess(() -> Component.translatable("commands.starwake.planet.list.header", registry.size()), false);
		registry.listElements().forEach(planet -> ctx.getSource().sendSuccess(() -> Component.translatable(
				"commands.starwake.planet.list.entry",
				planet.value().name(),
				planet.key().identifier().toString(),
				String.format("%.2f", planet.value().gravity())), false));
		return registry.size();
	}

	private static int travel(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players)
			throws CommandSyntaxException {
		Identifier id = IdentifierArgument.getId(ctx, "planet");
		Holder<Planet> planet = Planets.registry(ctx.getSource().getServer())
				.get(ResourceKey.create(Planets.REGISTRY_KEY, id))
				.orElseThrow(() -> UNKNOWN_PLANET.create(id));

		int moved = 0;
		for (ServerPlayer player : players) {
			String error = PlanetTravel.travelTo(player, planet);
			if (error != null) {
				ctx.getSource().sendFailure(Component.translatable(error, id.toString()));
				return moved;
			}
			moved++;
		}
		int count = moved;
		ctx.getSource().sendSuccess(() -> Component.translatable(
				"commands.starwake.planet.tp.success", count, planet.value().name()), true);
		return moved;
	}

	private static int leave(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players) {
		players.forEach(PlanetTravel::returnHome);
		ctx.getSource().sendSuccess(() -> Component.translatable(
				"commands.starwake.planet.leave.success", players.size()), true);
		return players.size();
	}
}
