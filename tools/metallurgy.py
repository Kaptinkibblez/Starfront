#!/usr/bin/env python3
"""Generates Starwake's metallurgy data (M8a): real alloy grades and base-metal models.

Writes:
  data/starwake/starwake/alloy_grade/<id>.json  - named alloys: composition ranges (wt%), the
      balance element, handbook properties in a standard condition, colour and tier.
      "exact": false entries are generic family names ("Carbon Steel") with broad ranges; they
      only name a material, its properties are estimated.
  data/starwake/starwake/alloy_base/<symbol>.json - base-metal models for compositions that match
      no grade: the pure metal's properties plus per-solute effects (solid-solution strengthening
      ~ sqrt(wt%), ductility loss, conductivity loss, corrosion, service temperature, solidus
      depression, embrittlement thresholds).

Properties (MaterialProperties): density g/cm3, yield and tensile strength MPa, elongation %,
modulus GPa, hardness HV, solidus C, max service temperature C (strength/oxidation limit),
electrical conductivity %IACS, corrosion resistance 0-10.

Values are typical handbook figures (ASM / MatWeb style) for the stated condition, rounded.

Usage: python3 tools/metallurgy.py
"""
import json
import os

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "data", "starwake", "starwake")
GRADES = os.path.join(ROOT, "alloy_grade")
BASES = os.path.join(ROOT, "alloy_base")


def props(density, ys, uts, el, modulus, hv, solidus, service, cond, corr):
    return {"density": density, "yield_strength": ys, "tensile_strength": uts, "elongation": el, "modulus": modulus,
            "hardness": hv, "solidus": solidus, "service_temp": service, "conductivity": cond, "corrosion": corr}


# id, name, family, base, tier, colour, condition, ranges {el: [min, max]}, properties, extra
G = []


def grade(gid, name, family, base, tier, color, condition, ranges, p, max_other=0.6, nominal=None, note=None):
    G.append(dict(id=gid, name=name, family=family, base=base, tier=tier, color=color, condition=condition,
                  ranges=ranges, properties=p, max_other=max_other, nominal=nominal, note=note, exact=True))


def generic(gid, name, family, base, ranges, color, max_other=100.0):
    G.append(dict(id=gid, name=name, family=family, base=base, tier=0, color=color, condition=None, ranges=ranges,
                  properties=None, max_other=max_other, nominal=None, note=None, exact=False))


# --- Irons and steels (Fe) ----------------------------------------------------------------------
grade("pure_iron", "Pure Iron", "iron", "Fe", 1, "#b9b9b9", "annealed",
      {"C": [0, 0.02], "Mn": [0, 0.1], "Si": [0, 0.05]}, props(7.87, 150, 280, 40, 211, 70, 1538, 400, 17.6, 2), max_other=0.2)
grade("pig_iron", "Pig Iron", "cast_iron", "Fe", 0, "#7d7a77", "as cast",
      {"C": [3.5, 4.7], "Si": [0.3, 1.5], "Mn": [0, 1.5], "P": [0, 0.3], "S": [0, 0.1]},
      props(7.0, 120, 150, 0.2, 100, 200, 1150, 300, 3, 2), max_other=1.0,
      note="Blast-furnace iron: brittle, the raw material for steelmaking.")
grade("cast_iron_gray", "Gray Cast Iron", "cast_iron", "Fe", 1, "#6f6d6b", "as cast",
      {"C": [2.5, 4.0], "Si": [1.0, 3.0], "Mn": [0.2, 1.0], "P": [0, 0.2], "S": [0, 0.15]},
      props(7.15, 180, 250, 0.5, 110, 200, 1150, 350, 3, 3), max_other=1.0)
grade("aisi_1010", "AISI 1010 Low-Carbon Steel", "carbon_steel", "Fe", 1, "#b0b0b0", "hot rolled",
      {"C": [0.08, 0.13], "Mn": [0.30, 0.60], "P": [0, 0.04], "S": [0, 0.05]}, props(7.87, 180, 325, 28, 205, 95, 1495, 400, 12, 2))
grade("aisi_1020", "AISI 1020 Mild Steel", "carbon_steel", "Fe", 1, "#a9a9aa", "hot rolled",
      {"C": [0.18, 0.23], "Mn": [0.30, 0.60], "P": [0, 0.04], "S": [0, 0.05]}, props(7.87, 210, 380, 25, 205, 111, 1480, 400, 11, 2))
grade("aisi_1045", "AISI 1045 Medium-Carbon Steel", "carbon_steel", "Fe", 1, "#a3a4a6", "normalized",
      {"C": [0.43, 0.50], "Mn": [0.60, 0.90], "P": [0, 0.04], "S": [0, 0.05]}, props(7.85, 450, 655, 16, 205, 190, 1460, 400, 10, 2))
grade("aisi_1095", "AISI 1095 High-Carbon Steel", "carbon_steel", "Fe", 2, "#9a9b9e", "normalized",
      {"C": [0.90, 1.03], "Mn": [0.30, 0.50], "P": [0, 0.04], "S": [0, 0.05]}, props(7.85, 525, 1015, 9.5, 205, 293, 1430, 400, 9, 2))
grade("aisi_4140", "AISI 4140 Chromoly Steel", "alloy_steel", "Fe", 2, "#9ea2a8", "normalized",
      {"C": [0.38, 0.43], "Mn": [0.75, 1.0], "Cr": [0.8, 1.1], "Mo": [0.15, 0.25], "Si": [0.15, 0.35]},
      props(7.85, 655, 1020, 17.7, 205, 302, 1416, 480, 8, 3))
grade("aisi_4340", "AISI 4340 Nickel-Chromoly Steel", "alloy_steel", "Fe", 2, "#989ca3", "normalized",
      {"C": [0.38, 0.43], "Mn": [0.6, 0.8], "Cr": [0.7, 0.9], "Mo": [0.2, 0.3], "Ni": [1.65, 2.0], "Si": [0.15, 0.35]},
      props(7.85, 862, 1282, 12.2, 205, 380, 1427, 480, 7, 3))
grade("maraging_300", "Maraging 300 Steel", "alloy_steel", "Fe", 3, "#a7aab4", "aged",
      {"Ni": [18, 19], "Co": [8.5, 9.5], "Mo": [4.6, 5.2], "Ti": [0.5, 0.8], "Al": [0.05, 0.15], "C": [0, 0.03]},
      props(8.0, 2000, 2050, 7, 190, 540, 1413, 400, 3, 4), note="Ultra-high-strength steel for rocket casings.")
grade("aisi_d2", "AISI D2 Tool Steel", "tool_steel", "Fe", 2, "#8f939a", "hardened",
      {"C": [1.4, 1.6], "Cr": [11, 13], "Mo": [0.7, 1.2], "V": [0.5, 1.1], "Mn": [0, 0.6], "Si": [0, 0.6]},
      props(7.7, 1650, 1900, 1, 210, 700, 1420, 425, 2.5, 5))
grade("aisi_m2", "AISI M2 High-Speed Steel", "tool_steel", "Fe", 3, "#868a93", "hardened",
      {"C": [0.78, 1.05], "W": [5.5, 6.75], "Mo": [4.5, 5.5], "Cr": [3.75, 4.5], "V": [1.75, 2.2]},
      props(8.16, 2000, 2200, 1, 210, 830, 1400, 600, 2.3, 3), note="Keeps its hardness red-hot.")
grade("ss_304", "304 Stainless Steel", "stainless", "Fe", 2, "#c8ccd1", "annealed",
      {"Cr": [18, 20], "Ni": [8, 10.5], "C": [0, 0.08], "Mn": [0, 2], "Si": [0, 0.75], "N": [0, 0.1]},
      props(8.0, 215, 505, 70, 193, 170, 1400, 870, 2.5, 8))
grade("ss_316", "316 Stainless Steel", "stainless", "Fe", 2, "#c4c9cf", "annealed",
      {"Cr": [16, 18], "Ni": [10, 14], "Mo": [2, 3], "C": [0, 0.08], "Mn": [0, 2], "Si": [0, 0.75]},
      props(8.0, 205, 515, 60, 193, 170, 1375, 870, 2.4, 9))
grade("ss_430", "430 Ferritic Stainless Steel", "stainless", "Fe", 2, "#bfc3c8", "annealed",
      {"Cr": [16, 18], "C": [0, 0.12], "Mn": [0, 1], "Si": [0, 1]}, props(7.75, 310, 517, 30, 200, 165, 1425, 815, 3, 6))
grade("ss_17_4ph", "17-4 PH Stainless Steel", "stainless", "Fe", 3, "#b8bcc3", "aged H900",
      {"Cr": [15, 17.5], "Ni": [3, 5], "Cu": [3, 5], "Nb": [0.15, 0.45], "C": [0, 0.07], "Mn": [0, 1], "Si": [0, 1]},
      props(7.78, 1170, 1310, 10, 197, 420, 1400, 315, 2.5, 7))
grade("invar_36", "Invar 36", "special", "Fe", 3, "#b5b6b0", "annealed",
      {"Ni": [35, 37], "Mn": [0, 0.5], "C": [0, 0.1], "Si": [0, 0.35]}, props(8.05, 276, 490, 42, 141, 160, 1427, 300, 2, 5),
      note="Almost no thermal expansion: precision optics and sensors.")
generic("cast_iron", "Cast Iron", "cast_iron", "Fe", {"C": [2.1, 6.7]}, "#727070")
generic("stainless", "Stainless Steel", "stainless", "Fe", {"Cr": [10.5, 30], "C": [0, 1.2]}, "#c2c6cc")
generic("alloy_steel", "Alloy Steel", "alloy_steel", "Fe", {"C": [0, 2.1], "Cr": [0, 10.5]}, "#a0a3a8")
generic("carbon_steel", "Carbon Steel", "carbon_steel", "Fe", {"C": [0.02, 2.1]}, "#a8a9ab", max_other=3.0)

# --- Aluminium (Al) -----------------------------------------------------------------------------
grade("al_1100", "1100 Aluminium", "aluminium", "Al", 1, "#d4d7da", "O temper",
      {"Cu": [0.05, 0.2], "Si": [0, 0.5], "Fe": [0, 0.5], "Mn": [0, 0.05], "Zn": [0, 0.1]},
      props(2.71, 34, 90, 35, 69, 23, 643, 150, 59, 7), max_other=0.15)
grade("al_2024", "2024 Aluminium", "aluminium", "Al", 2, "#cdd0d3", "T3",
      {"Cu": [3.8, 4.9], "Mg": [1.2, 1.8], "Mn": [0.3, 0.9], "Fe": [0, 0.5], "Si": [0, 0.5], "Zn": [0, 0.25]},
      props(2.78, 345, 483, 18, 73, 137, 502, 150, 30, 3), note="Classic aircraft skin alloy.")
grade("al_6061", "6061 Aluminium", "aluminium", "Al", 1, "#d0d3d6", "T6",
      {"Mg": [0.8, 1.2], "Si": [0.4, 0.8], "Cu": [0.15, 0.4], "Cr": [0.04, 0.35], "Fe": [0, 0.7]},
      props(2.70, 276, 310, 12, 69, 107, 582, 150, 43, 7))
grade("al_7075", "7075 Aluminium", "aluminium", "Al", 2, "#c9ccd0", "T6",
      {"Zn": [5.1, 6.1], "Mg": [2.1, 2.9], "Cu": [1.2, 2.0], "Cr": [0.18, 0.28], "Fe": [0, 0.5], "Si": [0, 0.4]},
      props(2.81, 503, 572, 11, 72, 175, 477, 120, 33, 4))
grade("al_5083", "5083 Marine Aluminium", "aluminium", "Al", 1, "#d2d5d8", "H116",
      {"Mg": [4.0, 4.9], "Mn": [0.4, 1.0], "Cr": [0.05, 0.25], "Fe": [0, 0.4], "Si": [0, 0.4]},
      props(2.66, 215, 317, 16, 71, 96, 574, 150, 29, 9))
grade("al_2195", "2195 Aluminium-Lithium", "aluminium", "Al", 3, "#c6cad0", "T8",
      {"Cu": [3.7, 4.3], "Li": [0.8, 1.2], "Mg": [0.25, 0.8], "Ag": [0.25, 0.6], "Zr": [0.08, 0.16]},
      props(2.71, 560, 595, 9, 76, 170, 535, 120, 25, 5), note="Light cryogenic tankage (the Shuttle's super-lightweight tank).")
generic("aluminium_alloy", "Aluminium Alloy", "aluminium", "Al", {}, "#cbced2")

# --- Titanium (Ti) ------------------------------------------------------------------------------
grade("ti_grade2", "Grade 2 Titanium", "titanium", "Ti", 2, "#a9a7a3", "annealed",
      {"O": [0, 0.25], "Fe": [0, 0.3], "C": [0, 0.08], "N": [0, 0.03]}, props(4.51, 275, 345, 20, 105, 145, 1665, 315, 3.1, 10), max_other=0.4)
grade("ti_6al4v", "Ti-6Al-4V (Grade 5)", "titanium", "Ti", 3, "#a3a19d", "annealed",
      {"Al": [5.5, 6.75], "V": [3.5, 4.5], "Fe": [0, 0.3], "O": [0, 0.2]}, props(4.43, 880, 950, 14, 114, 349, 1604, 400, 1.0, 9),
      note="The workhorse aerospace titanium.")
grade("ti_6242", "Ti-6Al-2Sn-4Zr-2Mo", "titanium", "Ti", 3, "#9e9c99", "duplex annealed",
      {"Al": [5.5, 6.5], "Sn": [1.8, 2.2], "Zr": [3.6, 4.4], "Mo": [1.8, 2.2], "Si": [0.06, 0.1]},
      props(4.54, 900, 990, 15, 114, 340, 1590, 540, 1.0, 9), note="High-temperature titanium for engine parts.")
generic("titanium_alloy", "Titanium Alloy", "titanium", "Ti", {}, "#a5a39f")

# --- Nickel (Ni) --------------------------------------------------------------------------------
grade("ni_200", "Nickel 200", "nickel", "Ni", 2, "#c1bdb2", "annealed",
      {"Fe": [0, 0.4], "Mn": [0, 0.35], "Cu": [0, 0.25], "C": [0, 0.15], "Si": [0, 0.35]},
      props(8.89, 148, 462, 47, 204, 120, 1435, 600, 22, 9), max_other=0.3)
grade("monel_400", "Monel 400", "nickel", "Ni", 2, "#bcb4a6", "annealed",
      {"Cu": [28, 34], "Fe": [0, 2.5], "Mn": [0, 2]}, props(8.80, 240, 550, 48, 179, 160, 1300, 540, 3.4, 10))
grade("inconel_625", "Inconel 625", "superalloy", "Ni", 3, "#b4b0a8", "annealed",
      {"Cr": [20, 23], "Mo": [8, 10], "Nb": [3.15, 4.15], "Fe": [0, 5], "Co": [0, 1], "Ti": [0, 0.4], "Al": [0, 0.4]},
      props(8.44, 460, 880, 42, 205, 210, 1290, 980, 1.3, 10))
grade("inconel_718", "Inconel 718", "superalloy", "Ni", 3, "#b0aca4", "aged",
      {"Cr": [17, 21], "Fe": [15, 21], "Nb": [4.75, 5.5], "Mo": [2.8, 3.3], "Ti": [0.65, 1.15], "Al": [0.2, 0.8], "Co": [0, 1]},
      props(8.19, 1035, 1240, 12, 200, 400, 1260, 700, 1.4, 9), note="Rocket engine turbomachinery and combustion chambers.")
grade("waspaloy", "Waspaloy", "superalloy", "Ni", 3, "#aca9a3", "aged",
      {"Cr": [18, 21], "Co": [12, 15], "Mo": [3.5, 5], "Ti": [2.75, 3.25], "Al": [1.2, 1.6], "Fe": [0, 2],
       "C": [0.02, 0.1], "B": [0.003, 0.01], "Zr": [0.02, 0.08]}, props(8.20, 795, 1275, 25, 213, 380, 1330, 870, 1.2, 9))
grade("cmsx_4", "CMSX-4 Single-Crystal Superalloy", "superalloy", "Ni", 4, "#a7a39b", "single crystal",
      {"Cr": [6.2, 6.8], "Co": [9.3, 10], "Mo": [0.5, 0.7], "W": [6.2, 6.6], "Ta": [6.3, 6.7], "Al": [5.45, 5.75],
       "Ti": [0.9, 1.1], "Re": [2.8, 3.2], "Hf": [0.07, 0.12]}, props(8.70, 950, 1100, 10, 130, 430, 1320, 1150, 1.0, 8),
      note="Turbine-blade alloy: needs rhenium and tantalum.")
generic("nickel_alloy", "Nickel Alloy", "nickel", "Ni", {}, "#b6b2aa")

# --- Cobalt (Co) --------------------------------------------------------------------------------
grade("stellite_6", "Stellite 6", "cobalt", "Co", 3, "#a9abb3", "cast",
      {"Cr": [27, 32], "W": [4, 6], "C": [0.9, 1.4], "Ni": [0, 3], "Fe": [0, 3], "Si": [0, 2], "Mn": [0, 1], "Mo": [0, 1]},
      props(8.44, 540, 900, 1, 209, 420, 1285, 800, 1.5, 9), note="Wear- and heat-resistant hardfacing.")
generic("cobalt_alloy", "Cobalt Alloy", "cobalt", "Co", {}, "#a6a9b2")

# --- Copper (Cu) --------------------------------------------------------------------------------
grade("cu_c11000", "C11000 Copper (ETP)", "copper", "Cu", 1, "#c9774f", "annealed",
      {"O": [0, 0.04]}, props(8.94, 69, 220, 50, 115, 50, 1065, 200, 101, 6), max_other=0.05)
grade("brass_c26000", "C26000 Cartridge Brass", "copper", "Cu", 1, "#d1ad5a", "annealed",
      {"Zn": [28.5, 31.5], "Pb": [0, 0.07], "Fe": [0, 0.05]}, props(8.53, 75, 300, 66, 110, 70, 915, 200, 28, 5))
grade("bronze_c51000", "C51000 Phosphor Bronze", "copper", "Cu", 1, "#b9824a", "annealed",
      {"Sn": [4.2, 5.8], "P": [0.03, 0.35], "Zn": [0, 0.3], "Fe": [0, 0.1]}, props(8.86, 130, 340, 55, 110, 85, 950, 250, 15, 7))
grade("becu_c17200", "C17200 Beryllium Copper", "copper", "Cu", 3, "#c98f5d", "aged",
      {"Be": [1.8, 2.0], "Co": [0.2, 0.6]}, props(8.25, 1100, 1300, 4, 128, 380, 865, 300, 22, 7),
      note="Spark-free, strong and conductive. Beryllium dust is toxic.")
grade("cupronickel_7030", "C71500 Cupronickel 70/30", "copper", "Cu", 2, "#b9a99b", "annealed",
      {"Ni": [29, 33], "Fe": [0.4, 1.0], "Mn": [0, 1]}, props(8.94, 125, 380, 45, 152, 85, 1170, 300, 4.6, 9))
generic("copper_alloy", "Copper Alloy", "copper", "Cu", {}, "#c68a58")

# --- Magnesium (Mg) -----------------------------------------------------------------------------
grade("mg_az31b", "AZ31B Magnesium", "magnesium", "Mg", 2, "#c2c4c0", "H24",
      {"Al": [2.5, 3.5], "Zn": [0.6, 1.4], "Mn": [0.2, 1.0]}, props(1.77, 200, 260, 15, 45, 49, 566, 120, 18.5, 2))
generic("magnesium_alloy", "Magnesium Alloy", "magnesium", "Mg", {}, "#c0c2be")

# --- Refractory metals --------------------------------------------------------------------------
grade("w_pure", "Tungsten", "refractory", "W", 3, "#8d8f94", "wrought",
      {}, props(19.3, 750, 980, 2, 411, 350, 3422, 1650, 31, 4), max_other=0.1, note="Service limit is in vacuum; burns in hot air.")
grade("wc_co", "Tungsten Carbide (WC-Co)", "refractory", "W", 3, "#6c6e75", "sintered",
      {"C": [5.4, 5.8], "Co": [6, 10]}, props(14.9, 1500, 1600, 0.3, 600, 1500, 1320, 800, 8, 6),
      note="Cemented carbide: the hardest tooling material.")
grade("mo_tzm", "TZM Molybdenum", "refractory", "Mo", 3, "#9a9ca3", "stress relieved",
      {"Ti": [0.4, 0.55], "Zr": [0.06, 0.12], "C": [0.01, 0.04]}, props(10.22, 860, 965, 10, 320, 260, 2623, 1400, 31, 3))
grade("nb_c103", "C-103 Niobium", "refractory", "Nb", 3, "#9c9ba6", "recrystallized",
      {"Hf": [9, 11], "Ti": [0.7, 1.3], "Zr": [0, 0.7], "W": [0, 0.5], "Ta": [0, 0.5]},
      props(8.85, 296, 420, 26, 90, 140, 2350, 1370, 7, 5), note="Rocket nozzle extensions (Apollo service module).")
grade("ta_pure", "Tantalum", "refractory", "Ta", 3, "#8f8a93", "annealed",
      {}, props(16.65, 165, 285, 40, 186, 110, 3017, 1500, 13, 10), max_other=0.1)

# --- Others -------------------------------------------------------------------------------------
grade("zircaloy_4", "Zircaloy-4", "zirconium", "Zr", 3, "#a4a6a2", "annealed",
      {"Sn": [1.2, 1.7], "Fe": [0.18, 0.24], "Cr": [0.07, 0.13], "O": [0.09, 0.16]},
      props(6.56, 380, 520, 25, 99, 180, 1850, 400, 2.4, 9), note="Reactor fuel cladding: barely absorbs neutrons.")
grade("solder_sn63", "Sn63/Pb37 Solder", "solder", "Sn", 1, "#b9bcbf", "cast",
      {"Pb": [36.5, 37.5]}, props(8.40, 27, 52, 35, 32, 15, 183, 100, 11.5, 6))
grade("sterling_silver", "Sterling Silver", "precious", "Ag", 2, "#d9dbdd", "annealed",
      {"Cu": [7.0, 7.8]}, props(10.36, 125, 270, 40, 76, 65, 802, 300, 87, 6))

# --- Base-metal models ----------------------------------------------------------------------------
# solute: strength (MPa per sqrt(wt%)), cap (wt% where strengthening saturates), ductility (per wt%,
# elongation x exp(-d w)), conductivity (k per wt%, cond / (1 + sum k w)), corrosion (per wt%),
# service (C per wt% up to cap), solidus (C depression per wt%), threshold/bonus (corrosion jump),
# brittle_above (wt% where the alloy turns brittle).
def s(strength=15, cap=20, ductility=0.02, conductivity=0.5, corrosion=0.0, service=0.0, solidus=None, threshold=None, bonus=0.0, brittle=None):
    d = {"strength": strength, "cap": cap, "ductility": ductility, "conductivity": conductivity, "corrosion": corrosion, "service": service}
    if solidus is not None:
        d["solidus"] = solidus
    if threshold is not None:
        d["threshold"] = threshold
        d["bonus"] = bonus
    if brittle is not None:
        d["brittle_above"] = brittle
    return d


BASE = {
    "Fe": ("Iron", props(7.87, 130, 260, 45, 211, 70, 1538, 400, 17.6, 2), {
        "C": s(420, 1.0, 2.2, 3.0, 0, 0, 75, brittle=2.1), "Mn": s(55, 2, 0.1, 1.2), "Si": s(80, 3, 0.15, 3.0, 0.05, 10),
        "Cr": s(30, 30, 0.01, 0.4, 0.08, 25, 2, threshold=10.5, bonus=4), "Ni": s(30, 25, -0.01, 0.4, 0.05, 5, 3),
        "Mo": s(70, 6, 0.05, 0.4, 0.3, 20, 3), "V": s(200, 1, 0.3, 1.0), "W": s(45, 10, 0.05, 0.4, 0, 15, 2),
        "Co": s(30, 12, 0.02), "Ti": s(150, 1, 0.3), "Al": s(50, 2, 0.1, 1.0, 0.1, 10), "Cu": s(60, 4, 0.05, 0.3, 0.1),
        "Nb": s(200, 0.5, 0.2), "N": s(400, 0.2, 0.5, 2.0), "P": s(100, 0.1, 15, 4.0), "S": s(0, 0.1, 12, 2.0),
        "O": s(0, 0.1, 20, 1.0), "B": s(100, 0.01, 1.0), "Pb": s(0, 0.3, 3, 1.0)}),
    "Al": ("Aluminium", props(2.70, 20, 70, 50, 69, 20, 660, 150, 64.9, 7), {
        "Cu": s(110, 6, 0.08, 0.35, -0.6, 3, 8), "Mg": s(90, 6, 0.05, 0.25, 0.1, 2, 6), "Zn": s(70, 8, 0.03, 0.1, -0.2, 0, 5),
        "Si": s(60, 12, 0.08, 0.4, 0, 0, 6), "Mn": s(50, 1.5, 0.05, 0.6, 0.1), "Li": s(150, 2.5, 0.2, 1.0, -0.2),
        "Fe": s(30, 1, 0.2, 0.5), "Cr": s(60, 0.4, 0.1, 1.0, 0.3), "Zr": s(80, 0.2, 0.1, 0.6), "Ag": s(40, 1, 0.02, 0.1),
        "Sc": s(400, 0.4, 0.05, 0.5)}),
    "Ti": ("Titanium", props(4.51, 140, 220, 50, 116, 100, 1668, 315, 3.1, 10), {
        "Al": s(220, 8, 0.05, 0.3, 0, 15, 5), "V": s(140, 15, 0.02, 0.2, 0, 3, 4), "O": s(900, 0.4, 2.5, 0.5),
        "N": s(1200, 0.1, 5, 0.5), "C": s(500, 0.1, 2, 0.5), "Fe": s(250, 1, 0.3, 0.3), "Sn": s(100, 4, 0.02, 0.2, 0, 12),
        "Zr": s(70, 6, 0.01, 0.1, 0, 10), "Mo": s(160, 10, 0.03, 0.2, 0, 15), "Si": s(300, 0.3, 0.5, 0.5, 0, 50),
        "H": s(0, 0.02, 40, 0.5)}),
    "Ni": ("Nickel", props(8.91, 148, 462, 47, 207, 110, 1455, 600, 25, 9), {
        "Cr": s(55, 25, 0.005, 0.5, 0.05, 12, 4), "Mo": s(90, 12, 0.01, 0.5, 0.1, 15, 4), "W": s(80, 12, 0.01, 0.4, 0, 20, 3),
        "Co": s(30, 20, 0.0, 0.2, 0, 6, 1), "Al": s(220, 6, 0.08, 0.6, 0.05, 40, 8), "Ti": s(200, 4, 0.08, 0.6, 0, 35, 10),
        "Nb": s(180, 6, 0.06, 0.6, 0, 25, 12), "Ta": s(120, 8, 0.03, 0.4, 0, 25, 4), "Re": s(150, 6, 0.02, 0.4, 0, 45, 0),
        "Hf": s(100, 1.5, 0.05, 0.4, 0, 20), "Fe": s(20, 25, 0.005, 0.3, -0.02, 0, 2), "Cu": s(25, 35, 0.0, 0.25, 0.03, 0, 4),
        "C": s(200, 0.15, 0.8, 1.0), "B": s(200, 0.02, 2, 1.0), "Zr": s(100, 0.1, 0.5, 0.5), "S": s(0, 0.01, 40, 1.0)}),
    "Cu": ("Copper", props(8.96, 69, 220, 50, 117, 45, 1085, 200, 101, 6), {
        "Zn": s(40, 38, -0.01, 0.08, -0.04, 0, 6), "Sn": s(70, 12, 0.02, 0.45, 0.05, 3, 25), "Ni": s(40, 35, -0.005, 0.4, 0.1, 4, -3),
        "Be": s(600, 2.0, 0.5, 2.0, 0.2, 40, 100), "Al": s(80, 11, 0.02, 1.0, 0.2, 5, 10), "P": s(150, 0.3, 0.5, 8.0, 0, 0, 30),
        "Fe": s(60, 2, 0.05, 3.0), "Mn": s(50, 12, 0.02, 1.5), "Si": s(80, 4, 0.05, 2.0), "Pb": s(0, 3, 0.1, 0.3),
        "Cr": s(250, 1, 0.1, 1.5, 0, 20), "Ag": s(30, 6, 0.0, 0.05), "Co": s(150, 2, 0.1, 2.0), "O": s(0, 0.05, 2, 1.0)}),
    "Mg": ("Magnesium", props(1.74, 21, 90, 5, 45, 30, 650, 120, 38, 2), {
        "Al": s(100, 10, -0.05, 0.6, 0.05, 2, 8), "Zn": s(70, 6, -0.03, 0.3, 0, 0, 7), "Mn": s(40, 1.5, 0.02, 0.3, 0.3),
        "Zr": s(120, 0.7, -0.1, 0.4), "Y": s(80, 6, 0.0, 0.5, 0.05, 15), "Nd": s(90, 3, 0.0, 0.5, 0, 15)}),
    "Co": ("Cobalt", props(8.90, 225, 255, 5, 209, 140, 1495, 700, 27.6, 7), {
        "Cr": s(60, 32, 0.01, 0.5, 0.08, 6, 5, threshold=20, bonus=2), "W": s(90, 15, 0.02, 0.4, 0, 12, 3),
        "C": s(450, 1.5, 0.5, 1.0, 0, 0, 40), "Ni": s(30, 20, -0.02, 0.3), "Mo": s(80, 8, 0.02, 0.4, 0.05, 10)}),
    "W": ("Tungsten", props(19.3, 750, 980, 2, 411, 350, 3422, 1650, 31, 4), {
        "Re": s(100, 26, -0.08, 0.6, 0, 10, 15), "C": s(300, 6, 0.3, 1.0, 0, 0, 340), "Co": s(150, 12, 0.0, 0.8, 0.1, -30, 150),
        "Ni": s(80, 6, -0.2, 0.8, 0, -40, 150), "Fe": s(60, 4, -0.2, 0.8, 0, -40, 150), "Th": s(150, 2, 0.0, 0.5)}),
    "Mo": ("Molybdenum", props(10.28, 415, 550, 10, 329, 230, 2623, 1300, 34, 3), {
        "Ti": s(350, 0.6, 0.1, 0.6, 0, 50), "Zr": s(400, 0.15, 0.1, 0.6, 0, 50), "C": s(400, 0.05, 0.5, 0.5),
        "Re": s(80, 47, -0.03, 0.4, 0, 5, 10), "W": s(40, 50, 0.0, 0.3, 0, 5, -10), "La": s(150, 1, 0.0, 0.3, 0, 30)}),
    "Nb": ("Niobium", props(8.57, 105, 275, 30, 105, 80, 2477, 1100, 13, 6), {
        "Hf": s(50, 12, 0.01, 0.3, 0, 15, 5), "Ti": s(80, 2, 0.02, 0.3, 0, 10), "Zr": s(120, 1, 0.02, 0.3, 0, 20),
        "W": s(70, 10, 0.02, 0.3, 0, 15), "Ta": s(30, 10, 0.0, 0.2, 0.1)}),
    "Ta": ("Tantalum", props(16.69, 165, 285, 40, 186, 110, 3017, 1500, 13, 10), {
        "W": s(80, 10, 0.02, 0.4, 0, 20), "Nb": s(30, 40, 0.0, 0.2, -0.02)}),
    "Zr": ("Zirconium", props(6.52, 230, 330, 32, 88, 120, 1855, 400, 4.1, 9), {
        "Sn": s(90, 2, 0.02, 0.3, 0.2, 10), "Fe": s(150, 0.3, 0.1, 0.4, 0.3), "Cr": s(150, 0.2, 0.1, 0.4, 0.3),
        "O": s(800, 0.2, 2, 0.5), "Nb": s(150, 2.5, 0.02, 0.3, 0.1, 10)}),
    "Sn": ("Tin", props(7.29, 9, 15, 50, 50, 5, 232, 100, 15.6, 6), {
        "Pb": s(4, 40, 0.0, 0.05, -0.02, 0, 1.3), "Ag": s(20, 4, 0.0, 0.2, 0, 5, 3), "Cu": s(20, 1, 0.1, 0.2, 0, 5, 2),
        "Sb": s(15, 8, 0.02, 0.2)}),
    "Ag": ("Silver", props(10.49, 54, 170, 50, 83, 25, 962, 300, 108, 7), {
        "Cu": s(45, 28, 0.0, 0.08, -0.1, 0, 15), "Au": s(10, 50, 0.0, 0.1, 0.05), "Pd": s(20, 30, 0.0, 0.2, 0.1, 5, -5)}),
    "Au": ("Gold", props(19.3, 30, 120, 45, 79, 25, 1064, 400, 73, 10), {
        "Ag": s(15, 50, 0.0, 0.1), "Cu": s(30, 50, 0.0, 0.15, -0.03, 0, 6), "Pd": s(20, 30, 0.0, 0.2), "Ni": s(40, 15, 0.0, 0.3)}),
    "Pb": ("Lead", props(11.34, 5, 18, 50, 16, 5, 327, 100, 8, 8), {
        "Sb": s(12, 12, 0.05, 0.2, 0, 0, 10), "Sn": s(4, 60, 0.0, 0.05, 0, 0, 2), "Ca": s(40, 0.1, 0.1, 0.2)}),
    "Zn": ("Zinc", props(7.14, 35, 120, 40, 108, 30, 420, 100, 28, 4), {
        "Al": s(60, 27, 0.05, 0.2, 0.05, 0, 3), "Cu": s(60, 3, 0.05, 0.2), "Mg": s(80, 0.1, 0.5, 0.2)}),
    "Pt": ("Platinum", props(21.45, 50, 145, 40, 168, 40, 1768, 1000, 16, 10), {
        "Ir": s(60, 30, 0.01, 0.4, 0, 15, 0), "Rh": s(50, 40, 0.01, 0.4, 0, 15, -5), "Ru": s(80, 10, 0.02, 0.4)}),
    "Cr": ("Chromium", props(7.19, 280, 410, 1, 279, 200, 1907, 900, 13, 9), {}),
    "V": ("Vanadium", props(6.0, 125, 220, 20, 128, 70, 1910, 500, 7, 6), {"Ti": s(80, 10, 0.02, 0.3), "Cr": s(70, 10, 0.02, 0.3)}),
    "Be": ("Beryllium", props(1.85, 240, 370, 3, 287, 120, 1287, 600, 43, 5), {}),
    "Hf": ("Hafnium", props(13.31, 240, 445, 23, 78, 160, 2233, 600, 5, 9), {}),
    "U": ("Uranium", props(19.1, 200, 400, 4, 208, 200, 1132, 400, 3, 1), {"Mo": s(150, 10, 0.05, 0.3, 0.2), "Zr": s(80, 10, 0.05, 0.3, 0.2)}),
}


def nominal(g):
    if g["nominal"]:
        return g["nominal"]
    return {el: round((lo + hi) / 2 if hi > 0 else 0, 4) for el, (lo, hi) in g["ranges"].items() if (lo + hi) > 0}


def main():
    for d in (GRADES, BASES):
        os.makedirs(d, exist_ok=True)
        for f in os.listdir(d):
            os.remove(os.path.join(d, f))
    for i, g in enumerate(G):
        data = {"name": {"translate": f"alloy.starwake.{g['id']}", "fallback": g["name"]}, "family": g["family"], "base": g["base"],
                "ranges": {el: [lo, hi] for el, (lo, hi) in g["ranges"].items()}, "max_other": g["max_other"], "exact": g["exact"],
                "color": g["color"], "tier": g["tier"], "order": i}
        if g["exact"]:
            data["nominal"] = nominal(g)
            data["properties"] = g["properties"]
            data["condition"] = g["condition"]
        if g["note"]:
            data["note"] = {"translate": f"alloy.starwake.{g['id']}.note", "fallback": g["note"]}
        with open(os.path.join(GRADES, g["id"] + ".json"), "w") as f:
            json.dump(data, f, indent=1)
    for sym, (name, pure, solutes) in BASE.items():
        data = {"element": sym, "pure": pure, "solutes": solutes}
        with open(os.path.join(BASES, sym.lower() + ".json"), "w") as f:
            json.dump(data, f, indent=1)
    print(f"{sum(1 for g in G if g['exact'])} grades, {sum(1 for g in G if not g['exact'])} families, {len(BASE)} base models")


if __name__ == "__main__":
    main()
