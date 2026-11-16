package com.starwake.registry;

import com.starwake.trade.block.TerminalBlock;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/**
 * Trade hub blocks (M7): station construction blocks, landing pads and the three terminals, plus
 * the courier contract's sealed package item.
 */
public final class ModHubBlocks {
	/** Station Panel: the pale composite wall of stations and outposts. */
	public static final Block STATION_PANEL = ModBlocks.register("station_panel", Block::new, structure(MapColor.QUARTZ, 0));
	/** Station Floor: dark grated decking. */
	public static final Block STATION_FLOOR = ModBlocks.register("station_floor", Block::new, structure(MapColor.COLOR_GRAY, 0));
	/** Light Panel: flush ceiling/wall lighting. */
	public static final Block LIGHT_PANEL = ModBlocks.register("light_panel", Block::new, structure(MapColor.SNOW, 15));
	/** Landing Pad: hazard-striped pad surface marking where ships park. */
	public static final Block LANDING_PAD = ModBlocks.register("landing_pad", Block::new, structure(MapColor.COLOR_YELLOW, 4));

	public static final Block COMMODITY_TERMINAL = terminal("commodity_terminal", TerminalBlock.Kind.COMMODITY, MapColor.COLOR_LIGHT_BLUE);
	public static final Block OUTFITTER_TERMINAL = terminal("outfitter_terminal", TerminalBlock.Kind.OUTFITTER, MapColor.COLOR_ORANGE);
	public static final Block CONTRACT_BOARD = terminal("contract_board", TerminalBlock.Kind.CONTRACTS, MapColor.COLOR_PURPLE);

	/** Trade Kiosk (M9): a player shop. */
	public static final Block TRADE_KIOSK = ModBlocks.register("trade_kiosk", com.starwake.trade.kiosk.TradeKioskBlock::new,
			BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GREEN).strength(3.0f, 1200.0f).sound(SoundType.METAL).lightLevel(s -> 9)
					.requiresCorrectToolForDrops().noOcclusion());
	public static final net.minecraft.world.level.block.entity.BlockEntityType<com.starwake.trade.kiosk.TradeKioskBlockEntity> TRADE_KIOSK_BE =
			net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE, com.starwake.StarwakeMod.id("trade_kiosk"),
					net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder.create(
							com.starwake.trade.kiosk.TradeKioskBlockEntity::new, TRADE_KIOSK).build());

	/** Sealed Cargo: a courier contract's package (tagged with its contract). */
	public static final Item SEALED_CARGO = ModItems.register("sealed_cargo", Item::new, new Item.Properties().stacksTo(1));

	private ModHubBlocks() {}

	private static BlockBehaviour.Properties structure(MapColor color, int light) {
		return BlockBehaviour.Properties.of().mapColor(color).strength(3.0f, 9.0f).sound(SoundType.METAL).lightLevel(s -> light)
				.requiresCorrectToolForDrops();
	}

	private static Block terminal(String name, TerminalBlock.Kind kind, MapColor color) {
		return ModBlocks.register(name, p -> new TerminalBlock(kind, p), BlockBehaviour.Properties.of().mapColor(color)
				.strength(3.0f, 9.0f).sound(SoundType.METAL).lightLevel(s -> 9).requiresCorrectToolForDrops().noOcclusion());
	}

	public static void init() {}
}
