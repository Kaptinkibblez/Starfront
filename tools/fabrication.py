#!/usr/bin/env python3
"""Fabricator blueprints (M8e-1) -> src/main/resources/data/starwake/starwake/fabrication/*.json.

Each blueprint: output item and count, energy (kJ), and inputs. An input takes a metal `form`
(ingot, plate, rod, wire, dust, billet, slab, casting, forging), a specific `item`, or any item made
of an `element`; `count`; optional `base` element; `main` (the product inherits that input's alloy
and temper); `min`/`max` property gates: yield, tensile (MPa), elongation (%), hardness (HV),
service (C), conductivity (% IACS), corrosion (0-10), density (g/cm3), modulus (GPa), purity (wt%
of the base element).

Gates follow real practice for the part, scaled to the Mk I design:
  pressure vessel  - plate, yield >= 200 MPa, elongation >= 12% (leak-before-break)
  structural frame - rods, yield >= 250 MPa
  combustion chamber - hot-section plate (service >= 500 C) + a high-conductivity liner (>= 40% IACS,
                     regenerative cooling like a copper-alloy liner)
  nozzle           - plate, service >= 700 C
  turbopump        - forging, yield >= 600 MPa (impeller/shaft) + hard bearing rods (>= 250 HV)
  heat exchanger   - plates >= 40% IACS + corrosion-resistant rods (>= 5/10)
  magnet coil      - wire >= 95% IACS (copper) + a soft-iron core rod (base Fe)
  control board    - microchips + copper wire (>= 95% IACS) + tin solder wire + gold (>= 99.9%)
  hull plating     - plate, any alloy with yield >= 100 MPa -> 4 hull plating carrying the alloy
"""
import json
import os

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "data", "starwake", "starwake", "fabrication")


def form(name, count, main=False, base=None, mn=None, mx=None):
    d = {"form": name, "count": count}
    if main:
        d["main"] = True
    if base:
        d["base"] = base
    if mn:
        d["min"] = mn
    if mx:
        d["max"] = mx
    return d


def item(name, count, mn=None):
    d = {"item": name, "count": count}
    if mn:
        d["min"] = mn
    return d


def element(sym, count, mn=None):
    d = {"element": sym, "count": count}
    if mn:
        d["min"] = mn
    return d


BLUEPRINTS = {
    "hull_plating": {"output": "starwake:hull_plating", "count": 4, "order": 0, "energy": 400,
                     "inputs": [form("plate", 2, main=True, mn={"yield": 100})]},
    "structural_frame": {"output": "starwake:structural_frame", "order": 10, "energy": 600,
                         "inputs": [form("rod", 4, main=True, mn={"yield": 250})]},
    "pressure_vessel": {"output": "starwake:pressure_vessel", "order": 11, "energy": 1200,
                        "inputs": [form("plate", 4, main=True, mn={"yield": 200, "elongation": 12})]},
    "combustion_chamber": {"output": "starwake:combustion_chamber", "order": 20, "energy": 2500,
                           "inputs": [form("plate", 2, main=True, mn={"service": 500, "yield": 200}),
                                      form("plate", 1, mn={"conductivity": 40})]},
    "nozzle": {"output": "starwake:nozzle", "order": 21, "energy": 2000,
               "inputs": [form("plate", 2, main=True, mn={"service": 700})]},
    "turbopump": {"output": "starwake:turbopump", "order": 22, "energy": 4000,
                  "inputs": [form("forging", 1, main=True, mn={"yield": 600}), form("rod", 2, mn={"hardness": 250})]},
    "heat_exchanger": {"output": "starwake:heat_exchanger", "order": 30, "energy": 1500,
                       "inputs": [form("plate", 4, main=True, mn={"conductivity": 40}), form("rod", 2, mn={"corrosion": 5})]},
    "magnet_coil": {"output": "starwake:magnet_coil", "order": 31, "energy": 1500,
                    "inputs": [form("wire", 8, main=True, mn={"conductivity": 95}), form("rod", 1, base="Fe")]},
    "control_board": {"output": "starwake:control_board", "order": 40, "energy": 3000,
                      "inputs": [item("starwake:microchip", 4), form("wire", 2, mn={"conductivity": 95}),
                                 form("wire", 1, base="Sn"), element("Au", 1, mn={"purity": 99.9})]},
}

# M8h: tools, weapons and armour take every stat from the main input's alloy (MaterialGear); suit upgrades
# return the Survey Suit with the upgrade fitted.
def gear(name, plates, rods, order, energy):
    inputs = [form("plate", plates, main=True, mn={"yield": 150})]
    if rods:
        inputs.append(form("rod", rods))
    return {"output": "starwake:" + name, "count": 1, "order": order, "energy": energy, "inputs": inputs}


BLUEPRINTS.update({
    "material_pickaxe": gear("material_pickaxe", 3, 2, 60, 800),
    "material_axe": gear("material_axe", 3, 2, 61, 800),
    "material_shovel": gear("material_shovel", 1, 2, 62, 500),
    "material_sword": gear("material_sword", 2, 1, 63, 700),
    "material_helmet": gear("material_helmet", 5, 0, 64, 1200),
    "material_chestplate": gear("material_chestplate", 8, 0, 65, 2000),
    "material_leggings": gear("material_leggings", 7, 0, 66, 1700),
    "material_boots": gear("material_boots", 4, 0, 67, 1000),
})
# M8i: the nuclear machines are built from components here instead of crafted.
BLUEPRINTS.update({
    "gas_centrifuge": {"output": "starwake:gas_centrifuge", "order": 80, "energy": 8000,
                       "inputs": [item("starwake:magnet_coil", 2), item("starwake:turbopump", 1, {"yield": 800}),
                                  item("starwake:control_board", 2), item("starwake:structural_frame", 1)]},
    "fission_reactor": {"output": "starwake:fission_reactor", "order": 81, "energy": 30000,
                        "inputs": [item("starwake:pressure_vessel", 4, {"yield": 400}), item("starwake:heat_exchanger", 2),
                                   item("starwake:turbopump", 1), item("starwake:control_board", 3), item("starwake:structural_frame", 2)]},
    "fusion_reactor": {"output": "starwake:fusion_reactor", "order": 82, "energy": 80000,
                       "inputs": [item("starwake:magnet_coil", 8, {"conductivity": 100}), item("starwake:heat_exchanger", 4, {"conductivity": 90}),
                                  item("starwake:pressure_vessel", 2, {"service": 600}), item("starwake:control_board", 6)]},
    "particle_accelerator": {"output": "starwake:particle_accelerator", "order": 83, "energy": 60000,
                             "inputs": [item("starwake:magnet_coil", 8, {"conductivity": 98}), item("starwake:control_board", 6),
                                        item("starwake:structural_frame", 4, {"yield": 400}), item("starwake:pressure_vessel", 1)]},
})
SUIT = {"item": "starwake:survey_suit", "count": 1, "main": True}
BLUEPRINTS.update({
    "suit_lead_lining": {"output": "starwake:survey_suit", "order": 70, "energy": 1500, "upgrade": "lead_lining",
                         "inputs": [SUIT, form("plate", 4, base="Pb", mn={"density": 11})]},
    "suit_oxygen_tank": {"output": "starwake:survey_suit", "order": 71, "energy": 1000, "upgrade": "oxygen_tank",
                         "inputs": [SUIT, item("starwake:pressure_vessel", 1)]},
    "suit_thermal_layer": {"output": "starwake:survey_suit", "order": 72, "energy": 2000, "upgrade": "thermal_layer",
                           "inputs": [SUIT, form("plate", 2, mn={"service": 900}), item("starwake:heat_exchanger", 1)]},
    "suit_armor_insert": {"output": "starwake:survey_suit", "order": 73, "energy": 1500, "upgrade": "armor_insert",
                          "inputs": [SUIT, form("plate", 4, mn={"yield": 800})]},
})


def main():
    os.makedirs(OUT, exist_ok=True)
    # Only this tool's files: tools/ship_parts.py writes the Mk part blueprints into the same folder.
    for name, bp in BLUEPRINTS.items():
        with open(os.path.join(OUT, name + ".json"), "w") as fh:
            json.dump(bp, fh, indent=1)
    print(f"{len(BLUEPRINTS)} blueprints")


if __name__ == "__main__":
    main()
