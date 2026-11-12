#!/usr/bin/env python3
"""Wristcom Codex (M8i) -> src/main/resources/starwake/codex.json (bundled, read by the client).

Each entry: id, title, category, and paragraphs. Write how things really work and what to do next;
numbers here are the mod's (they match the data files).
"""
import json
import os

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "starwake", "codex.json")

E = []


def entry(id, title, category, *paras):
    E.append({"id": id, "title": title, "category": category, "text": list(paras)})


entry("start", "Getting started", "Basics",
      "Starwake is a ladder: ore -> refined metal -> alloys -> formed and heat-treated parts -> ship components -> a ship. "
      "Vanilla smelting still works (vanilla iron counts as plain AISI 1010 steel), so your first machines come from vanilla materials.",
      "Power: burn coal in a Combustion Generator (10 kW) and run Copper Cable to your machines; Solar Panels and Battery Banks come next. "
      "Every machine shows its power draw and the grid's supply on its screen.",
      "Pipes move fluids (use an arm touching a tank to make it pull; sneak-use to filter one species). Conveyors and hoppers move items; "
      "machines push their outputs into neighbouring inventories.")
entry("ore", "Ore processing", "Chemistry",
      "Ore is mineral-rich rock: crush it (Jaw Crusher), concentrate it (Flotation Cell with water and pine oil for sulfides, Magnetic "
      "Separator for magnetite/ilmenite, Shaking Table for heavy minerals), then roast, smelt and convert.",
      "Copper: chalcopyrite concentrate -> Roasting Kiln (SO2 off-gas) -> Electric Smelter with quartz flux (matte + fayalite slag) -> "
      "Converter with oxygen (blister copper). Iron: hematite + coke + limestone in the smelter makes pig iron (C ~4.3%).",
      "Trace elements follow the real chemistry: gold and silver ride with the matte, selenium and tellurium leave as flue dust, "
      "lithophile oxides go to the slag. Keep your residues: they are ores too.")
entry("wet", "Wet chemistry", "Chemistry",
      "The Chemical Reactor, Leach Tank, Evaporator, Crystallizer and Electrolysis Cell work on real mixtures: pipe solutions between "
      "them. Catalysts sit in the catalyst slot and are never used up (vanadium for sulfuric acid, iron for ammonia, KOH for electrolysis).",
      "Aluminium: bauxite -> caustic leach -> crystallizer (gibbsite) -> roaster (alumina) -> Hall-Heroult electrolysis with cryolite "
      "and carbon (~12.5 kWh per kg). Titanium: ilmenite smelted to titania slag -> Chlorinator with coal + Cl2 (TiCl4) -> Kroll Reactor "
      "with magnesium.",
      "Chlor-alkali (brine electrolysis) gives NaOH, Cl2 and H2; the Air Separation Unit gives O2, N2, Ar from whatever air the planet has.")
entry("rare", "Rare elements", "Chemistry",
      "Every natural element has a real route. Rare earths: crack monazite with NaOH, dissolve in HCl, then the Solvent Extraction Battery "
      "(sneak-use to set the cut; re-run products for purity). Platinum metals: anode slimes -> aqua regia -> chloroplatinate. "
      "Uranium: acid leach -> yellowcake -> UO2 -> UF4 -> Mg reduction.",
      "Pumps read the biome: oceans give seawater, deserts salt-lake brine (lithium), acid seas acid water. Gas Wells give natural gas "
      "with helium. Radioactive material doses you: check the HUD card.")
entry("melt", "Melting and steelmaking", "Metallurgy",
      "The Induction Furnace melts metal items into a bath and taps ingots or 10 kg billets, slabs and castings (moulds). Blow oxygen to "
      "burn carbon, silicon and phosphorus in real order (lime flux takes P and S); add argon with the oxygen (AOD) to keep chromium in "
      "stainless steel.",
      "Pick a target grade on the screen and the assistant lists exact additions. Reactive metals (Ti, Zr, Nb, Ta...) need the Vacuum Arc "
      "Furnace: in air they pick up oxygen and nitrogen. Tungsten and carbides are pressed and sintered (Sintering Press).")
entry("form", "Forming and heat treatment", "Metallurgy",
      "Rolling Mill: slab -> 10 plates; Wire Drawer: rod -> wire; Extrusion Press: billet -> 20 rods; Forging Press: billet -> forging. "
      "Cold passes work-harden (stronger, less ductile) and crack past the alloy's limit: anneal between passes, or switch the mill to HOT.",
      "Heat Treatment Furnace programs: anneal, normalise, harden (water/oil/air quench), temper, solution treat, age, stress relieve. "
      "Steel hardens by carbon and hardenability: 4140 hardens through a thick billet in oil where 1045 doesn't. 6061 aluminium needs "
      "solution treatment then ageing at ~175 C for T6.")
entry("fab", "Fabrication", "Ships",
      "The Fabricator builds from blueprints (< > on its screen). Each input has gates (minimum strength, service temperature, "
      "conductivity...); the panel names the gate an item fails. The main input's alloy is remembered by the product, and its quality is "
      "how far the materials beat the gates (up to 1.5, worth up to +20% in a ship part).",
      "Components: pressure vessel, structural frame, combustion chamber (hot alloy + copper liner), nozzle, turbopump (forging), heat "
      "exchanger, magnet coil (pure copper wire), control board (microchips, copper wire, solder, gold).")
entry("chips", "Electronics", "Ships",
      "Silicon: quartz + coke in the Submerged Arc Furnace (metallurgical Si) -> Chlorinator with HCl (trichlorosilane distils off, "
      "impurities stay) -> Kroll Reactor with H2 (Siemens: electronic-grade Si).",
      "Crystal Puller: electronic-grade silicon (99.999%+) + boron or phosphorus + argon -> 10 doped wafers per kg. Clean Fab: a wafer + "
      "the opposite dopant + aluminium or copper wire (99.99%) -> 8 microchips.")
entry("ships", "Ship parts and hulls", "Ships",
      "Every part comes in Mk I, II and III. Mk I parts can still be crafted; all tiers can be fabricated. Mk II/III need components made "
      "of better alloys: nozzles rated to 950 C (Inconel 625) for Mk II and 1300 C (C-103 niobium, TZM, tantalum, tungsten) for Mk III.",
      "Hull plating carries its alloy: aluminium is light, steel tougher, superalloys and refractories survive re-entry. Coming in fast "
      "heats the leading face; plates rated below the skin temperature burn off (watch SKIN on the HUD). In a crash the face that hits "
      "shares the energy; weak blocks break off.")
entry("fuel", "Propellants and fusion fuel", "Ships",
      "Fuel tanks take gases from pipes or Gas Cylinders (sneak-use) and burn pairs: hydrogen + oxygen at 1:6, or methane + oxygen at "
      "1:3.6. Hydrogen comes from electrolysis, oxygen from the Air Separation Unit, methane from coke ovens and gas wells.",
      "Quantum and jump drives burn fusion fuel: deuterium + helium-3. Heavy water from the Isotope Column (10 kg of water per cycle), "
      "split by electrolysis with KOH; helium-3 from the primordial helium of noble-gas-rich worlds (Air Separation Unit). A few "
      "milligrams carry you between planets.")
entry("nuclear", "Nuclear power", "Power",
      "Enrich: fluorinate UF4 with F2 in the Roasting Kiln (UF6), spin it in Gas Centrifuges (each pass raises the U-235 share ~1.6x on "
      "the abundance ratio; feed the product back in), reduce it with H2, then magnesium.",
      "Fission Reactor: uranium fuel + a moderator. Light water needs ~3% U-235; heavy water runs on natural uranium. Redstone scrams it. "
      "Burning breeds plutonium and fills the fuel with fission products: pyroprocess spent fuel in the Electrolysis Cell. The irradiation "
      "slot transmutes targets (uranium -> plutonium -> americium -> curium -> berkelium -> californium...; bismuth -> polonium).",
      "Fusion Reactor: 2 MW from deuterium + helium-3. Particle Accelerator: the superheavy elements from real target + beam pairs "
      "(oganesson = californium + calcium).")
entry("gear", "Tools, armour and the suit", "Survival",
      "Fabricated tools and armour take every stat from their alloy: hardness sets the mining tier and speed, toughness the durability, "
      "strength the damage and protection; dense alloys slow you down. A tempered 4140 pickaxe mines like diamond; mild steel like stone.",
      "Survey Suit upgrades (Fabricator, two of each): lead lining (halves gamma), extra oxygen tank, thermal layer (-100 / +240 C), "
      "armour insert.")


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    json.dump(E, open(OUT, "w"), indent=1)
    print(f"{len(E)} codex entries")


if __name__ == "__main__":
    main()
