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


def sp(id, name, kind, density, mp, bp, flags=(), toxicity=0.0, color="#8a8a8a", formula=None, solubility=None):
    """solubility: kg per kg of water at 25 C for "soluble" species (None = unlimited); beyond it the
    excess crystallises (evaporators, precipitation in reactors)."""
    SPECIES.append({"id": id, "name": name, "formula": formula or id, "kind": kind, "density": density, "melting": mp,
                    "boiling": bp, "flags": list(flags), "toxicity": toxicity, "color": color})
    if solubility is not None:
        SPECIES[-1]["solubility"] = solubility


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
sp("KCl", "Sylvite", "halide", 1.98, 770, 1420, flags=["soluble"], color="#e8c8c8", solubility=0.34)
sp("NaCl", "Halite", "halide", 2.16, 801, 1465, flags=["soluble"], color="#efefef", solubility=0.36)
sp("Na2B4O7(H2O)10", "Borax", "sulfate", 1.71, 75, 9999, color="#efefef")
sp("NaNO3", "Nitratine", "sulfate", 2.26, 308, 380, flags=["soluble"], color="#efefef", solubility=0.91)
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
sp("CaSO4", "Anhydrite", "sulfate", 2.97, 1460, 9999, flags=["soluble"], color="#efece4", solubility=0.0024)
sp("CuSO4", "Copper sulfate", "sulfate", 3.6, 590, 9999, flags=["soluble"], color="#2a6aff")
sp("CuO", "Tenorite", "oxide", 6.31, 1326, 2000, color="#1e1e1e")
sp("ZnSO4", "Zinc sulfate", "sulfate", 3.54, 680, 9999, flags=["soluble"], color="#efefef")
sp("NiSO4", "Nickel sulfate", "sulfate", 3.68, 840, 9999, flags=["soluble"], color="#3aa84a")
sp("FeSO4", "Iron(II) sulfate", "sulfate", 3.65, 680, 9999, flags=["soluble"], color="#8ac8a0")
sp("CoSO4", "Cobalt sulfate", "sulfate", 3.71, 735, 9999, flags=["soluble"], color="#c83a6a")
sp("Na2SO4", "Sodium sulfate", "sulfate", 2.66, 884, 1429, flags=["soluble"], color="#f2f2f2", solubility=0.28)
sp("PbSO4", "Anglesite", "sulfate", 6.29, 1087, 9999, color="#e8e8e8")
sp("TiCl4", "Titanium tetrachloride", "halide", 1.73, -24, 136, flags=["toxic"], toxicity=0.6, color="#e8e8a8")
sp("MgCl2", "Magnesium chloride", "halide", 2.32, 714, 1412, flags=["soluble"], color="#f0f0f0", solubility=0.54)
sp("FeCl2", "Iron(II) chloride", "halide", 3.16, 677, 1023, flags=["soluble"], color="#a8c87a")
sp("V2O5", "Vanadium pentoxide", "oxide", 3.36, 690, 1750, flags=["toxic"], toxicity=0.3, color="#c88a2a")
# --- Brines, salts and by-product chemistry (M8c-3a) ---
sp("MgSO4", "Epsomite", "sulfate", 2.66, 1124, 9999, flags=["soluble"], color="#f2f2f2", solubility=0.35)
sp("K2SO4", "Arcanite", "sulfate", 2.66, 1069, 1689, flags=["soluble"], color="#f0ece8", solubility=0.12)
sp("NaBr", "Sodium bromide", "halide", 3.21, 747, 1390, flags=["soluble"], color="#f2f2f2", solubility=0.94)
sp("NaI", "Sodium iodide", "halide", 3.67, 661, 1304, flags=["soluble"], color="#f2f2ea", solubility=1.84)
sp("LiCl", "Lithium chloride", "halide", 2.07, 605, 1382, flags=["soluble"], color="#f2f2f2", solubility=0.84)
sp("SrCl2", "Strontium chloride", "halide", 3.05, 874, 1250, flags=["soluble"], color="#f2f2f2", solubility=0.54)
sp("CaCl2", "Calcium chloride", "halide", 2.15, 772, 1935, flags=["soluble"], color="#f2f2f2", solubility=0.81)
sp("H3BO3", "Sassolite", "oxide", 1.44, 171, 300, flags=["soluble"], color="#f2f2f2", solubility=0.057)
sp("B2O3", "Boron trioxide", "oxide", 2.46, 450, 1860, color="#efefef")
sp("Na2CO3", "Soda ash", "carbonate", 2.54, 851, 1600, flags=["soluble"], color="#f2f2f2", solubility=0.30)
sp("Li2CO3", "Lithium carbonate", "carbonate", 2.11, 723, 1310, flags=["soluble"], color="#f6f6f6", solubility=0.013)
sp("Mg(OH)2", "Brucite", "oxide", 2.34, 350, 9999, color="#efefef")
sp("NH3", "Ammonia", "gas", 0.00073, -78, -33, flags=["soluble", "toxic"], toxicity=0.5, color="#e8e8ff")
sp("CdSO4", "Cadmium sulfate", "sulfate", 4.69, 1000, 9999, flags=["soluble", "toxic"], toxicity=0.6, color="#f2f2f2")
sp("In2(SO4)3", "Indium sulfate", "sulfate", 3.44, 600, 9999, flags=["soluble"], color="#f2f2f2")
sp("NaGa(OH)4", "Sodium gallate", "oxide", 1.6, 1000, 9999, flags=["soluble"], color="#efefef")
sp("SeO2", "Selenium dioxide", "oxide", 3.95, 340, 315, flags=["toxic"], toxicity=0.6, color="#f2f2e0")
sp("H2SeO3", "Selenous acid", "oxide", 3.0, 70, 9999, flags=["soluble", "toxic"], toxicity=0.6, color="#f2f2e8")
sp("TeO2", "Tellurite", "oxide", 5.67, 732, 1245, color="#efefe0")
sp("Na2TeO3", "Sodium tellurite", "oxide", 3.0, 700, 9999, flags=["soluble", "toxic"], toxicity=0.4, color="#f2f2f2")
sp("GeCl4", "Germanium tetrachloride", "halide", 1.88, -49, 83, flags=["toxic"], toxicity=0.5, color="#f0f0e0")
sp("GeO2", "Germanium dioxide", "oxide", 4.23, 1115, 9999, color="#f2f2f2")
sp("Re2O7", "Rhenium heptoxide", "oxide", 6.1, 220, 360, color="#f0e8a0")
sp("HReO4", "Perrhenic acid", "oxide", 3.0, 100, 9999, flags=["soluble"], color="#f2f2e8")
sp("NH4ReO4", "Ammonium perrhenate", "oxide", 3.97, 365, 9999, flags=["soluble"], color="#f6f6f6", solubility=0.062)
sp("Bi2O3", "Bismite", "oxide", 8.9, 817, 1890, color="#f0e060")

KNOWN = {s["id"] for s in SPECIES}

# Elements as species (traces, metals produced by reduction).
BOILING = {"H": -253, "He": -269, "N": -196, "O": -183, "F": -188, "Ne": -246, "Cl": -34, "Ar": -186, "Kr": -153, "Xe": -108,
           "Rn": -62, "Br": 59, "I": 184, "Hg": 357, "Cd": 767, "Zn": 907, "As": 615, "Se": 685, "Te": 988, "S": 445, "P": 280,
           "Na": 883, "K": 759, "Rb": 688, "Cs": 671, "Li": 1342, "Mg": 1091, "Ca": 1484, "Sr": 1377, "Ba": 1845, "Tl": 1473,
           "Pb": 1749, "Bi": 1564, "Sb": 1587, "In": 2072, "Ga": 2400, "Sn": 2602, "Ag": 2162, "Cu": 2562, "Au": 2856,
           "Al": 2470, "Mn": 2061, "Cr": 2671, "Fe": 2861, "Co": 2927, "Ni": 2913, "Po": 962, "At": 337, "Fr": 677, "Ra": 1737}
# Radioactivity (M8c-3a): specific activity of the element as it occurs (Bq per kg, natural isotopic
# mix: K-40, Rb-87, U-238/234/235, Th-232...; longest-lived isotope for the others) and an external
# dose-rate constant (Sv m^2 per Bq per hour) including short-lived daughters where they build up
# within days (Ra-226 + Rn/Pb/Bi progeny, Th with its chain, Rn with progeny). Pure alpha emitters
# (Po-210, Pu-239) are near zero outside the body; they matter when breathed in.
ACTIVITY = {"K": (3.1e4, 2.1e-14), "Rb": (8.9e5, 1e-17), "Sm": (1.27e5, 1e-18), "Lu": (5.2e4, 6e-14), "Re": (1.0e3, 1e-18),
            "Th": (4.07e6, 1.0e-13), "U": (2.54e7, 2.0e-15), "Ra": (3.66e13, 2.4e-13), "Rn": (5.7e18, 2.4e-13),
            "Po": (1.66e17, 1e-18), "Ac": (2.68e15, 1.0e-13), "Pa": (1.75e12, 5.0e-14), "Tc": (6.3e11, 1e-17),
            "Pm": (3.4e16, 1e-17), "Np": (2.6e10, 3.0e-14), "Pu": (2.3e12, 1e-17), "Fr": (1.4e21, 1.0e-13),
            "At": (2.5e20, 1.0e-13)}
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
    if sym in ACTIVITY:
        SPECIES[-1]["activity"] = ACTIVITY[sym][0]
        SPECIES[-1]["gamma"] = ACTIVITY[sym][1]

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
    "starwake:raw_uraninite": ore({"UO2": 1.0}, 0.7, {"Ra": 0.3, "Rn": 0.000002, "Po": 0.0001, "Pa": 0.3, "Th": 3000, "Pb": 20000, "Np": 0.000002,
                                                      "Pu": 0.00001, "Tc": 0.0000005, "Ac": 0.00015, "Pm": 4e-12, "At": 1e-14,
                                                      "Fr": 1e-14}),
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
    # Brines (M8c-3a), 1 kg of solution, what a Water Pump gives from the sea or a salt lake.
    # Seawater: 35 g/kg salinity as the salts it crystallises to, plus Br, I, B, Li, Sr and trace U.
    "starwake:seawater": {"mass": 1.0, "assay": {"H2O": 0.965, "NaCl": 0.0272, "MgCl2": 0.0038, "MgSO4": 0.0017, "CaSO4": 0.0013,
                                                 "K2SO4": 0.00086, "CaCO3": 0.00012, "NaBr": 0.000084, "H3BO3": 0.000026,
                                                 "SrCl2": 0.000015, "LiCl": 0.0000010, "NaI": 0.00000007, "U": 0.0000000033}},
    # Salt-lake brine (Atacama-type): near-saturated NaCl with potash, magnesium, lithium and boron.
    "starwake:salt_lake_brine": {"mass": 1.0, "assay": {"H2O": 0.715, "NaCl": 0.2, "KCl": 0.03, "MgCl2": 0.025, "Na2SO4": 0.012,
                                                        "LiCl": 0.0095, "H3BO3": 0.004, "CaSO4": 0.002, "NaBr": 0.0005,
                                                        "Rb": 0.00002, "Cs": 0.000002}},
    # Acid crater-lake water (Kawah Ijen-type, pH ~0) on toxic worlds.
    "starwake:acid_lake_water": {"mass": 1.0, "assay": {"H2O": 0.88, "H2SO4": 0.065, "HCl": 0.02, "FeSO4": 0.015, "NaCl": 0.012,
                                                        "ZnSO4": 0.003, "CuSO4": 0.002, "MgSO4": 0.003}},
    # Natural gas (M8c-3b) from a Gas Well on a gas field: methane with ethane, N2, CO2, H2S and helium.
    "starwake:natural_gas": {"mass": 1.0, "assay": {"CH4": 0.87, "C2H6": 0.07, "N2": 0.035, "CO2": 0.02, "He": 0.003, "H2S": 0.002}},
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


def rx(machine, name, equation, priority, note=None, draw=None, electrons=None, voltage=None, catalyst=None, anhydrous=None):
    """draw: reactants the machine may feed from its reagent slots, inlet tank or the air (O2).
    electrons/voltage: electrolysis; energy per mole of reaction = electrons x Faraday x voltage.
    catalyst: an element or species that must be present in the catalyst slot (not consumed)."""
    REACTIONS.append({"name": name, "machine": machine, "equation": equation, "priority": priority, "draw": draw,
                      "electrons": electrons, "voltage": voltage, "catalyst": catalyst, "anhydrous": anhydrous, "note": note})


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
   draw=["C"], electrons=12, voltage=4.2, catalyst="Na3AlF6", anhydrous=True)
rx("electrolysis", "magnesium_chloride", "MgCl2 -> Mg + Cl2", 11, "Molten magnesium chloride (Dow / Kroll recycle).", electrons=2, voltage=3.2,
   anhydrous=True)
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

# --- M8c-3a: brines, alkali and alkaline-earth metals, by-product recovery ---
# Molten-salt cells ("anhydrous": skipped while the charge holds water, so brine goes to chlor-alkali).
rx("electrolysis", "downs_sodium", "2 NaCl -> 2 Na + Cl2", 12, "Downs cell: molten salt gives sodium and chlorine.", electrons=2, voltage=7.0,
   anhydrous=True)
rx("electrolysis", "lithium_chloride", "2 LiCl -> 2 Li + Cl2", 13, "Molten LiCl-KCl cell (450 C).", electrons=2, voltage=7.0, anhydrous=True)
rx("electrolysis", "calcium_chloride", "CaCl2 -> Ca + Cl2", 14, "Molten calcium chloride.", electrons=2, voltage=6.0, anhydrous=True)
rx("electrolysis", "strontium_chloride", "SrCl2 -> Sr + Cl2", 15, electrons=2, voltage=6.5, anhydrous=True)
rx("electrolysis", "electrowin_gallium", "4 NaGa(OH)4 -> 4 Ga + 4 NaOH + 3 O2 + 6 H2O", 34,
   "Gallium plates out of concentrated Bayer liquor.", electrons=12, voltage=4.0)
rx("electrolysis", "electrowin_tellurium", "Na2TeO3 + H2O -> Te + 2 NaOH + O2", 35, "Tellurium from caustic leach liquor.",
   draw=["H2O"], electrons=4, voltage=2.5)
# Reactor: ammonia, soda ash, lithium carbonate, magnesium removal, halogens, cementation, selenium.
rx("reactor", "haber_ammonia", "N2 + 3 H2 -> 2 NH3", 40, "Haber-Bosch: needs an iron catalyst.", draw=["H2"], catalyst="Fe")
rx("reactor", "soda_ash", "2 NaOH + CO2 -> Na2CO3 + H2O", 41, "Carbonating caustic soda.", draw=["NaOH"])
rx("reactor", "brucite_precipitation", "MgCl2 + 2 NaOH -> Mg(OH)2 + 2 NaCl", 42, "Magnesium removed from brine as brucite.", draw=["NaOH"])
rx("reactor", "lithium_carbonate", "2 LiCl + Na2CO3 -> Li2CO3 + 2 NaCl", 43, "Soda ash precipitates lithium carbonate.", draw=["Na2CO3"])
rx("reactor", "bromine", "2 NaBr + Cl2 -> 2 NaCl + 2 Br", 44, "Chlorine displaces bromine from bittern.", draw=["Cl2"])
rx("reactor", "iodine", "2 NaI + Cl2 -> 2 NaCl + 2 I", 45, "Chlorine displaces iodine.", draw=["Cl2"])
rx("reactor", "cement_copper", "CuSO4 + Zn -> ZnSO4 + Cu", 50, "Zinc dust cements copper out of zinc liquor.", draw=["Zn"])
rx("reactor", "cement_cadmium", "CdSO4 + Zn -> ZnSO4 + Cd", 51, "Zinc dust cements cadmium.", draw=["Zn"])
rx("reactor", "cement_indium", "In2(SO4)3 + 3 Zn -> 3 ZnSO4 + 2 In", 52, "Zinc dust cements indium.", draw=["Zn"])
rx("reactor", "reduce_selenous_acid", "H2SeO3 + 2 SO2 + H2O -> Se + 2 H2SO4", 53, "Sulfur dioxide precipitates red selenium.",
   draw=["SO2", "H2O"])
rx("reactor", "hydrolyse_germanium", "GeCl4 + 2 H2O -> GeO2 + 4 HCl", 54, "Germanium tetrachloride hydrolyses to the oxide.", draw=["H2O"])
rx("reactor", "ammonium_perrhenate", "HReO4 + NH3 -> NH4ReO4", 55, "Ammonia crystallises ammonium perrhenate.", draw=["NH3"])
# Leach: by-product dissolution.
rx("leach", "leach_cadmium", "Cd + H2SO4 -> CdSO4 + H2", 30, draw=["H2SO4"])
rx("leach", "leach_indium", "2 In + 3 H2SO4 -> In2(SO4)3 + 3 H2", 31, draw=["H2SO4"])
rx("leach", "leach_gallium", "4 Ga + 4 NaOH + 3 O2 + 6 H2O -> 4 NaGa(OH)4", 32, "Gallium follows aluminium into Bayer liquor.",
   draw=["NaOH", "O2", "H2O"])
rx("leach", "leach_selenium_dioxide", "SeO2 + H2O -> H2SeO3", 33, draw=["H2O"])
rx("leach", "leach_tellurite", "TeO2 + 2 NaOH -> Na2TeO3 + H2O", 34, "Caustic leach of roasted slimes.", draw=["NaOH"])
rx("leach", "leach_rhenium", "Re2O7 + H2O -> 2 HReO4", 35, "Rhenium heptoxide from flue dust dissolves in water.", draw=["H2O"])
rx("leach", "leach_lithium_carbonate", "Li2CO3 + 2 HCl -> 2 LiCl + H2O + CO2", 36, draw=["HCl"])
rx("leach", "leach_magnesia_hcl", "MgO + 2 HCl -> MgCl2 + H2O", 37, "Hydrochloric acid washes magnesia out of crude boron.", draw=["HCl"])
# Roaster: volatile oxides to the flue, hydrogen reductions (inlet H2).
rx("roaster", "roast_selenium", "Se + O2 -> SeO2", 20, "Selenium dioxide sublimes into the flue dust.", draw=["O2"])
rx("roaster", "roast_tellurium", "Te + O2 -> TeO2", 21, draw=["O2"])
rx("roaster", "roast_rhenium", "4 Re + 7 O2 -> 2 Re2O7", 22, "Rhenium leaves molybdenite roasting as volatile heptoxide.", draw=["O2"])
rx("roaster", "dehydrate_boric_acid", "2 H3BO3 -> B2O3 + 3 H2O", 34)
rx("roaster", "reduce_molybdite_h2", "MoO3 + 3 H2 -> Mo + 3 H2O", 50, "Hydrogen reduction to molybdenum powder.", draw=["H2"])
rx("roaster", "reduce_germania_h2", "GeO2 + 2 H2 -> Ge + 2 H2O", 51, draw=["H2"])
rx("roaster", "reduce_perrhenate_h2", "2 NH4ReO4 + 7 H2 -> 2 Re + 2 NH3 + 8 H2O", 52, "Rhenium metal powder.", draw=["H2"])
# Chlorinator: germanium.
rx("chlorinator", "chlorinate_germanium", "Ge + 2 Cl2 -> GeCl4", 13, "Germanium tetrachloride distils off.", draw=["Cl2"])
# Kroll reactor: magnesium and sodium reductions.
rx("kroll", "reduce_boron_trioxide", "B2O3 + 3 Mg -> 2 B + 3 MgO", 11, "Moissan process: crude boron in magnesia.", draw=["Mg"])
rx("kroll", "reduce_potassium", "KCl + Na -> NaCl + K", 12, "Sodium reduction; potassium distils off.", draw=["Na"])
# Converter: antimony and arsenic oxidise out of metal (softening).
rx("converter", "soften_antimony", "4 Sb + 3 O2 -> 2 Sb2O3", 30, "Softening: antimony oxidises into the slag.", draw=["O2"])
rx("converter", "soften_arsenic", "4 As + 3 O2 -> 2 As2O3", 31, "Arsenic leaves as toxic As2O3 fume.", draw=["O2"])
# Smelter: by-product oxides back to metal.
rx("smelter", "reduce_senarmontite", "Sb2O3 + 3 C -> 2 Sb + 3 CO", 26, draw=["C"])
rx("smelter", "reduce_arsenolite", "As2O3 + 3 C -> 2 As + 3 CO", 27, "Arsenic sublimes and condenses as grey arsenic.", draw=["C"])
rx("smelter", "reduce_bismite", "Bi2O3 + 3 C -> 2 Bi + 3 CO", 28, draw=["C"])

# --- M8c-3b: rare earths, PGMs, actinides and the element audit ---------------------------------------
REE = ["La", "Ce", "Pr", "Nd", "Sm", "Eu", "Gd", "Tb", "Dy", "Ho", "Y", "Er", "Tm", "Yb", "Lu", "Sc"]
for re_ in REE:
    nm = ELEMENTS[re_]["name"].capitalize()
    sp(f"{re_}Cl3", f"{nm} chloride", "halide", 3.9, 800, 1500, flags=["soluble"], color="#f2f2ee")
    sp(f"{re_}(OH)3", f"{nm} hydroxide", "oxide", 4.5, 300, 9999, color="#efefe6")
    sp(f"{re_}2(CO3)3", f"{nm} carbonate", "carbonate", 4.0, 300, 9999, color="#f2f2ea")
    sp(f"{re_}2O3", f"{nm} oxide", "oxide", 7.0, 2300, 3700, color="#f0eee0")
    sp(f"{re_}F3", f"{nm} fluoride", "halide", 6.0, 1400, 2300, color="#eeeee8")
sp("Na3PO4", "Trisodium phosphate", "phosphate", 2.54, 1583, 9999, flags=["soluble"], color="#f2f2f2", solubility=0.14)
sp("NaF", "Villiaumite", "halide", 2.56, 993, 1704, flags=["soluble", "toxic"], toxicity=0.3, color="#efefef", solubility=0.04)
sp("KF", "Potassium fluoride", "halide", 2.48, 858, 1502, flags=["soluble", "toxic"], toxicity=0.3, color="#efefef")
sp("LiF", "Griceite", "halide", 2.64, 845, 1676, color="#f2f2f2")
sp("MgF2", "Sellaite", "halide", 3.15, 1263, 2260, color="#efefef")
sp("F2", "Fluorine", "gas", 0.0017, -220, -188, flags=["toxic"], toxicity=1.0, color="#e8f088")
sp("H2S", "Hydrogen sulfide", "gas", 0.0014, -82, -60, flags=["toxic"], toxicity=0.8, color="#e8e8a0")
sp("C2H6", "Ethane", "gas", 0.0013, -183, -89, color="#d8e8ff")
sp("HNO3", "Nitric acid", "oxide", 1.51, -42, 83, flags=["soluble", "toxic"], toxicity=0.6, color="#f0e8b0")
sp("NO", "Nitric oxide", "gas", 0.0013, -164, -152, flags=["toxic"], toxicity=0.5, color="#e8d8c8")
sp("NO2", "Nitrogen dioxide", "gas", 0.0019, -11, 21, flags=["toxic"], toxicity=0.8, color="#c86a2a")
sp("NH4Cl", "Sal ammoniac", "halide", 1.52, 338, 520, flags=["soluble"], color="#f2f2f2", solubility=0.37)
sp("NH4F", "Ammonium fluoride", "halide", 1.01, 100, 9999, flags=["soluble", "toxic"], toxicity=0.3, color="#f2f2f2")
sp("(NH4)2SO4", "Mascagnite", "sulfate", 1.77, 235, 9999, flags=["soluble"], color="#f2f2f2", solubility=0.75)
sp("H2PtCl6", "Chloroplatinic acid", "halide", 2.43, 60, 9999, flags=["soluble", "toxic"], toxicity=0.4, color="#e8a830")
sp("H2PdCl4", "Chloropalladic acid", "halide", 2.5, 100, 9999, flags=["soluble"], color="#a85a2a")
sp("HAuCl4", "Chloroauric acid", "halide", 3.9, 254, 9999, flags=["soluble", "toxic"], toxicity=0.3, color="#e8c830")
sp("AgCl", "Chlorargyrite", "halide", 5.56, 455, 1547, color="#e8e8e0")
sp("(NH4)2PtCl6", "Ammonium chloroplatinate", "halide", 3.07, 380, 9999, flags=["soluble"], color="#e8d838", solubility=0.005)
sp("Pd(NH3)4Cl2", "Tetraamminepalladium chloride", "halide", 1.9, 120, 9999, flags=["soluble"], color="#f0f0e8")
sp("Pd(NH3)2Cl2", "Diamminepalladium dichloride", "halide", 2.5, 200, 9999, color="#e8d870")
sp("OsO4", "Osmium tetroxide", "gas", 4.9, 40, 130, flags=["toxic"], toxicity=1.0, color="#f0f0d8")
sp("RuO4", "Ruthenium tetroxide", "gas", 3.29, 25, 40, flags=["toxic"], toxicity=0.9, color="#e8c040")
sp("Na3RhCl6", "Sodium hexachlororhodate", "halide", 2.8, 500, 9999, flags=["soluble"], color="#c83a5a")
sp("Na2IrCl6", "Sodium hexachloroiridate", "halide", 3.0, 500, 9999, flags=["soluble"], color="#5a2a1e")
sp("(NH4)2IrCl6", "Ammonium hexachloroiridate", "halide", 2.86, 300, 9999, flags=["soluble"], color="#3a1a14", solubility=0.007)
sp("ZnCl2", "Zinc chloride", "halide", 2.91, 290, 732, flags=["soluble"], color="#f2f2f2")
sp("UO2SO4", "Uranyl sulfate", "sulfate", 3.28, 1000, 9999, flags=["soluble", "toxic"], toxicity=0.4, color="#e8e830")
sp("(NH4)2U2O7", "Ammonium diuranate (yellowcake)", "oxide", 4.0, 300, 9999, flags=["toxic"], toxicity=0.4, color="#f0d820")
sp("UO3", "Uranium trioxide", "oxide", 7.3, 200, 9999, flags=["toxic"], toxicity=0.4, color="#e8a820")
sp("UF4", "Uranium tetrafluoride (green salt)", "halide", 6.7, 1036, 1417, flags=["toxic"], toxicity=0.4, color="#4a8a3a")
sp("ThF4", "Thorium tetrafluoride", "halide", 6.3, 1110, 1680, color="#efefef")
sp("RaSO4", "Radium sulfate", "sulfate", 4.5, 1000, 9999, color="#f2f2f2")
sp("RaS", "Radium sulfide", "sulfide", 4.0, 1200, 9999, color="#efefe8")
sp("RaCl2", "Radium chloride", "halide", 4.9, 900, 9999, flags=["soluble"], color="#f2f2f2")
sp("BeSO4", "Beryllium sulfate", "sulfate", 2.44, 1127, 9999, flags=["soluble", "toxic"], toxicity=0.6, color="#f2f2f2")
sp("Al2(SO4)3", "Aluminium sulfate", "sulfate", 2.67, 770, 9999, flags=["soluble"], color="#f2f2f2", solubility=0.36)
sp("Be(OH)2", "Beryllium hydroxide", "oxide", 1.92, 400, 9999, flags=["toxic"], toxicity=0.6, color="#efefef")
sp("BeF2", "Beryllium fluoride", "halide", 1.99, 552, 1169, flags=["toxic"], toxicity=0.7, color="#efefef")
sp("AlCl3", "Aluminium chloride", "halide", 2.48, 192, 180, flags=["soluble"], color="#f2f2e8", solubility=0.45)
sp("CsCl", "Caesium chloride", "halide", 3.99, 646, 1297, flags=["soluble"], color="#f2f2f2", solubility=1.9)
sp("RbCl", "Rubidium chloride", "halide", 2.8, 718, 1390, flags=["soluble"], color="#f2f2f2", solubility=0.91)
sp("SrS", "Strontium sulfide", "sulfide", 3.7, 2002, 9999, color="#efefe8")
sp("BaS", "Barium sulfide", "sulfide", 4.25, 2235, 9999, color="#efefe8")
sp("BaCl2", "Barium chloride", "halide", 3.86, 962, 1560, flags=["soluble", "toxic"], toxicity=0.5, color="#f2f2f2", solubility=0.36)
sp("ZrCl4", "Zirconium tetrachloride", "halide", 2.8, 437, 331, color="#f2f2f2")
sp("HfCl4", "Hafnium tetrachloride", "halide", 3.89, 432, 317, color="#f2f2f2")
sp("SiCl4", "Silicon tetrachloride", "halide", 1.48, -69, 58, flags=["toxic"], toxicity=0.3, color="#f0f0f0")
sp("ZrOCl2", "Zirconyl chloride", "halide", 1.9, 400, 9999, flags=["soluble"], color="#f2f2f2")
sp("HfOCl2", "Hafnyl chloride", "halide", 2.5, 400, 9999, flags=["soluble"], color="#f2f2f2")
sp("H2NbOF5", "Oxyfluoroniobic acid", "halide", 2.0, 100, 9999, flags=["soluble", "toxic"], toxicity=0.5, color="#f0f0f0")
sp("H2TaF7", "Fluorotantalic acid", "halide", 2.5, 100, 9999, flags=["soluble", "toxic"], toxicity=0.5, color="#f0f0f0")
sp("FeF2", "Iron(II) fluoride", "halide", 4.09, 970, 1100, flags=["soluble"], color="#d8d8c8")
sp("MnF2", "Manganese fluoride", "halide", 3.98, 856, 1820, flags=["soluble"], color="#e8c8c8")
sp("K2TaF7", "Potassium heptafluorotantalate", "halide", 5.24, 630, 9999, flags=["soluble"], color="#f2f2f2", solubility=0.005)
sp("Nb2O5", "Niobium pentoxide", "oxide", 4.6, 1512, 9999, color="#f2f2f2")
sp("Na2WO4", "Sodium tungstate", "oxide", 4.18, 698, 9999, flags=["soluble"], color="#f2f2f2")
sp("Fe(OH)2", "Iron(II) hydroxide", "oxide", 3.4, 150, 9999, color="#6a7a5a")
sp("Mn(OH)2", "Pyrochroite", "oxide", 3.26, 140, 9999, color="#d8c8b8")
sp("H2WO4", "Tungstic acid", "oxide", 5.5, 100, 9999, color="#e8e030")
sp("WO3", "Tungsten trioxide", "oxide", 7.16, 1473, 1700, color="#d8e030")
sp("NaVO3", "Sodium metavanadate", "oxide", 2.84, 630, 9999, flags=["soluble", "toxic"], toxicity=0.3, color="#f0e8c0")
sp("NH4VO3", "Ammonium metavanadate", "oxide", 2.33, 200, 9999, flags=["soluble", "toxic"], toxicity=0.3, color="#f2f2e0", solubility=0.005)
sp("Na2CrO4", "Sodium chromate", "oxide", 2.72, 792, 9999, flags=["soluble", "toxic"], toxicity=0.8, color="#f0d820")
sp("Na2Cr2O7", "Sodium dichromate", "oxide", 2.52, 357, 400, flags=["soluble", "toxic"], toxicity=0.8, color="#e87a20")
sp("Cr2O3", "Eskolaite", "oxide", 5.22, 2435, 4000, color="#3a8a3a")
sp("Tc2O7", "Technetium heptoxide", "oxide", 3.5, 120, 311, color="#f0e8a0")
sp("HTcO4", "Pertechnetic acid", "oxide", 3.0, 100, 9999, flags=["soluble"], color="#f2f2e8")
sp("NH4TcO4", "Ammonium pertechnetate", "oxide", 2.7, 300, 9999, flags=["soluble"], color="#f2f2f2", solubility=0.06)
sp("Tl2SO4", "Thallium sulfate", "sulfate", 6.77, 632, 9999, flags=["soluble", "toxic"], toxicity=0.9, color="#f2f2f2", solubility=0.048)

# Rare earths: phosphate cracking, bastnaesite roasting, HCl dissolution, SX (solvent_extraction.json),
# carbonate precipitation, calcining, then metal: fluoride electrolysis (La-Nd) or Ca reduction.
for re_ in ["La", "Ce", "Pr", "Nd", "Y"]:
    rx("leach", f"crack_{re_.lower()}po4", f"{re_}PO4 + 3 NaOH -> {re_}(OH)3 + Na3PO4", 40,
       "Caustic cracking of monazite/xenotime: rare-earth hydroxides stay, phosphate dissolves.", draw=["NaOH"])
for re_ in ["Ce", "La"]:
    rx("roaster", f"roast_{re_.lower()}co3f", f"3 {re_}CO3F -> {re_}F3 + {re_}2O3 + 3 CO2", 60, "Bastnaesite decomposes to oxide and fluoride.")
for re_ in REE:
    lo = re_.lower()
    rx("leach", f"dissolve_{lo}_hydroxide", f"{re_}(OH)3 + 3 HCl -> {re_}Cl3 + 3 H2O", 41, draw=["HCl"])
    rx("leach", f"dissolve_{lo}_oxide", f"{re_}2O3 + 6 HCl -> 2 {re_}Cl3 + 3 H2O", 42, draw=["HCl"])
    rx("leach", f"dissolve_{lo}_metal", f"2 {re_} + 6 HCl -> 2 {re_}Cl3 + 3 H2", 43, draw=["HCl"])
    rx("leach", f"convert_{lo}_fluoride", f"{re_}F3 + 3 NaOH -> {re_}(OH)3 + 3 NaF", 39, draw=["NaOH"])
    rx("reactor", f"precipitate_{lo}_carbonate", f"2 {re_}Cl3 + 3 Na2CO3 -> {re_}2(CO3)3 + 6 NaCl", 60, "Soda ash precipitates rare-earth carbonate.",
       draw=["Na2CO3"])
    rx("reactor", f"fluorinate_{lo}_oxide", f"{re_}2O3 + 6 HF -> 2 {re_}F3 + 3 H2O", 61, draw=["HF"])
    rx("roaster", f"calcine_{lo}_carbonate", f"{re_}2(CO3)3 -> {re_}2O3 + 3 CO2", 61)
    rx("kroll", f"reduce_{lo}_fluoride", f"2 {re_}F3 + 3 Ca -> 2 {re_} + 3 CaF2", 20, "Calciothermic reduction (heavy rare earths, Y, Sc).",
       draw=["Ca"])
for re_ in ["La", "Ce", "Pr", "Nd"]:
    rx("electrolysis", f"electrolyse_{re_.lower()}_oxide", f"2 {re_}2O3 + 3 C -> 4 {re_} + 3 CO2", 40,
       "Oxide dissolved in a molten fluoride bath (LiF), carbon anode.", draw=["C"], electrons=12, voltage=6.0, catalyst="LiF",
       anhydrous=True)
rx("reactor", "lithium_fluoride", "Li2CO3 + 2 HF -> 2 LiF + CO2 + H2O", 62, draw=["HF"])

# Nitric acid (Ostwald, platinum gauze catalyst) and ammonium chloride.
rx("reactor", "ostwald_oxidation", "4 NH3 + 5 O2 -> 4 NO + 6 H2O", 70, "Ostwald process over platinum.", draw=["O2"], catalyst="Pt")
rx("reactor", "nitric_oxide_oxidation", "2 NO + O2 -> 2 NO2", 71, draw=["O2"])
rx("reactor", "nitric_absorption", "3 NO2 + H2O -> 2 HNO3 + NO", 72, draw=["H2O"])
rx("reactor", "ammonium_chloride", "NH3 + HCl -> NH4Cl", 73, draw=["HCl"])
# Platinum-group metals and gold: roast arsenide, aqua regia, selective precipitation, distillation.
rx("roaster", "roast_sperrylite", "2 PtAs2 + 3 O2 -> 2 Pt + 2 As2O3", 23, "Arsenic fumes off; platinum sponge stays.", draw=["O2"])
rx("leach", "aqua_regia_platinum", "3 Pt + 4 HNO3 + 18 HCl -> 3 H2PtCl6 + 4 NO + 8 H2O", 50, "Aqua regia dissolves platinum.", draw=["HNO3", "HCl"])
rx("leach", "aqua_regia_palladium", "3 Pd + 2 HNO3 + 12 HCl -> 3 H2PdCl4 + 2 NO + 4 H2O", 51, draw=["HNO3", "HCl"])
rx("leach", "aqua_regia_gold", "Au + HNO3 + 4 HCl -> HAuCl4 + NO + 2 H2O", 52, draw=["HNO3", "HCl"])
rx("leach", "aqua_regia_silver", "3 Ag + HNO3 + 3 HCl -> 3 AgCl + NO + 2 H2O", 53, "Silver stays behind as chloride.", draw=["HNO3", "HCl"])
rx("reactor", "precipitate_gold", "2 HAuCl4 + 3 SO2 + 6 H2O -> 2 Au + 3 H2SO4 + 8 HCl", 80, "Sulfur dioxide reduces gold first.", draw=["SO2", "H2O"])
rx("reactor", "precipitate_chloroplatinate", "H2PtCl6 + 2 NH4Cl -> (NH4)2PtCl6 + 2 HCl", 81, "Yellow ammonium chloroplatinate.", draw=["NH4Cl"])
rx("reactor", "palladium_tetraammine", "H2PdCl4 + 6 NH3 -> Pd(NH3)4Cl2 + 2 NH4Cl", 82, draw=["NH3"])
rx("reactor", "palladium_diammine", "Pd(NH3)4Cl2 + 2 HCl -> Pd(NH3)2Cl2 + 2 NH4Cl", 83, "Acidifying precipitates yellow Pd(NH3)2Cl2.", draw=["HCl"])
rx("reactor", "distil_osmium", "Os + 4 Cl2 + 8 NaOH -> OsO4 + 8 NaCl + 4 H2O", 84, "Oxidative distillation: very toxic OsO4 vapour.",
   draw=["Cl2", "NaOH"])
rx("reactor", "distil_ruthenium", "Ru + 4 Cl2 + 8 NaOH -> RuO4 + 8 NaCl + 4 H2O", 85, "Toxic, explosive RuO4 vapour.", draw=["Cl2", "NaOH"])
rx("reactor", "precipitate_iridium", "Na2IrCl6 + 2 NH4Cl -> (NH4)2IrCl6 + 2 NaCl", 86, "Rhodium stays in solution.", draw=["NH4Cl"])
rx("reactor", "cement_rhodium", "2 Na3RhCl6 + 3 Zn -> 2 Rh + 3 ZnCl2 + 6 NaCl", 87, draw=["Zn"])
rx("reactor", "cement_silver_chloride", "2 AgCl + Zn -> 2 Ag + ZnCl2", 88, draw=["Zn"])
rx("chlorinator", "chlorinate_rhodium", "2 Rh + 3 Cl2 + 6 NaCl -> 2 Na3RhCl6", 20, "Chlorinating with salt makes soluble chlororhodate.", draw=["Cl2", "NaCl"])
rx("chlorinator", "chlorinate_iridium", "Ir + 2 Cl2 + 2 NaCl -> Na2IrCl6", 21, draw=["Cl2", "NaCl"])
rx("roaster", "ignite_chloroplatinate", "3 (NH4)2PtCl6 -> 3 Pt + 2 NH4Cl + 16 HCl + 2 N2", 70, "Platinum sponge.")
rx("roaster", "reduce_palladium_salt", "Pd(NH3)2Cl2 + H2 -> Pd + 2 NH4Cl", 71, draw=["H2"])
rx("roaster", "reduce_iridium_salt", "(NH4)2IrCl6 + 2 H2 -> Ir + 2 NH4Cl + 4 HCl", 72, draw=["H2"])
rx("kroll", "reduce_osmium_tetroxide", "OsO4 + 4 H2 -> Os + 4 H2O", 30, draw=["H2"])
rx("kroll", "reduce_ruthenium_tetroxide", "RuO4 + 4 H2 -> Ru + 4 H2O", 31, draw=["H2"])

# Uranium (acid leach -> yellowcake -> UO2 -> UF4 -> Mg reduction), thorium, radium.
rx("leach", "leach_uraninite", "2 UO2 + O2 + 2 H2SO4 -> 2 UO2SO4 + 2 H2O", 60, "Oxidising acid leach; radium stays in the tailings.",
   draw=["O2", "H2SO4"])
rx("leach", "sulfate_radium", "Ra + H2SO4 -> RaSO4 + H2", 61, draw=["H2SO4"])
rx("reactor", "yellowcake", "2 UO2SO4 + 6 NH3 + 3 H2O -> (NH4)2U2O7 + 2 (NH4)2SO4", 90, "Ammonia precipitates ammonium diuranate.",
   draw=["NH3", "H2O"])
rx("roaster", "calcine_yellowcake", "2 (NH4)2U2O7 -> 4 UO3 + 4 NH3 + 2 H2O", 73)
rx("roaster", "reduce_uranium_trioxide", "UO3 + H2 -> UO2 + H2O", 74, draw=["H2"])
rx("reactor", "hydrofluorinate_uranium", "UO2 + 4 HF -> UF4 + 2 H2O", 91, "Green salt.", draw=["HF"])
rx("reactor", "hydrofluorinate_thorium", "ThO2 + 4 HF -> ThF4 + 2 H2O", 92, draw=["HF"])
rx("kroll", "reduce_uranium", "UF4 + 2 Mg -> U + 2 MgF2", 32, "Magnesiothermic uranium metal.", draw=["Mg"])
rx("kroll", "reduce_thorium", "ThF4 + 2 Ca -> Th + 2 CaF2", 33, draw=["Ca"])
rx("smelter", "reduce_radium_sulfate", "RaSO4 + 4 C -> RaS + 4 CO", 30, draw=["C"])
rx("leach", "dissolve_radium_sulfide", "RaS + 2 HCl -> RaCl2 + H2S", 62, draw=["HCl"])
rx("electrolysis", "radium_chloride", "RaCl2 -> Ra + Cl2", 16, electrons=2, voltage=6.0, anhydrous=True)
rx("electrolysis", "fluorine", "2 HF -> H2 + F2", 17, "Anhydrous HF electrolysis in molten KF.HF.", electrons=2, voltage=9.0, catalyst="KF",
   anhydrous=True)
# Beryllium, silicon, phosphorus, caesium, rubidium, strontium, barium.
rx("leach", "leach_beryl", "Be3Al2Si6O18 + 6 H2SO4 -> 3 BeSO4 + Al2(SO4)3 + 6 SiO2 + 6 H2O", 63, "Sulfate process (after melting and quenching beryl).",
   draw=["H2SO4"])
rx("reactor", "precipitate_beryllium", "BeSO4 + 2 NH3 + 2 H2O -> Be(OH)2 + (NH4)2SO4", 93, draw=["NH3", "H2O"])
rx("reactor", "precipitate_alumina_sulfate", "Al2(SO4)3 + 6 NH3 + 6 H2O -> 2 Al(OH)3 + 3 (NH4)2SO4", 94, draw=["NH3", "H2O"])
rx("reactor", "fluorinate_beryllium", "Be(OH)2 + 2 HF -> BeF2 + 2 H2O", 95, draw=["HF"])
rx("kroll", "reduce_beryllium", "BeF2 + Mg -> Be + MgF2", 34, draw=["Mg"])
rx("kroll", "reduce_silica", "SiO2 + 2 Mg -> Si + 2 MgO", 35, "Magnesiothermic silicon; wash the magnesia out with HCl.", draw=["Mg"])
rx("smelter", "phosphorus_furnace", "2 Ca5(PO4)3F + 15 C + 9 SiO2 -> 6 P + 15 CO + 9 CaSiO3 + CaF2", 31, "Electric phosphorus furnace: P vapour condenses.",
   draw=["C", "SiO2"])
rx("leach", "leach_pollucite", "2 CsAlSi2O6 + 8 HCl -> 2 CsCl + 2 AlCl3 + 4 SiO2 + 4 H2O", 64, draw=["HCl"])
rx("leach", "dissolve_rubidium", "2 Rb + 2 HCl -> 2 RbCl + H2", 65, draw=["HCl"])
rx("kroll", "reduce_caesium", "2 CsCl + Ca -> 2 Cs + CaCl2", 36, "Calcium reduction; caesium distils.", draw=["Ca"])
rx("kroll", "reduce_rubidium", "2 RbCl + Ca -> 2 Rb + CaCl2", 37, draw=["Ca"])
rx("smelter", "black_ash_strontium", "SrSO4 + 4 C -> SrS + 4 CO", 32, "Black-ash process.", draw=["C"])
rx("smelter", "black_ash_barium", "BaSO4 + 4 C -> BaS + 4 CO", 33, draw=["C"])
rx("leach", "dissolve_strontium_sulfide", "SrS + 2 HCl -> SrCl2 + H2S", 66, draw=["HCl"])
rx("leach", "dissolve_barium_sulfide", "BaS + 2 HCl -> BaCl2 + H2S", 67, draw=["HCl"])
rx("electrolysis", "barium_chloride", "BaCl2 -> Ba + Cl2", 18, electrons=2, voltage=6.5, anhydrous=True)
# Zirconium and hafnium.
rx("chlorinator", "chlorinate_zircon", "ZrSiO4 + 4 C + 4 Cl2 -> ZrCl4 + SiCl4 + 4 CO", 22, "Zircon chlorination.", draw=["C", "Cl2"])
rx("chlorinator", "chlorinate_hafnium", "Hf + 2 Cl2 -> HfCl4", 23, draw=["Cl2"])
rx("chlorinator", "rechlorinate_zirconyl", "ZrOCl2 + C + Cl2 -> ZrCl4 + CO", 24, draw=["C", "Cl2"])
rx("chlorinator", "rechlorinate_hafnyl", "HfOCl2 + C + Cl2 -> HfCl4 + CO", 25, draw=["C", "Cl2"])
rx("leach", "hydrolyse_zirconium", "ZrCl4 + H2O -> ZrOCl2 + 2 HCl", 68, draw=["H2O"])
rx("leach", "hydrolyse_hafnium", "HfCl4 + H2O -> HfOCl2 + 2 HCl", 69, draw=["H2O"])
rx("kroll", "reduce_zirconium", "ZrCl4 + 2 Mg -> Zr + 2 MgCl2", 38, draw=["Mg"])
rx("kroll", "reduce_hafnium", "HfCl4 + 2 Mg -> Hf + 2 MgCl2", 39, draw=["Mg"])
# Niobium and tantalum.
rx("leach", "digest_columbite", "FeNb2O6 + 12 HF -> 2 H2NbOF5 + FeF2 + 4 H2O", 70, "Hydrofluoric digestion.", draw=["HF"])
rx("leach", "digest_mangancolumbite", "MnNb2O6 + 12 HF -> 2 H2NbOF5 + MnF2 + 4 H2O", 71, draw=["HF"])
rx("leach", "digest_tantalite", "FeTa2O6 + 16 HF -> 2 H2TaF7 + FeF2 + 6 H2O", 72, draw=["HF"])
rx("reactor", "marignac_tantalum", "H2TaF7 + 2 KCl -> K2TaF7 + 2 HCl", 96, "Potassium heptafluorotantalate crystallises.", draw=["KCl"])
rx("reactor", "precipitate_niobia", "2 H2NbOF5 + 10 NH3 + 3 H2O -> Nb2O5 + 10 NH4F", 97, draw=["NH3", "H2O"])
rx("kroll", "reduce_tantalum", "K2TaF7 + 5 Na -> Ta + 2 KF + 5 NaF", 40, "Sodium reduction to tantalum powder.", draw=["Na"])
rx("kroll", "reduce_niobia", "3 Nb2O5 + 10 Al -> 6 Nb + 5 Al2O3", 41, "Aluminothermic niobium.", draw=["Al"])
# Tungsten.
rx("leach", "digest_ferberite", "FeWO4 + 2 NaOH -> Na2WO4 + Fe(OH)2", 73, draw=["NaOH"])
rx("leach", "digest_hubnerite", "MnWO4 + 2 NaOH -> Na2WO4 + Mn(OH)2", 74, draw=["NaOH"])
rx("reactor", "tungstic_acid", "Na2WO4 + 2 HCl -> H2WO4 + 2 NaCl", 98, draw=["HCl"])
rx("roaster", "dehydrate_tungstic_acid", "H2WO4 -> WO3 + H2O", 75)
rx("roaster", "reduce_tungsten_trioxide", "WO3 + 3 H2 -> W + 3 H2O", 76, "Tungsten powder.", draw=["H2"])
# Vanadium.
rx("converter", "oxidise_vanadium", "4 V + 5 O2 -> 2 V2O5", 32, "Vanadium leaves pig iron into the slag.", draw=["O2"])
rx("leach", "leach_vanadium_slag", "V2O5 + 2 NaOH -> 2 NaVO3 + H2O", 75, draw=["NaOH"])
rx("reactor", "ammonium_metavanadate", "NaVO3 + NH4Cl -> NH4VO3 + NaCl", 99, draw=["NH4Cl"])
rx("roaster", "calcine_metavanadate", "2 NH4VO3 -> V2O5 + 2 NH3 + H2O", 77)
rx("kroll", "reduce_vanadium", "3 V2O5 + 10 Al -> 6 V + 5 Al2O3", 42, draw=["Al"])
# Chromium.
rx("roaster", "soda_roast_chromite", "4 FeCr2O4 + 8 Na2CO3 + 7 O2 -> 8 Na2CrO4 + 2 Fe2O3 + 8 CO2", 24, "Toxic hexavalent chromate.",
   draw=["Na2CO3", "O2"])
rx("reactor", "sodium_dichromate", "2 Na2CrO4 + H2SO4 -> Na2Cr2O7 + Na2SO4 + H2O", 100, draw=["H2SO4"])
rx("roaster", "reduce_dichromate", "Na2Cr2O7 + 2 C -> Cr2O3 + Na2CO3 + CO", 25, draw=["C"])
rx("kroll", "reduce_chromium", "Cr2O3 + 2 Al -> 2 Cr + Al2O3", 43, draw=["Al"])
# Technetium (traces; same chemistry as rhenium), thallium.
rx("roaster", "roast_technetium", "4 Tc + 7 O2 -> 2 Tc2O7", 26, draw=["O2"])
rx("leach", "leach_technetium", "Tc2O7 + H2O -> 2 HTcO4", 76, draw=["H2O"])
rx("reactor", "ammonium_pertechnetate", "HTcO4 + NH3 -> NH4TcO4", 101, draw=["NH3"])
rx("roaster", "reduce_pertechnetate_h2", "2 NH4TcO4 + 7 H2 -> 2 Tc + 2 NH3 + 8 H2O", 78, draw=["H2"])
rx("leach", "leach_thallium", "2 Tl + H2SO4 -> Tl2SO4 + H2", 77, draw=["H2SO4"])
rx("reactor", "cement_thallium", "Tl2SO4 + Zn -> 2 Tl + ZnSO4", 102, draw=["Zn"])

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


# --- Atmospheres (M8c-3a): mass fractions, by planet id or archetype ---------------------------------
# Real compositions where there is one (Earth, Mars for Veyra, Titan for frozen worlds, Venus/Io for
# toxic and volcanic ones). Java adds Earth's oxygen to planets that rolled breathable and removes free
# oxygen from those that didn't; airless planets have none.
EARTH = {"N2": 0.7552, "O2": 0.2314, "Ar": 0.0129, "CO2": 0.00062, "Ne": 0.0000127, "He": 0.00000072, "CH4": 0.00000097,
         "Kr": 0.0000033, "Xe": 0.00000039, "H2": 0.000000035}
ATMOSPHERES = {
    "overworld": {**EARTH, "H2O": 0.010},
    "veyra": {"CO2": 0.958, "N2": 0.0172, "Ar": 0.0145, "O2": 0.0005, "CO": 0.0005, "H2O": 0.0002},
    "lush": {**EARTH, "H2O": 0.020},
    "ocean": {**EARTH, "H2O": 0.025},
    "desert": {**EARTH, "H2O": 0.002, "CO2": 0.002},
    "frozen": {"N2": 0.94, "CH4": 0.05, "Ar": 0.009, "H2": 0.0001, "Ne": 0.0002},
    "toxic": {"CO2": 0.85, "N2": 0.08, "SO2": 0.04, "Ar": 0.012, "HCl": 0.01, "H2O": 0.005, "CO": 0.003},
    "volcanic": {"CO2": 0.55, "H2O": 0.2, "SO2": 0.12, "N2": 0.1, "Ar": 0.015, "HCl": 0.01, "CO": 0.005},
    "crystal": {"N2": 0.75, "Ar": 0.18, "Ne": 0.04, "He": 0.02, "Kr": 0.002, "Xe": 0.0005},
    "barren": {"Ar": 0.7, "He": 0.2, "Ne": 0.1},
}


# --- Solvent extraction families (M8c-3b) --------------------------------------------------------------
# Order of extraction with log10 of each adjacent separation factor (beta = D_heavier / D_lighter).
# Lanthanides: P507 (HEHEHP) in kerosene from chloride solution (literature betas: Ce/La 3.0, Pr/Ce 2.0,
# Nd/Pr 1.4, Sm/Nd 9.8, Eu/Sm 2.2, Gd/Eu 1.5, Tb/Gd 5.6, Dy/Tb 2.1, Ho/Dy 2.0, Y/Ho 1.4, Er/Y 1.25,
# Tm/Er 3.3, Yb/Tm 2.8, Lu/Yb 1.8; Sc is extracted almost completely). The saponified extractant swaps
# 3 Na+ for each RE3+, and stripping returns it with 3 HCl: net 3 NaOH + 3 HCl -> 3 NaCl + 3 H2O.
# Zr/Hf: MIBK-thiocyanate (Hf preferred, beta ~6). Nb/Ta: MIBK from HF (Ta preferred, beta ~100).
SX_FAMILIES = {
    "lanthanides": {"stages": 20, "per_mole": {"NaOH": 3, "HCl": 3}, "products": {"NaCl": 3, "H2O": 3},
                    "order": [["LaCl3", 0], ["CeCl3", 0.48], ["PrCl3", 0.30], ["NdCl3", 0.15], ["SmCl3", 0.99], ["EuCl3", 0.34],
                              ["GdCl3", 0.18], ["TbCl3", 0.75], ["DyCl3", 0.32], ["HoCl3", 0.30], ["YCl3", 0.15], ["ErCl3", 0.10],
                              ["TmCl3", 0.52], ["YbCl3", 0.45], ["LuCl3", 0.26], ["ScCl3", 2.0]],
                    "note": "P507 solvent extraction battery (chloride)."},
    "zirconium_hafnium": {"stages": 20, "per_mole": {}, "products": {}, "order": [["ZrOCl2", 0], ["HfOCl2", 0.78]],
                          "note": "MIBK-thiocyanate extraction: hafnium goes to the extract."},
    "niobium_tantalum": {"stages": 20, "per_mole": {}, "products": {}, "order": [["H2NbOF5", 0], ["H2TaF7", 2.0]],
                         "note": "MIBK extraction from HF: tantalum goes to the extract."},
}


def audit():
    """Every natural element (Z 1-94) must have a source: a reaction that makes it, or occurrence as itself
    (native metals, noble gases from air, radioactive traces in uraninite: practical amounts from reactors)."""
    diatomic = {"H": "H2", "N": "N2", "O": "O2", "Cl": "Cl2", "F": "F2"}
    native = set()
    for o in ORES.values():
        native |= set(o["assay"])
    for a in ATMOSPHERES.values():
        native |= set(a)
    made = set()
    for r in REACTIONS:
        for t in r["equation"].split("->")[1].split(" + "):
            m = re.match(r"^(\d+(?:\.\d+)?)\s+(.+)$", t.strip())
            made.add(m.group(2) if m else t.strip())
    physical, missing = [], []
    for sym, e in sorted(ELEMENTS.items(), key=lambda x: x[1]["z"]):
        if e["z"] > 94:
            continue
        sid = diatomic.get(sym, sym)
        if sid in made:
            continue
        (physical if sid in native else missing).append(sym)
    assert not missing, ("elements without any source", missing)
    return physical


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
    d = os.path.join(DATA, "atmosphere")
    os.makedirs(d, exist_ok=True)
    for k, v in ATMOSPHERES.items():
        for sid in v:
            assert sid in {x["id"] for x in SPECIES}, (k, sid)
        json.dump({"composition": v}, open(os.path.join(d, k + ".json"), "w"), indent=1)
    for k, v in SEPARATIONS.items():
        json.dump(v, open(os.path.join(DATA, "separation", k + ".json"), "w"), indent=1)
    d = os.path.join(DATA, "solvent_extraction")
    os.makedirs(d, exist_ok=True)
    for f in os.listdir(d):
        os.remove(os.path.join(d, f))
    ids = {x["id"] for x in SPECIES}
    for k, v in SX_FAMILIES.items():
        for sid, _ in v["order"]:
            assert sid in ids, (k, sid)
        out = dict(v, order=[[sid, str(l)] for sid, l in v["order"]])
        json.dump(out, open(os.path.join(d, k + ".json"), "w"), indent=1)
    physical = audit()
    print(f"{len(SPECIES)} species, {len(ORES)} ore/feed items, {len(REACTIONS)} reactions (all balanced)")
    print("Every natural element has a route; occurring as themselves only:", " ".join(physical))


if __name__ == "__main__":
    main()
