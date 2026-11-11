package com.starwake.ship.scan;

import com.starwake.planet.Planets;
import com.starwake.registry.ModBlocks;
import com.starwake.ship.block.FuelTankBlockEntity;
import com.starwake.ship.block.HelmBlock;
import com.starwake.ship.block.ThrusterBlock;
import com.starwake.ship.data.ShipStats;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The pre-flight check: turns a scanned structure into pass/warn/fail lines and stats.
 *
 * These are functional conditions, not shape rules. Any design that meets them flies:
 * - one Helm, with room above it for the pilot
 * - lift: thrust from down-facing thrusters must exceed mass x local gravity
 * - thrusters need clear exhaust (no ship block in front of the nozzle) to count
 * - fuel on board
 * - (for space) a sealed cabin: air around the Helm can't leak out of the hull
 *
 * Directional thrust is reported, not required: a ship with no reverse thrusters simply
 * can't reverse. Design decides handling.
 */
public record PreflightReport(List<Line> lines, boolean canFly, boolean spaceCapable,
		double mass, double lift, int fuel, int fuelCapacity, Map<Direction, Double> thrust) {

	public enum Status {
		PASS("✔", 0xFF55FF55), WARN("!", 0xFFFFAA00), FAIL("✘", 0xFFFF5555), INFO("•", 0xFFAAAAAA);

		public final String icon;
		/** ARGB colour for the icon. */
		public final int color;

		Status(String icon, int color) {
			this.icon = icon;
			this.color = color;
		}
	}

	public record Line(Status status, Component text) {}

	/** Minimum lift-to-weight ratio for takeoff (a small margin so ships can actually climb). */
	public static final double MIN_LIFT_RATIO = 1.05;
	/** Cabin flood-fill cap: bigger "cabins" are treated as open to space. */
	public static final int MAX_CABIN = 32768;

	public static PreflightReport analyze(Level level, ShipScanner.ShipScan scan) {
		List<Line> lines = new ArrayList<>();
		boolean canFly = true;
		Set<BlockPos> ship = scan.blocks();
		BlockState helmState = level.getBlockState(scan.helm());
		Direction forward = helmState.hasProperty(HelmBlock.FACING) ? helmState.getValue(HelmBlock.FACING) : Direction.NORTH;

		// --- Structure ----------------------------------------------------------------
		double mass = 0;
		int helms = 0, thrusters = 0, blocked = 0, tanks = 0, fuel = 0, capacity = 0;
		double fuelBurn = 0;
		Map<Direction, Double> thrust = new EnumMap<>(Direction.class);
		for (Direction d : Direction.values()) thrust.put(d, 0.0);

		for (BlockPos pos : ship) {
			BlockState state = level.getBlockState(pos);
			ShipStats.BlockStats stats = ShipStats.get(state);
			mass += stats.mass();
			if (state.is(ModBlocks.HELM)) {
				helms++;
			} else if (state.getBlock() instanceof ThrusterBlock) {
				thrusters++;
				Direction nozzle = state.getValue(ThrusterBlock.FACING);
				if (ship.contains(pos.relative(nozzle))) {
					blocked++;
				} else {
					thrust.merge(nozzle.getOpposite(), stats.thrust(), Double::sum);
					fuelBurn += stats.fuelBurn();
				}
			} else if (level.getBlockEntity(pos) instanceof FuelTankBlockEntity tank) {
				tanks++;
				fuel += tank.fuel();
				capacity += tank.capacity();
			}
		}

		if (scan.tooBig()) {
			lines.add(new Line(Status.FAIL, Component.translatable("preflight.starwake.too_big", ShipScanner.MAX_BLOCKS)));
			canFly = false;
		} else {
			lines.add(new Line(Status.INFO, Component.translatable("preflight.starwake.structure", ship.size(), fmt(mass))));
		}

		// --- Helm ---------------------------------------------------------------------
		if (helms > 1) {
			lines.add(new Line(Status.FAIL, Component.translatable("preflight.starwake.multiple_helms", helms)));
			canFly = false;
		}
		boolean pilotRoom = !ship.contains(scan.helm().above()) && !ship.contains(scan.helm().above(2));
		if (!pilotRoom) {
			lines.add(new Line(Status.FAIL, Component.translatable("preflight.starwake.no_pilot_room")));
			canFly = false;
		}

		// --- Lift -----------------------------------------------------------------------
		double gravity = Planets.gravityOf(level.dimension());
		double weight = mass * gravity;
		double lift = thrust.get(Direction.UP);
		if (thrusters == 0) {
			lines.add(new Line(Status.FAIL, Component.translatable("preflight.starwake.no_thrusters")));
			canFly = false;
		} else if (lift < weight * MIN_LIFT_RATIO) {
			lines.add(new Line(Status.FAIL, Component.translatable("preflight.starwake.lift_low",
					fmt(lift), fmt(weight), fmt2(gravity), fmt(weight * MIN_LIFT_RATIO))));
			canFly = false;
		} else {
			lines.add(new Line(Status.PASS, Component.translatable("preflight.starwake.lift_ok",
					fmt(lift), fmt(weight), fmt2(gravity), fmt2(lift / weight))));
		}
		if (blocked > 0) {
			lines.add(new Line(Status.WARN, Component.translatable("preflight.starwake.blocked_exhaust", blocked)));
		}

		// --- Handling (per-direction thrust, relative to the Helm) --------------------
		Direction left = forward.getCounterClockWise(), right = forward.getClockWise(), back = forward.getOpposite();
		lines.add(new Line(Status.INFO, Component.translatable("preflight.starwake.handling",
				fmt(thrust.get(forward)), fmt(thrust.get(back)), fmt(thrust.get(left)), fmt(thrust.get(right)),
				fmt(thrust.get(Direction.DOWN)))));
		List<String> missing = new ArrayList<>();
		if (thrust.get(forward) <= 0) missing.add("forward");
		if (thrust.get(back) <= 0) missing.add("reverse");
		if (thrust.get(left) <= 0) missing.add("left");
		if (thrust.get(right) <= 0) missing.add("right");
		if (!missing.isEmpty() && thrusters > 0) {
			lines.add(new Line(Status.WARN, Component.translatable("preflight.starwake.cant_move", String.join(", ", missing))));
		}

		// --- Fuel -----------------------------------------------------------------------
		if (tanks == 0) {
			lines.add(new Line(Status.FAIL, Component.translatable("preflight.starwake.no_tanks")));
			canFly = false;
		} else if (fuel <= 0) {
			lines.add(new Line(Status.FAIL, Component.translatable("preflight.starwake.no_fuel", capacity)));
			canFly = false;
		} else {
			// Endurance at full throttle on every clear thruster: a worst-case number.
			String endurance = fuelBurn > 0 ? fmt(fuel / fuelBurn / 20.0) : "∞";
			lines.add(new Line(Status.PASS, Component.translatable("preflight.starwake.fuel_ok", fuel, capacity, endurance)));
		}

		// --- Cabin seal (needed to leave the atmosphere) -----------------------------------
		boolean sealed = false;
		if (pilotRoom) {
			CabinResult cabin = floodCabin(level, ship, scan.helm().above());
			sealed = cabin.sealed();
			if (sealed) {
				lines.add(new Line(Status.PASS, Component.translatable("preflight.starwake.sealed", cabin.volume())));
			} else {
				BlockPos leak = cabin.leak();
				lines.add(new Line(Status.WARN, Component.translatable("preflight.starwake.not_sealed",
						leak.getX(), leak.getY(), leak.getZ())));
			}
		}

		boolean spaceCapable = canFly && sealed;
		lines.add(canFly
				? new Line(spaceCapable ? Status.PASS : Status.WARN, Component.translatable(spaceCapable
						? "preflight.starwake.verdict_space" : "preflight.starwake.verdict_atmo"))
				: new Line(Status.FAIL, Component.translatable("preflight.starwake.verdict_grounded")));

		return new PreflightReport(List.copyOf(lines), canFly, spaceCapable, mass, lift, fuel, capacity, thrust);
	}

	private record CabinResult(boolean sealed, int volume, BlockPos leak) {}

	/**
	 * Flood-fills the air around the pilot seat. Only ship blocks can seal: full-collision
	 * blocks (any material, glass included), plus closed doors and trapdoors (airlocks).
	 * If the air reaches the edge of the ship's bounding box, the cabin leaks.
	 */
	private static CabinResult floodCabin(Level level, Set<BlockPos> ship, BlockPos seat) {
		BoundingBox box = BoundingBox.encapsulatingPositions(ship).orElseThrow();
		Set<BlockPos> visited = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		visited.add(seat);
		queue.add(seat);
		while (!queue.isEmpty()) {
			BlockPos pos = queue.poll();
			if (!box.isInside(pos) || visited.size() > MAX_CABIN) {
				return new CabinResult(false, visited.size(), pos);
			}
			for (Direction direction : Direction.values()) {
				BlockPos next = pos.relative(direction);
				if (visited.contains(next) || seals(level, ship, next)) {
					continue;
				}
				visited.add(next);
				queue.add(next);
			}
		}
		return new CabinResult(true, visited.size(), seat);
	}

	private static boolean seals(Level level, Set<BlockPos> ship, BlockPos pos) {
		if (!ship.contains(pos)) {
			return false;
		}
		return com.starwake.ship.ShipEntity.sealsCabin(level.getBlockState(pos));
	}

	private static String fmt(double value) {
		return String.format("%.1f", value);
	}

	private static String fmt2(double value) {
		return String.format("%.2f", value);
	}
}
