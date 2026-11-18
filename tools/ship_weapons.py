#!/usr/bin/env python3
"""Ship weapons (M11): ship_weapon/<block>.json for every gun at Mk I-III.

Numbers are per Mk I and scaled per tier (a Mk II gun hits ~40% harder and a little faster; a Mk III twice as hard).
Units: damage in hull points (shields count the same points), speed m/s, rate shots/s, range m, spread and gimbal cone in
degrees, energy/heat/capacitor in kJ. Ballistic guns eat ammo items (rounds_per_item each) and let 40% of each hit past
shields; energy guns draw on the weapon capacitor (charged by the weapon pips, ShipPower) and hit shields fully.
Mass, power draw and armour live in ship_stats like any part (tools/ship_parts.py scales those).
Run: python3 tools/ship_weapons.py
"""
import json
import os

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "data", "starwake", "starwake", "ship_weapon")

GUNS = {
    "ballistic_cannon": {"kind": "ballistic", "damage": 20, "speed": 1200, "rate": 4, "range": 2000, "spread": 0.5,
                         "gimbal_cone": 20, "gimbal_factor": 0.8, "heat": 0.6, "ammo": "starwake:autocannon_rounds",
                         "rounds_per_item": 50, "shield_factor": 0.6, "break_damage": 150},
    "laser_repeater": {"kind": "energy", "damage": 12, "speed": 1800, "rate": 5, "range": 1500, "spread": 0.3,
                       "gimbal_cone": 25, "gimbal_factor": 0.8, "energy": 2.0, "heat": 1.5, "capacitor": 30,
                       "shield_factor": 1.0, "break_damage": 150},
}
TIER = {1: {"damage": 1.0, "rate": 1.0, "speed": 1.0, "range": 1.0, "capacitor": 1.0, "energy": 1.0},
        2: {"damage": 1.4, "rate": 1.1, "speed": 1.1, "range": 1.15, "capacitor": 1.5, "energy": 1.25},
        3: {"damage": 2.0, "rate": 1.2, "speed": 1.2, "range": 1.3, "capacitor": 2.2, "energy": 1.5}}


def main():
    os.makedirs(ROOT, exist_ok=True)
    n = 0
    for gun, base in GUNS.items():
        for mk, t in TIER.items():
            d = dict(base)
            for k, f in t.items():
                if k in d:
                    d[k] = round(d[k] * f, 3)
            name = gun if mk == 1 else f"{gun}_mk{mk}"
            json.dump(d, open(os.path.join(ROOT, name + ".json"), "w"), indent=2)
            n += 1
    print(f"{n} weapon specs")


if __name__ == "__main__":
    main()
