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
	private static final com.starwake.ship.panel.ProfileShape.Profile SLOPE = com.starwake.ship.panel.ProfileShape.Profile.SLOPE,
			CURVE = com.starwake.ship.panel.ProfileShape.Profile.CURVE, COVE = com.starwake.ship.panel.ProfileShape.Profile.COVE,
			RIDGE = com.starwake.ship.panel.ProfileShape.Profile.RIDGE;

	private static final List<Block> ALL = new ArrayList<>();

	/**
	 * Hull Plating: the basic starship construction block (a test block in Milestone 1).
	 * Iron-like hardness, mined with a stone pickaxe or better.
	 */
	public static final Block HULL_PLATING = register("hull_plating", com.starwake.ship.block.HullPlatingBlock::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.METAL)
					.strength(5.0f, 6.0f)
					.sound(SoundType.METAL)
					.requiresCorrectToolForDrops());

	/** Hull Plating Stairs and Slab (M9 pass 2): sloped and half-height hull pieces for shaping ships. Plain steel. */
	public static final Block HULL_PLATING_STAIRS = register("hull_plating_stairs",
			p -> new net.minecraft.world.level.block.StairBlock(HULL_PLATING.defaultBlockState(), p),
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.METAL)
					.strength(5.0f, 6.0f)
					.sound(SoundType.METAL)
					.requiresCorrectToolForDrops());
	public static final Block HULL_PLATING_SLAB = register("hull_plating_slab", net.minecraft.world.level.block.SlabBlock::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.METAL)
					.strength(5.0f, 6.0f)
					.sound(SoundType.METAL)
					.requiresCorrectToolForDrops());

	/**
	 * Hull pieces (M9 pass 4): auto-connecting slopes, curves (convex) and coves (concave) at 1:1, 1:2 and 1:4, the wedge,
	 * plates, the pillar and the frame. All keep their alloy like Hull Plating.
	 */
	public static final Block HULL_SLOPE = hull("hull_slope", p -> new com.starwake.ship.block.ProfileHullBlock.One(SLOPE, p));
	public static final Block HULL_SLOPE_2 = hull("hull_slope_2", p -> new com.starwake.ship.block.ProfileHullBlock.Two(SLOPE, p));
	public static final Block HULL_SLOPE_4 = hull("hull_slope_4", p -> new com.starwake.ship.block.ProfileHullBlock.Four(SLOPE, p));
	public static final Block HULL_CURVE = hull("hull_curve", p -> new com.starwake.ship.block.ProfileHullBlock.One(CURVE, p));
	public static final Block HULL_CURVE_2 = hull("hull_curve_2", p -> new com.starwake.ship.block.ProfileHullBlock.Two(CURVE, p));
	public static final Block HULL_CURVE_4 = hull("hull_curve_4", p -> new com.starwake.ship.block.ProfileHullBlock.Four(CURVE, p));
	public static final Block HULL_COVE = hull("hull_cove", p -> new com.starwake.ship.block.ProfileHullBlock.One(COVE, p));
	public static final Block HULL_COVE_2 = hull("hull_cove_2", p -> new com.starwake.ship.block.ProfileHullBlock.Two(COVE, p));
	public static final Block HULL_COVE_4 = hull("hull_cove_4", p -> new com.starwake.ship.block.ProfileHullBlock.Four(COVE, p));
	public static final Block HULL_WEDGE = hull("hull_wedge", p -> new com.starwake.ship.block.ProfileHullBlock.One(RIDGE, p));
	public static final Block HULL_PLATE = hull("hull_plate", p -> new com.starwake.ship.block.HullPieceBlock.Plate(4, p));
	public static final Block HULL_HALF_PLATE = hull("hull_half_plate", p -> new com.starwake.ship.block.HullPieceBlock.Plate(8, p));
	public static final Block HULL_PILLAR = hull("hull_pillar", com.starwake.ship.block.HullPieceBlock.Pillar::new);
	public static final Block HULL_FRAME = hull("hull_frame", com.starwake.ship.block.HullPieceBlock.Frame::new);
	/** M10: half-height slope, 45-degree diagonal wall, square taper and rounded nose cone. */
	public static final Block HULL_SLOPE_HALF = hull("hull_slope_half", com.starwake.ship.block.ProfileHullBlock.Half::new);
	public static final Block HULL_DIAGONAL = hull("hull_diagonal",
			p -> new com.starwake.ship.block.HullPieceBlock.Fixed(new com.starwake.ship.panel.HullPartShapes.Diagonal(), false, p));
	public static final Block HULL_TAPER = hull("hull_taper",
			p -> new com.starwake.ship.block.HullPieceBlock.Fixed(new com.starwake.ship.panel.HullPartShapes.Taper(0.5), true, p));
	public static final Block HULL_NOSE = hull("hull_nose",
			p -> new com.starwake.ship.block.HullPieceBlock.Fixed(new com.starwake.ship.panel.HullPartShapes.Nose(), true, p));

	/** Every hull piece (alloy-keeping, shaped): for datagen, the block entity type and recipes. */
	public static List<Block> hullPieces() {
		return List.of(HULL_SLOPE, HULL_SLOPE_2, HULL_SLOPE_4, HULL_CURVE, HULL_CURVE_2, HULL_CURVE_4, HULL_COVE, HULL_COVE_2, HULL_COVE_4,
				HULL_WEDGE, HULL_PLATE, HULL_HALF_PLATE, HULL_PILLAR, HULL_FRAME, HULL_SLOPE_HALF, HULL_DIAGONAL, HULL_TAPER, HULL_NOSE);
	}

	/** Greeble (M9 pass 3): surface detail for hulls and interiors (shape cycled with the Hull Tool). */
	public static final Block GREEBLE = register("greeble", com.starwake.ship.block.GreebleBlock::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.METAL)
					.strength(3.0f, 6.0f)
					.sound(SoundType.METAL)
					.requiresCorrectToolForDrops());

	/** Ship Light (M9 pass 3): strip, panel, running, flood, landing lights and the nav beacon, in five colours. */
	public static final Block SHIP_LIGHT = register("ship_light", com.starwake.ship.block.ShipLightBlock::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.METAL)
					.strength(1.0f, 3.0f)
					.sound(SoundType.METAL));

	/** Hinge and Rotor (M9 pass 3): turn the part built on their face as a sub-assembly (hinges follow the landing gear). */
	public static final Block HINGE = register("hinge", p -> new com.starwake.ship.block.JointBlock(false, p),
			BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5.0f, 6.0f).sound(SoundType.METAL).requiresCorrectToolForDrops());
	public static final Block ROTOR = register("rotor", p -> new com.starwake.ship.block.JointBlock(true, p),
			BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5.0f, 6.0f).sound(SoundType.METAL).requiresCorrectToolForDrops());

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
					.requiresCorrectToolForDrops().noOcclusion());

	/** Main Engine: a big, heavy, thirsty thruster for primary propulsion (3h). */
	public static final Block MAIN_ENGINE = register("main_engine", ThrusterBlock::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.COLOR_GRAY)
					.strength(5.0f, 8.0f)
					.sound(SoundType.METAL)
					.lightLevel(state -> 4)
					.requiresCorrectToolForDrops().noOcclusion());

	/** Maneuvering Thruster: light, low-thrust; place far from the centre of mass to turn the ship (3h). */
	public static final Block MANEUVERING_THRUSTER = register("maneuvering_thruster", ThrusterBlock::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.METAL)
					.strength(3.0f, 6.0f)
					.sound(SoundType.METAL)
					.requiresCorrectToolForDrops().noOcclusion());

	/**
	 * Canopy Glass (M9 pass 2): frameless, faintly tinted ship glass for canopies and windows. Neighbouring panes merge
	 * seamlessly (no frame lines), it's tougher than window glass, seals a cabin, and rates 1200 C for re-entry.
	 */
	public static final Block CANOPY_GLASS = register("canopy_glass", net.minecraft.world.level.block.TransparentBlock::new,
			BlockBehaviour.Properties.of()
					.strength(1.5f, 6.0f)
					.sound(SoundType.GLASS)
					.noOcclusion()
					.isValidSpawn(net.minecraft.world.level.block.Blocks::never)
					.isRedstoneConductor(net.minecraft.world.level.block.Blocks::never)
					.isSuffocating(net.minecraft.world.level.block.Blocks::never));

	/**
	 * Dry Dock (M10): a steel gantry block for building ships on. It never becomes part of a ship: the scan stops at it
	 * like terrain, so a hull built on a dock (or a crafted floor of dock blocks) assembles without swallowing it.
	 */
	public static final Block DRY_DOCK = register("dry_dock", Block::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.COLOR_YELLOW)
					.strength(4.0f, 8.0f)
					.sound(SoundType.METAL)
					.requiresCorrectToolForDrops());

	/** Landing Gear: deploy/retract with N; soft landings need it down (3j). */
	public static final Block LANDING_GEAR = register("landing_gear", com.starwake.ship.block.LandingGearBlock::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.COLOR_GRAY)
					.strength(3.0f, 6.0f)
					.sound(SoundType.METAL)
					.noOcclusion()
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

	/** Ballistic Cannon (M11): a ship autocannon firing industry-made rounds; partly ignores shields. */
	public static final Block BALLISTIC_CANNON = register("ballistic_cannon", com.starwake.ship.block.ShipWeaponBlock::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.COLOR_GRAY)
					.strength(4.0f, 8.0f)
					.sound(SoundType.METAL)
					.requiresCorrectToolForDrops().noOcclusion());

	/** Laser Repeater (M11): a ship energy gun on the weapon capacitor; strong against shields. */
	public static final Block LASER_REPEATER = register("laser_repeater", com.starwake.ship.block.ShipWeaponBlock::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.COLOR_RED)
					.strength(4.0f, 8.0f)
					.sound(SoundType.METAL)
					.lightLevel(state -> 3)
					.requiresCorrectToolForDrops().noOcclusion());

	// --- Ship components (stats in ship_stats JSON) -------------------------------------

	/** Power Plant: generates power (and heat). Every ship needs at least one. */
	public static final Block POWER_PLANT = component("power_plant", MapColor.COLOR_LIGHT_BLUE, 7);
	/** Cooler: removes heat so power plants can run at full output. */
	public static final Block COOLER = component("cooler", MapColor.COLOR_CYAN, 0);
	/** Shield Generator: a regenerating shield that absorbs crash impacts. */
	public static final Block SHIELD_GENERATOR = component("shield_generator", MapColor.COLOR_BLUE, 5);
	/** Quantum Drive: spools up and jumps the ship between planets. */
	public static final Block QUANTUM_DRIVE = component("quantum_drive", MapColor.COLOR_PURPLE, 6);
	/** Jump Drive (M6): folds space to jump between star systems, light-years at a time. */
	public static final Block JUMP_DRIVE = component("jump_drive", MapColor.COLOR_ORANGE, 8);

	private static Block component(String name, MapColor color, int light) {
		return register(name, com.starwake.ship.block.ShipComponentBlock::new, BlockBehaviour.Properties.of()
				.mapColor(color)
				.strength(4.0f, 6.0f)
				.sound(SoundType.METAL)
				.lightLevel(state -> light)
				.requiresCorrectToolForDrops());
	}

	private ModBlocks() {}

	/** Registers a block and its block item under {@code starwake:<name>}. */
	static Block register(String name, Function<BlockBehaviour.Properties, Block> factory,
			BlockBehaviour.Properties properties) {
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, StarwakeMod.id(name));
		Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey, factory.apply(properties.setId(blockKey)));

		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, StarwakeMod.id(name));
		Registry.register(BuiltInRegistries.ITEM, itemKey,
				new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));

		ALL.add(block);
		return block;
	}

	private static Block hull(String name, Function<BlockBehaviour.Properties, Block> factory) {
		return register(name, factory, BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5.0f, 6.0f)
				.sound(SoundType.METAL).requiresCorrectToolForDrops());
	}

	/** All Starwake blocks in registration order (used by datagen and the creative tab). */
	public static List<Block> all() {
		return Collections.unmodifiableList(ALL);
	}

	public static int count() {
		return ALL.size();
	}

	/** Forces static initialization so every field above is registered. */
	public static void init() {
		// M9 pass 2: the Wing block is gone (starships don't fly on wings). Saved wings load as hull plating.
		((net.fabricmc.fabric.api.event.registry.FabricRegistry) BuiltInRegistries.BLOCK).addAlias(StarwakeMod.id("wing"), StarwakeMod.id("hull_plating"));
		((net.fabricmc.fabric.api.event.registry.FabricRegistry) BuiltInRegistries.ITEM).addAlias(StarwakeMod.id("wing"), StarwakeMod.id("hull_plating"));
	}
}
