package com.starwake.registry;

import com.starwake.planet.block.PlanetPlantBlock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.util.ColorRGBA;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ColoredFallingBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.sounds.AmbientLeavesBlockSoundPlayer;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/**
 * Planet content blocks (M5): rocks, soils, flora and ores for the procedural planets.
 *
 * <p>Grounded in real geology so the later metallurgy system has real elements to work with
 * (user, 2026-11-11): each ore is a real mineral and names the metal it yields. Flora is
 * plausible alien biology (photosynthetic mats and fronds in dark pigments, fungal growth on
 * toxic worlds), not magic.
 */
public final class ModPlanetBlocks {
	private static final List<Block> PLANTS = new ArrayList<>();
	private static final List<Ore> ORES = new ArrayList<>();

	/** A real mineral ore: its block and the raw mineral it drops (composition in {@link #mineral}). */
	public record Ore(com.starwake.chem.Chemistry.Mineral mineral, Block block, Item raw) {
		public String id() {
			return mineral.id();
		}
	}

	// --- Rock and soil -------------------------------------------------------------------

	/** Anorthosite: pale feldspar-rich crust rock of airless highlands (like the Moon's). */
	public static final Block ANORTHOSITE = ModBlocks.register("anorthosite", Block::new, rock(MapColor.QUARTZ, 1.5f));
	/** Fine Regolith: loose impact-shattered rock dust covering airless worlds. */
	public static final Block FINE_REGOLITH = ModBlocks.register("fine_regolith",
			p -> new ColoredFallingBlock(new ColorRGBA(0xFF8E8C88), p), soil(MapColor.COLOR_LIGHT_GRAY, SoundType.SAND));
	/** Volcanic Ash: fine tephra blanketing volcanic worlds. */
	public static final Block VOLCANIC_ASH = ModBlocks.register("volcanic_ash",
			p -> new ColoredFallingBlock(new ColorRGBA(0xFF3E3B3B), p), soil(MapColor.COLOR_GRAY, SoundType.SAND));
	/** Permafrost: soil frozen solid; the ground of cold worlds. */
	public static final Block PERMAFROST = ModBlocks.register("permafrost", Block::new,
			BlockBehaviour.Properties.of().mapColor(MapColor.ICE).strength(1.2f).sound(SoundType.GRAVEL).friction(0.8f));
	/** Halite: rock salt (NaCl) left by evaporated seas; desert salt flats. */
	public static final Block HALITE = ModBlocks.register("halite", Block::new,
			BlockBehaviour.Properties.of().mapColor(MapColor.SNOW).strength(1.0f).sound(SoundType.CALCITE).requiresCorrectToolForDrops());
	/** Selenite: giant gypsum (CaSO4·2H2O) crystal, as in Naica's Cave of Crystals. */
	public static final Block SELENITE = ModBlocks.register("selenite", Block::new,
			BlockBehaviour.Properties.of().mapColor(MapColor.QUARTZ).strength(1.2f).sound(SoundType.CALCITE).lightLevel(s -> 2)
					.requiresCorrectToolForDrops());
	/** Selenite Cluster: a spray of gypsum blades growing from rock. */
	public static final Block SELENITE_CLUSTER = plant("selenite_cluster", p -> new AmethystClusterBlock(7.0f, 10.0f, p),
			BlockBehaviour.Properties.of().mapColor(MapColor.QUARTZ).forceSolidOn().noOcclusion().sound(SoundType.CALCITE)
					.strength(1.0f).lightLevel(s -> 3).pushReaction(PushReaction.POPPED));

	// --- Flora ---------------------------------------------------------------------------

	/** Mat Turf: soil under a dense mat of dark-pigmented photosynthetic microbes. */
	public static final Block MAT_TURF = ModBlocks.register("mat_turf", Block::new,
			BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(0.6f).sound(SoundType.MOSS));
	/** Tuft Grass: wiry purple-black ground cover (an anthocyanin-like pigment soaks up a redder sun). */
	public static final Block TUFT_GRASS = plant("tuft_grass", PlanetPlantBlock::new, plantProperties(MapColor.COLOR_PURPLE, SoundType.GRASS));
	/** Fernwood: the woody stem of tree-sized fronds (like Earth's Carboniferous tree ferns). */
	public static final Block FERNWOOD_LOG = ModBlocks.register("fernwood_log", RotatedPillarBlock::new,
			Blocks.logProperties(MapColor.COLOR_BROWN, MapColor.TERRACOTTA_BLACK, SoundType.WOOD));
	/** Fernwood Fronds: the crown of a fernwood. */
	public static final Block FERNWOOD_FRONDS = plant("fernwood_fronds",
			p -> new LeavesBlock(AmbientLeavesBlockSoundPlayer.noAmbientSound(), p), Blocks.leavesProperties(SoundType.GRASS));
	/** Fungal Stem: the stalk of a giant saprophytic fungus thriving in sulfurous air. */
	public static final Block FUNGAL_STEM = ModBlocks.register("fungal_stem", RotatedPillarBlock::new,
			Blocks.logProperties(MapColor.TERRACOTTA_WHITE, MapColor.TERRACOTTA_YELLOW, SoundType.STEM));
	/** Fungal Cap: spongy cap tissue of a giant fungus. */
	public static final Block FUNGAL_CAP = ModBlocks.register("fungal_cap", Block::new,
			BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(1.0f).sound(SoundType.WART_BLOCK));
	/** Puffball: a small spore-bearing fungus. */
	public static final Block PUFFBALL = plant("puffball", PlanetPlantBlock::new, plantProperties(MapColor.COLOR_YELLOW, SoundType.FUNGUS));

	// --- Ores (real minerals, data-driven from the chemistry tables) -------------------

	static {
		for (com.starwake.chem.Chemistry.Mineral mineral : com.starwake.chem.Chemistry.minerals()) {
			if (mineral.registered()) ore(mineral);
		}
	}

	private ModPlanetBlocks() {}

	private static BlockBehaviour.Properties rock(MapColor color, float hardness) {
		return BlockBehaviour.Properties.of().mapColor(color).strength(hardness, 6.0f).sound(SoundType.STONE).requiresCorrectToolForDrops();
	}

	private static BlockBehaviour.Properties soil(MapColor color, SoundType sound) {
		return BlockBehaviour.Properties.of().mapColor(color).strength(0.5f).sound(sound);
	}

	private static BlockBehaviour.Properties plantProperties(MapColor color, SoundType sound) {
		return BlockBehaviour.Properties.of().mapColor(color).replaceable().noCollision().instabreak().sound(sound)
				.offsetType(BlockBehaviour.OffsetType.XZ).pushReaction(PushReaction.POPPED);
	}

	/** Non-solid blocks (kept out of the solid-block tag). */
	private static Block plant(String name, java.util.function.Function<BlockBehaviour.Properties, Block> factory,
			BlockBehaviour.Properties properties) {
		Block block = ModBlocks.register(name, factory, properties);
		PLANTS.add(block);
		return block;
	}

	private static Ore ore(com.starwake.chem.Chemistry.Mineral mineral) {
		Block block = ModBlocks.register(mineral.id() + "_ore", Block::new,
				BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(3.0f, 3.0f).sound(SoundType.STONE).requiresCorrectToolForDrops());
		Item raw = ModItems.register("raw_" + mineral.id(), Item::new, new Item.Properties());
		Ore ore = new Ore(mineral, block, raw);
		ORES.add(ore);
		return ore;
	}

	/** Blocks that aren't full solid cubes (plants, leaves, crystal clusters). */
	public static List<Block> nonSolid() {
		return Collections.unmodifiableList(PLANTS);
	}

	public static List<Ore> ores() {
		return Collections.unmodifiableList(ORES);
	}

	public static void init() {}
}
