#!/usr/bin/env python3
"""Generates Starwake's trade data (M7) under src/main/resources/data/starwake/starwake/:
  commodity/<group>.json   item -> base price in credits
  hub_economy/<id>.json    what each kind of hub sells and buys
  shop/<id>.json           outfitter stock

Element prices follow real crustal abundance (ppm), the main driver of real metal prices:
price = 3 + 40 * ppm^-0.3, so iron is ~5 cr, copper ~15, gold ~200, rhodium ~320. Noble gases
and radioactive/synthetic elements are priced by hand. Raw minerals are worth 45% of the
elements they contain (unrefined), plus a little for their trace by-products.

Reads the chemistry tables written by tools/periodic_table.py.
Usage: python3 tools/trade_data.py
"""
import json
import os

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources")
OUT = os.path.join(ROOT, "data", "starwake", "starwake")
CHEM = os.path.join(ROOT, "starwake", "chemistry")

# Upper continental crust abundance, ppm (approximate textbook values).
PPM = {
    "H": 1400, "Li": 20, "Be": 2.8, "B": 10, "C": 200, "N": 19, "O": 461000, "F": 585, "Na": 23600, "Mg": 23300,
    "Al": 82300, "Si": 282000, "P": 1050, "S": 350, "Cl": 145, "K": 20900, "Ca": 41500, "Sc": 22, "Ti": 5650, "V": 120,
    "Cr": 102, "Mn": 950, "Fe": 56300, "Co": 25, "Ni": 84, "Cu": 60, "Zn": 70, "Ga": 19, "Ge": 1.5, "As": 1.8, "Se": 0.05,
    "Br": 2.4, "Rb": 90, "Sr": 370, "Y": 33, "Zr": 165, "Nb": 20, "Mo": 1.2, "Ru": 0.001, "Rh": 0.001, "Pd": 0.015,
    "Ag": 0.075, "Cd": 0.15, "In": 0.25, "Sn": 2.3, "Sb": 0.2, "Te": 0.001, "I": 0.45, "Cs": 3, "Ba": 425, "La": 39,
    "Ce": 66.5, "Pr": 9.2, "Nd": 41.5, "Sm": 7.05, "Eu": 2, "Gd": 6.2, "Tb": 1.2, "Dy": 5.2, "Ho": 1.3, "Er": 3.5,
    "Tm": 0.52, "Yb": 3.2, "Lu": 0.8, "Hf": 3, "Ta": 2, "W": 1.25, "Re": 0.0007, "Os": 0.0015, "Ir": 0.001, "Pt": 0.005,
    "Au": 0.004, "Hg": 0.085, "Tl": 0.85, "Pb": 14, "Bi": 0.0085, "Th": 9.6, "U": 2.7,
}
GAS = {"He": 30, "Ne": 40, "Ar": 8, "Kr": 60, "Xe": 120, "Rn": 600, "N": 2, "O": 2, "H": 3, "F": 20, "Cl": 8}
PRECIOUS = {"Ag", "Au", "Pt", "Pd", "Rh", "Ru", "Ir", "Os", "Re"}


def element_price(e):
    s, z = e["symbol"], e["z"]
    if e["occurrence"] == "synthetic":
        return 10000 + 2000 * (z - 95)
    if e["occurrence"] == "trace":
        return 800 + 40 * (z - 40)
    if e["phase"] == "gas" and s in GAS:
        return GAS[s]
    ppm = PPM.get(s, 1.0)
    return round(3 + 40 * ppm ** -0.3, 1)


def main():
    elements = json.load(open(os.path.join(CHEM, "elements.json")))
    minerals = json.load(open(os.path.join(CHEM, "minerals.json")))
    by_symbol = {e["symbol"]: e for e in elements}
    price = {e["symbol"]: element_price(e) for e in elements}

    groups = {k: {} for k in ("metals", "precious", "rare_earths", "gases", "radioactives", "nonmetals", "raw_minerals",
                              "food", "fuel", "supplies", "samples")}
    for e in elements:
        item, s = e["item"], e["symbol"]
        if e["form"] == "canister":
            g = "gases"
        elif e["radioactive"] or e["form"] == "sample":
            g = "radioactives"
        elif s in PRECIOUS:
            g = "precious"
        elif e["category"] == "lanthanide" or s in ("Sc", "Y"):
            g = "rare_earths"
        elif e["form"] == "ingot":
            g = "metals"
        else:
            g = "nonmetals"
        groups[g][item] = price[s]

    raw_items = {"hematite": "minecraft:raw_iron", "chalcopyrite": "minecraft:raw_copper", "native_gold": "minecraft:raw_gold",
                 "graphite": "minecraft:coal", "cinnabar": "minecraft:cinnabar", "native_sulfur": "minecraft:sulfur"}
    for m in minerals:
        item = m["raw"] or raw_items.get(m["id"])
        if not item:
            continue
        value = 0.45 * sum(f * price[s] for s, f in m["composition"].items()) + 2 * len(m["trace"])
        groups["raw_minerals"][item] = max(2.0, round(value, 1))

    groups["food"] = {"minecraft:bread": 4, "minecraft:baked_potato": 3, "minecraft:cooked_beef": 7, "minecraft:cooked_chicken": 5,
                      "minecraft:cooked_cod": 4, "minecraft:apple": 3, "minecraft:carrot": 2, "minecraft:golden_carrot": 30,
                      "minecraft:dried_kelp": 1, "minecraft:mushroom_stew": 6}
    groups["fuel"] = {"minecraft:coal_block": 36, "minecraft:charcoal": 4, "minecraft:blaze_rod": 40, "minecraft:blaze_powder": 20}
    groups["supplies"] = {"minecraft:torch": 1, "minecraft:glass": 2, "minecraft:iron_pickaxe": 45, "minecraft:iron_shovel": 25,
                          "minecraft:bucket": 15, "minecraft:water_bucket": 18, "minecraft:ladder": 1, "minecraft:rail": 2,
                          "starwake:hull_plating": 10, "minecraft:redstone": 3}
    groups["samples"] = {"starwake:tuft_grass": 8, "starwake:fernwood_log": 6, "starwake:fernwood_fronds": 4, "starwake:puffball": 10,
                         "starwake:fungal_cap": 6, "starwake:fungal_stem": 6, "starwake:selenite_cluster": 25, "starwake:selenite": 12,
                         "starwake:mat_turf": 5, "starwake:permafrost": 3, "starwake:fine_regolith": 2, "starwake:volcanic_ash": 2,
                         "starwake:halite": 3, "starwake:anorthosite": 2, "minecraft:amethyst_shard": 15, "minecraft:potent_sulfur": 9}
    for g, items in groups.items():
        write(f"commodity/{g}.json", {"items": items})

    def trade(group, price=1.0, stock=256, local=False):
        d = {"group": group, "price": price, "stock": stock}
        if local:
            d["local"] = True
        return d

    economies = {
        "trade_port": ("Trade Port", [trade("food", 1.0, 400), trade("fuel", 1.0, 256), trade("supplies", 1.0, 256), trade("metals", 1.15, 128),
                                      trade("gases", 1.1, 64)],
                       [trade("raw_minerals", 1.25, 512), trade("samples", 1.3, 128), trade("precious", 1.2, 32), trade("rare_earths", 1.2, 64)], "port"),
        "refinery": ("Refinery", [trade("metals", 0.85, 512), trade("precious", 1.0, 48), trade("rare_earths", 1.0, 96), trade("nonmetals", 0.9, 256)],
                     [trade("raw_minerals", 1.4, 768), trade("fuel", 1.3, 256), trade("food", 1.2, 128)], "industrial"),
        "research_station": ("Research Station", [trade("supplies", 1.0, 128), trade("gases", 0.9, 128)],
                             [trade("samples", 1.6, 256), trade("radioactives", 1.4, 32), trade("rare_earths", 1.3, 64)], "science"),
        "settlement": ("Settlement", [trade("food", 0.8, 512), trade("samples", 0.9, 128)],
                       [trade("metals", 1.3, 128), trade("fuel", 1.3, 128), trade("supplies", 1.4, 128)], "basic"),
        "mining_camp": ("Mining Camp", [trade("raw_minerals", 0.7, 512, local=True), trade("nonmetals", 0.9, 128)],
                        [trade("food", 1.5, 128), trade("fuel", 1.4, 128), trade("supplies", 1.4, 128)], "industrial"),
        "research_post": ("Research Post", [trade("samples", 0.8, 192), trade("gases", 1.0, 64)],
                          [trade("rare_earths", 1.3, 64), trade("radioactives", 1.5, 16), trade("food", 1.3, 96)], "science"),
        "trading_post": ("Trading Post", [trade("food", 1.0, 256), trade("supplies", 1.0, 192), trade("fuel", 1.0, 192)],
                         [trade("raw_minerals", 1.15, 256), trade("samples", 1.15, 96)], "basic"),
    }
    for eid, (name, sells, buys, shop) in economies.items():
        write(f"hub_economy/{eid}.json", {"name": {"translate": f"hub_economy.starwake.{eid}"}, "sells": sells, "buys": buys,
                                          "outfitter": f"starwake:{shop}"})

    def item(i, p, count=1, rep=-100):
        return {"item": i, "price": p, "count": count, "rep": rep}

    ship = "starwake:"
    shops = {
        "basic": [item(ship + "survey_suit", 900), item("minecraft:iron_pickaxe", 60), item("minecraft:torch", 20, 16),
                  item("minecraft:bread", 30, 8), item(ship + "fuel_tank", 650), item("minecraft:coal_block", 60, 2)],
        "port": [item(ship + "helm", 1500), item(ship + "seat", 150), item(ship + "fuel_tank", 600), item(ship + "thruster", 400),
                 item(ship + "maneuvering_thruster", 250), item(ship + "main_engine", 2500), item(ship + "landing_gear", 300),
                 item(ship + "wing", 200), item(ship + "power_plant", 2000), item(ship + "cooler", 700),
                 item(ship + "shield_generator", 2500, rep=200), item(ship + "quantum_drive", 4000),
                 item(ship + "jump_drive", 12000, rep=300), item(ship + "hull_plating", 160, 16), item("minecraft:glass", 30, 16),
                 item(ship + "survey_suit", 900)],
        "industrial": [item(ship + "hull_plating", 120, 16), item(ship + "main_engine", 2300), item(ship + "thruster", 380),
                       item(ship + "power_plant", 1800), item(ship + "cooler", 600), item(ship + "fuel_tank", 550),
                       item("minecraft:iron_pickaxe", 50), item("minecraft:diamond_pickaxe", 900, rep=200), item("minecraft:coal", 50, 16)],
        "science": [item(ship + "survey_suit", 800), item(ship + "quantum_drive", 3800), item(ship + "jump_drive", 11000, rep=200),
                    item("minecraft:spyglass", 80), item("minecraft:compass", 40), item("minecraft:glass", 25, 16)],
    }
    for sid, items in shops.items():
        write(f"shop/{sid}.json", {"items": items})
    print(f"{sum(len(v) for v in groups.values())} commodities in {len(groups)} groups, {len(economies)} economies, {len(shops)} shops")
    print("sample prices:", {s: price[s] for s in ("Fe", "Cu", "Al", "Ti", "Au", "Pt", "Rh", "Nd", "U", "Li")})


def write(rel, data):
    path = os.path.join(OUT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(data, f, indent=1)


if __name__ == "__main__":
    main()
