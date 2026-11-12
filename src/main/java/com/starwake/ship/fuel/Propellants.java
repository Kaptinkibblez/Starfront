package com.starwake.ship.fuel;

import java.util.Map;
import java.util.Set;

/**
 * Real propellants (M8f). Fuel tanks (and ships, through their tanks) take gases from pipes and Gas Cylinders
 * and burn matching pairs into the ship's two fuel pools:
 * <ul>
 *   <li><b>Propellant units</b> (thrusters): hydrolox, H2 + O2 at the engine mixture ratio O/F 6.0 (fuel-rich, Isp
 *   ~450 s), {@link #HYDROLOX_UNITS_PER_KG} units per kg of mixture; methalox, CH4 + O2 at O/F 3.6 (Isp ~360 s),
 *   {@link #METHALOX_UNITS_PER_KG}. Units per kg follow Isp, scaled so a kilogram of coal (the provisional
 *   fallback) is worth less than a kilogram of real propellant.</li>
 *   <li><b>Fusion units</b> (quantum and jump drives): D + He-3, D2 and He3 at the reaction's mass ratio
 *   (2.014 : 3.016 per atom pair); one unit = {@link #FUSION_KG_PER_UNIT} kg of mixture (0.1 mg). D-He3 releases
 *   ~3.5e14 J/kg, so a few milligrams carry a ship between planets.</li>
 * </ul>
 * Unpaired gas waits in the tank for its partner. The gases themselves come from the chemistry: H2 from
 * electrolysis, O2 from the Air Separation Unit, CH4 from coke ovens and gas wells, D2 from heavy water
 * (Isotope Column + electrolysis), He3 from primordial helium (ASU on noble-gas-rich worlds).
 */
public final class Propellants {
	public static final double HYDROLOX_OF = 6.0, METHALOX_OF = 3.6;
	public static final double HYDROLOX_UNITS_PER_KG = 150, METHALOX_UNITS_PER_KG = 120;
	public static final double FUSION_KG_PER_UNIT = 1e-7;
	/** He3 : D2 mass ratio for equal atoms of D and He-3 (D2 carries two D per molecule). */
	public static final double HE3_PER_D2 = 2 * 3.016 / 4.028;
	public static final Set<String> SPECIES = Set.of("H2", "O2", "CH4", "D2", "He3");

	private Propellants() {}

	/** Fuel and fusion units made from {@code buffer} (kg by species), which is drawn down. Never more than the room given. */
	public static int[] convert(Map<String, Double> buffer, int fuelRoom, int fusionRoom) {
		int fuel = 0, fusion = 0;
		fuel += pair(buffer, "H2", HYDROLOX_OF, HYDROLOX_UNITS_PER_KG, fuelRoom);
		fuel += pair(buffer, "CH4", METHALOX_OF, METHALOX_UNITS_PER_KG, fuelRoom - fuel);
		// Fusion: D2 + He3 at the atom ratio.
		double d2 = buffer.getOrDefault("D2", 0.0), he3 = buffer.getOrDefault("He3", 0.0);
		double pairD2 = Math.min(d2, he3 / HE3_PER_D2);
		int units = (int) Math.min(fusionRoom, Math.floor(pairD2 * (1 + HE3_PER_D2) / FUSION_KG_PER_UNIT + 1e-6));
		if (units > 0) {
			double usedD2 = units * FUSION_KG_PER_UNIT / (1 + HE3_PER_D2);
			buffer.merge("D2", -usedD2, Double::sum);
			buffer.merge("He3", -usedD2 * HE3_PER_D2, Double::sum);
			fusion = units;
		}
		buffer.values().removeIf(v -> v < 1e-12);
		return new int[] {fuel, fusion};
	}

	private static int pair(Map<String, Double> buffer, String fuel, double of, double unitsPerKg, int room) {
		if (room <= 0) return 0;
		double f = buffer.getOrDefault(fuel, 0.0), ox = buffer.getOrDefault("O2", 0.0);
		double pairFuel = Math.min(f, ox / of);
		int units = (int) Math.min(room, Math.floor(pairFuel * (1 + of) * unitsPerKg + 1e-6));
		if (units <= 0) return 0;
		double usedFuel = units / unitsPerKg / (1 + of);
		buffer.merge(fuel, -usedFuel, Double::sum);
		buffer.merge("O2", -usedFuel * of, Double::sum);
		return units;
	}

	/** Fusion capacity of a tank: ten fusion units per propellant unit of capacity. */
	public static int fusionCapacity(int fuelCapacity) {
		return fuelCapacity * 10;
	}
}
