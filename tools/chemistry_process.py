#!/usr/bin/env python3
"""Generates Starwake's process-chemistry data (M8c).

Bundled (read at startup on both sides, because they define chemical fluids and tooltips):
  src/main/resources/starwake/chemistry/species.json - every chemical species: id (its formula or
      a name), display name, formula (parsed in Java for molar mass and element fractions),
      kind (metal, sulfide, oxide, silicate, carbonate, sulfate, phosphate, halide, gas, organic,
      carbon, water, element), density g/cm3, melting/boiling point C, flags (floats = flotation,
      magnetic / weak_magnetic, toxic), toxicity, colour.
  src/main/resources/starwake/chemistry/ores.json - what raw items are made of: item id -> mass
      (kg per item) + assay (species mass fractions). Ore chunks are "mineral-rich" (user,
      2026-11-12): 60-90% mineral, the rest gangue, plus trace elements.
Datapack (server, reloadable):
  data/starwake/starwake/reaction/*.json - balanced equations per machine, in priority order.
  data/starwake/starwake/separation/*.json - flotation / magnetic / gravity separation settings.

Every equation is checked for element balance here and again in Java.

Usage: python3 tools/chemistry_process.py
"""
import json
import os
import re

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources")
BUNDLE = os.path.join(ROOT, "starwake", "chemistry")
DATA = os.path.join(ROOT, "data", "starwake", "starwake")

ELEMENTS = {e["symbol"]: e for e in json.load(open(os.path.join(BUNDLE, "elements.json")))}

# --- Species ---------------------------------------------------------------------------------------
SPECIES = []


def sp(id, name, kind, density, mp, bp, flags=(), toxicity=0.0, color="#8a8a8a", formula=None):
    SPECIES.append({"id": id, "name": name, "formula": formula or id, "kind": kind, "density": density, "melting": mp,
                    "boiling": bp, "flags": list(flags), "toxicity": toxicity, "color": color})


# Standard electrode potentials E0 (V, M^n+/M): in electrorefining, nobler elements than the metal
# being refined fall to the anode slimes; less noble ones dissolve into the electrolyte.
POTENTIAL = {"Au": 1.50, "Pt": 1.18, "Ir": 1.16, "Pd": 0.95, "Hg": 0.85, "Os": 0.85, "Ag": 0.80, "Rh": 0.76, "Te": 0.53,
             "Ru": 0.45, "Cu": 0.34, "Bi": 0.31, "As": 0.25, "Re": 0.30, "Sb": 0.15, "W": -0.12, "Pb": -0.13, "Sn": -0.14,
             "Mo": -0.20, "Ni": -0.25, "Co": -0.28, "In": -0.34, "Tl": -0.34, "Cd": -0.40, "Fe": -0.44, "Ga": -0.53,
             "Cr": -0.74, "Zn": -0.76, "V": -1.18, "Mn": -1.18, "Nb": -1.10, "Zr": -1.45, "Ti": -1.63, "Hf": -1.55,
             "Al": -1.66, "Be": -1.85, "U": -1.80, "Th": -1.90, "Sc": -2.08, "Y": -2.37, "Mg": -2.37, "La": -2.38,
             "Ce": -2.34, "Nd": -2.32, "Na": -2.71, "Ca": -2.87, "Sr": -2.89, "Ba": -2.91, "K": -2.93, "Li": -3.04}


# Gangue and fluxes
sp("SiO2", "Quartz", "oxide", 2.65, 1713, 2950, color="#d8d4cc")
sp("Al2O3", "Alumina", "oxide", 3.95, 2072, 2977, color="#e8e4e0")
sp("CaCO3", "Calcite", "carbonate", 2.71, 825, 9999, color="#efe9dc")
sp("MgCO3", "Magnesite", "carbonate", 3.0, 350, 9999, color="#e4e0d6")
sp("CaO", "Quicklime", "oxide", 3.34, 2613, 2850, color="#f2efe8")
sp("MgO", "Magnesia", "oxide", 3.58, 2852, 3600, color="#efefef")
sp("KAlSi3O8", "Orthoclase", "silicate", 2.56, 1200, 9999, color="#d8b8a8")
# Iron and steelmaking
sp("Fe2O3", "Hematite", "oxide", 5.26, 1565, 9999, flags=["weak_magnetic"], color="#7a3a2a")
sp("Fe3O4", "Magnetite", "oxide", 5.17, 1597, 9999, flags=["magnetic"], color="#2a2a2e")
sp("FeO", "Wustite", "oxide", 5.7, 1377, 3414, color="#3a3634")
sp("Fe2SiO4", "Fayalite", "silicate", 4.39, 1205, 9999, color="#4a4038")
sp("CaSiO3", "Wollastonite", "silicate", 2.9, 1540, 9999, color="#cfc8b8")
# Copper, nickel, cobalt, lead, zinc, tin, molybdenum
sp("CuFeS2", "Chalcopyrite", "sulfide", 4.2, 950, 9999, flags=["floats"], color="#c8a838")
sp("Cu2S", "Chalcocite", "sulfide", 5.6, 1130, 9999, flags=["floats"], color="#3c3c44")
sp("FeS", "Pyrrhotite", "sulfide", 4.8, 1194, 9999, flags=["floats", "weak_magnetic"], color="#a8884a")
sp("FeS2", "Pyrite", "sulfide", 5.0, 1180, 9999, flags=["floats"], color="#d8c060")
sp("NiS", "Millerite", "sulfide", 5.5, 797, 9999, flags=["floats"], color="#b8a858")
sp("Cu2O", "Cuprite", "oxide", 6.0, 1232, 1800, color="#8a2a20")
sp("NiO", "Bunsenite", "oxide", 6.67, 1955, 9999, color="#4a6a3a")
sp("CoO", "Cobalt oxide", "oxide", 6.44, 1933, 9999, color="#3a3a6a")
sp("CoAsS", "Cobaltite", "sulfide", 6.3, 1000, 9999, flags=["floats"], color="#b8b8c8")
sp("PbS", "Galena", "sulfide", 7.6, 1118, 1281, flags=["floats"], color="#6a6e78")
sp("PbO", "Litharge", "oxide", 9.53, 888, 1477, color="#e8a838")
sp("ZnS", "Sphalerite", "sulfide", 4.05, 1700, 9999, flags=["floats"], color="#6a4a2a")
sp("ZnO", "Zincite", "oxide", 5.61, 1975, 2360, color="#efefe8")
sp("SnO2", "Cassiterite", "oxide", 6.95, 1630, 1900, color="#3a2e2a")
sp("MoS2", "Molybdenite", "sulfide", 5.0, 1750, 9999, flags=["floats"], color="#5a5e6a")
sp("MoO3", "Molybdite", "oxide", 4.69, 795, 1155, color="#d8d8b8")
sp("HgS", "Cinnabar", "sulfide", 8.1, 580, 9999, flags=["floats"], color="#b82a2a")
sp("Sb2S3", "Stibnite", "sulfide", 4.63, 550, 1150, flags=["floats"], color="#5e626e")
sp("Bi2S3", "Bismuthinite", "sulfide", 6.8, 775, 9999, flags=["floats"], color="#7a7e88")
sp("Ag2S", "Acanthite", "sulfide", 7.2, 825, 9999, flags=["floats"], color="#3a3a3a")
sp("PtAs2", "Sperrylite", "sulfide", 10.6, 1500, 9999, flags=["floats"], color="#c8c8c8")
sp("AuTe2", "Calaverite", "sulfide", 9.1, 464, 9999, flags=["floats"], color="#c8b878")
sp("As2O3", "Arsenolite", "oxide", 3.74, 312, 465, flags=["toxic"], toxicity=0.8, color="#efefef")
# Ferroalloy and refractory ores
sp("FeCr2O4", "Chromite", "oxide", 4.6, 2180, 9999, flags=["weak_magnetic"], color="#2a2620")
sp("MnO2", "Pyrolusite", "oxide", 5.03, 535, 9999, color="#2e2a28")
sp("MnO", "Manganosite", "oxide", 5.37, 1945, 9999, color="#3a6a3a")
sp("FeTiO3", "Ilmenite", "oxide", 4.72, 1050, 9999, flags=["weak_magnetic"], color="#2a2a2a")
sp("TiO2", "Rutile", "oxide", 4.23, 1843, 2972, color="#8a4a2a")
sp("FeWO4", "Ferberite", "oxide", 7.5, 1200, 9999, flags=["weak_magnetic"], color="#3a3430")
sp("MnWO4", "Hubnerite", "oxide", 7.2, 1200, 9999, flags=["weak_magnetic"], color="#6a4a3a")
sp("FeNb2O6", "Ferrocolumbite", "oxide", 5.3, 1500, 9999, flags=["weak_magnetic"], color="#2a2a2a")
sp("FeTa2O6", "Ferrotantalite", "oxide", 7.9, 1500, 9999, flags=["weak_magnetic"], color="#2e2a2a")
sp("MnNb2O6", "Manganocolumbite", "oxide", 5.0, 1500, 9999, color="#3a2e2a")
sp("ZrSiO4", "Zircon", "silicate", 4.65, 2550, 9999, color="#a86a4a")
# Aluminium, lithium, beryllium, caesium (processed in 8c-2)
sp("Al(OH)3", "Gibbsite", "oxide", 2.42, 300, 9999, color="#d8b8a0")
sp("AlO(OH)", "Boehmite", "oxide", 3.0, 450, 9999, color="#c8a890")
sp("LiAlSi2O6", "Spodumene", "silicate", 3.15, 1423, 9999, color="#d8c8d8")
sp("Be3Al2Si6O18", "Beryl", "silicate", 2.76, 1650, 9999, color="#8ad8b8")
sp("CsAlSi2O6", "Pollucite", "silicate", 2.9, 1900, 9999, color="#efefef")
# Rare earths, actinides, phosphates, sulfates, halides
sp("CePO4", "Monazite-(Ce)", "phosphate", 5.2, 2045, 9999, flags=["weak_magnetic"], color="#c87a3a")
sp("LaPO4", "Monazite-(La)", "phosphate", 5.1, 2072, 9999, flags=["weak_magnetic"], color="#c8883a")
sp("NdPO4", "Monazite-(Nd)", "phosphate", 5.3, 1975, 9999, flags=["weak_magnetic"], color="#b87a5a")
sp("PrPO4", "Monazite-(Pr)", "phosphate", 5.2, 1938, 9999, flags=["weak_magnetic"], color="#a8a85a")
sp("ThO2", "Thorianite", "oxide", 10.0, 3390, 4400, flags=["toxic"], toxicity=0.3, color="#4a4a4a")
sp("YPO4", "Xenotime", "phosphate", 4.3, 2150, 9999, flags=["weak_magnetic"], color="#a87a4a")
sp("CeCO3F", "Bastnaesite-(Ce)", "carbonate", 4.95, 1000, 9999, color="#c8a87a")
sp("LaCO3F", "Bastnaesite-(La)", "carbonate", 4.9, 1000, 9999, color="#c8a88a")
sp("UO2", "Uraninite", "oxide", 10.97, 2865, 9999, flags=["toxic"], toxicity=0.5, color="#1e1e1e")
sp("BaSO4", "Barite", "sulfate", 4.48, 1580, 9999, color="#e8e0d8")
sp("SrSO4", "Celestine", "sulfate", 3.96, 1605, 9999, color="#c8d8e8")
sp("CaF2", "Fluorite", "halide", 3.18, 1418, 2533, color="#8a5ac8")
sp("Ca5(PO4)3F", "Fluorapatite", "phosphate", 3.19, 1660, 9999, color="#5a8a7a")
sp("KCl", "Sylvite", "halide", 1.98, 770, 1420, flags=["soluble"], color="#e8c8c8")
sp("NaCl", "Halite", "halide", 2.16, 801, 1465, flags=["soluble"], color="#efefef")
sp("Na2B4O7(H2O)10", "Borax", "sulfate", 1.71, 75, 9999, color="#efefef")
sp("NaNO3", "Nitratine", "sulfate", 2.26, 308, 380, flags=["soluble"], color="#efefef")
sp("CaSO4(H2O)2", "Gypsum", "sulfate", 2.32, 150, 9999, color="#f2f0ea")
# Gases and vapours (room-temperature gases go to gas outlets)
sp("O2", "Oxygen", "gas", 0.0014, -218, -183, color="#a8d8ff")
sp("N2", "Nitrogen", "gas", 0.0012, -210, -196, color="#c8d0e8")
sp("H2", "Hydrogen", "gas", 0.00009, -259, -253, color="#e8f0ff")
sp("CO", "Carbon monoxide", "gas", 0.0012, -205, -191, flags=["toxic"], toxicity=0.7, color="#c8c8c8")
sp("CO2", "Carbon dioxide", "gas", 0.0018, -56, -78, flags=["toxic"], toxicity=0.1, color="#d8d8d8")
sp("SO2", "Sulfur dioxide", "gas", 0.0026, -72, -10, flags=["toxic"], toxicity=0.6, color="#e8e0a8")
sp("CH4", "Methane", "gas", 0.0007, -182, -161, color="#d8e8ff")
sp("H2O", "Water", "water", 1.0, 0, 100, color="#3a6aff")
# Organics and carbon
sp("C", "Carbon", "carbon", 2.0, 3550, 4027, flags=["floats"], color="#2a2a2a")
sp("C6H10O5", "Cellulose", "organic", 1.5, 260, 9999, color="#a8885a")
sp("C10H16", "Pine oil", "organic", 0.86, -64, 155, color="#d8b84a")
sp("C10H8", "Coal tar", "organic", 1.14, 80, 218, flags=["toxic"], toxicity=0.2, color="#1e1a16")

# --- Wet chemistry and electrochemistry (M8c-2). "soluble" species dissolve into solutions. ---
sp("H2SO4", "Sulfuric acid", "sulfate", 1.83, 10, 337, flags=["soluble", "toxic"], toxicity=0.6, color="#e8e0c8")
sp("SO3", "Sulfur trioxide", "gas", 0.0036, 17, 45, flags=["toxic"], toxicity=0.7, color="#efefe0")
sp("HCl", "Hydrogen chloride", "gas", 0.0015, -114, -85, flags=["soluble", "toxic"], toxicity=0.6, color="#d8f0d8")
sp("HF", "Hydrogen fluoride", "gas", 0.0012, -83, 20, flags=["soluble", "toxic"], toxicity=0.9, color="#e8f8e8")
sp("Cl2", "Chlorine", "gas", 0.0032, -101, -34, flags=["toxic"], toxicity=0.9, color="#b8e858")
sp("NaOH", "Sodium hydroxide", "oxide", 2.13, 318, 1388, flags=["soluble", "toxic"], toxicity=0.4, color="#f0f0f0")
sp("KOH", "Potassium hydroxide", "oxide", 2.04, 406, 1327, flags=["soluble", "toxic"], toxicity=0.4, color="#f0f0f0")
sp("NaAl(OH)4", "Sodium aluminate", "oxide", 1.5, 1650, 9999, flags=["soluble"], color="#efefe8")
sp("Na3AlF6", "Cryolite", "halide", 2.95, 1012, 9999, color="#e8e8f0")
sp("CaSO4", "Anhydrite", "sulfate", 2.97, 1460, 9999, color="#efece4")
sp("CuSO4", "Copper sulfate", "sulfate", 3.6, 590, 9999, flags=["soluble"], color="#2a6aff")
sp("CuO", "Tenorite", "oxide", 6.31, 1326, 2000, color="#1e1e1e")
sp("ZnSO4", "Zinc sulfate", "sulfate", 3.54, 680, 9999, flags=["soluble"], color="#efefef")
sp("NiSO4", "Nickel sulfate", "sulfate", 3.68, 840, 9999, flags=["soluble"], color="#3aa84a")
sp("FeSO4", "Iron(II) sulfate", "sulfate", 3.65, 680, 9999, flags=["soluble"], color="#8ac8a0")
sp("CoSO4", "Cobalt sulfate", "sulfate", 3.71, 735, 9999, flags=["soluble"], color="#c83a6a")
sp("Na2SO4", "Sodium sulfate", "sulfate", 2.66, 884, 1429, flags=["soluble"], color="#f2f2f2")
sp("PbSO4", "Anglesite", "sulfate", 6.29, 1087, 9999, color="#e8e8e8")
sp("TiCl4", "Titanium tetrachloride", "halide", 1.73, -24, 136, flags=["toxic"], toxicity=0.6, color="#e8e8a8")
sp("MgCl2", "Magnesium chloride", "halide", 2.32, 714, 1412, flags=["soluble"], color="#f0f0f0")
sp("FeCl2", "Iron(II) chloride", "halide", 3.16, 677, 1023, flags=["soluble"], color="#a8c87a")
sp("V2O5", "Vanadium pentoxide", "oxide", 3.36, 690, 1750, flags=["toxic"], toxicity=0.3, color="#c88a2a")

KNOWN = {s["id"] for s in SPECIES}

# Elements as species (traces, metals produced by reduction).
BOILING = {"H": -253, "He": -269, "N": -196, "O": -183, "F": -188, "Ne": -246, "Cl": -34, "Ar": -186, "Kr": -153, "Xe": -108,
           "Rn": -62, "Br": 59, "I": 184, "Hg": 357, "Cd": 767, "Zn": 907, "As": 615, "Se": 685, "Te": 988, "S": 445, "P": 280,
           "Na": 883, "K": 759, "Rb": 688, "Cs": 671, "Li": 1342, "Mg": 1091, "Ca": 1484, "Sr": 1377, "Ba": 1845, "Tl": 1473,
           "Pb": 1749, "Bi": 1564, "Sb": 1587, "In": 2072, "Ga": 2400, "Sn": 2602, "Ag": 2162, "Cu": 2562, "Au": 2856,
           "Al": 2470, "Mn": 2061, "Cr": 2671, "Fe": 2861, "Co": 2927, "Ni": 2913, "Po": 962, "At": 337, "Fr": 677, "Ra": 1737}
TOXIC = {"Hg": 0.9, "As": 0.8, "Cd": 0.7, "Tl": 0.9, "Pb": 0.3, "Be": 0.6, "Po": 1.0, "Pu": 1.0, "Se": 0.4, "Cl": 0.7, "F": 0.9,
         "Br": 0.6, "Rn": 0.5, "U": 0.4, "Th": 0.3, "Ra": 0.9}
for sym, e in ELEMENTS.items():
    if sym in KNOWN:
        continue
    kind = "metal" if e["category"] not in ("nonmetal", "halogen", "noble_gas", "metalloid") else "element"
    if e["phase"] == "gas":
        kind = "gas"
    mp = round(e["melting_point"] - 273.15) if e["melting_point"] else 1000
    bp = BOILING.get(sym, 3000)
    flags = ["toxic"] if sym in TOXIC else []
    sp(sym, e["name"].capitalize(), kind, e["density"], mp, bp, flags=flags, toxicity=TOXIC.get(sym, 0.0), color=e["color"])
    if sym in POTENTIAL:
        SPECIES[-1]["potential"] = POTENTIAL[sym]

# --- Formula check (mirrors the Java parser) -------------------------------------------------------
TOKEN = re.compile(r"([A-Z][a-z]?|\(|\)|\d+(?:\.\d+)?)")


def parse(formula):
    stack = [{}]
    tokens = TOKEN.findall(formula)
    i = 0
    while i < len(tokens):
        t = tokens[i]
        if t == "(":
            stack.append({})
        elif t == ")":
            group = stack.pop()
            n = 1.0
            if i + 1 < len(tokens) and re.fullmatch(r"\d+(?:\.\d+)?", tokens[i + 1]):
                n = float(tokens[i + 1]); i += 1
            for k, v in group.items():
                stack[-1][k] = stack[-1].get(k, 0) + v * n
        elif re.fullmatch(r"\d+(?:\.\d+)?", t):
            pass
        else:
            n = 1.0
            if i + 1 < len(tokens) and re.fullmatch(r"\d+(?:\.\d+)?", tokens[i + 1]):
                n = float(tokens[i + 1]); i += 1
            stack[-1][t] = stack[-1].get(t, 0) + n
        i += 1
    return stack[0]


def mass(formula):
    return sum(ELEMENTS[k]["mass"] * v for k, v in parse(formula).items())


# --- Ores --------------------------------------------------------------------------------------------
GANGUE = {"SiO2": 0.6, "Al2O3": 0.15, "CaCO3": 0.1, "KAlSi3O8": 0.15}


def ore(mineral, grade, traces=None, gangue=None, mass=2.0):
    """mineral: {species: fraction of the mineral part}; grade: mineral share; traces: {element: ppm}."""
    assay = {}
    for s, f in mineral.items():
        assay[s] = assay.get(s, 0) + f * grade
    g = gangue or GANGUE
    rest = 1 - grade - sum((traces or {}).values()) / 1e6
    for s, f in g.items():
        assay[s] = assay.get(s, 0) + f * rest
    for el, ppm in (traces or {}).items():
        assay[el] = assay.get(el, 0) + ppm / 1e6
    return {"mass": mass, "assay": {k: round(v, 6) for k, v in assay.items()}}


ORES = {
    "minecraft:raw_iron": ore({"Fe2O3": 0.8, "Fe3O4": 0.2}, 0.85, {"Mn": 3000, "Ti": 800, "P": 400, "V": 200}),
    # Vanilla copper ore = supergene-enriched ore (chalcocite blanket over chalcopyrite), so our
    # chain yields about what a vanilla furnace does (~0.8 kg Cu per 2 kg chunk) plus by-products.
    "minecraft:raw_copper": ore({"Cu2S": 0.55, "CuFeS2": 0.35, "FeS2": 0.10}, 0.75, {"Ag": 120, "Au": 8, "Se": 60, "Te": 15, "Mo": 300}),
    "minecraft:raw_gold": ore({"Au": 0.85, "Ag": 0.15}, 0.6, {"Cu": 500, "Te": 30}, gangue={"SiO2": 0.9, "Al2O3": 0.1}),
    "starwake:raw_bauxite": ore({"Al(OH)3": 0.6, "AlO(OH)": 0.25, "Fe2O3": 0.15}, 0.85, {"Ga": 60, "Ti": 8000, "Sc": 40},
                                gangue={"SiO2": 0.6, "TiO2": 0.2, "Al2O3": 0.2}),
    "starwake:raw_ilmenite": ore({"FeTiO3": 1.0}, 0.8, {"V": 1500, "Mn": 3000, "Mg": 2000}),
    "starwake:raw_pentlandite": ore({"NiS": 0.51, "FeS": 0.49}, 0.7, {"Co": 1500, "Pt": 4, "Pd": 6, "Cu": 6000},
                                    gangue={"SiO2": 0.5, "MgO": 0.3, "Al2O3": 0.2}),
    "starwake:raw_chromite": ore({"FeCr2O4": 1.0}, 0.85, {"Mg": 5000, "Al": 4000, "V": 900}, gangue={"MgO": 0.5, "SiO2": 0.3, "Al2O3": 0.2}),
    "starwake:raw_cassiterite": ore({"SnO2": 1.0}, 0.7, {"In": 50, "Nb": 300, "Ta": 150}),
    "starwake:raw_wolframite": ore({"FeWO4": 0.5, "MnWO4": 0.5}, 0.75, {"Sc": 100, "Nb": 400, "Ta": 200}),
    "starwake:raw_cobaltite": ore({"CoAsS": 1.0}, 0.65, {"Ni": 4000, "Fe": 6000}),
    "starwake:raw_galena": ore({"PbS": 1.0}, 0.8, {"Ag": 800, "Bi": 300, "Tl": 20, "Sb": 400}),
    "starwake:raw_sphalerite": ore({"ZnS": 0.94, "FeS": 0.06}, 0.8, {"Cd": 3000, "Ga": 80, "Ge": 60, "In": 100}),
    "starwake:raw_molybdenite": ore({"MoS2": 1.0}, 0.65, {"Re": 500}),
    "starwake:raw_pyrolusite": ore({"MnO2": 1.0}, 0.8, {"Co": 1000, "Ni": 800, "Cu": 600}),
    "starwake:raw_stibnite": ore({"Sb2S3": 1.0}, 0.75, {"As": 2000, "Hg": 300}),
    "starwake:raw_bismuthinite": ore({"Bi2S3": 1.0}, 0.7, {"Te": 300, "Se": 200}),
    "starwake:raw_acanthite": ore({"Ag2S": 1.0}, 0.7, {"Au": 400}),
    "starwake:raw_sperrylite": ore({"PtAs2": 1.0}, 0.6, {"Pd": 8000, "Rh": 1500, "Ru": 1500, "Ir": 500, "Os": 400}),
    "starwake:raw_calaverite": ore({"AuTe2": 1.0}, 0.6, {"Ag": 5000, "Se": 300}),
    "starwake:raw_spodumene": ore({"LiAlSi2O6": 1.0}, 0.8, {"Na": 3000, "K": 2000}),
    "starwake:raw_beryl": ore({"Be3Al2Si6O18": 1.0}, 0.8, {"Cs": 1500, "Li": 800}),
    "starwake:raw_pollucite": ore({"CsAlSi2O6": 1.0}, 0.75, {"Rb": 8000, "Li": 2000}),
    "starwake:raw_zircon": ore({"ZrSiO4": 1.0}, 0.8, {"Hf": 10000, "U": 300, "Th": 200, "Y": 1000}),
    "starwake:raw_monazite": ore({"CePO4": 0.48, "LaPO4": 0.24, "NdPO4": 0.18, "PrPO4": 0.05, "ThO2": 0.05}, 0.8,
                                 {"Sm": 3000, "Gd": 1500, "Eu": 200, "Y": 2000, "Tb": 200, "Dy": 600}),
    "starwake:raw_xenotime": ore({"YPO4": 1.0}, 0.75, {"Dy": 6000, "Er": 4000, "Yb": 4000, "Ho": 1200, "Tb": 800, "Tm": 600,
                                                       "Lu": 600, "Gd": 3000, "U": 2000, "Th": 1000, "Sc": 200}),
    "starwake:raw_bastnaesite": ore({"CeCO3F": 0.62, "LaCO3F": 0.38}, 0.75, {"Nd": 30000, "Pr": 9000, "Eu": 300, "Sm": 2000}),
    "starwake:raw_columbite": ore({"FeNb2O6": 0.6, "FeTa2O6": 0.25, "MnNb2O6": 0.15}, 0.7, {"Sn": 3000, "Sc": 400, "U": 1000}),
    "starwake:raw_uraninite": ore({"UO2": 1.0}, 0.7, {"Ra": 0.3, "Po": 0.0001, "Pa": 0.3, "Th": 3000, "Pb": 20000, "Np": 0.01,
                                                      "Pu": 0.01, "Tc": 0.01, "Ac": 0.15}),
    "starwake:raw_barite": ore({"BaSO4": 1.0}, 0.85, {"Sr": 10000}),
    "starwake:raw_celestine": ore({"SrSO4": 1.0}, 0.85, {"Ba": 10000}),
    "starwake:raw_fluorite": ore({"CaF2": 1.0}, 0.8, {"Y": 300, "Ce": 200}),
    "starwake:raw_apatite": ore({"Ca5(PO4)3F": 1.0}, 0.75, {"Ce": 2000, "Y": 500, "U": 100}),
    "starwake:raw_magnesite": ore({"MgCO3": 1.0}, 0.85, {"Fe": 5000, "Ca": 5000}),
    "starwake:raw_sylvite": ore({"KCl": 0.7, "NaCl": 0.3}, 0.9, {"Rb": 300, "Br": 2000}, gangue={"CaSO4(H2O)2": 0.6, "SiO2": 0.4}),
    "starwake:raw_borax": ore({"Na2B4O7(H2O)10": 1.0}, 0.8, {"Li": 300}),
    "starwake:raw_nitratine": ore({"NaNO3": 1.0}, 0.75, {"I": 600, "K": 10000}),
    "minecraft:cinnabar": ore({"HgS": 1.0}, 0.7, {"Se": 200, "Sb": 500}),
    "minecraft:sulfur": ore({"S": 1.0}, 0.9, {"Se": 300, "Te": 50}, gangue={"CaSO4(H2O)2": 0.7, "CaCO3": 0.3}),
    "starwake:halite": ore({"NaCl": 0.95, "KCl": 0.03, "CaSO4(H2O)2": 0.02}, 0.95, {"Br": 300, "I": 20}, gangue={"SiO2": 1.0}, mass=1.0),
    "minecraft:ice": {"mass": 1.0, "assay": {"H2O": 1.0}},
    "minecraft:packed_ice": {"mass": 1.0, "assay": {"H2O": 1.0}},
    "minecraft:blue_ice": {"mass": 1.0, "assay": {"H2O": 1.0}},
    # Fuels, fluxes and feedstocks (1 kg items).
    "minecraft:coal": {"mass": 1.0, "assay": {"C": 0.78, "CH4": 0.07, "C10H8": 0.04, "H2O": 0.05, "S": 0.01, "SiO2": 0.03, "Al2O3": 0.02}},
    "minecraft:charcoal": {"mass": 1.0, "assay": {"C": 0.9, "H2O": 0.04, "CaCO3": 0.03, "KAlSi3O8": 0.03}},
    "minecraft:calcite": {"mass": 1.0, "assay": {"CaCO3": 0.97, "MgCO3": 0.02, "SiO2": 0.01}},
    "minecraft:quartz": {"mass": 1.0, "assay": {"SiO2": 1.0}},
    "minecraft:sand": {"mass": 1.0, "assay": {"SiO2": 0.95, "Al2O3": 0.03, "Fe2O3": 0.02}},
    "minecraft:clay_ball": {"mass": 1.0, "assay": {"Al2O3": 0.4, "SiO2": 0.46, "H2O": 0.14}},
    "minecraft:oak_log": {"mass": 1.0, "assay": {"C6H10O5": 0.96, "H2O": 0.03, "KAlSi3O8": 0.01}},
    "minecraft:spruce_log": {"mass": 1.0, "assay": {"C6H10O5": 0.92, "C10H16": 0.04, "H2O": 0.03, "KAlSi3O8": 0.01}},
    "minecraft:birch_log": {"mass": 1.0, "assay": {"C6H10O5": 0.96, "H2O": 0.03, "KAlSi3O8": 0.01}},
    "minecraft:dark_oak_log": {"mass": 1.0, "assay": {"C6H10O5": 0.96, "H2O": 0.03, "KAlSi3O8": 0.01}},
    "minecraft:jungle_log": {"mass": 1.0, "assay": {"C6H10O5": 0.96, "H2O": 0.03, "KAlSi3O8": 0.01}},
    "minecraft:acacia_log": {"mass": 1.0, "assay": {"C6H10O5": 0.96, "H2O": 0.03, "KAlSi3O8": 0.01}},
}

# --- Reactions ---------------------------------------------------------------------------------------
REACTIONS = []


def rx(machine, name, equation, priority, note=None, draw=None, electrons=None, voltage=None, catalyst=None):
    """draw: reactants the machine may feed from its reagent slots, inlet tank or the air (O2).
    electrons/voltage: electrolysis; energy per mole of reaction = electrons x Faraday x voltage.
    catalyst: an element or species that must be present in the catalyst slot (not consumed)."""
    REACTIONS.append({"name": name, "machine": machine, "equation": equation, "priority": priority, "draw": draw,
                      "electrons": electrons, "voltage": voltage, "catalyst": catalyst, "note": note})


# Roaster (650 C, air): sulfides to oxides + SO2, carbonates calcine, cinnabar gives mercury vapour.
rx("roaster", "roast_chalcopyrite", "2 CuFeS2 + 4 O2 -> Cu2S + 2 FeO + 3 SO2", 10, "Partial roast: iron oxidises, copper stays as sulfide for the matte.", draw=["O2"])
rx("roaster", "roast_pyrite", "4 FeS2 + 11 O2 -> 2 Fe2O3 + 8 SO2", 11, draw=["O2"])
rx("roaster", "roast_pyrrhotite", "4 FeS + 7 O2 -> 2 Fe2O3 + 4 SO2", 12, draw=["O2"])
rx("roaster", "roast_galena", "2 PbS + 3 O2 -> 2 PbO + 2 SO2", 13, draw=["O2"])
rx("roaster", "roast_sphalerite", "2 ZnS + 3 O2 -> 2 ZnO + 2 SO2", 14, draw=["O2"])
rx("roaster", "roast_millerite", "2 NiS + 3 O2 -> 2 NiO + 2 SO2", 15, draw=["O2"])
rx("roaster", "roast_cobaltite", "4 CoAsS + 9 O2 -> 4 CoO + 2 As2O3 + 4 SO2", 16, "Arsenic leaves as toxic As2O3 fume (flue dust).", draw=["O2"])
rx("roaster", "roast_molybdenite", "2 MoS2 + 7 O2 -> 2 MoO3 + 4 SO2", 17, draw=["O2"])
rx("roaster", "roast_cinnabar", "HgS + O2 -> Hg + SO2", 18, "Mercury distils off and is condensed.", draw=["O2"])
rx("roaster", "roast_stibnite", "2 Sb2S3 + 9 O2 -> 2 Sb2O3 + 6 SO2", 19, draw=["O2"])
rx("roaster", "calcine_calcite", "CaCO3 -> CaO + CO2", 30)
rx("roaster", "calcine_magnesite", "MgCO3 -> MgO + CO2", 31)
rx("roaster", "dehydrate_gibbsite", "2 Al(OH)3 -> Al2O3 + 3 H2O", 32)
rx("roaster", "dehydrate_boehmite", "2 AlO(OH) -> Al2O3 + H2O", 33)
rx("roaster", "burn_sulfur", "S + O2 -> SO2", 40, draw=["O2"])
# Coke oven (1000 C, no air): pyrolysis.
rx("coke_oven", "char_cellulose", "C6H10O5 -> 6 C + 5 H2O", 10, "Wood chars to charcoal; spruce gives pine oil.")
rx("coke_oven", "calcine_calcite_coke", "CaCO3 -> CaO + CO2", 20)
# Smelter (1550 C, reducing): reductions first, then slagging, then fluxing.
rx("smelter", "decompose_calcite", "CaCO3 -> CaO + CO2", 1)
rx("smelter", "decompose_magnesite", "MgCO3 -> MgO + CO2", 2)
rx("smelter", "reduce_hematite", "Fe2O3 + 3 C -> 2 Fe + 3 CO", 10, "Blast-furnace ironmaking; excess carbon dissolves in the iron (pig iron).", draw=["C"])
rx("smelter", "reduce_magnetite", "Fe3O4 + 4 C -> 3 Fe + 4 CO", 11, draw=["C"])
rx("smelter", "reduce_wustite", "FeO + C -> Fe + CO", 12, draw=["C"])
rx("smelter", "reduce_chromite", "FeCr2O4 + 4 C -> Fe + 2 Cr + 4 CO", 13, "Ferrochrome for stainless steel.", draw=["C"])
rx("smelter", "reduce_pyrolusite", "MnO2 + 2 C -> Mn + 2 CO", 14, draw=["C"])
rx("smelter", "reduce_manganosite", "MnO + C -> Mn + CO", 15, draw=["C"])
rx("smelter", "reduce_ilmenite", "FeTiO3 + C -> Fe + TiO2 + CO", 16, "Titania slag (for the Kroll process) plus iron.", draw=["C"])
rx("smelter", "reduce_litharge", "PbO + C -> Pb + CO", 17, draw=["C"])
rx("smelter", "reduce_zincite", "ZnO + C -> Zn + CO", 18, "Zinc boils off and is condensed (Imperial Smelting).", draw=["C"])
rx("smelter", "reduce_bunsenite", "NiO + C -> Ni + CO", 19, draw=["C"])
rx("smelter", "reduce_cobalt_oxide", "CoO + C -> Co + CO", 20, draw=["C"])
rx("smelter", "reduce_cassiterite", "SnO2 + 2 C -> Sn + 2 CO", 21, draw=["C"])
rx("smelter", "reduce_cuprite", "Cu2O + C -> 2 Cu + CO", 22, draw=["C"])
rx("smelter", "reduce_molybdite", "MoO3 + 3 C -> Mo + 3 CO", 23, draw=["C"])
rx("smelter", "reduce_ferberite", "FeWO4 + 4 C -> Fe + W + 4 CO", 24, "Ferrotungsten.", draw=["C"])
rx("smelter", "reduce_hubnerite", "MnWO4 + 4 C -> Mn + W + 4 CO", 25, draw=["C"])
rx("smelter", "slag_wustite", "2 FeO + SiO2 -> Fe2SiO4", 40, "Without carbon, iron oxide goes to fayalite slag (copper and nickel matte smelting).", draw=["SiO2"])
rx("smelter", "flux_lime", "CaO + SiO2 -> CaSiO3", 41)
rx("smelter", "flux_limestone", "CaCO3 + SiO2 -> CaSiO3 + CO2", 42, "Limestone flux takes up silica gangue as slag.", draw=["CaCO3"])
rx("smelter", "flare_carbon_monoxide", "2 CO + O2 -> 2 CO2", 90, "Off-gas CO is burned at the flare.", draw=["O2"])
# Converter (1250 C, oxygen blown): matte to blister.
rx("converter", "blow_iron_sulfide", "2 FeS + 3 O2 -> 2 FeO + 2 SO2", 10, "Slag blow: iron sulfide burns first.", draw=["O2"])
rx("converter", "slag_converter", "2 FeO + SiO2 -> Fe2SiO4", 11, draw=["O2"])
rx("converter", "blow_nickel_sulfide", "2 NiS + 3 O2 -> 2 NiO + 2 SO2", 12, draw=["O2"])
rx("converter", "blow_copper_sulfide", "Cu2S + O2 -> 2 Cu + SO2", 20, "Copper blow: blister copper (~98.5% Cu) with gold and silver.", draw=["O2"])
rx("converter", "reduce_nickel_oxide_converter", "NiO + C -> Ni + CO", 21, draw=["C"])

# Chemical reactor (acids, fluorides, cryolite). Process temperatures vary; products are cooled.
rx("reactor", "contact_oxidation", "2 SO2 + O2 -> 2 SO3", 10, "Contact process: needs a vanadium catalyst.", draw=["O2"], catalyst="V")
rx("reactor", "acid_absorption", "SO3 + H2O -> H2SO4", 11, "Sulfur trioxide absorbed in water: sulfuric acid.", draw=["H2O"])
rx("reactor", "hydrogen_chloride", "H2 + Cl2 -> 2 HCl", 20, "Burning hydrogen in chlorine.", draw=["Cl2"])
rx("reactor", "hydrofluoric_acid", "CaF2 + H2SO4 -> CaSO4 + 2 HF", 30, "Fluorspar + sulfuric acid: hydrogen fluoride (very toxic).", draw=["H2SO4"])
rx("reactor", "cryolite_synthesis", "Al(OH)3 + 6 HF + 3 NaOH -> Na3AlF6 + 6 H2O", 31, "Synthetic cryolite for the aluminium bath.",
   draw=["HF", "NaOH"])
# Leach tank (25-150 C): dissolves the valuable part, leaves residue.
rx("leach", "leach_chalcocite", "Cu2S + 2 H2SO4 + O2 -> 2 CuSO4 + S + 2 H2O", 10, "Oxidative acid leach of chalcocite.", draw=["H2SO4", "O2"])
rx("leach", "leach_tenorite", "CuO + H2SO4 -> CuSO4 + H2O", 11, draw=["H2SO4"])
rx("leach", "leach_zincite", "ZnO + H2SO4 -> ZnSO4 + H2O", 12, "Roasted zinc calcine dissolves for electrowinning.", draw=["H2SO4"])
rx("leach", "leach_bunsenite", "NiO + H2SO4 -> NiSO4 + H2O", 13, draw=["H2SO4"])
rx("leach", "leach_cobalt_oxide", "CoO + H2SO4 -> CoSO4 + H2O", 14, draw=["H2SO4"])
rx("leach", "bayer_gibbsite", "Al(OH)3 + NaOH -> NaAl(OH)4", 20, "Bayer process: caustic soda dissolves alumina; iron and titania stay as red mud.",
   draw=["NaOH"])
rx("leach", "bayer_boehmite", "AlO(OH) + NaOH + H2O -> NaAl(OH)4", 21, draw=["NaOH", "H2O"])
# Crystallizer (cooling/seeding precipitation).
rx("crystallizer", "bayer_precipitation", "NaAl(OH)4 -> Al(OH)3 + NaOH", 10, "Seeded cooling precipitates gibbsite; the caustic liquor is recycled.")
# Electrolysis cell. Energy = electrons x 96485 C/mol x voltage per mole of reaction.
rx("electrolysis", "hall_heroult", "2 Al2O3 + 3 C -> 4 Al + 3 CO2", 10, "Alumina dissolved in molten cryolite (960 C); carbon anodes burn away.",
   draw=["C"], electrons=12, voltage=4.2, catalyst="Na3AlF6")
rx("electrolysis", "magnesium_chloride", "MgCl2 -> Mg + Cl2", 11, "Molten magnesium chloride (Dow / Kroll recycle).", electrons=2, voltage=3.2)
rx("electrolysis", "chlor_alkali", "2 NaCl + 2 H2O -> 2 NaOH + H2 + Cl2", 20, "Brine electrolysis: chlorine, hydrogen and caustic soda.",
   draw=["H2O"], electrons=2, voltage=3.5)
rx("electrolysis", "chlor_potash", "2 KCl + 2 H2O -> 2 KOH + H2 + Cl2", 21, "Potash brine: chlorine, hydrogen and caustic potash.",
   draw=["H2O"], electrons=2, voltage=3.5)
rx("electrolysis", "electrowin_copper", "2 CuSO4 + 2 H2O -> 2 Cu + O2 + 2 H2SO4", 30, "Copper electrowinning; the acid is regenerated.",
   draw=["H2O"], electrons=4, voltage=2.0)
rx("electrolysis", "electrowin_zinc", "2 ZnSO4 + 2 H2O -> 2 Zn + O2 + 2 H2SO4", 31, draw=["H2O"], electrons=4, voltage=3.3)
rx("electrolysis", "electrowin_nickel", "2 NiSO4 + 2 H2O -> 2 Ni + O2 + 2 H2SO4", 32, draw=["H2O"], electrons=4, voltage=3.0)
rx("electrolysis", "electrowin_cobalt", "2 CoSO4 + 2 H2O -> 2 Co + O2 + 2 H2SO4", 33, draw=["H2O"], electrons=4, voltage=3.0)
rx("electrolysis", "water_electrolysis", "2 H2O -> 2 H2 + O2", 90, "Alkaline electrolyser: needs caustic potash electrolyte.",
   electrons=4, voltage=2.0, catalyst="KOH")
# Chlorinator (1000 C fluidised bed).
rx("chlorinator", "chlorinate_rutile", "TiO2 + 2 C + 2 Cl2 -> TiCl4 + 2 CO", 10, "Titanium tetrachloride distils off and is condensed.",
   draw=["C", "Cl2"])
rx("chlorinator", "chlorinate_magnesia", "MgO + C + Cl2 -> MgCl2 + CO", 11, draw=["C", "Cl2"])
rx("chlorinator", "chlorinate_wustite", "FeO + C + Cl2 -> FeCl2 + CO", 12, draw=["C", "Cl2"])
rx("chlorinator", "flare_chlorinator", "2 CO + O2 -> 2 CO2", 90, draw=["O2"])
# Kroll reactor (850 C, argon).
rx("kroll", "kroll_titanium", "TiCl4 + 2 Mg -> Ti + 2 MgCl2", 10, "Magnesium reduces TiCl4 to titanium sponge; MgCl2 goes back to the cell.",
   draw=["Mg"])

# Missing species used by reactions.
sp("Sb2O3", "Senarmontite", "oxide", 5.2, 656, 1425, flags=["toxic"], toxicity=0.3, color="#efefef")

# --- Separations -------------------------------------------------------------------------------------
SEPARATIONS = {
    "flotation": {"select": ["floats"], "recovery": 0.62, "entrainment": 0.1, "water": 1.5,
                  "frother": {"species": "C10H16", "kg": 0.02, "recovery": 0.9},
                  "note": "Froth flotation: sulfides, tellurides, arsenides, graphite and sulfur float. Pine oil frother raises recovery."},
    "magnetic": {"select": ["magnetic"], "weak": ["weak_magnetic"], "recovery": 0.95, "weak_recovery": 0.55, "entrainment": 0.05,
                 "note": "Magnetite is strongly magnetic; ilmenite, chromite, wolframite, hematite and monazite weakly."},
    "gravity": {"min_density": 4.5, "recovery": 0.8, "entrainment": 0.08, "water": 1.0,
                "note": "Shaking table: heavy minerals (cassiterite, wolframite, gold, zircon...) separate from light gangue."},
}


def check():
    ids = {s["id"] for s in SPECIES}
    for s in SPECIES:
        for el in parse(s["formula"]):
            assert el in ELEMENTS, (s["id"], el)
    for r in REACTIONS:
        left, right = r["equation"].split("->")

        def side(text):
            total = {}
            for term in text.split(" + "):
                term = term.strip()
                m = re.match(r"^(\d+(?:\.\d+)?)\s+(.+)$", term)
                n, s = (float(m.group(1)), m.group(2)) if m else (1.0, term)
                assert s in ids, (r["name"], s)
                for el, v in parse(next(x["formula"] for x in SPECIES if x["id"] == s)).items():
                    total[el] = total.get(el, 0) + n * v
            return total
        a, b = side(left), side(right)
        for el in set(a) | set(b):
            assert abs(a.get(el, 0) - b.get(el, 0)) < 1e-6, (r["name"], el, a, b)
    for item, o in ORES.items():
        for s in o["assay"]:
            assert s in ids, (item, s)
        assert abs(sum(o["assay"].values()) - 1) < 0.02, (item, sum(o["assay"].values()))


def main():
    check()
    os.makedirs(BUNDLE, exist_ok=True)
    json.dump(SPECIES, open(os.path.join(BUNDLE, "species.json"), "w"), indent=1)
    json.dump(ORES, open(os.path.join(BUNDLE, "ores.json"), "w"), indent=1)
    for folder in ("reaction", "separation"):
        d = os.path.join(DATA, folder)
        os.makedirs(d, exist_ok=True)
        for f in os.listdir(d):
            os.remove(os.path.join(d, f))
    for r in REACTIONS:
        data = {k: v for k, v in r.items() if v is not None and k != "name"}
        json.dump(data, open(os.path.join(DATA, "reaction", r["name"] + ".json"), "w"), indent=1)
    for k, v in SEPARATIONS.items():
        json.dump(v, open(os.path.join(DATA, "separation", k + ".json"), "w"), indent=1)
    print(f"{len(SPECIES)} species, {len(ORES)} ore/feed items, {len(REACTIONS)} reactions (all balanced)")


if __name__ == "__main__":
    main()
