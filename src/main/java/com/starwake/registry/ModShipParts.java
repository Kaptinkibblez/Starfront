package com.starwake.registry;

import com.starwake.ship.block.FuelTankBlock;
import com.starwake.ship.block.LandingGearBlock;
import com.starwake.ship.block.ShipComponentBlock;
import com.starwake.ship.block.ThrusterBlock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Mk tiers of every functional ship part (M8e-2). Mk I keeps the original block ids (thruster, main_engine,
 * ...); Mk II and III are new blocks of the same classes ({@code <id>_mk2}, {@code <id>_mk3}) with their own
 * ship_stats JSON. All of them store the material and quality they were fabricated with on a block entity
 * (MaterialBlockEntity, or the fuel tank's own), which scales their stats (ship/data/ShipMaterials).
 */
public final class ModShipParts {
	public enum Part {
		THRUSTER("thruster"), MAIN_ENGINE("main_engine"), MANEUVERING_THRUSTER("maneuvering_thruster"), POWER_PLANT("power_plant"),
		COOLER("cooler"), SHIELD_GENERATOR("shield_generator"), QUANTUM_DRIVE("quantum_drive"), JUMP_DRIVE("jump_drive"),
		FUEL_TANK("fuel_tank"), LANDING_GEAR("landing_gear");

		public final String id;

		Part(String id) {
			this.id = id;
		}
	}

	private static final Map<Part, Block[]> TIERS = new EnumMap<>(Part.class);

	static {
		tiers(Part.THRUSTER, ModBlocks.THRUSTER, ThrusterBlock::new);
		tiers(Part.MAIN_ENGINE, ModBlocks.MAIN_ENGINE, ThrusterBlock::new);
		tiers(Part.MANEUVERING_THRUSTER, ModBlocks.MANEUVERING_THRUSTER, ThrusterBlock::new);
		tiers(Part.POWER_PLANT, ModBlocks.POWER_PLANT, ShipComponentBlock::new);
		tiers(Part.COOLER, ModBlocks.COOLER, ShipComponentBlock::new);
		tiers(Part.SHIELD_GENERATOR, ModBlocks.SHIELD_GENERATOR, ShipComponentBlock::new);
		tiers(Part.QUANTUM_DRIVE, ModBlocks.QUANTUM_DRIVE, ShipComponentBlock::new);
		tiers(Part.JUMP_DRIVE, ModBlocks.JUMP_DRIVE, ShipComponentBlock::new);
		tiers(Part.FUEL_TANK, ModBlocks.FUEL_TANK, FuelTankBlock::new);
		tiers(Part.LANDING_GEAR, ModBlocks.LANDING_GEAR, LandingGearBlock::new);
	}

	private ModShipParts() {}

	public static void init() {}

	private static void tiers(Part part, Block mk1, Function<BlockBehaviour.Properties, Block> factory) {
		Block mk2 = ModBlocks.register(part.id + "_mk2", factory, BlockBehaviour.Properties.ofFullCopy(mk1));
		Block mk3 = ModBlocks.register(part.id + "_mk3", factory, BlockBehaviour.Properties.ofFullCopy(mk1));
		TIERS.put(part, new Block[] {mk1, mk2, mk3});
	}

	/** The block for a part at Mk 1-3. */
	public static Block get(Part part, int mk) {
		return TIERS.get(part)[mk - 1];
	}

	/** Every Mk II and III block (Mk I blocks live in ModBlocks). */
	public static List<Block> upgrades() {
		List<Block> out = new ArrayList<>();
		for (Block[] t : TIERS.values()) {
			out.add(t[1]);
			out.add(t[2]);
		}
		return out;
	}

	/** Every tier of every part except fuel tanks (which have their own block entity). */
	public static Block[] materialBlocks() {
		List<Block> out = new ArrayList<>();
		TIERS.forEach((part, t) -> {
			if (part != Part.FUEL_TANK) out.addAll(List.of(t));
		});
		return out.toArray(Block[]::new);
	}

	public static Block[] fuelTanks() {
		return TIERS.get(Part.FUEL_TANK).clone();
	}

	/** Mk tier of a part block (1 for Mk I and for anything else). */
	public static int tierOf(Block block) {
		for (Block[] t : TIERS.values()) {
			for (int i = 0; i < 3; i++) if (t[i] == block) return i + 1;
		}
		return 1;
	}
}
