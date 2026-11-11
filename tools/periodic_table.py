#!/usr/bin/env python3
"""Starwake's chemistry data (M5, user: "the entire periodic table").

Writes:
  src/main/resources/starwake/chemistry/elements.json   all 118 elements
  src/main/resources/starwake/chemistry/minerals.json   ore minerals and what they contain
  textures for every element item and every mineral (ore block + raw mineral)

Design:
  * Every element is an item in its natural refined form: metals as ingots, non-metal solids as
    powder, gases in canisters, liquids in flasks, and the short-lived radioactive elements as a
    sealed sample. Iron, copper and gold reuse the vanilla ingots.
  * Every naturally occurring element (Z 1-94) has a real source: an ore mineral (major or
    trace constituent), the atmosphere, or water/brine. The rest (Am onward) are synthetic and
    will come from reactors and accelerators (Milestone 8); they exist as items already.
  * Mineral compositions are mass fractions (approximate, real formulas). The later metallurgy
    system refines raw minerals into these elements.

Data is approximate textbook values: atomic mass (u), density (g/cm3 at STP, gases in g/L*1e-3
style left as their solid/liquid density where known), melting point (K).
Usage: python3 tools/periodic_table.py
"""
import json
import os
import random

import numpy as np
from PIL import Image

HERE = os.path.dirname(__file__)
RES = os.path.join(HERE, "..", "src", "main", "resources")
OUT = os.path.join(RES, "starwake", "chemistry")
TEX = os.path.join(RES, "assets", "starwake", "textures")

# (Z, symbol, name, category, phase, atomic mass, density, melting K, colour)
# categories: alkali, alkaline_earth, transition, post_transition, metalloid, nonmetal, halogen,
#             noble_gas, lanthanide, actinide
E = [
    (1, "H", "hydrogen", "nonmetal", "gas", 1.008, 0.00009, 14, "#e8f0ff"),
    (2, "He", "helium", "noble_gas", "gas", 4.0026, 0.00018, 1, "#ffd8f0"),
    (3, "Li", "lithium", "alkali", "solid", 6.94, 0.534, 454, "#c8c8c0"),
    (4, "Be", "beryllium", "alkaline_earth", "solid", 9.012, 1.85, 1560, "#a8b0a8"),
    (5, "B", "boron", "metalloid", "solid", 10.81, 2.34, 2349, "#5a4a3a"),
    (6, "C", "carbon", "nonmetal", "solid", 12.011, 2.267, 3823, "#2a2a2a"),
    (7, "N", "nitrogen", "nonmetal", "gas", 14.007, 0.00125, 63, "#c8d8ff"),
    (8, "O", "oxygen", "nonmetal", "gas", 15.999, 0.00143, 54, "#a8d0ff"),
    (9, "F", "fluorine", "halogen", "gas", 18.998, 0.0017, 53, "#e8f0a0"),
    (10, "Ne", "neon", "noble_gas", "gas", 20.180, 0.0009, 25, "#ff7a4a"),
    (11, "Na", "sodium", "alkali", "solid", 22.990, 0.971, 371, "#d0d0c8"),
    (12, "Mg", "magnesium", "alkaline_earth", "solid", 24.305, 1.738, 923, "#c0c4c8"),
    (13, "Al", "aluminium", "post_transition", "solid", 26.982, 2.70, 933, "#d0d4d8"),
    (14, "Si", "silicon", "metalloid", "solid", 28.085, 2.329, 1687, "#6a6a78"),
    (15, "P", "phosphorus", "nonmetal", "solid", 30.974, 1.82, 317, "#c84a3a"),
    (16, "S", "sulfur", "nonmetal", "solid", 32.06, 2.067, 388, "#e8d84a"),
    (17, "Cl", "chlorine", "halogen", "gas", 35.45, 0.0032, 172, "#c8e86a"),
    (18, "Ar", "argon", "noble_gas", "gas", 39.948, 0.00178, 84, "#c8a8ff"),
    (19, "K", "potassium", "alkali", "solid", 39.098, 0.862, 337, "#c8c8c0"),
    (20, "Ca", "calcium", "alkaline_earth", "solid", 40.078, 1.54, 1115, "#d8d8d0"),
    (21, "Sc", "scandium", "transition", "solid", 44.956, 2.985, 1814, "#c8c8c0"),
    (22, "Ti", "titanium", "transition", "solid", 47.867, 4.506, 1941, "#a8a8b0"),
    (23, "V", "vanadium", "transition", "solid", 50.942, 6.0, 2183, "#a0a8b0"),
    (24, "Cr", "chromium", "transition", "solid", 51.996, 7.15, 2180, "#b8c0c8"),
    (25, "Mn", "manganese", "transition", "solid", 54.938, 7.21, 1519, "#a8a098"),
    (26, "Fe", "iron", "transition", "solid", 55.845, 7.874, 1811, "#b0aca8"),
    (27, "Co", "cobalt", "transition", "solid", 58.933, 8.86, 1768, "#a8a8b8"),
    (28, "Ni", "nickel", "transition", "solid", 58.693, 8.912, 1728, "#c0b8a0"),
    (29, "Cu", "copper", "transition", "solid", 63.546, 8.96, 1358, "#c8783a"),
    (30, "Zn", "zinc", "transition", "solid", 65.38, 7.14, 693, "#b8c0c8"),
    (31, "Ga", "gallium", "post_transition", "solid", 69.723, 5.91, 303, "#c8ccd4"),
    (32, "Ge", "germanium", "metalloid", "solid", 72.630, 5.323, 1211, "#9a9aa0"),
    (33, "As", "arsenic", "metalloid", "solid", 74.922, 5.727, 1090, "#7a7a80"),
    (34, "Se", "selenium", "nonmetal", "solid", 78.971, 4.81, 494, "#5a3a3a"),
    (35, "Br", "bromine", "halogen", "liquid", 79.904, 3.1028, 266, "#8a2a1a"),
    (36, "Kr", "krypton", "noble_gas", "gas", 83.798, 0.00375, 116, "#e0e8ff"),
    (37, "Rb", "rubidium", "alkali", "solid", 85.468, 1.532, 312, "#c8c4b8"),
    (38, "Sr", "strontium", "alkaline_earth", "solid", 87.62, 2.64, 1050, "#d0ccc0"),
    (39, "Y", "yttrium", "transition", "solid", 88.906, 4.472, 1799, "#c0c0c8"),
    (40, "Zr", "zirconium", "transition", "solid", 91.224, 6.52, 2128, "#b8b8b0"),
    (41, "Nb", "niobium", "transition", "solid", 92.906, 8.57, 2750, "#a8a8b8"),
    (42, "Mo", "molybdenum", "transition", "solid", 95.95, 10.28, 2896, "#a0a0a8"),
    (43, "Tc", "technetium", "transition", "solid", 98.0, 11.0, 2430, "#a8a8b0"),
    (44, "Ru", "ruthenium", "transition", "solid", 101.07, 12.45, 2607, "#b0b0b8"),
    (45, "Rh", "rhodium", "transition", "solid", 102.91, 12.41, 2237, "#c8c8d0"),
    (46, "Pd", "palladium", "transition", "solid", 106.42, 12.023, 1828, "#c0c0c0"),
    (47, "Ag", "silver", "transition", "solid", 107.87, 10.49, 1235, "#d8d8e0"),
    (48, "Cd", "cadmium", "transition", "solid", 112.41, 8.65, 594, "#b8b8c0"),
    (49, "In", "indium", "post_transition", "solid", 114.82, 7.31, 430, "#c0c0c8"),
    (50, "Sn", "tin", "post_transition", "solid", 118.71, 7.265, 505, "#c8c8c8"),
    (51, "Sb", "antimony", "metalloid", "solid", 121.76, 6.697, 904, "#a0a0a8"),
    (52, "Te", "tellurium", "metalloid", "solid", 127.60, 6.24, 723, "#9898a0"),
    (53, "I", "iodine", "halogen", "solid", 126.90, 4.933, 387, "#4a2a5a"),
    (54, "Xe", "xenon", "noble_gas", "gas", 131.29, 0.0059, 161, "#a8c8ff"),
    (55, "Cs", "caesium", "alkali", "solid", 132.91, 1.93, 302, "#d8c890"),
    (56, "Ba", "barium", "alkaline_earth", "solid", 137.33, 3.51, 1000, "#c8c8c0"),
    (57, "La", "lanthanum", "lanthanide", "solid", 138.91, 6.162, 1193, "#c8c8c0"),
    (58, "Ce", "cerium", "lanthanide", "solid", 140.12, 6.77, 1068, "#c0c0b8"),
    (59, "Pr", "praseodymium", "lanthanide", "solid", 140.91, 6.77, 1208, "#b8c0b0"),
    (60, "Nd", "neodymium", "lanthanide", "solid", 144.24, 7.01, 1297, "#c0c0c8"),
    (61, "Pm", "promethium", "lanthanide", "solid", 145.0, 7.26, 1315, "#c0d0c0"),
    (62, "Sm", "samarium", "lanthanide", "solid", 150.36, 7.52, 1345, "#c8c8c0"),
    (63, "Eu", "europium", "lanthanide", "solid", 151.96, 5.244, 1099, "#c8c4b8"),
    (64, "Gd", "gadolinium", "lanthanide", "solid", 157.25, 7.90, 1585, "#c0c0c0"),
    (65, "Tb", "terbium", "lanthanide", "solid", 158.93, 8.23, 1629, "#c0c0b8"),
    (66, "Dy", "dysprosium", "lanthanide", "solid", 162.50, 8.540, 1680, "#c0c0c0"),
    (67, "Ho", "holmium", "lanthanide", "solid", 164.93, 8.79, 1734, "#c0c0c0"),
    (68, "Er", "erbium", "lanthanide", "solid", 167.26, 9.066, 1802, "#c8c0c0"),
    (69, "Tm", "thulium", "lanthanide", "solid", 168.93, 9.32, 1818, "#c0c0c0"),
    (70, "Yb", "ytterbium", "lanthanide", "solid", 173.05, 6.90, 1097, "#c8c8c0"),
    (71, "Lu", "lutetium", "lanthanide", "solid", 174.97, 9.841, 1925, "#c0c0c0"),
    (72, "Hf", "hafnium", "transition", "solid", 178.49, 13.31, 2506, "#b0b0b8"),
    (73, "Ta", "tantalum", "transition", "solid", 180.95, 16.69, 3290, "#9898a8"),
    (74, "W", "tungsten", "transition", "solid", 183.84, 19.25, 3695, "#a0a0a0"),
    (75, "Re", "rhenium", "transition", "solid", 186.21, 21.02, 3459, "#a8a8b0"),
    (76, "Os", "osmium", "transition", "solid", 190.23, 22.59, 3306, "#a8b0c0"),
    (77, "Ir", "iridium", "transition", "solid", 192.22, 22.56, 2719, "#c0c8d0"),
    (78, "Pt", "platinum", "transition", "solid", 195.08, 21.45, 2041, "#d0d0d8"),
    (79, "Au", "gold", "transition", "solid", 196.97, 19.3, 1337, "#e8c040"),
    (80, "Hg", "mercury", "transition", "liquid", 200.59, 13.534, 234, "#c8c8d0"),
    (81, "Tl", "thallium", "post_transition", "solid", 204.38, 11.85, 577, "#a8a8b0"),
    (82, "Pb", "lead", "post_transition", "solid", 207.2, 11.34, 601, "#7a7a88"),
    (83, "Bi", "bismuth", "post_transition", "solid", 208.98, 9.78, 545, "#d0a8c0"),
    (84, "Po", "polonium", "post_transition", "solid", 209.0, 9.196, 527, "#b0b0b8"),
    (85, "At", "astatine", "halogen", "solid", 210.0, 6.4, 575, "#3a3a3a"),
    (86, "Rn", "radon", "noble_gas", "gas", 222.0, 0.00973, 202, "#d0f0d0"),
    (87, "Fr", "francium", "alkali", "solid", 223.0, 2.48, 300, "#c8c0a0"),
    (88, "Ra", "radium", "alkaline_earth", "solid", 226.0, 5.5, 973, "#e8f0e0"),
    (89, "Ac", "actinium", "actinide", "solid", 227.0, 10.07, 1323, "#c8d0e0"),
    (90, "Th", "thorium", "actinide", "solid", 232.04, 11.72, 2023, "#909098"),
    (91, "Pa", "protactinium", "actinide", "solid", 231.04, 15.37, 1841, "#a8a8b0"),
    (92, "U", "uranium", "actinide", "solid", 238.03, 19.1, 1405, "#8a9080"),
    (93, "Np", "neptunium", "actinide", "solid", 237.0, 20.45, 917, "#a0a0a8"),
    (94, "Pu", "plutonium", "actinide", "solid", 244.0, 19.816, 913, "#909098"),
    (95, "Am", "americium", "actinide", "solid", 243.0, 12.0, 1449, "#c0c0c8"),
    (96, "Cm", "curium", "actinide", "solid", 247.0, 13.51, 1613, "#c0c0c8"),
    (97, "Bk", "berkelium", "actinide", "solid", 247.0, 14.78, 1259, "#c0c0c8"),
    (98, "Cf", "californium", "actinide", "solid", 251.0, 15.1, 1173, "#c0c0c8"),
    (99, "Es", "einsteinium", "actinide", "solid", 252.0, 8.84, 1133, "#c0c0c8"),
    (100, "Fm", "fermium", "actinide", "solid", 257.0, 9.7, 1800, "#c0c0c8"),
    (101, "Md", "mendelevium", "actinide", "solid", 258.0, 10.3, 1100, "#c0c0c8"),
    (102, "No", "nobelium", "actinide", "solid", 259.0, 9.9, 1100, "#c0c0c8"),
    (103, "Lr", "lawrencium", "actinide", "solid", 266.0, 15.6, 1900, "#c0c0c8"),
    (104, "Rf", "rutherfordium", "transition", "solid", 267.0, 23.2, 2400, "#b0b0b8"),
    (105, "Db", "dubnium", "transition", "solid", 268.0, 29.3, 0, "#b0b0b8"),
    (106, "Sg", "seaborgium", "transition", "solid", 269.0, 35.0, 0, "#b0b0b8"),
    (107, "Bh", "bohrium", "transition", "solid", 270.0, 37.1, 0, "#b0b0b8"),
    (108, "Hs", "hassium", "transition", "solid", 269.0, 41.0, 0, "#b0b0b8"),
    (109, "Mt", "meitnerium", "transition", "solid", 278.0, 37.4, 0, "#b0b0b8"),
    (110, "Ds", "darmstadtium", "transition", "solid", 281.0, 34.8, 0, "#b0b0b8"),
    (111, "Rg", "roentgenium", "transition", "solid", 282.0, 28.7, 0, "#b0b0b8"),
    (112, "Cn", "copernicium", "transition", "solid", 285.0, 14.0, 0, "#b0b0b8"),
    (113, "Nh", "nihonium", "post_transition", "solid", 286.0, 16.0, 700, "#b0b0b8"),
    (114, "Fl", "flerovium", "post_transition", "solid", 289.0, 11.4, 0, "#b0b0b8"),
    (115, "Mc", "moscovium", "post_transition", "solid", 290.0, 13.5, 670, "#b0b0b8"),
    (116, "Lv", "livermorium", "post_transition", "solid", 293.0, 12.9, 700, "#b0b0b8"),
    (117, "Ts", "tennessine", "halogen", "solid", 294.0, 7.2, 700, "#b0b0b8"),
    (118, "Og", "oganesson", "noble_gas", "solid", 294.0, 7.2, 325, "#b0b0b8"),
]

# Natural occurrence: primordial (1-83 except Tc/Pm, plus Th, U), trace (Tc, Pm, Po-Ac, Pa, Np, Pu
# in uranium ores), synthetic (95+).
TRACE = {43, 61, 84, 85, 86, 87, 88, 89, 91, 93, 94}
RADIOACTIVE = {43, 61} | set(range(84, 119))
VANILLA = {"iron": "minecraft:iron_ingot", "copper": "minecraft:copper_ingot", "gold": "minecraft:gold_ingot"}
METAL_CATEGORIES = {"alkali", "alkaline_earth", "transition", "post_transition", "lanthanide", "actinide"}

# Where each natural element comes from (beyond the minerals below): atmosphere or water.
EXTRA_SOURCES = {
    "H": ["water", "ice"], "O": ["water", "atmosphere"], "N": ["atmosphere"], "He": ["atmosphere", "uraninite"],
    "Ne": ["atmosphere"], "Ar": ["atmosphere"], "Kr": ["atmosphere"], "Xe": ["atmosphere"], "Rn": ["uraninite"],
    "Br": ["brine"], "C": ["coal", "atmosphere"], "Cl": ["brine"], "I": ["brine"], "Mg": ["brine"],
}


def form(z, cat, phase):
    if z in RADIOACTIVE and z not in (90, 92) and phase != "gas":
        return "sample"
    if phase == "gas":
        return "canister"
    if phase == "liquid":
        return "flask"
    if cat in METAL_CATEGORIES:
        return "ingot"
    return "powder"


# Ore minerals: id, formula, display colours (matrix speckle dark/light), host rock tier, mass
# fractions (major constituents), trace elements (listed, refined as by-products), worldgen
# (vein size, veins per chunk, min y, max y).
M = [
    ("hematite", "Fe2O3", ("#5a2a20", "#a85a48"), "stone", {"Fe": 0.70, "O": 0.30}, ["Mn", "Ti"], None),  # vanilla iron ore
    ("chalcopyrite", "CuFeS2", ("#8a6a20", "#d8b850"), "stone", {"Cu": 0.35, "Fe": 0.30, "S": 0.35}, ["Se", "Te", "Ag", "Au", "Mo"], None),  # vanilla copper ore
    ("native_gold", "Au", ("#a8801a", "#f0d050"), "iron", {"Au": 0.90, "Ag": 0.10}, [], None),  # vanilla gold ore
    ("graphite", "C", ("#1a1a1a", "#4a4a4a"), "wood", {"C": 1.0}, [], None),  # vanilla coal ore
    ("bauxite", "Al(OH)3 + AlO(OH)", ("#7a3b1e", "#d08a52"), "stone", {"Al": 0.30, "O": 0.60, "H": 0.03, "Fe": 0.07}, ["Ga", "Ti", "Sc"], (14, 10, 40, 140)),
    ("ilmenite", "FeTiO3", ("#1e1b24", "#6a6280"), "iron", {"Fe": 0.37, "Ti": 0.32, "O": 0.31}, ["V", "Mn", "Mg"], (9, 10, -48, 96)),
    ("pentlandite", "(Fe,Ni)9S8", ("#8a7330", "#e2c97a"), "stone", {"Ni": 0.34, "Fe": 0.32, "S": 0.34}, ["Co", "Pt", "Pd", "Cu"], (8, 8, -64, 48)),
    ("chromite", "FeCr2O4", ("#15110f", "#4a3d34"), "iron", {"Cr": 0.46, "Fe": 0.25, "O": 0.29}, ["Mg", "Al", "V"], (8, 7, -64, 40)),
    ("cassiterite", "SnO2", ("#3e2614", "#9a6a40"), "stone", {"Sn": 0.79, "O": 0.21}, ["In", "Nb", "Ta"], (7, 7, -16, 96)),
    ("wolframite", "(Fe,Mn)WO4", ("#2c2826", "#7a736d"), "iron", {"W": 0.60, "Fe": 0.09, "Mn": 0.09, "O": 0.22}, ["Sc", "Nb", "Ta"], (6, 5, -64, 32)),
    ("cobaltite", "CoAsS", ("#8f8590", "#e8d4e2"), "stone", {"Co": 0.36, "As": 0.45, "S": 0.19}, ["Ni", "Fe"], (6, 5, -64, 48)),
    ("galena", "PbS", ("#4a4a58", "#a8a8b8"), "stone", {"Pb": 0.87, "S": 0.13}, ["Ag", "Bi", "Tl", "Sb"], (9, 8, -32, 80)),
    ("sphalerite", "(Zn,Fe)S", ("#5a3a1a", "#b8803a"), "stone", {"Zn": 0.64, "S": 0.33, "Fe": 0.03}, ["Cd", "Ga", "Ge", "In"], (9, 8, -32, 80)),
    ("molybdenite", "MoS2", ("#3a3e48", "#9aa4b8"), "iron", {"Mo": 0.60, "S": 0.40}, ["Re"], (6, 5, -64, 40)),
    ("pyrolusite", "MnO2", ("#1a1a20", "#4a4a5a"), "stone", {"Mn": 0.63, "O": 0.37}, ["Co", "Ni", "Cu"], (10, 6, -16, 96)),
    ("stibnite", "Sb2S3", ("#2a2e38", "#8a94a8"), "stone", {"Sb": 0.72, "S": 0.28}, ["As", "Hg"], (7, 5, -16, 80)),
    ("bismuthinite", "Bi2S3", ("#5a5060", "#c8b0c8"), "stone", {"Bi": 0.81, "S": 0.19}, ["Te", "Se"], (6, 4, -32, 48)),
    ("acanthite", "Ag2S", ("#2a2a30", "#9a9aa8"), "iron", {"Ag": 0.87, "S": 0.13}, ["Au"], (5, 3, -48, 32)),
    ("sperrylite", "PtAs2", ("#7a7a80", "#e0e0e8"), "iron", {"Pt": 0.57, "As": 0.43}, ["Pd", "Rh", "Ru", "Ir", "Os"], (4, 3, -64, 16)),
    ("calaverite", "AuTe2", ("#8a7a3a", "#e0d080"), "iron", {"Au": 0.44, "Te": 0.56}, ["Ag", "Se"], (4, 3, -48, 32)),
    ("spodumene", "LiAlSi2O6", ("#a8a098", "#e8d8e0"), "stone", {"Li": 0.04, "Al": 0.15, "Si": 0.30, "O": 0.51}, ["Na", "K"], (10, 6, -16, 80)),
    ("beryl", "Be3Al2Si6O18", ("#3a7a6a", "#8ad8c0"), "iron", {"Be": 0.05, "Al": 0.10, "Si": 0.31, "O": 0.54}, ["Cs", "Li"], (5, 4, -32, 64)),
    ("pollucite", "(Cs,Na)2Al2Si4O12", ("#b8b0a8", "#f0ece8"), "stone", {"Cs": 0.36, "Al": 0.08, "Si": 0.16, "O": 0.36, "Na": 0.04}, ["Rb", "Li"], (5, 3, -32, 48)),
    ("zircon", "ZrSiO4", ("#6a3a2a", "#c8885a"), "iron", {"Zr": 0.50, "Si": 0.15, "O": 0.35}, ["Hf", "U", "Th", "Y"], (5, 5, -32, 64)),
    ("monazite", "(Ce,La,Nd,Th)PO4", ("#8a5a2a", "#d8a060"), "stone",
     {"Ce": 0.28, "La": 0.13, "Nd": 0.11, "Pr": 0.03, "Th": 0.06, "P": 0.13, "O": 0.26},
     ["Sm", "Gd", "Eu", "Y", "Tb", "Dy"], (6, 4, -32, 64)),
    ("xenotime", "YPO4", ("#6a5a3a", "#c8b080"), "stone", {"Y": 0.48, "P": 0.17, "O": 0.35},
     ["Dy", "Er", "Yb", "Ho", "Tb", "Tm", "Lu", "Gd", "U", "Th", "Sc"], (5, 3, -32, 48)),
    ("bastnaesite", "(Ce,La)CO3F", ("#7a4a2a", "#d89060"), "stone", {"Ce": 0.40, "La": 0.25, "C": 0.05, "O": 0.21, "F": 0.09},
     ["Nd", "Pr", "Eu", "Sm"], (6, 4, -32, 64)),
    ("columbite", "(Fe,Mn)(Nb,Ta)2O6", ("#1a1820", "#585060"), "iron", {"Nb": 0.40, "Ta": 0.20, "Fe": 0.10, "Mn": 0.05, "O": 0.25},
     ["Sn", "Sc", "U"], (5, 4, -48, 48)),
    ("uraninite", "UO2", ("#1a1e14", "#5a6a3a"), "iron", {"U": 0.88, "O": 0.12},
     ["Ra", "Po", "Pa", "Ac", "Th", "Pb", "He", "Rn", "Np", "Pu", "Tc", "Pm", "At", "Fr"], (5, 3, -64, 40)),
    ("barite", "BaSO4", ("#b8b0b0", "#f0e8e8"), "stone", {"Ba": 0.59, "S": 0.14, "O": 0.27}, ["Sr"], (9, 6, -16, 96)),
    ("celestine", "SrSO4", ("#7a98c8", "#c8d8f0"), "stone", {"Sr": 0.48, "S": 0.17, "O": 0.35}, ["Ba"], (8, 5, -16, 96)),
    ("fluorite", "CaF2", ("#5a3a8a", "#b890e0"), "stone", {"Ca": 0.51, "F": 0.49}, ["Y", "Ce"], (8, 6, -32, 96)),
    ("apatite", "Ca5(PO4)3F", ("#3a6a5a", "#7ac0a8"), "stone", {"Ca": 0.40, "P": 0.18, "O": 0.38, "F": 0.04}, ["Cl", "Ce", "Y", "U"], (8, 6, -32, 96)),
    ("magnesite", "MgCO3", ("#c8c0b8", "#f0ece4"), "stone", {"Mg": 0.29, "C": 0.14, "O": 0.57}, ["Fe", "Ca"], (10, 6, -16, 96)),
    ("sylvite", "KCl", ("#c87a6a", "#f0c8b8"), "wood", {"K": 0.52, "Cl": 0.48}, ["Rb", "Br"], (16, 6, 40, 120)),
    ("borax", "Na2B4O7·10H2O", ("#c8c8c0", "#f8f8f0"), "wood", {"B": 0.11, "Na": 0.12, "O": 0.72, "H": 0.05}, ["Li"], (16, 6, 40, 120)),
    ("nitratine", "NaNO3", ("#c8b8a0", "#f0e8d8"), "wood", {"Na": 0.27, "N": 0.16, "O": 0.57}, ["I", "K"], (16, 6, 40, 120)),
    ("cinnabar", "HgS", ("#8a1a1a", "#e04a3a"), "stone", {"Hg": 0.86, "S": 0.14}, ["Se", "Sb"], None),  # vanilla cinnabar block
    ("native_sulfur", "S", ("#a89a1a", "#f0e050"), "wood", {"S": 1.0}, ["Se", "Te"], None),  # vanilla sulfur block
    ("halite", "NaCl", ("#d8d0cc", "#ffffff"), "wood", {"Na": 0.39, "Cl": 0.61}, ["Br", "I", "K"], None),  # starwake:halite
    ("quartz", "SiO2", ("#d8d4d0", "#ffffff"), "stone", {"Si": 0.47, "O": 0.53}, [], None),  # amethyst / quartz
    ("calcite", "CaCO3", ("#e8e4dc", "#ffffff"), "wood", {"Ca": 0.40, "C": 0.12, "O": 0.48}, ["Mg", "Sr"], None),
    ("gypsum", "CaSO4·2H2O", ("#e8e4dc", "#ffffff"), "wood", {"Ca": 0.23, "S": 0.19, "O": 0.56, "H": 0.02}, ["Sr"], None),  # selenite
]
# Minerals that are vanilla or existing blocks: which block carries the composition.
EXISTING_BLOCKS = {
    "hematite": "minecraft:iron_ore", "chalcopyrite": "minecraft:copper_ore", "native_gold": "minecraft:gold_ore",
    "graphite": "minecraft:coal_ore", "cinnabar": "minecraft:cinnabar", "native_sulfur": "minecraft:sulfur",
    "halite": "starwake:halite", "quartz": "minecraft:quartz_block", "calcite": "minecraft:calcite", "gypsum": "starwake:selenite",
}


def hexrgb(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], dtype=float)


def save(path, rgb, a):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    Image.fromarray(np.dstack([np.clip(rgb, 0, 255).astype(np.uint8), a.astype(np.uint8)]), "RGBA").save(path)


def shade(base, f):
    return np.clip(base * f, 0, 255)


def tex_ingot(color, seed):
    rgb, a = np.zeros((16, 16, 3)), np.zeros((16, 16))
    c = hexrgb(color)
    rng = random.Random(seed)
    # A trapezoid bar seen at an angle: top face lighter, front face mid, side darker.
    for y in range(5, 12):
        inset = 12 - y if y < 8 else 0
        for x in range(2 + max(0, 7 - y), 14 - max(0, 7 - y) + (0 if y < 8 else 0)):
            if y < 8:
                rgb[y, x] = shade(c, 1.25 + rng.uniform(-0.03, 0.03))
            else:
                rgb[y, x] = shade(c, 0.95 + rng.uniform(-0.04, 0.04))
            a[y, x] = 255
    for y in range(8, 12):
        rgb[y, 13] = shade(c, 0.7)
        rgb[y, 2] = shade(c, 0.8)
    for x in range(2, 14):
        rgb[11, x] = shade(c, 0.65)
    rgb[6, 6] = shade(c, 1.5)
    rgb[6, 7] = shade(c, 1.45)
    return rgb, a


def tex_powder(color, seed):
    rgb, a = np.zeros((16, 16, 3)), np.zeros((16, 16))
    c = hexrgb(color)
    rng = random.Random(seed)
    for y in range(6, 14):
        half = int((y - 5) * 0.9) + 1
        for x in range(8 - half, 8 + half):
            if rng.random() < 0.93:
                rgb[y, x] = shade(c, 0.8 + 0.4 * rng.random() - 0.2 * (y - 6) / 8)
                a[y, x] = 255
    for x in range(1, 15):
        if rng.random() < 0.25:
            rgb[14, x] = shade(c, 0.7)
            a[14, x] = 255
    return rgb, a


def tex_canister(color, seed, band=None):
    rgb, a = np.zeros((16, 16, 3)), np.zeros((16, 16))
    steel = hexrgb("#9aa0a8")
    c = hexrgb(color)
    for y in range(2, 15):
        for x in range(5, 11):
            rgb[y, x] = shade(steel, 1.15 - 0.1 * abs(x - 7))
            a[y, x] = 255
    for x in range(6, 10):  # valve
        rgb[1, x] = hexrgb("#3a3e44")
        a[1, x] = 255
    for y in range(6, 11):  # coloured gas band
        for x in range(5, 11):
            rgb[y, x] = shade(c, 1.05 - 0.08 * abs(x - 7))
    if band is not None:
        for x in range(5, 11):
            rgb[12, x] = hexrgb(band)
    return rgb, a


def tex_flask(color, seed):
    rgb, a = np.zeros((16, 16, 3)), np.zeros((16, 16))
    glass, c = hexrgb("#c8d8e0"), hexrgb(color)
    for y in range(3, 15):
        w = 1 if y < 7 else 4
        for x in range(8 - w, 8 + w):
            rgb[y, x] = glass
            a[y, x] = 140
            if y >= 9:
                rgb[y, x] = shade(c, 1.1 - 0.05 * abs(x - 7))
                a[y, x] = 255
    for x in range(7, 9):
        rgb[2, x] = hexrgb("#6a4a3a")
        a[2, x] = 255
    return rgb, a


def tex_sample(color, seed):
    """A lead-shielded sample vial with a radiation band."""
    rgb, a = np.zeros((16, 16, 3)), np.zeros((16, 16))
    lead, c = hexrgb("#5a5e68"), hexrgb(color)
    for y in range(3, 15):
        for x in range(4, 12):
            rgb[y, x] = shade(lead, 1.1 - 0.06 * abs(x - 7.5))
            a[y, x] = 255
    for y in range(6, 11):  # window showing the sample
        for x in range(6, 10):
            rgb[y, x] = shade(c, 1.2)
    for x in range(4, 12):  # yellow/black hazard band
        rgb[12, x] = hexrgb("#e8c820") if x % 2 == 0 else hexrgb("#202020")
        rgb[3, x] = hexrgb("#3a3e44")
    return rgb, a


def tex_ore(seed, mineral):
    from planet_textures import ore, STONE
    return ore(seed, STONE, mineral)


def tex_raw(seed, colors):
    from planet_textures import raw_item
    return raw_item(seed, [colors[0], colors[0], colors[1]])


def main():
    import sys
    sys.path.insert(0, HERE)
    elements = []
    syms = {}
    for z, sym, name, cat, phase, mass, density, melt, color in E:
        f = form(z, cat, phase)
        occurrence = "synthetic" if z >= 95 else ("trace" if z in TRACE else "primordial")
        item = VANILLA.get(name, f"starwake:{name}_{f}")
        elements.append({"z": z, "symbol": sym, "name": name, "category": cat, "phase": phase, "mass": mass,
                         "density": density, "melting_point": melt, "color": color, "form": f, "item": item,
                         "occurrence": occurrence, "radioactive": z in RADIOACTIVE,
                         "sources": EXTRA_SOURCES.get(sym, [])})
        syms[sym] = name

    minerals = []
    for mid, formula, colors, tier, comp, trace, gen in M:
        assert abs(sum(comp.values()) - 1.0) < 0.02, mid
        for s in list(comp) + trace:
            assert s in syms, (mid, s)
        block = EXISTING_BLOCKS.get(mid, f"starwake:{mid}_ore")
        raw = None if mid in EXISTING_BLOCKS else f"starwake:raw_{mid}"
        minerals.append({"id": mid, "formula": formula, "color": colors, "tool": tier, "composition": comp, "trace": trace,
                         "block": block, "raw": raw, "registered": mid not in EXISTING_BLOCKS,
                         "worldgen": None if gen is None else {"size": gen[0], "count": gen[1], "min_y": gen[2], "max_y": gen[3]}})
        for s in list(comp) + trace:
            elements[[e["symbol"] for e in elements].index(s)]["sources"].append(mid)

    # Every natural element needs a source.
    for e in elements:
        if e["occurrence"] != "synthetic":
            assert e["sources"], f"no source for {e['name']}"
        e["sources"] = sorted(set(e["sources"]))

    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "elements.json"), "w") as f:
        json.dump(elements, f, indent=1)
    with open(os.path.join(OUT, "minerals.json"), "w") as f:
        json.dump(minerals, f, indent=1)

    # Textures.
    for e in elements:
        if e["item"].startswith("minecraft:"):
            continue
        maker = {"ingot": tex_ingot, "powder": tex_powder, "canister": tex_canister, "flask": tex_flask, "sample": tex_sample}[e["form"]]
        rgb, a = maker(e["color"], e["z"])
        save(os.path.join(TEX, "item", "element", f"{e['name']}_{e['form']}.png"), rgb, a)
    for i, m in enumerate(minerals):
        if not m["registered"]:
            continue
        save(os.path.join(TEX, "block", f"{m['id']}_ore.png"), tex_ore(40 + i, m["color"]), np.full((16, 16), 255))
        save(os.path.join(TEX, "item", f"raw_{m['id']}.png"), *tex_raw(60 + i, m["color"]))
    natural = sum(1 for e in elements if e["occurrence"] != "synthetic")
    print(f"{len(elements)} elements ({natural} natural, all sourced), {len(minerals)} minerals "
          f"({sum(m['registered'] for m in minerals)} new ores)")


if __name__ == "__main__":
    main()
