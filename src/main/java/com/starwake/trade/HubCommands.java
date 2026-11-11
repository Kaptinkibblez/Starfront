package com.starwake.trade;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Admin/testing commands for trade hubs (M7):
 * <ul>
 *   <li>{@code /starwake hub list}: the hubs planned in your system, built or not;</li>
 *   <li>{@code /starwake hub place <template>}: places a template's blocks just south of you
 *       (outposts sink their foundation layers), without registering a hub;</li>
 *   <li>{@code /starwake hub rebuild}: clears and rebuilds every built hub in your dimension from
 *       the current templates (after editing them).</li>
 * </ul>
 */
public final class HubCommands {
	private HubCommands() {}

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(Commands.literal("starwake")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("hub")
						.then(Commands.literal("list").executes(HubCommands::list))
						.then(Commands.literal("rebuild").executes(HubCommands::rebuild))
						.then(Commands.literal("place")
								.then(Commands.argument("template", StringArgumentType.word())
										.executes(HubCommands::place))))));
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		ServerLevel level = ctx.getSource().getLevel();
		var server = ctx.getSource().getServer();
		var hubs = TradeHubs.plan(server, com.starwake.galaxy.SolarSystem.systemOf(level));
		for (TradeHubs.Hub hub : hubs) {
			BlockPos o = TradeHubs.origin(server, hub);
			ctx.getSource().sendSuccess(() -> Component.literal(String.format("%s '%s' %s/%s in %s at %s%s", hub.id(), hub.name(),
					hub.faction(), hub.template(), hub.dimension().identifier(), hub.anchor().toShortString(),
					o == null ? " (not built)" : " built at " + o.toShortString())), false);
		}
		return hubs.size();
	}

	private static int place(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String id = StringArgumentType.getString(ctx, "template");
		var t = TradeData.template(id).orElse(null);
		if (t == null) {
			ctx.getSource().sendFailure(Component.literal("No hub template " + id));
			return 0;
		}
		BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
		BlockPos origin = at.offset(-t.sizeX() / 2, -t.ground(), 3);
		TradeHubs.place(ctx.getSource().getLevel(), id, t, origin);
		ctx.getSource().sendSuccess(() -> Component.literal("Placed " + id + " at " + origin.toShortString()), true);
		return 1;
	}

	private static int rebuild(CommandContext<CommandSourceStack> ctx) {
		ServerLevel level = ctx.getSource().getLevel();
		var server = ctx.getSource().getServer();
		int n = 0;
		for (TradeHubs.Hub hub : TradeHubs.plan(server, com.starwake.galaxy.SolarSystem.systemOf(level))) {
			BlockPos o = TradeHubs.origin(server, hub);
			var t = TradeData.template(hub.template()).orElse(null);
			if (o == null || t == null || !hub.dimension().equals(level.dimension())) continue;
			for (BlockPos p : BlockPos.betweenClosed(o, o.offset(t.sizeX(), t.sizeY(), t.sizeZ()))) {
				if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
			TradeHubs.forget(server, hub);
			TradeHubs.build(level, hub, t);
			n++;
		}
		int built = n;
		ctx.getSource().sendSuccess(() -> Component.literal("Rebuilt " + built + " hubs"), true);
		return n;
	}
}
