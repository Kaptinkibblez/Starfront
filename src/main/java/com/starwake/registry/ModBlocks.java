package com.starwake.registry;

import com.starwake.StarwakeMod;
import com.starwake.ship.block.FuelTankBlock;
import com.starwake.ship.block.HelmBlock;
import com.starwake.ship.block.SeatBlock;
import com.starwake.ship.block.ThrusterBlock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/**
 * Registry of every Starwake block. Each block also gets a matching {@link BlockItem}.
 *
 * Since 1.21.2, block and item properties must carry their registry key before construction
 * ({@code setId}). The {@link #register} helper handles that, so call sites stay one line.
 */
public final class ModBlocks {
	private static final List<Block> ALL = new ArrayList<>();

	/**
	 * Hull Plating: the basic starship construction block (a test block in Milestone 1).
	 * Iron-like hardness, mined with a stone pickaxe or better.
	 */
	public static final Block HULL_PLATING = register("hull_plating", Block::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.METAL)
					.strength(5.0f, 6.0f)
					.sound(SoundType.METAL)
					.requiresCorrectToolForDrops());

	/** Regolith: the bedrock-to-surface stone of rocky planets (Veyra's "stone"). */
	public static final Block REGOLITH = register("regolith", Block::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.TERRACOTTA_PURPLE)
					.strength(1.5f, 6.0f)
					.sound(SoundType.TUFF)
					.requiresCorrectToolForDrops());

	/** Rust Sand: oxidised dust covering Veyra's surface. Shovel-mineable. */
	public static final Block RUST_SAND = register("rust_sand", Block::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.COLOR_ORANGE)
					.strength(0.5f)
					.sound(SoundType.SAND));

	/** Ferrite Ore: regolith veined with ferrite. Drops Raw Ferrite. */
	public static final Block FERRITE_ORE = register("ferrite_ore", Block::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.TERRACOTTA_PURPLE)
					.strength(3.0f, 3.0f)
					.sound(SoundType.TUFF)
					.requiresCorrectToolForDrops());

	// --- Ship parts --------------------------------------------------------------------

	/** Helm: the ship's control seat. A ship is the structure connected to one Helm. */
	public static final Block HELM = register("helm", HelmBlock::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.METAL)
					.strength(4.0f, 6.0f)
					.sound(SoundType.METAL)
					.requiresCorrectToolForDrops());

	/** Thruster: pushes the ship away from its nozzle. Stats in ship_stats JSON. */
	public static final Block THRUSTER = register("thruster", ThrusterBlock::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.METAL)
					.strength(4.0f, 6.0f)
					.sound(SoundType.METAL)
					.requiresCorrectToolForDrops());

	/** Fuel Tank: stores fuel for the ship's thrusters. */
	public static final Block FUEL_TANK = register("fuel_tank", FuelTankBlock::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.COLOR_ORANGE)
					.strength(4.0f, 6.0f)
					.sound(SoundType.METAL)
					.requiresCorrectToolForDrops());

	/** Seat: crew ride here while the ship maneuvers. */
	public static final Block SEAT = register("seat", SeatBlock::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.COLOR_GRAY)
					.strength(2.0f, 6.0f)
					.sound(SoundType.METAL)
					.noOcclusion());

	// --- Ship components (stats in ship_stats JSON) -------------------------------------

	/** Power Plant: generates power (and heat). Every ship needs at least one. */
	public static final Block POWER_PLANT = component("power_plant", MapColor.COLOR_LIGHT_BLUE, 7);
	/** Cooler: removes heat so power plants can run at full output. */
	public static final Block COOLER = component("cooler", MapColor.COLOR_CYAN, 0);
	/** Shield Generator: a regenerating shield that absorbs crash impacts. */
	public static final Block SHIELD_GENERATOR = component("shield_generator", MapColor.COLOR_BLUE, 5);
	/** Quantum Drive: spools up and jumps the ship between planets. */
	public static final Block QUANTUM_DRIVE = component("quantum_drive", MapColor.COLOR_PURPLE, 6);

	private static Block component(String name, MapColor color, int light) {
		return register(name, Block::new, BlockBehaviour.Properties.of()
				.mapColor(color)
				.strength(4.0f, 6.0f)
				.sound(SoundType.METAL)
				.lightLevel(state -> light)
				.requiresCorrectToolForDrops());
	}

	private ModBlocks() {}

	/** Registers a block and its block item under {@code starwake:<name>}. */
	private static Block register(String name, Function<BlockBehaviour.Properties, Block> factory,
			BlockBehaviour.Properties properties) {
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, StarwakeMod.id(name));
		Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey, factory.apply(properties.setId(blockKey)));

		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, StarwakeMod.id(name));
		Registry.register(BuiltInRegistries.ITEM, itemKey,
				new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));

		ALL.add(block);
		return block;
	}

	/** All Starwake blocks in registration order (used by datagen and the creative tab). */
	public static List<Block> all() {
		return Collections.unmodifiableList(ALL);
	}

	public static int count() {
		return ALL.size();
	}

	/** Forces static initialization so every field above is registered. */
	public static void init() {}
}
