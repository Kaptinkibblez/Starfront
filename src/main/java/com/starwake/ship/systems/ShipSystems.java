package com.starwake.ship.systems;

import com.starwake.ship.block.ThrusterBlock;
import com.starwake.ship.data.ShipStats;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A ship's components summed up: power generation, heat and cooling, and what each
 * subsystem draws. Shared by the pre-flight check (blocks in the world) and the flying ship
 * (its stored structure) so the numbers always agree.
 *
 * <h2>Rules</h2>
 * - Power plants make power and heat. If coolers can't remove all the heat, plants throttle
 *   down to what the coolers can handle ("overheating").
 * - Available power is split by the pilot's {@link PowerAllocation}. A subsystem's
 *   performance factor is (power given / power it wants at full load):
 *   - engines: thrust scales with it, up to 125% when overpowered
 *   - shields: regeneration scales with it (0-100%)
 *   - quantum: spool speed scales with it (0-100%)
 * - Thrusters only draw power (and count) when their exhaust is clear.
 */
public final class ShipSystems {
	/** Engines can be overclocked to this factor by routing extra power to them. */
	public static final double MAX_ENGINE_FACTOR = 1.25;

	private double powerOutput, heat, cooling;
	private double engineDraw, shieldDraw, quantumDraw;
	private double shieldCapacity, shieldRegen;
	private double quantumSpool; // best (shortest) drive spool time; 0 = no quantum drive
	private int powerPlants, coolers, shieldGenerators, quantumDrives;
	private double jumpRange, jumpSpool, jumpDraw; // jump drives (M6): best range, shortest spool
	private int jumpDrives;

	/** Adds one block. {@code clearExhaust} only matters for thrusters. */
	public void add(BlockState state, boolean clearExhaust) {
		ShipStats.BlockStats stats = ShipStats.get(state);
		if (stats.powerOutput() > 0) {
			powerOutput += stats.powerOutput();
			if (stats.heat() > 0) powerPlants++;
		}
		heat += stats.heat();
		if (stats.cooling() > 0) {
			cooling += stats.cooling();
			coolers++;
		}
		if (state.getBlock() instanceof ThrusterBlock) {
			if (clearExhaust) engineDraw += stats.powerDraw();
		} else if (stats.shieldCapacity() > 0) {
			shieldCapacity += stats.shieldCapacity();
			shieldRegen += stats.shieldRegen();
			shieldDraw += stats.powerDraw();
			shieldGenerators++;
		} else if (stats.jumpRange() > 0) {
			jumpRange = Math.max(jumpRange, stats.jumpRange());
			jumpSpool = jumpSpool == 0 ? stats.jumpSpool() : Math.min(jumpSpool, stats.jumpSpool());
			jumpDraw += stats.powerDraw();
			quantumDraw += stats.powerDraw(); // jump drives run on the quantum share of power
			jumpDrives++;
		} else if (stats.quantumSpool() > 0) {
			quantumSpool = quantumSpool == 0 ? stats.quantumSpool() : Math.min(quantumSpool, stats.quantumSpool());
			quantumDraw += stats.powerDraw();
			quantumDrives++;
		}
	}

	/** Fraction of nominal output the plants can run at given cooling (1 = fully cooled). */
	public double thermalFactor() {
		return heat <= 0 ? 1.0 : Mth.clamp(cooling / heat, 0.0, 1.0);
	}

	/** Power actually available after thermal throttling. */
	public double availablePower() {
		return powerOutput * thermalFactor();
	}

	public double engineFactor(PowerAllocation allocation) {
		return factor(availablePower() * allocation.engines(), engineDraw, MAX_ENGINE_FACTOR);
	}

	public double shieldFactor(PowerAllocation allocation) {
		return factor(availablePower() * allocation.shields(), shieldDraw, 1.0);
	}

	public double quantumFactor(PowerAllocation allocation) {
		return factor(availablePower() * allocation.quantum(), quantumDraw, 1.0);
	}

	private static double factor(double given, double wanted, double max) {
		if (wanted <= 0) return 1.0; // nothing to power: runs at full
		return Mth.clamp(given / wanted, 0.0, max);
	}

	public double powerOutput() { return powerOutput; }
	public double heat() { return heat; }
	public double cooling() { return cooling; }
	public double engineDraw() { return engineDraw; }
	public double shieldCapacity() { return shieldCapacity; }
	public double shieldRegen() { return shieldRegen; }
	public double quantumSpool() { return quantumSpool; }
	public int powerPlants() { return powerPlants; }
	public int coolers() { return coolers; }
	public int shieldGenerators() { return shieldGenerators; }
	public int quantumDrives() { return quantumDrives; }
	public boolean hasQuantumDrive() { return quantumDrives > 0; }
	public boolean hasJumpDrive() { return jumpDrives > 0; }
	public double jumpRange() { return jumpRange; }
	public double jumpSpool() { return jumpSpool; }
	public int jumpDrives() { return jumpDrives; }
}
