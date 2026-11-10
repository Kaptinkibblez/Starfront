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
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Admin/debug ship commands (permission level 2).
 * <pre>
 * /starwake ship check &lt;helmPos&gt;   print the pre-flight report for the ship at a Helm
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
												.executes(ShipCommands::check))))));
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
