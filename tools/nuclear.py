#!/usr/bin/env python3
"""Nuclear data (M8g) -> src/main/resources/data/starwake/starwake/nuclear/default.json.

Real numbers where they exist: 8.2e13 J per kg of U-235 fissioned (~200 MeV per fission), ~33% thermal-to-electric,
light-water reactors need ~3% U-235 while heavy-water (CANDU) runs on natural uranium, ~0.6 Pu-239 bred per
U-235 burned, fission-product mass yields grouped by element (the two humps at A ~ 90-100 and 135-145), D-He3
releasing 18.3 MeV per reaction (3.5e14 J/kg). Neutron-capture chains follow how the real transuranics were
made (U-238 -> Pu-239 -> Am -> Cm -> Bk -> Cf -> Es -> Fm in high-flux reactors; Bi-209 -> Po-210; Th-232 ->
U-233); heavier elements come from heavy-ion fusion-evaporation with the real target/beam pairs (Og = Cf + Ca-48).
Times and energies are compressed for play (burn_scale; accelerator energies ~ a few hours of a fusion plant).
"""
import json
import os

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "data", "starwake", "starwake", "nuclear")

FISSION_YIELDS = {  # mass share of fission products by element (U-235 thermal fission, grouped)
    "Zr": 0.142, "Mo": 0.120, "Ru": 0.083, "Tc": 0.030, "Sr": 0.052, "Y": 0.030, "Rb": 0.018, "Kr": 0.018, "Pd": 0.030,
    "Cs": 0.120, "Xe": 0.115, "Ba": 0.053, "La": 0.031, "Ce": 0.068, "Pr": 0.029, "Nd": 0.085, "Pm": 0.012, "Sm": 0.016,
    "I": 0.012, "Te": 0.017,
}
total = sum(FISSION_YIELDS.values())
FISSION_YIELDS = {k: round(v / total, 4) for k, v in FISSION_YIELDS.items()}

IRRADIATION = [  # target element -> product element, seconds at full power
    ("U", "Pu", 1200), ("Pu", "Am", 1800), ("Am", "Cm", 2400), ("Cm", "Bk", 3600), ("Bk", "Cf", 3600),
    ("Cf", "Es", 5400), ("Es", "Fm", 7200), ("Bi", "Po", 900), ("Th", "Pa", 600), ("Pa", "U", 600), ("Ra", "Ac", 1200),
    ("Np", "Pu", 900),
]
GJ = 1e8  # x0.1: per-sample energies are a few hours of a fusion plant at most
ACCELERATOR = [  # target + beam -> product (heavy-ion fusion-evaporation), energy per sample
    ("Es", "He", "Md", 20 * GJ), ("Cm", "C", "No", 25 * GJ), ("Cf", "B", "Lr", 30 * GJ), ("Cf", "C", "Rf", 40 * GJ),
    ("Cf", "N", "Db", 50 * GJ), ("Cf", "O", "Sg", 60 * GJ), ("Bi", "Cr", "Bh", 80 * GJ), ("Pb", "Fe", "Hs", 90 * GJ),
    ("Bi", "Fe", "Mt", 100 * GJ), ("Pb", "Ni", "Ds", 120 * GJ), ("Bi", "Ni", "Rg", 140 * GJ), ("Pb", "Zn", "Cn", 160 * GJ),
    ("Bi", "Zn", "Nh", 180 * GJ), ("Pu", "Ca", "Fl", 200 * GJ), ("Am", "Ca", "Mc", 220 * GJ), ("Cm", "Ca", "Lv", 240 * GJ),
    ("Bk", "Ca", "Ts", 260 * GJ), ("Cf", "Ca", "Og", 300 * GJ),
]

DATA = {
    "centrifuge_beta": 1.6,  # one machine = a short cascade section (a single centrifuge is ~1.3)
    "centrifuge_j_per_kg": 2.0e6,
    "min_enrichment": {"H2O": 0.030, "D2O": 0.0071},
    "joules_per_kg": 8.2e13,
    "efficiency": 0.33,
    "burn_scale": 1000,
    "conversion": 0.6,
    "spent_fraction": 0.35,
    "fission_yields": FISSION_YIELDS,
    "irradiation": [{"target": t, "product": p, "seconds": s} for t, p, s in IRRADIATION],
    "accelerator": [{"target": t, "beam": b, "product": p, "energy": e} for t, b, p, e in ACCELERATOR],
    "fusion_j_per_kg": 3.5e14,
    "fusion_efficiency": 0.4,
}


def main():
    os.makedirs(OUT, exist_ok=True)
    json.dump(DATA, open(os.path.join(OUT, "default.json"), "w"), indent=1)
    print(f"nuclear data: {len(FISSION_YIELDS)} fission-product elements, {len(IRRADIATION)} capture chains, {len(ACCELERATOR)} accelerator routes")


if __name__ == "__main__":
    main()
