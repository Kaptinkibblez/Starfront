#!/usr/bin/env python3
"""Mk II / Mk III ship parts (M8e-2): stats and Fabricator blueprints.

Reads each Mk I part's ship_stats JSON (hand-written, src/main/resources/data/starwake/starwake/ship_stats)
and writes <part>_mk2.json / <part>_mk3.json next to it, scaled by TIER below, plus Fabricator blueprints
(data/starwake/starwake/fabrication/<part>_mk1..3.json; Mk I parts can also still be crafted).

Mk tiers are designs: a Mk II engine has a hotter chamber and better nozzle (more thrust per kg, less fuel
per thrust); a Mk III one needs refractory or superalloy hot sections. Gates follow real alloy classes:
nozzles Mk I >= 700 C (stainless, Inconel 718), Mk II >= 950 C (Inconel 625), Mk III >= 1300 C (C-103, TZM,
Ta, W); chambers Mk II >= 700 C, Mk III >= 950 C; turbopumps Mk II >= 800 MPa (Ti-6Al-4V, 718), Mk III >= 1000 MPa
(718, maraging, quenched-and-tempered 4340); coils >= 98-100% IACS (refined copper); tanks >= 400 / 800 MPa. The materials the components were
made from gate which Mk you can build (min service temperature, strength, conductivity...), and the
quality margin of those components adds up to +20% on top (ShipMaterials.withQuality).
"""
import json
import os

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "data", "starwake", "starwake")
STATS = os.path.join(ROOT, "ship_stats")
FAB = os.path.join(ROOT, "fabrication")

PARTS = ["thruster", "main_engine", "maneuvering_thruster", "power_plant", "cooler", "shield_generator", "quantum_drive",
         "jump_drive", "fuel_tank", "landing_gear"]
# Multipliers per tier: output-type stats up, mass/burn/draw/spool down.
UP = {"thrust", "power_output", "cooling", "shield_capacity", "shield_regen", "jump_range", "fuel_capacity", "armor", "heat_limit"}
DOWN = {"mass", "fuel_burn", "power_draw", "heat", "quantum_spool", "jump_spool"}
TIER = {2: {"up": 1.5, "down": 0.85, "armor": 1.5, "heat_limit": 1.15}, 3: {"up": 2.2, "down": 0.72, "armor": 2.2, "heat_limit": 1.3}}


def item(name, count=1, mn=None):
    d = {"item": name, "count": count}
    if mn:
        d["min"] = mn
    return d


def form(name, count, mn=None, main=False):
    d = {"form": name, "count": count}
    if main:
        d["main"] = True
    if mn:
        d["min"] = mn
    return d


C = "starwake:"
# Per part and tier: the components (with gates on what they were made of).
RECIPES = {
    "thruster": {
        1: [item(C + "combustion_chamber"), item(C + "nozzle"), item(C + "control_board")],
        2: [item(C + "combustion_chamber", 1, {"service": 700}), item(C + "nozzle", 1, {"service": 950}), item(C + "turbopump", 1, {"yield": 800}),
            item(C + "control_board")],
        3: [item(C + "combustion_chamber", 1, {"service": 950}), item(C + "nozzle", 1, {"service": 1300}),
            item(C + "turbopump", 1, {"yield": 1000}), item(C + "control_board", 2)],
    },
    "main_engine": {
        1: [item(C + "combustion_chamber", 2), item(C + "nozzle", 2), item(C + "turbopump"), item(C + "structural_frame"),
            item(C + "control_board")],
        2: [item(C + "combustion_chamber", 2, {"service": 700}), item(C + "nozzle", 2, {"service": 950}), item(C + "turbopump", 2, {"yield": 800}),
            item(C + "structural_frame", 1, {"yield": 400}), item(C + "control_board", 2)],
        3: [item(C + "combustion_chamber", 2, {"service": 950}), item(C + "nozzle", 2, {"service": 1300}),
            item(C + "turbopump", 2, {"yield": 1000}), item(C + "structural_frame", 1, {"yield": 800}), item(C + "control_board", 3)],
    },
    "maneuvering_thruster": {
        1: [item(C + "nozzle"), item(C + "control_board")],
        2: [item(C + "nozzle", 1, {"service": 870}), item(C + "pressure_vessel"), item(C + "control_board")],
        3: [item(C + "nozzle", 1, {"service": 1150}), item(C + "pressure_vessel", 1, {"yield": 800}), item(C + "control_board", 2)],
    },
    "power_plant": {
        1: [item(C + "magnet_coil"), item(C + "heat_exchanger"), item(C + "pressure_vessel"), item(C + "control_board")],
        2: [item(C + "magnet_coil", 2, {"conductivity": 98}), item(C + "heat_exchanger", 2, {"conductivity": 60}),
            item(C + "pressure_vessel", 1, {"service": 400}), item(C + "control_board", 2)],
        3: [item(C + "magnet_coil", 4, {"conductivity": 100}), item(C + "heat_exchanger", 2, {"conductivity": 90}),
            item(C + "pressure_vessel", 1, {"service": 600}), item(C + "control_board", 3)],
    },
    "cooler": {
        1: [item(C + "heat_exchanger", 2), item(C + "pressure_vessel")],
        2: [item(C + "heat_exchanger", 3, {"conductivity": 60}), item(C + "pressure_vessel"), item(C + "control_board")],
        3: [item(C + "heat_exchanger", 4, {"conductivity": 90}), item(C + "pressure_vessel", 1, {"yield": 400}),
            item(C + "control_board")],
    },
    "shield_generator": {
        1: [item(C + "magnet_coil", 2), item(C + "control_board")],
        2: [item(C + "magnet_coil", 3, {"conductivity": 98}), item(C + "control_board", 2), item(C + "heat_exchanger")],
        3: [item(C + "magnet_coil", 4, {"conductivity": 100}), item(C + "control_board", 4), item(C + "heat_exchanger", 2, {"conductivity": 90})],
    },
    "quantum_drive": {
        1: [item(C + "magnet_coil", 2), item(C + "control_board", 2), item(C + "structural_frame")],
        2: [item(C + "magnet_coil", 4, {"conductivity": 98}), item(C + "control_board", 3), item(C + "structural_frame", 1, {"yield": 400})],
        3: [item(C + "magnet_coil", 6, {"conductivity": 100}), item(C + "control_board", 5), item(C + "structural_frame", 1, {"yield": 800}),
            item(C + "heat_exchanger", 2, {"conductivity": 90})],
    },
    "jump_drive": {
        1: [item(C + "magnet_coil", 4), item(C + "control_board", 3), item(C + "structural_frame", 2), item(C + "heat_exchanger")],
        2: [item(C + "magnet_coil", 6, {"conductivity": 98}), item(C + "control_board", 4), item(C + "structural_frame", 2, {"yield": 400}),
            item(C + "heat_exchanger", 2, {"conductivity": 60})],
        3: [item(C + "magnet_coil", 8, {"conductivity": 100}), item(C + "control_board", 6), item(C + "structural_frame", 2, {"yield": 800}),
            item(C + "heat_exchanger", 2, {"conductivity": 90})],
    },
    "fuel_tank": {
        1: [item(C + "pressure_vessel", 2)],
        2: [item(C + "pressure_vessel", 2, {"yield": 400}), item(C + "structural_frame")],
        3: [item(C + "pressure_vessel", 2, {"yield": 800}), item(C + "structural_frame", 1, {"yield": 600})],
    },
    "landing_gear": {
        1: [item(C + "structural_frame"), form("forging", 1, main=True)],
        2: [item(C + "structural_frame", 1, {"yield": 500}), form("forging", 1, {"yield": 700}, main=True)],
        3: [item(C + "structural_frame", 1, {"yield": 900}), form("forging", 1, {"yield": 1100}, main=True)],
    },
}
ENERGY = {1: 4000, 2: 9000, 3: 20000}


def block_id(part, mk):
    return part if mk == 1 else f"{part}_mk{mk}"


def main():
    os.makedirs(FAB, exist_ok=True)
    stats_written = 0
    for part in PARTS:
        base = json.load(open(os.path.join(STATS, part + ".json")))
        for mk, t in TIER.items():
            d = {"blocks": [f"starwake:{part}_mk{mk}"]}
            for k, v in base.items():
                if k == "blocks":
                    continue
                if k in ("heat_limit",):
                    d[k] = round(v * t["heat_limit"])
                elif k == "armor":
                    d[k] = round(v * t["armor"], 2)
                elif k in UP:
                    d[k] = int(round(v * t["up"])) if isinstance(v, int) and k == "fuel_capacity" else round(v * t["up"], 3)
                elif k in DOWN:
                    d[k] = round(v * t["down"], 4)
                else:
                    d[k] = v
            json.dump(d, open(os.path.join(STATS, f"{part}_mk{mk}.json"), "w"), indent=2)
            stats_written += 1
    n = 0
    for part, tiers in RECIPES.items():
        for mk, inputs in tiers.items():
            bp = {"output": "starwake:" + block_id(part, mk), "count": 1, "order": 100 + PARTS.index(part) * 3 + mk,
                  "energy": ENERGY[mk], "inputs": inputs}
            json.dump(bp, open(os.path.join(FAB, f"{part}_mk{mk}.json"), "w"), indent=1)
            n += 1
    print(f"{stats_written} Mk II/III stat files, {n} part blueprints")


if __name__ == "__main__":
    main()
