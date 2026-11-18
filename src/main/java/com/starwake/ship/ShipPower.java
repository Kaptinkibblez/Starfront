package com.starwake.ship;

import com.starwake.registry.ModComponents;
import com.starwake.ship.data.ShipMaterials;
import com.starwake.ship.systems.PowerPips;
import com.starwake.ship.systems.ShipSystems;
import com.starwake.ship.systems.ShipSystems.Subsystem;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * A ship's power, heat and wear at run time (M10, Star Citizen style; split out of {@link ShipEntity}).
 *
 * <h2>Pips</h2> The pilot's {@link PowerPips}, refitted to the ship ({@link PowerPips#normalise}) whenever its systems
 * change. {@link #factor} is what each subsystem gets.
 *
 * <h2>Heat</h2> Every tick the plants make heat in proportion to the pips handed out, used power adds half its draw
 * (thrusters by their throttle, shields while recharging, drives while spooling), and the coolers remove up to their
 * cooling x their pip factor. The balance fills or empties a buffer that holds {@link #BUFFER_SECONDS} of the plants'
 * full heat: 100% = overheating. Over 100% every part wears; over {@link #CABIN_HEAT} the cabin cooks the crew.
 *
 * <h2>Wear</h2> Each functional part has a health (component {@code starwake:health} on its block entity, so it stays
 * with the part through disassembly and as an item). Overheating and hull impacts wear parts; a part works at
 * {@link ShipMaterials#effectiveness} and is disabled below 25%. A metal plate used on a part repairs 25%.
 *
 * <h2>Capacitor</h2> Weapon pips charge the weapon capacitor (sized by the guns aboard, M11); energy guns draw on it.
 */
public final class ShipPower {
	/** Seconds of the plants' full heat the buffer holds before overheating. */
	public static final double BUFFER_SECONDS = 60;
	/** Heat level (fraction of the buffer) where the cabin starts to burn the crew. */
	public static final double CABIN_HEAT = 1.5;
	/** Health a repair restores. */
	public static final float REPAIR = 0.25f;

	private static final com.mojang.serialization.Codec<Map<String, Float>> HEALTH_CODEC =
			com.mojang.serialization.Codec.unboundedMap(com.mojang.serialization.Codec.STRING, com.mojang.serialization.Codec.FLOAT);

	private final ShipEntity ship;
	private PowerPips pips = PowerPips.AUTO;
	/** Stored heat, kJ. */
	private double heat;
	/** Weapon capacitor charge, kJ. */
	private double capacitor;
	/** Part health by local position (only parts below 1 are kept). */
	private final Map<BlockPos, Float> health = new HashMap<>();
	/** Parts whose health moved enough that their stats need refreshing. */
	private final Set<BlockPos> dirty = new HashSet<>();
	private boolean warnedHot, warnedCabin;

	ShipPower(ShipEntity ship) {
		this.ship = ship;
	}

	// --- Pips --------------------------------------------------------------------------------

	public PowerPips pips() {
		return pips;
	}

	/** The pilot (or the saved ship) sets the pips; they're refitted to what the ship has. */
	public void setPips(PowerPips requested) {
		pips = requested.normalise(ship.systems());
		ship.publishSystems();
	}

	/** Refit after the ship's systems changed (built, edited, worn). */
	void refit() {
		pips = pips.normalise(ship.systems());
	}

	public double factor(Subsystem s) {
		return ship.systems().factor(s, pips);
	}

	// --- Heat --------------------------------------------------------------------------------

	/** Heat as a fraction of the buffer (1 = overheating). */
	public double heatLevel() {
		return heat / buffer();
	}

	private double buffer() {
		return BUFFER_SECONDS * Math.max(10, ship.systems().heat());
	}

	/**
	 * Server, every tick: heat in and out. {@code engineLoad} is the mean thruster throttle, {@code shieldsCharging} and
	 * {@code spooling} say whether those draw power right now.
	 */
	void tick(double engineLoad, boolean shieldsCharging, boolean spooling) {
		ShipSystems systems = ship.systems();
		double plantHeat = systems.heat() * pips.total() / (double) ShipSystems.PIPS;
		double used = systems.want(Subsystem.ENGINES) * factor(Subsystem.ENGINES) * engineLoad
				+ (shieldsCharging ? systems.want(Subsystem.SHIELDS) * factor(Subsystem.SHIELDS) : 0)
				+ (spooling ? systems.want(Subsystem.QUANTUM) * factor(Subsystem.QUANTUM) : 0);
		double cooling = systems.cooling() * factor(Subsystem.COOLERS);
		double net = plantHeat + 0.5 * used - cooling; // kW
		heat = Math.clamp(heat + net / 20.0, 0, 2 * buffer());
		capacitor = Math.min(systems.capacitor(), capacitor + systems.want(Subsystem.WEAPONS) * factor(Subsystem.WEAPONS) / 20.0);
		if (ship.tickCount % 20 == 0) overheat();
	}

	/** Once a second: wear from overheating, the crew cooking, warnings. */
	private void overheat() {
		double level = heatLevel();
		if (!(ship.level() instanceof ServerLevel server)) return;
		if (level > 1) {
			float wear = (float) (0.005 * (level - 1) / 0.1);
			wearAll(wear);
			if (!warnedHot) ship.messageAboard(Component.translatable("message.starwake.power.overheating"));
			warnedHot = true;
		} else if (level < 0.9) {
			warnedHot = false;
		}
		if (level > CABIN_HEAT) {
			if (!warnedCabin) ship.messageAboard(Component.translatable("message.starwake.power.cabin_heat"));
			warnedCabin = true;
			float damage = (float) Math.min(4, 1 + (level - CABIN_HEAT) * 6);
			for (Entity e : server.getEntities(ship, ship.getBoundingBox().inflate(1), e -> e instanceof LivingEntity)) {
				if (e.getVehicle() == ship || ship.isSupporting(e)) {
					e.hurtServer(server, server.damageSources().source(com.starwake.planet.PlanetHazards.EXTREME_HEAT), damage);
				}
			}
		} else if (level < CABIN_HEAT - 0.1) {
			warnedCabin = false;
		}
	}

	// --- Wear --------------------------------------------------------------------------------

	public float health(BlockPos part) {
		return health.getOrDefault(part, 1f);
	}

	/** Called when the ship (re)reads a part from its structure: block-entity health unless we know better. */
	float load(BlockPos part, float fromBlock) {
		Float known = health.get(part);
		if (known != null) return known;
		if (fromBlock < 1) health.put(part.immutable(), fromBlock);
		return fromBlock;
	}

	void forget(BlockPos part) {
		health.remove(part);
	}

	/** Wears one part; stats refresh when it has moved a 5% step or crossed the disabled line. */
	public void wear(BlockPos part, float amount) {
		if (amount <= 0) return;
		set(part, Math.max(0, health(part) - amount));
	}

	private void wearAll(float amount) {
		for (BlockPos part : List.copyOf(ship.components())) wear(part, amount);
		flush();
	}

	/** A repair (a metal plate used on the part): returns false when it's already like new. */
	public boolean repair(BlockPos part) {
		float h = health(part);
		if (h >= 0.999f) return false;
		set(part, Math.min(1, h + REPAIR));
		flush();
		return true;
	}

	private void set(BlockPos part, float next) {
		float before = health(part);
		if (next >= 0.999f) health.remove(part);
		else health.put(part.immutable(), next);
		boolean crossed = (before < ShipMaterials.DISABLED_BELOW) != (next < ShipMaterials.DISABLED_BELOW);
		if (crossed || Math.floor(before * 20) != Math.floor(next * 20) || next >= 0.999f) {
			dirty.add(part.immutable());
			if (crossed && next < ShipMaterials.DISABLED_BELOW) {
				ship.messageAboard(Component.translatable("message.starwake.power.part_disabled", partName(part)));
			}
		}
	}

	private Component partName(BlockPos part) {
		var state = ship.structure() == null ? null : ship.structure().get(part);
		return state == null ? Component.literal("?") : state.getBlock().getName();
	}

	/** Applies pending health changes: the parts' stats, and the health stored on their block entities in the plot. */
	void flush() {
		if (dirty.isEmpty()) return;
		Set<BlockPos> changed = Set.copyOf(dirty);
		dirty.clear();
		if (ship.level() instanceof ServerLevel server) {
			ServerLevel yard = com.starwake.ship.yard.Shipyard.level(server.getServer());
			if (yard != null && ship.plot() >= 0) {
				for (BlockPos part : changed) {
					BlockEntity be = yard.getBlockEntity(com.starwake.ship.yard.Shipyard.toPlot(ship.plot(), part));
					if (be == null) continue;
					DataComponentMap.Builder next = DataComponentMap.builder().addAll(be.components());
					float h = health(part);
					next.set(ModComponents.HEALTH, h);
					be.setComponents(next.build());
					be.setChanged();
				}
			}
		}
		ship.refreshParts(changed);
	}

	/** Adds heat (kJ), e.g. a gun firing (M11). */
	public void addHeat(double kJ) {
		heat = Math.clamp(heat + kJ, 0, 2 * buffer());
	}

	/** Admin/testing: sets the heat level (fraction of the buffer). */
	public void setHeatLevel(double level) {
		heat = Math.max(0, level) * buffer();
	}

	/** Admin/testing: sets every part's health. */
	public void setAll(float h) {
		for (BlockPos part : List.copyOf(ship.components())) set(part, Math.clamp(h, 0, 1));
		flush();
	}

	/** Parts below full health (for the Ship app). */
	public Map<BlockPos, Float> worn() {
		return Map.copyOf(health);
	}

	// --- Capacitor ---------------------------------------------------------------------------

	public double capacitor() {
		return capacitor;
	}

	/** Takes energy from the weapon capacitor (M11); false if there isn't enough. */
	public boolean drawCapacitor(double kJ) {
		if (capacitor < kJ) return false;
		capacitor -= kJ;
		return true;
	}

	// --- Saving ------------------------------------------------------------------------------

	void read(net.minecraft.world.level.storage.ValueInput input) {
		pips = input.read("pips", PowerPips.CODEC).orElse(PowerPips.AUTO);
		heat = input.getDoubleOr("heat", 0);
		capacitor = input.getDoubleOr("capacitor", 0);
		health.clear();
		input.read("part_health", HEALTH_CODEC).ifPresent(map -> map.forEach((key, h) -> {
			BlockPos pos = unpack(key);
			if (pos != null) health.put(pos, h);
		}));
	}

	void write(net.minecraft.world.level.storage.ValueOutput output) {
		output.store("pips", PowerPips.CODEC, pips);
		output.putDouble("heat", heat);
		output.putDouble("capacitor", capacitor);
		Map<String, Float> map = new HashMap<>();
		health.forEach((pos, h) -> map.put(pos.getX() + "," + pos.getY() + "," + pos.getZ(), h));
		output.store("part_health", HEALTH_CODEC, map);
	}

	private static @Nullable BlockPos unpack(String key) {
		String[] p = key.split(",");
		if (p.length != 3) return null;
		try {
			return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
