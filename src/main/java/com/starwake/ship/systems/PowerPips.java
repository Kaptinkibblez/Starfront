package com.starwake.ship.systems;

import com.mojang.serialization.Codec;
import com.starwake.ship.systems.ShipSystems.Subsystem;
import java.util.Arrays;
import java.util.List;

/**
 * The pilot's power pips per {@link Subsystem} (M10; replaces the 3f power triangle). At most {@link ShipSystems#PIPS}
 * in all and never more than a subsystem can use ({@link #normalise}). Immutable.
 */
public final class PowerPips {
	private static final Subsystem[] ALL = Subsystem.values();
	/** Who gets power first when there isn't enough for everything (auto distribution, and trimming). */
	private static final Subsystem[] PRIORITY = {Subsystem.COOLERS, Subsystem.ENGINES, Subsystem.SHIELDS, Subsystem.QUANTUM, Subsystem.WEAPONS};

	/** "Not set yet": {@link #normalise} fills it automatically. */
	public static final PowerPips AUTO = new PowerPips(new int[] {-1, -1, -1, -1, -1});

	public static final Codec<PowerPips> CODEC = Codec.INT.listOf().xmap(
			list -> new PowerPips(list.stream().mapToInt(Integer::intValue).toArray()),
			pips -> Arrays.stream(pips.pips).boxed().toList());

	private final int[] pips;

	private PowerPips(int[] pips) {
		this.pips = Arrays.copyOf(pips, ALL.length);
	}

	public static PowerPips of(int[] pips) {
		return new PowerPips(pips);
	}

	public int get(Subsystem s) {
		return Math.max(0, pips[s.ordinal()]);
	}

	public int total() {
		int sum = 0;
		for (int p : pips) sum += Math.max(0, p);
		return sum;
	}

	public boolean isAuto() {
		return pips[0] < 0;
	}

	public PowerPips with(Subsystem s, int value) {
		int[] next = Arrays.copyOf(pips, pips.length);
		for (int i = 0; i < next.length; i++) next[i] = Math.max(0, next[i]);
		next[s.ordinal()] = value;
		return new PowerPips(next);
	}

	/**
	 * Fits these pips to the ship: each subsystem gets at most what it can use; if the total is over the budget the
	 * lowest-priority ones give pips back. Unset pips fill each subsystem to its need in priority order.
	 */
	public PowerPips normalise(ShipSystems systems) {
		int[] next = new int[ALL.length];
		int budget = systems.powerOutput() > 0 ? ShipSystems.PIPS : 0;
		if (isAuto()) {
			for (Subsystem s : PRIORITY) {
				int give = Math.min(systems.maxPips(s), budget);
				next[s.ordinal()] = give;
				budget -= give;
			}
			return new PowerPips(next);
		}
		for (Subsystem s : ALL) next[s.ordinal()] = Math.min(get(s), systems.maxPips(s));
		int over = Arrays.stream(next).sum() - budget;
		for (int i = PRIORITY.length - 1; i >= 0 && over > 0; i--) {
			int idx = PRIORITY[i].ordinal(), take = Math.min(next[idx], over);
			next[idx] -= take;
			over -= take;
		}
		return new PowerPips(next);
	}

	/** Packed for entity data: 5 bits per subsystem. */
	public int pack() {
		int out = 0;
		for (int i = 0; i < ALL.length; i++) out |= (Math.clamp(get(ALL[i]), 0, 31)) << (5 * i);
		return out;
	}

	public static int[] unpack(int packed) {
		int[] out = new int[ALL.length];
		for (int i = 0; i < ALL.length; i++) out[i] = (packed >> (5 * i)) & 31;
		return out;
	}

	public List<Integer> asList() {
		return Arrays.stream(pips).map(p -> Math.max(0, p)).boxed().toList();
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof PowerPips other && Arrays.equals(pips, other.pips);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(pips);
	}
}
