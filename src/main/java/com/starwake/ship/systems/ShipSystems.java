package com.starwake.ship.systems;

import com.starwake.ship.block.ThrusterBlock;
import com.starwake.ship.data.ShipStats;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A ship's components summed up: power generation, heat and cooling, and what each subsystem draws. Shared by the
 * pre-flight check (blocks in the world) and the flying ship (its stored structure) so the numbers always agree.
 *
 * <h2>Power pips (M10, Star Citizen style)</h2>
 * The plants' output is split into {@link #PIPS} pips. The pilot gives each {@link Subsystem} some pips
 * ({@link PowerPips}); a subsystem's performance factor is (pips x pip size) / (power it wants at full load), at most 1.
 * A subsystem can't take more pips than it can use ({@link #maxPips}). Engines scale thrust, shields regeneration,
 * coolers cooling, quantum the spool, weapons the capacitor charge (M11).
 *
 * <h2>Heat</h2>
 * Plants make heat in proportion to the pips handed out, and power that's used becomes heat; coolers remove up to
 * their cooling (scaled by their pips). The difference fills or empties a heat buffer ({@code ShipPower}). Static
 * numbers for pre-flight live here: {@link #heat()} is the plants' heat at full output.
 *
 * Thrusters only draw power (and count) when their exhaust is clear. A component's wear (M10) scales its stats
 * before they get here.
 */
public final class ShipSystems {
	/** Pips a full power plant output is divided into. */
	public static final int PIPS = 20;

	/** The power consumers the pilot shares pips between. */
	public enum Subsystem {
		ENGINES("eng"), SHIELDS("shd"), WEAPONS("wpn"), COOLERS("clr"), QUANTUM("qt");

		public final String code;

		Subsystem(String code) {
			this.code = code;
		}
	}

	private double powerOutput, heat, cooling;
	private final double[] draw = new double[Subsystem.values().length];
	private double shieldCapacity, shieldRegen, capacitor;
	private double quantumSpool; // best (shortest) drive spool time; 0 = no quantum drive
	private int powerPlants, coolers, shieldGenerators, quantumDrives;
	private double jumpRange, jumpSpool; // jump drives (M6): best range, shortest spool
	private int jumpDrives, weapons;

	/** Adds one block. {@code clearExhaust} only matters for thrusters. */
	public void add(BlockState state, boolean clearExhaust) {
		add(state, clearExhaust, ShipStats.get(state));
	}

	/** Adds a component with explicit stats (a part's quality bonus, M8e-2; its wear, M10). */
	public void add(BlockState state, boolean clearExhaust, ShipStats.BlockStats stats) {
		if (stats.powerOutput() > 0) {
			powerOutput += stats.powerOutput();
			if (stats.heat() > 0) powerPlants++;
		}
		heat += stats.heat();
		if (stats.cooling() > 0) {
			cooling += stats.cooling();
			draw[Subsystem.COOLERS.ordinal()] += stats.powerDraw();
			coolers++;
		} else if (state.getBlock() instanceof com.starwake.ship.block.ShipWeaponBlock) {
			// M11: guns draw on the weapon pips and bring their capacitor.
			draw[Subsystem.WEAPONS.ordinal()] += stats.powerDraw();
			var spec = com.starwake.ship.weapon.WeaponSpec.of(state.getBlock());
			if (spec != null) capacitor += spec.capacitor();
			weapons++;
		} else if (state.getBlock() instanceof ThrusterBlock) {
			if (clearExhaust) draw[Subsystem.ENGINES.ordinal()] += stats.powerDraw();
		} else if (stats.shieldCapacity() > 0) {
			shieldCapacity += stats.shieldCapacity();
			shieldRegen += stats.shieldRegen();
			draw[Subsystem.SHIELDS.ordinal()] += stats.powerDraw();
			shieldGenerators++;
		} else if (stats.jumpRange() > 0) {
			jumpRange = Math.max(jumpRange, stats.jumpRange());
			jumpSpool = jumpSpool == 0 ? stats.jumpSpool() : Math.min(jumpSpool, stats.jumpSpool());
			draw[Subsystem.QUANTUM.ordinal()] += stats.powerDraw(); // jump drives run on the quantum pips
			jumpDrives++;
		} else if (stats.quantumSpool() > 0) {
			quantumSpool = quantumSpool == 0 ? stats.quantumSpool() : Math.min(quantumSpool, stats.quantumSpool());
			draw[Subsystem.QUANTUM.ordinal()] += stats.powerDraw();
			quantumDrives++;
		}
	}

	/** kW per pip. */
	public double pipPower() {
		return powerOutput / PIPS;
	}

	/** Power (kW) a subsystem wants at full load. */
	public double want(Subsystem s) {
		return draw[s.ordinal()];
	}

	/** Pips that run a subsystem at full load (0 when it wants nothing or there's no power). */
	public int maxPips(Subsystem s) {
		double want = want(s), pip = pipPower();
		if (want <= 0 || pip <= 0) return 0;
		return Math.min(PIPS, (int) Math.ceil(want / pip - 1.0e-6));
	}

	/** Performance factor 0..1 of a subsystem given its pips (1 when it wants nothing). */
	public double factor(Subsystem s, int pips) {
		double want = want(s);
		if (want <= 0) return 1;
		return Mth.clamp(pips * pipPower() / want, 0, 1);
	}

	public double factor(Subsystem s, PowerPips pips) {
		return factor(s, pips.get(s));
	}

	/** Cooling vs the plants' heat at full output (1 = the coolers keep up with everything the plants can make). */
	public double coolingRatio() {
		return heat <= 0 ? 1.0 : Mth.clamp(cooling / heat, 0.0, 1.0);
	}

	public double powerOutput() { return powerOutput; }
	public double heat() { return heat; }
	public double cooling() { return cooling; }
	public double engineDraw() { return want(Subsystem.ENGINES); }
	public double shieldCapacity() { return shieldCapacity; }
	public double shieldRegen() { return shieldRegen; }
	/** Weapon capacitor capacity (kJ), from the guns aboard (M11). */
	public double capacitor() { return capacitor; }
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
	public int weapons() { return weapons; }
}
