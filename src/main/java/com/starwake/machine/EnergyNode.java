package com.starwake.machine;

import net.minecraft.core.Direction;

/**
 * A block entity on the electric grid (M8b). Energy is in joules; rates are joules per tick
 * (1 kW = 50 J/tick). The network ({@link PowerNetworks}) moves energy between nodes once per tick:
 * generators push out of their buffers, consumers fill theirs, storage (batteries) balances.
 */
public interface EnergyNode {
	enum Role { GENERATOR, CONSUMER, STORAGE }

	Role energyRole();

	long energyStored();

	long energyCapacity();

	void setEnergyStored(long joules);

	/** Most this node accepts per tick (J). */
	long maxEnergyInput();

	/** Most this node gives per tick (J). */
	long maxEnergyOutput();

	default boolean connectsEnergy(Direction side) {
		return true;
	}

	/** J/tick for kilowatts. */
	static long perTick(double kw) {
		return Math.round(kw * 1000 / 20.0);
	}

	/** Kilowatts for J/tick. */
	static double kw(double joulesPerTick) {
		return joulesPerTick * 20 / 1000.0;
	}
}
