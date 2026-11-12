package com.starwake.metal;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Admin/testing commands for metallurgy (M8a):
 * <ul>
 *   <li>{@code /starwake metal give <form> <count> <composition>}: e.g.
 *       {@code /starwake metal give ingot 4 Fe:72,Cr:18,Ni:8,C:0.05} (wt%);</li>
 *   <li>{@code /starwake metal grade <grade> [form] [count]}: a grade at its nominal composition;</li>
 *   <li>{@code /starwake metal info}: the analysis of the held item;</li>
 *   <li>{@code /starwake metal grades}: lists every grade.</li>
 * </ul>
 */
public final class MetalCommands {
	private static final SuggestionProvider<CommandSourceStack> FORMS = (ctx, b) ->
			SharedSuggestionProvider.suggest(Arrays.stream(MetalForm.values()).map(f -> f.id), b);
	private static final SuggestionProvider<CommandSourceStack> GRADES = (ctx, b) ->
			SharedSuggestionProvider.suggestResource(Metallurgy.grades().stream().filter(e -> e.getValue().exact()).map(e -> e.getKey()), b);

	private MetalCommands() {}

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(Commands.literal("starwake")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("metal")
						.then(Commands.literal("give")
								.then(Commands.argument("form", StringArgumentType.word()).suggests(FORMS)
										.then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
												.then(Commands.argument("composition", StringArgumentType.greedyString())
														.executes(MetalCommands::give)))))
						.then(Commands.literal("grade")
								.then(Commands.argument("grade", IdentifierArgument.id()).suggests(GRADES)
										.executes(ctx -> grade(ctx, "ingot", 1))
										.then(Commands.argument("form", StringArgumentType.word()).suggests(FORMS)
												.executes(ctx -> grade(ctx, StringArgumentType.getString(ctx, "form"), 1))
												.then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
														.executes(ctx -> grade(ctx, StringArgumentType.getString(ctx, "form"),
																IntegerArgumentType.getInteger(ctx, "count")))))))
						.then(Commands.literal("info").executes(MetalCommands::info)
								.then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
										.then(Commands.argument("slot", IntegerArgumentType.integer(0, 64)).executes(MetalCommands::infoAt))))
						.then(Commands.literal("grades").executes(MetalCommands::grades)))));
	}

	private static int give(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		MetalForm form;
		Composition c;
		try {
			form = MetalForm.byId(StringArgumentType.getString(ctx, "form"));
			c = Composition.parse(StringArgumentType.getString(ctx, "composition"));
		} catch (IllegalArgumentException e) {
			ctx.getSource().sendFailure(Component.literal(e.getMessage()));
			return 0;
		}
		ItemStack stack = MetalItems.stack(form, c, IntegerArgumentType.getInteger(ctx, "count"));
		player.getInventory().add(stack);
		report(ctx.getSource(), c);
		return 1;
	}

	private static int grade(CommandContext<CommandSourceStack> ctx, String formId, int count) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Identifier id = IdentifierArgument.getId(ctx, "grade");
		var grade = Metallurgy.grade(id).orElse(null);
		if (grade == null || !grade.exact()) {
			ctx.getSource().sendFailure(Component.literal("No exact grade " + id));
			return 0;
		}
		MetalForm form;
		try {
			form = MetalForm.byId(formId);
		} catch (IllegalArgumentException e) {
			ctx.getSource().sendFailure(Component.literal(e.getMessage()));
			return 0;
		}
		Composition c = grade.nominalComposition();
		player.getInventory().add(MetalItems.stack(form, c, count));
		report(ctx.getSource(), c);
		return 1;
	}

	private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		var c = MetalItems.compositionOf(player.getMainHandItem());
		if (c.isEmpty()) {
			ctx.getSource().sendFailure(Component.literal("Not a metal"));
			return 0;
		}
		report(ctx.getSource(), c.get());
		reportTemper(ctx.getSource(), player.getMainHandItem());
		return 1;
	}

	/** {@code info <pos> <slot>}: a metal item in a container (headless tests). */
	private static int infoAt(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		var pos = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(ctx, "pos");
		int slot = IntegerArgumentType.getInteger(ctx, "slot");
		if (!(ctx.getSource().getLevel().getBlockEntity(pos) instanceof net.minecraft.world.Container container) || slot >= container.getContainerSize()) {
			ctx.getSource().sendFailure(Component.literal("No container slot there"));
			return 0;
		}
		ItemStack stack = container.getItem(slot);
		var c = MetalItems.compositionOf(stack);
		if (c.isEmpty()) {
			ctx.getSource().sendFailure(Component.literal("Not a metal: " + stack));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.literal(stack.getCount() + " x " + MetalItems.formOf(stack).map(f -> f.id).orElse("?")), false);
		report(ctx.getSource(), c.get());
		reportTemper(ctx.getSource(), stack);
		return 1;
	}

	/** The item's temper and the properties it gives. */
	public static void reportTemper(CommandSourceStack source, ItemStack stack) {
		Tempers.Temper t = Tempers.of(stack);
		MaterialProperties p = Tempers.properties(stack);
		source.sendSuccess(() -> Component.literal(String.format("temper %s cw=%.3f T=%d HV=%.0f quenched=%.0f progress=%.2f -> yield %.0f MPa, UTS %.0f MPa, elongation %.1f%%, HV %.0f",
				t.state().isEmpty() ? "(reference)" : t.state(), t.coldWork(), t.temperature(), t.hardness(), t.quenched(), t.progress(),
				p.yieldStrength(), p.tensileStrength(), p.elongation(), p.hardness())), false);
	}

	private static int grades(CommandContext<CommandSourceStack> ctx) {
		var all = Metallurgy.grades();
		for (var e : all) {
			AlloyGrade g = e.getValue();
			ctx.getSource().sendSuccess(() -> Component.literal(e.getKey() + "  ").append(g.name())
					.append(Component.literal(g.exact() ? "  [" + g.family() + ", tier " + g.tier() + "]" : "  (family)")), false);
		}
		return all.size();
	}

	/** Prints an analysis in chat (also the headless test path). */
	public static void report(CommandSourceStack source, Composition c) {
		Metallurgy.Analysis a = Metallurgy.analyze(c);
		MaterialProperties p = a.properties();
		List<Component> lines = List.of(
				Component.literal(a.kind() + ": ").append(a.name()).append(a.gradeId() == null ? "" : "  [" + a.gradeId() + "]"),
				Component.literal(c.format(8)),
				Component.literal(String.format("density %.2f g/cm3, yield %.0f MPa, UTS %.0f MPa, elongation %.1f%%, HV %.0f",
						p.density(), p.yieldStrength(), p.tensileStrength(), p.elongation(), p.hardness())),
				Component.literal(String.format("solidus %.0f C, service %.0f C, conductivity %.1f%% IACS, corrosion %.1f/10",
						p.solidus(), p.serviceTemp(), p.conductivity(), p.corrosion())));
		lines.forEach(l -> source.sendSuccess(() -> l, false));
		for (Metallurgy.Deviation d : a.deviations()) {
			source.sendSuccess(() -> Component.literal(String.format("  off-spec %s %.3f (spec %.3f-%.3f)", d.element(), d.actual(), d.min(), d.max())), false);
		}
	}
}
