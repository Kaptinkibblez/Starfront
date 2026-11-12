# Starwake

A Fabric overhaul mod for Minecraft, inspired by No Man's Sky: fly between procedurally
generated planets, live in a walkable starship, trade with faction NPCs, and climb a
fuel/upgrade/crafting progression.

> Named **Starwake** (2026-11-10), replacing the working title "Starbound Frontier",
> which clashed with an existing game. The **mod id is `starwake`** and must never change:
> changing it would orphan every saved block and item. The GitHub repo keeps the name `starfront`.

---

## Pinned versions (do not change without a migration plan)

| Component | Version | Notes |
|---|---|---|
| Minecraft | **26.3** ("Wilderness Bound", released 2026-09-15) | Latest stable that Fabric API fully supports. 26.4 is still in snapshots. |
| Java | **25** | Required by Minecraft 26.x. |
| Fabric Loader | 0.20.1 | |
| Fabric API | 0.166.0+26.3 | |
| Fabric Loom | 1.19-SNAPSHOT (plugin id `net.fabricmc.fabric-loom`) | 26.x is unobfuscated, so there is **no mappings line**. We code against Mojang's real names (`Level`, `BlockPos`, `ResourceKey`...). |
| Gradle | 9.8.2 (wrapper) | |

These match Fabric's official example mod template for 26.3. Upgrade only between
milestones, as a single dedicated commit.

---

## Architecture

### Source layout (Loom split source sets)

```
src/main/java/...      common + server logic (runs on both sides)
src/client/java/...    client-only: rendering, screens, input, sky, HUD
src/main/resources     fabric.mod.json, data/ (datapack), assets/ shared resources
src/main/generated     datagen output (models, lang, loot tables, recipes) - committed
```

`splitEnvironmentSourceSets()` makes it a **compile error** for common code to touch
client classes. This is our main guard for keeping multiplayer possible.

### Package layout (organised by system)

```
com.starwake
├── StarwakeMod             common entrypoint, wires every system's init()
├── registry/               ModBlocks, ModItems, ModItemGroups, ModEntities, ...
├── planet/                 planet definitions, dimensions, gravity, atmosphere
├── worldgen/               procedural terrain, biomes, and ore features
├── ship/                   ship entity, launch sequence, interior pocket dimension
├── galaxy/                 galaxy (seeded stars), star systems, SolarSystem bodies, jump targets
├── chem/                   periodic table and minerals (bundled data), element items
├── trade/                  factions, NPCs, price model, trade menus
├── progression/            fuel, upgrades, crafting chains
├── network/                custom payloads (C2S/S2C), one record per packet
└── config/                 server/client config (Milestone 9)
com.starwake.client         (client source set)
├── StarwakeClient          client entrypoint
├── datagen/                data generators (need client classes for models)
├── render/  screen/  sky/  hud/
```

### Principles

1. **Server-authoritative.** The client sends intents ("launch ship", "buy item") and the
   server validates and applies them. The client never decides outcomes.
2. **Data-driven first.** Planet types, star classes, factions, trade tables, ore
   distributions, fuel values and ship modules live in JSON (datapack, dynamic
   registries, or codecs) so they can be tweaked without recompiling. Java defines
   *behaviour*; JSON defines *content and numbers*.
3. **Deterministic procedural generation.** Everything procedural derives from
   `(world seed, galaxy coordinates)` through a stable hash, so the server and every
   client agree and nothing has to be stored until a player changes it.
4. **No heavy per-tick work.** Prefer events, cached values, attribute modifiers and
   lazy computation. Per-tick logic must be O(1) per player or entity, or amortised.
5. **Prefer vanilla mechanisms over mixins.** For example, gravity uses the vanilla
   `gravity` / `safe_fall_distance` / `jump_strength` attributes. When a mixin is
   unavoidable, keep it small, comment why it exists, and isolate it in a `mixin`
   package for that system.
6. **Verify APIs against real sources.** Read the decompiled 26.3 sources (`genSources`)
   or Fabric's docs before using an API. Don't guess names.

---

## Build-vs-depend decisions

Research done 2026-11-10. None of the big space/ship mods ships for 26.3, so we can
only learn from them, not depend on them.

| System | Reference mod | Status | Decision |
|---|---|---|---|
| Walkable moving ship | Valkyrien Skies 2; Create Aeronautics (built on the Sable physics library) | VS: official 1.20.1 only (unofficial port: 1.21.11). Aeronautics/Sable: NeoForge 1.21.1, custom license. | **Build.** Neither can run on Fabric 26.3. Learn from the "shipyard/sub-level" idea: ship blocks live in a reserved region of the world and are rendered and collided at the ship's transform. See decision A. |
| Planets, rockets, gravity, oxygen | Ad Astra | Latest Fabric build is 1.21.1 | **Build.** Learn from its data-driven planet JSON (gravity, temperature, oxygen, tier) and its rocket flow (fly up, threshold altitude, destination screen). |
| Space progression | Galacticraft 5 | Pre-alpha, no release | **Build.** Use only as a reference for Fabric transfer/energy patterns. |
| Runtime dimensions (one per procedural planet) | NucleoidMC **Fantasy** | Supports 26.3 (v0.8.4, LGPL-3.0) | **Depend (decided).** Creating and unloading dimensions at runtime is fiddly mixin work that Fantasy already maintains. Bundle it with jar-in-jar. Needs the `maven.nucleoid.xyz` host allowed in the environment. See decision B. |
| Items, blocks, networking, menus, datagen, game tests | Fabric API | 26.3 | **Depend.** |

---

## Decisions

- **A. Ships = player-built moving block structures (decided 2026-11-10, refined for M3).**
  - *Any shape.* A ship is the connected structure around a **Helm**, found by flood fill
    that stops at terrain blocks (tag `starwake:ship_excluded`). There are no shape rules.
  - *Functional conditions, not rigid rules.* A pre-flight check at the Helm reports
    pass/fail and stats. Lift: down-facing thrust must exceed mass x planet gravity.
    Per-direction thrust decides which ways the ship can accelerate. Thruster exhaust must
    be clear. Fuel is required. A sealed cabin around the Helm is required to leave the
    atmosphere. Mass, thrust, fuel and physics numbers live in JSON.
  - *Always a moving structure* (the user chose this over "lands back into blocks"). Once
    assembled, the ship is a persistent `ShipEntity` holding its blocks, even while parked.
    It's walkable via custom collision shapes, and players standing on it are carried along.
    Editing blocks on an assembled ship needs ship-space raycasts and placement, so it's
    Milestone 4. Until then, an explicit "Disassemble" (when landed) turns it back into
    blocks for editing.
  - *Full 6DOF, Star Citizen style* (user's choice, 2026-11-10, replacing "no turning
    yet"). Ships pitch, yaw and roll. Mouse-aim steering: the ship turns toward the pilot's
    look direction, Z/C roll, Alt free-look, boost. Orientation is a quaternion about a
    pivot (the structure's centre), and the entity position is the pivot's world position.
  - *Walking aboard a rotated ship* uses **ship-local physics** (3e): players aboard
    move in the ship's frame with deck-aligned artificial gravity, so decks stay "down" at
    any attitude. Until 3e, crew ride in Seats while the ship maneuvers.
  - *Space by flying up.* Climbing above the build limit moves a sealed ship and everyone
    aboard into the shared `starwake:space` dimension. Descending re-enters the planet it's
    orbiting. The console's "Set course" changes the orbited planet (the star map replaces
    this list in Milestone 6).
- **B. Planets = runtime dimensions via Fantasy (decided 2026-11-10)**, with **curated
  randomness** ("less random to make it more random"). Don't use raw noise soup. Each
  planet is a hand-authored *archetype* (lush, frozen, toxic, barren, ocean...) defined in
  JSON with palettes, terrain shapes, biome sets, ore tables and sky styles. The seed picks
  an archetype and varies parameters *within* designed ranges. Variety comes from
  structured combinations, so every planet feels deliberate and distinct.
  Milestone 2 uses one static datapack dimension; Milestone 5 adds seeded planets as Fantasy
  runtime dimensions (Veyra stays hand-made).

---

## Milestones

Work on one milestone at a time. After each: build, run, fix, summarize how to test,
commit. **Don't start the next milestone until the user confirms the current one works.**

- [x] **1. Scaffold.** The mod loads, with one test block (Hull Plating) and one test
      item (Raw Ferrite), a creative tab, datagen for models/lang/loot/tags, and a
      dedicated server that boots cleanly. *(Verified on server + client 2026-11-10;
      awaiting user playtest confirmation.)*
- [x] **2. First planet.** Veyra: a static datapack dimension with its own sky (teal sky,
      dusty fog, daytime stars, ash particles, 40-minute day), 0.45g gravity, and dry
      rust-world terrain with Ferrite Ore. Reachable with `/starwake planet tp starwake:veyra`
      and `/starwake planet leave`. *(Verified on server + client 2026-11-10; awaiting user
      playtest.)*
- [x] **3. Built starships to space (decision A).** *(Verified 2026-11-10; awaiting user playtest.)*
  - [x] 3a. Ship parts (Helm, Thruster, Fuel Tank), structure scan, pre-flight console
        with conditions and stats, and data-driven ship stats JSON. *(Verified 2026-11-10.)*
  - [x] 3b. Assemble into a `ShipEntity`, render it, pilot it, collide with terrain, walk on
        it while it moves, and interact with ship blocks (helm, fuel). *(Verified 2026-11-10:
        flight, roof-riding through all directions, mob carry, landing on terrain, persistence
        through Save & Quit while riding and parked, disassembly with fuel restored.)*
  - [x] 3c. Space dimension, leaving and re-entering atmosphere, setting a course between
        planets, fuel burn. *(Verified: Overworld -> space -> set course -> Veyra landing with
        the pilot aboard; mobs aboard come along; unsealed hulls are held at the ceiling.)*
  - **Star Citizen pass** (user request, 2026-11-10: all four feature sets plus full
    pitch/roll):
  - [x] 3d. 6DOF flight: quaternion orientation (synced, interpolated), rotated rendering
        and bounding box, rotated terrain collision, mouse-aim steering + roll + free-look +
        boost, attitude HUD, Seats for crew, rotation-aware disassembly (cardinal headings).
        *(Verified 2026-11-10: yaw/pitch/roll in flight, rotated rendering inside and out,
        atmospheric auto-level, mob carried rigidly through rotation, disassembly squaring up
        to east with rotated block states, seating synced to clients.)*
  - [x] 3e. Lived-in interiors: ship-local player physics with deck-aligned artificial
        gravity, camera roll with the ship, usable doors/chests aboard, EVA jetpack in
        zero-g, airlocks. *(Verified 2026-11-11: walking/strafing/jumping in a cabin rolled
        86-90 degrees and while it spins on two axes; camera stays deck-aligned; chest aboard
        in space persists edits; door opened onto space and walked out into EVA; ship-frame
        mouse look for walkers and the seated pilot. Beds aboard deferred: vanilla sleep
        needs a world bed and a planet with nights.)*
  - [x] 3f. Components and power: Power Plant, Shield Generator, Cooler, Quantum Drive
        blocks; power routing screen; more pre-flight conditions. *(Verified 2026-11-11:
        overheating/underpowered/shield/quantum checks; power triangle sliders re-route
        live; cockpit HUD; quantum spool then arrival in Veyra orbit; shields absorbing
        crash impacts, with the excess hurting crew.)*
  - [x] 3g. Space as a real place: planets rendered as spheres in space, enter by flying
        into them, quantum travel between them. *(Verified 2026-11-11: Overworld -> orbit
        (ship emerges pointing away from the planet) -> quantum mode, auto-target, spool,
        "obstructed" by the Overworld, align, hold-B engage -> ~2.5 min jump with streaks ->
        drop-out 600 m off Veyra -> fly into the sphere -> arrive nose-down over Veyra;
        Veyra -> Overworld via the console route list; save & quit mid-jump resumes it.)*
  - [x] 3h. Physicalized thrust (user request, 2026-11-11: "a bunch of thrusters on the bottom
        looks dumb"; chose flight computer + thrusters do the turning + main/maneuvering
        thrusters). *(Verified 2026-11-11: SC-style layout hovers level, pitches 30 degrees and
        drifts forward like a helicopter while holding altitude, yaws on maneuvering
        thrusters, coupled braking via retros; the bottom-thrusters ship can't yaw or strafe
        and pre-flight says why; exhaust visible per thruster.)*
  - [x] 3i. Decoupled mode (V), wings with aerodynamic lift, the Kestrel blueprint (a
        realistic test ship), cockpit HUD overhaul, and the Wristcom (wrist computer, mobiGlas-
        style) with an app registry for later systems. *(Verified 2026-11-11: Kestrel passes
        pre-flight and flies; wings carry ~40% of its weight at 28 m/s; DCPL holds speed in
        space; HUD tapes/ladder/markers/systems strip; Wristcom Home/Ship/Starmap work and
        Starmap plots a quantum route.)*
  - [x] 3j. Polish pass (user: "smooth out the rest of this milestone"; chose all of): ship
        sounds, F4 chase camera, landing gear, re-entry effects, ship-ship collision, cached
        ship meshes, multiplayer pass with ownership/crew/lock; Helm console merged into the
        Wristcom; orbit-exit settle; small HUD fixes. *(Verified 2026-11-11 on a dedicated
        server with two clients: owner/crew/lock rules, crew flying with another player
        walking aboard at 40 m/s (no kicks or rubber-banding), crew removal unseats the
        pilot; gear landings soft, belly landings damage; ships stop against and carry each
        other; meshes render correctly. Audio is untested here: the container has no sound
        device.)*
  - **Scaffolds (user, 2026-11-11):** the 3a-3g systems are deliberate scaffolds. They copy
    Star Citizen's systems and feel ("just copy star citizen's systems", long quantum travel)
    and will be overhauled once every core system exists. Prefer simple, replaceable code.
- [x] **4. Ship editing.** Place and break blocks on assembled ships, any time (parked, flying,
      in space), with survival rules; detached parts become debris; HUD spread to the edges.
      *(Verified 2026-11-11: creative and survival breaking (pickaxe timing, drops to inventory,
      durability), placing outward and oriented (stairs), placing on a rotated ship snaps to its
      grid, breaking a support detaches 2 blocks as items in space. Owner/crew permission is
      the 3j `canPilot` rule; not re-tested in multiplayer.)*
- [x] **5. Procedural planets.** Terrain, biomes and ores seeded by galaxy coordinates
      (decision B). User's choices (2026-11-11): keep Overworld + Veyra and add 4-6 seeded
      planets; all 8 archetypes (lush, frozen, barren, toxic, volcanic, ocean, desert, rare
      crystal); new alien blocks, **kept within real science** (planned metallurgy and alloys
      need real elements); hazards now. Mid-milestone addition: **the whole periodic table**
      ("in whatever feasible way"). *(Verified 2026-11-11: server + client; every archetype
      generated and toured (lush fernwood forest, frozen ice sea, barren craters under a black
      sky, toxic cinnabar/sulfur, volcanic vents, ocean islands, banded desert mesas, crystal
      calcite terraces); a ship drifted from space into a procedural planet; hypoxia kills,
      the Survey Suit's oxygen keeps you alive; element and mineral tooltips; Starmap lists the
      new planets.)*
- [x] **6. Star map.** A galaxy/star map UI for choosing seeded destinations. User's choices
      (2026-11-11): a Jump Drive that works anywhere (range/fuel limited), an endless seeded
      galaxy, real star classes (incl. white dwarfs, neutron stars, binaries), a 3D rotatable
      map. *(Verified 2026-11-11: galaxy generated identically on server and client; 14 stars
      within 10 ly of Solace, mostly red dwarfs; 3D map orbit/zoom/select with live planet
      details; Jump Drive ship armed, spooled, aligned and jumped 5.8 ly to Kalvair (17 s
      tunnel, 351 fuel); arrival in the new system's space; its planets generated with
      luminosity-scaled orbits (a 1 C desert at 0.2 Mm, a -71 C frozen world at 0.6 Mm around
      a 0.006 L red dwarf); the Starmap shows the current system; the opened system and the
      player in it survive a restart.)*
- [x] **7. Trading.** User's choices (2026-11-11): outposts + space stations, digital credits,
      dynamic commodities + NPC shops, factions with reputation and contracts, trading at
      terminals (guards and crew NPCs for life, not traders), Solace rich / galaxy sparser, fly
      into a hangar held by a pressure field; mid-milestone: "builds based off actual high
      quality builds, no boxes". *(Verified 2026-11-11: the Overworld Trade Port builds on
      approach (ribbed octagonal hangar with a lit mouth, catwalks, containers, two-deck core with
      a reactor atrium, quarters, control room, solar wings, dish); buy/sell at the Commodity
      Terminal moves stock and credits; the Outfitter sells a Seat; a courier contract accepted
      at the station and handed in at Neolvuia Trading Post pays out; Wristcom Trade/Contracts
      apps; hitting crew costs rep and a guard fights back; outposts build at the surface on
      Neolvuia and Veyra, pressurized inside.)*
- [ ] **8. Progression + metallurgy.** User's choices (2026-11-11; "the biggest one yet, the full
      metallurgical system"): **composition-tracked** metallurgy (ore -> crush -> concentrate ->
      smelt/reduce -> refine following each mineral's real composition and by-products; alloys
      carry their composition and get computed properties; real grades recognised by range);
      machines are **single blocks early, multiblocks later**; an **electric grid powers every
      machine** (cables, batteries; combustion generator, solar, fission, fusion); parts, armour,
      tools, weapons and hull plating take **stats from their material plus tier gates**
      (minimum properties); ship parts come in **Mk I-III** designs; ships burn **real
      propellants** (LH2/LOX, methalox) and quantum/jump drives burn **fusion fuel** (D + He-3);
      **vanilla smelting stays** (vanilla iron = plain low-carbon iron in our system);
      logistics = **cables + pipes + conveyors**; first survival ship in **~3-5 h**; learning
      through a **Wristcom Codex** app. Built in parts, each with a jar and a **pause for the
      user's OK**: 8a materials core, 8b power + logistics, 8c ore processing, 8d alloying +
      forming, 8e ship parts (Mk tiers, fabricator, hull grades), 8f propellants + fusion fuel,
      8g fission + fusion reactors (synthetic elements), 8h armour/tools/weapons/suit upgrades,
      8i Codex, recipes for everything, balance, release.
  - [x] 8a. Materials core: composition component, 47 real alloy grades + family names, base-metal
        models, off-spec detection, metal ingot/plate/rod/wire/dust tinted by composition,
        tooltips, `/starwake metal`. *(Verified 2026-11-11: 304, Inconel 718 and Grade 2 Ti
        certify; low-Ni 304 reads off-spec naming Ni; 2024 without Mn off-spec; arbitrary
        Fe-Cr-Co estimated as stainless; vanilla iron = AISI 1010; tints and tooltips in game.)*
  - **8c decisions (user, 2026-11-12):** ore blocks are **mineral-rich** (60-90% mineral + gangue,
    real stoichiometry); **full chemistry** (every process consumes its real reagents and makes
    its real by-products); minor/trace elements end up in **residues you refine** (slag, matte,
    flue dust, anode slimes, leach liquors); chains of **3-5 real steps**; the plant is **general
    unit-operation machines + data-driven reactions** (balanced equations in JSON); **real hazards
    on leaks** (toxic gas clouds, the Survey Suit protects); split into **8c-1** (reaction engine,
    chemical fluids, crushing/separation/roasting/coking/smelting/converting, Fe Cu Pb Zn Ni Sn +
    ferroalloys), **8c-2** (hydrometallurgy and electrochemistry: acids, chlor-alkali, Bayer +
    Hall-Heroult Al, Kroll Ti, leaching, electrorefining), **8c-3** (residue refining so every
    element is obtainable, atmospheric gases, brines), each with a jar + pause.
  - [x] 8b. Power and logistics: electric grid (J/kW/kWh), copper and aluminium HV cables,
        Combustion Generator, Solar Panel (sun + starlight), Battery Bank, Electric Furnace,
        machine screens, Fluid Pipe (extract sides), Fluid Tank, Conveyor Belt, auto-eject,
        provisional vanilla-material recipes. *(Verified 2026-11-12: coal -> generator (10 kW) ->
        cable -> electric furnace (3 kW) smelting raw iron -> output onto a conveyor -> chest;
        solar panels charging a battery; buckets fill a tank; a pipe with an extract side moved
        5 buckets into a second tank. Action-bar readouts (solar, battery, tank, pipe) can't be
        seen in the headless client: even `/title actionbar` doesn't render there.)*
  - **8c-2 decisions (user, 2026-11-12):** solutions are **real mixtures** (one fluid carrying its
    composition; tanks and pipes mix by mass); **real electrolysis energy** (electrons x Faraday x
    cell voltage); **all single-block machines** (no potline multiblocks); water from **pumps on
    water sources** (plus an atmospheric condenser on breathable worlds).
  - [x] 8c-2. Wet chemistry and electrochemistry: Water Pump, Atmospheric Condenser, Chemical
        Reactor (catalyst slot), Leach Tank, Evaporator, Crystallizer, Electrolysis Cell (electrowinning,
        molten-salt cells, electrorefining), Chlorinator, Kroll Reactor; mixing Fluid Tanks; residue,
        crystals and anode slimes. *(Verified 2026-11-12 on a dedicated server: SO2 + water over a
        vanadium catalyst -> 3.06 kg H2SO4 per 2 kg SO2; brine -> NaOH + Cl2 + H2 at 1.44 MJ per cycle;
        bauxite -> crusher -> caustic leach (red mud: Fe2O3 46%, SiO2 31%) -> pipe -> crystallizer
        (gibbsite, NaOH regenerated) -> roaster (alumina) -> Hall-Heroult cell with cryolite + carbon
        (0.528 kg Al per kg alumina, 23.8 MJ); titania slag + coal + Cl2 -> TiCl4 -> Kroll + Mg ->
        0.757 kg Ti per 3 kg TiCl4 + MgCl2; zinc calcine + acid -> ZnSO4 -> electrowinning (~2.7
        kWh/kg Zn, acid regenerated); impure copper anodes -> 100% Cu cathode, Ag/Au/Pb slimes, Ni
        in the bleed; brine evaporated to salt; pump and condenser fill tanks; charges wait for
        their reagents instead of passing through unreacted.)*
  - **8c-3 decisions (user, 2026-11-12):** **real routes** for every rare element (REE by solvent
    extraction, PGMs from slimes, Ga from Bayer liquor, Ge/In/Cd from zinc residues, Re from
    molybdenite flue dust, Se/Te from slimes, U/Th from monazite); **per-planet atmospheres** (an Air
    Separation Unit separates whatever that planet's air holds); **pumps read the biome** (ocean =
    seawater, evaporite worlds = Li/K/B salt-lake brine; fractional crystallisation in real order);
    **radiation now** (activity from the assay, dose from carried/nearby material, radon leaks;
    suit shielding in 8h); **tanks + a Gas Cylinder item** (refills the suit's O2); refined elements
    are **purity-tracked** (metals as composition ingots, non-metals as element items with a
    composition); split into **8c-3a** (atmospheres + ASU, brines + salts, radiation, by-product
    recovery: Se Te Ag Au Ga Ge Cd Re Mo Bi Sb As...) and **8c-3b** (rare earths, PGMs, actinides,
    audit that every natural element has a route), each with a jar + pause.
  - [x] 8c-3a. Atmospheres + Air Separation Unit, biome-aware pumps (seawater, salt-lake brine, acid
        lake water), fractional crystallisation, solubility limits, molten-salt cells (Na, Li, Ca, Sr,
        Mg), by-product recovery (Se, Te, Ga, Ge, Cd, In, Re, Mo, Br, I, B, K, Sb, As, Bi), Haber
        ammonia, pipe species filters, Gas Cylinder, purity-tracked element products, radiation.
        *(Verified 2026-11-12, dedicated server + client: Overworld air -> ASU -> O2-filtered pipe
        fills a tank with O2 only; salt-lake brine evaporated into gypsum / 99.3% halite / bittern (KCl
        27%, MgCl2 22%, LiCl 8.4%, borate 3.5%); LiCl + soda ash -> Li2CO3 (solubility loss real);
        Downs cell 0.393 kg Na per kg NaCl; molten LiCl -> Li; H2SeO3 + SO2 -> selenium powder
        (Se 100%); MoO3 + H2 -> Mo dust 99.996%; Re flue dust, NH4ReO4 crystals; GeCl4 -> GeO2;
        CdSO4 + Zn -> Cd; KCl + Na -> K; NaBr + Cl2 -> Br; Ga leached into Bayer liquor and
        electrowon; pumps give seawater / brine by biome; uraninite shows 4 uSv/h at 1 m per chunk,
        64 chunks + 4 g radium nearby read 1.9-4.8 mSv/h on the HUD; an O2 cylinder refills the
        suit; cylinders fill from tanks and sneak-empty into them.)*
  - **8c-3b decisions (user, 2026-11-12):** rare earths via an **SX battery block** (counter-current
    battery with real per-pair separation factors; you set the cut; re-run products to raise
    purity); **track U-235 from now** (uranium carries its U-235 fraction through every process,
    ready for enrichment in 8g); ultra-trace elements stay at **real abundances** (routes exist,
    practical Tc/Pm/Np/Pu/Po come from reactors in 8g; He from natural-gas wells); the **full classic
    PGM route** (aqua regia, ammonium chloroplatinate, OsO4/RuO4 distillation with real hazards).
  - [x] 8c-3b. Rare earths (caustic cracking, HCl dissolution, Solvent Extraction Battery, carbonate,
        oxide, fluoride electrolysis / Ca reduction), PGMs (sperrylite roast, Ostwald nitric acid, aqua
        regia, chloroplatinate, Pd ammines, OsO4/RuO4 distillation, Rh/Ir chlorination), uranium (acid
        leach, yellowcake, UO2, UF4, Mg reduction) with **U-235 tracking**, thorium, radium, fluorine,
        Be, Si, P, Cs, Rb, Sr, Ba, Zr/Hf (SX), Nb/Ta (SX, Marignac), W, V, Cr, Tc, Tl; Gas Well
        (natural gas, helium via the ASU); dissolution-only leaching; the element audit in the tool.
        *(Verified 2026-11-12 on a dedicated server + client: monazite cracked, ThO2 left in the HCl
        residue; SX cut at Nd sends 85% Nd / 15% Pr to the extract, a second pass cut at Sm strips all
        Sm/Dy leaving 93% Nd; Nd carbonate -> oxide -> fluoride electrolysis -> Nd 100% (3.3 kWh/kg);
        Dy2O3 -> DyF3 -> Ca -> Dy; sperrylite -> 84% Pt sponge -> aqua regia -> H2PtCl6/H2PdCl4 with
        Rh/Ru/Ir/Os left -> (NH4)2PtCl6; OsO4 + RuO4 distilled into the gas tank; Na3RhCl6/Na2IrCl6;
        Ostwald HNO3 over Pt; 5%-tagged uraninite -> uranyl liquor (5%) -> pipe -> yellowcake stamped
        5%; RaSO4 stays in the tailings; gas well + ASU -> helium; chromate dissolves in water; SX screen
        shows the cut.)*
  - **8d decisions (user, 2026-11-12):** **real furnace types** (Induction Furnace ~1750 C for steels,
    Cu, Al, Ni alloys; Vacuum Arc Furnace for Ti/Zr/Nb/Ta and other reactive metals, which pick up
    O/N if melted in air; Sintering Press for W/Mo/WC-Co powders); **real steel refining** (oxygen
    blowing burns C/Si/Mn/P in Ellingham order, lime slag takes P and S, Al/Si deoxidation, AOD
    with argon keeps Cr); **tempers tracked** (forming work-hardens; anneal, solution + age, quench +
    temper; handbook properties per temper); a **molten bath with a target-grade assistant** (live
    composition, nearest grade, exact additions to reach a chosen grade); taps give **ingots or
    shaped castings** (10 kg billet, slab, casting; as-cast properties until worked); split into
    **8d-1** (melting, refining, bath, assistant, castings, sintering) and **8d-2** (Rolling Mill,
    Wire Drawer, Extrusion Press, Forging Press, Heat Treatment Furnace, temper properties).
  - [x] 8d-1. Induction Furnace, Vacuum Arc Furnace, Sintering Press; molten bath with live
        composition, liquidus check, air pickup for reactive metals, oxygen blowing in Ellingham order,
        AOD with argon, lime slag (P, S), Al/Si deoxidation; target-grade assistant; tapping ingots or
        10 kg billets/slabs/castings through moulds ("as cast"); sintered ingots. *(Verified 2026-11-12,
        dedicated server + client: pig iron (C 4.25%) + limestone + O2 -> C 0.021%, P 0.004% into the
        lime slag, Si/Mn out, overblow makes FeO; a Cr-Ni melt with 1.5% C blown with O2 + Ar -> C
        0.008%, Cr 18.55%, Ni 9.3%, while pure O2 drops Cr to 14.5% with C still 1.6%; CP Ti melted in
        air picks up O 0.50%/N 0.20%, in vacuum stays 100% Ti; tungsten refused as too refractory;
        W + coke + Co powders sintered into 19 WC-Co ingots; slab mould taps a 10 kg slab; the screen's
        assistant asks for +0.424 kg Cr, +0.382 kg Ni and blowing 0.022 kg C to reach mid-spec 304.)*
  - **8d-2 decisions (user, 2026-11-12):** **handbook + rules** (real temper tables per grade where
    they exist, physical rules by family otherwise); **passes + cold work** (each pass adds real
    reduction; cracking past the material's limit unless annealed between); **hot/cold mode on the
    mills** (hot mode reheats the piece itself, no hardening or cracking; hot-worked steel ~
    normalised); **hardenability** (Heat Treatment Furnace programs; steel hardness from carbon and
    Grossmann hardenability against section size and quench medium).
  - [x] 8d-2. Tempers (component `starwake:temper`), handbook temper tables per grade, cold work + crack
        limit, Grossmann hardenability, tempering, ageing; Rolling Mill, Wire Drawer, Extrusion Press,
        Forging Press (hot/cold mode, one pass per cycle), Heat Treatment Furnace (9 programs); 10 kg
        forgings. *(Verified 2026-11-12 on a dedicated server + client: 6061 solution -> T4 145/241 MPa, aged
        at 175 C -> T6 276/310, at 300 C over-aged 204/250; 304 one cold pass -> 26% CW, 633/833 MPa, 50% ->
        63% CW 936/1019, annealed back to 215/505; 1045 slab cold rolled cracks on the third pass (59% >
        56%), hot rolled -> 10 plates ~normalised 450/655; 1045 plate water quench 667 HV; oil-quenched
        billets: 1045 213 HV vs 4140 526 HV (hardenability); 4140 at 600 HV tempered at 540 C -> 312 HV,
        1028 MPa; Cu rod drawn to 2 wires 75% CW 374 MPa; 4140 billet hot forged; 6061 billet hot extruded
        into 20 rods; screens show strain, mode, program and setpoint.)*
  - **8e decisions (user, 2026-11-12):** **Mk blocks + material bonus** (separate Mk I/II/III blocks with
    JSON base stats; each placed part stores a material quality factor: better alloys than the minimum
    give a modest bonus, worse ones can't be built); **real sub-components** (metals -> nozzle, combustion
    chamber, turbopump, heat exchanger, magnet coil, control board, pressure vessel... each with minimum
    properties -> part); **one single-block Fabricator** (data-driven blueprints, pick a recipe, it pulls
    inputs meeting each slot's gates, shows which input fails which gate); **hull blocks carry their
    alloy** (mass from density, armour from strength, re-entry limit from service temperature);
    **electronics real but compact** (Siemens polysilicon via SiHCl3 -> Czochralski crystal -> doped
    wafers -> chips in a Clean Fab -> boards with Cu wire, solder, Au); **Mk I-III for all functional
    parts** (drives' Mk III may wait for 8g fusion fuel); **flight effects now: re-entry heating and
    impact armour**; split into **8e-1** (electronics, sub-components, Fabricator, hull alloys, re-entry
    + impact) and **8e-2** (Mk I-III designs for every part, material bonus, balance), jar + pause each.
  - [x] 8e-1. Electronics (Submerged Arc Furnace -> metallurgical Si; Chlorinator + HCl -> SiHCl3; Kroll + H2
        Siemens deposition -> electronic-grade Si; Crystal Puller -> doped wafers; Clean Fab -> microchips),
        Fabricator with data-driven blueprints and gate checks, 8 sub-components that remember their alloy and
        quality, hull plating that carries its alloy (mass, heat limit, armour), re-entry heating that burns off
        under-rated leading-face blocks, impact armour. *(Verified 2026-11-12 on a dedicated server + client:
        6 quartz + coke -> 2.8 kg Si with ~0.6% Fe from the ash; 2 kg MG-Si + HCl -> 9.5 kg SiHCl3 (stoichiometric)
        with Fe/Al left in the residue; 3 kg SiHCl3 + H2 -> 0.62 kg Si, HCl recycled to the gas tank; 2 kg EG Si +
        B + Ar -> 20 p-type wafers; impure Si refused; 2 wafers + P + Al wire -> 16 microchips; control board from
        chips, Cu wire, Sn-Pb solder and a gold nugget; Inconel 718 plates -> nozzle carrying its alloy; mild-steel
        plates refused (service gate); 6061 plates -> 4 hull plating, quality 1.5; a 3x3 6061 deck + helm weighs
        6.6 t vs 15.5 t plain; a ship dropped at 60 m/s from near the sky's top burned off its 5 exposed 6061
        plates at 449 C (steel survived) and the ground impact tore off its 6 lowest blocks (engines, belly
        thrusters) after the hull soaked 46.)*
  - **Overnight run (user, 2026-11-12: "go ahead and finish the rest, I'm going to bed").** The remaining parts
    (8e-2 to 8i) were built without the usual clarifying questions; Claude took the recommended option at
    each decision point and records it in each part's entry below. Revisit any of them on request.
  - [x] 8e-2. Mk II/III blocks for all ten functional parts (`<id>_mk2/_mk3`, `registry/ModShipParts`), stats
        from `tools/ship_parts.py` (Mk II: output x1.5, mass/burn/draw/spool x0.85; Mk III: x2.2 / x0.72),
        Fabricator blueprints for Mk I-III with gates on the components' alloys, per-part quality bonus (+0.4
        and -0.3 per 1.0 of quality over the design, max +20% / -15%), tier textures, drops keep material and
        quality. Defaults chosen overnight: Mk I parts stay craftable (provisional recipes) and also
        fabricable; drives' Mk III use today's fuel until 8f. *(Verified 2026-11-12 on a dedicated server:
        test ship lift 2.44x weight with Mk I mains, 6.51x with Mk III, 8.67x with Mk III at quality 1.5 (mass
        255.5 -> 246.5 -> 243.1 t); a Thruster Mk II fabricated from a 718 chamber, Inconel 625 nozzle, 718
        turbopump and a control board (quality 1.0 = the weakest component); a 718 nozzle refused (950 C gate).)*
- [ ] **9. Polish.** Performance, multiplayer sync, config, balance.
  - **User notes for M9 (2026-11-12):** conveyors move items the wrong way; ship flight is buggy
    all around (overhaul it); hub NPCs (guards/crew) add little value: **remove them**.

---

## How planets work (Milestone 2)

A planet is a set of JSON files. Copy Veyra's and rename them to add one:

| File | Controls |
|---|---|
| `data/starwake/starwake/planet/<id>.json` | Starwake's planet registry: display name, dimension, `gravity` (multiplier), and `body`: where it sits in space (position, radius, atmosphere, world_radius, colours; see 3g) |
| `data/starwake/dimension/<id>.json` | Chunk generator, noise settings and biome source |
| `data/starwake/dimension_type/<id>.json` | Sky/fog/cloud colours, ambient particles, music, bed rules, height (vanilla *environment attributes*) |
| `data/starwake/world_clock/<id>.json` + `timeline/<id>_day.json` | Day length and the sky's day/night curve (sun angle, star brightness, sunset colour) |
| `data/starwake/worldgen/noise_settings/<id>.json` | Terrain shape (Veyra reuses Overworld density functions with no water, so oceans become dry basins) |
| `data/starwake/worldgen/material_rule/<id>.json` | Surface blocks |
| `data/starwake/worldgen/biome/*.json`, `feature/`, `placed_feature/` | Biomes, ores, decorations |

Code: `planet/Planets` (registry plus O(1) dimension index), `planet/PlanetGravity`
(transient attribute modifiers on entity load or level change, no per-tick work),
`planet/PlanetTravel` (teleport plus persistent return point, reused by the rocket in
Milestone 3) and `planet/PlanetCommands`.

**Not yet:** custom sky objects (other planets or moons hanging in the sky) need a client
renderer, planned for Milestone 5 or 6. Non-living entities (items, arrows) still fall at
normal gravity.

## How ships work (Milestone 3)

- `ship/scan/ShipScanner`: flood fill from the Helm. Stops at air, replaceable blocks and
  `#starwake:ship_excluded` (hand-written tag JSON in `src/main/resources`). 4096-block cap.
- `ship/scan/PreflightReport`: every functional condition lives here (lift vs mass x
  gravity, exhaust clearance, fuel, pilot room, sealed cabin via air flood fill).
- `ship/data/ShipStats`: `data/<ns>/starwake/ship_stats/*.json` has `blocks` (ids/#tags/"*"),
  `mass`, `thrust`, `fuel_burn`, `fuel_capacity`. The most specific match wins; reloads with /reload.
- `ship/data/ShipFuels`: `data/<ns>/starwake/ship_fuel/*.json` has `items` and `units`.
- Ship Console: `HelmBlock` use -> `ShipConsole.open` -> `ShipConsolePayload` (S2C) ->
  `ShipConsoleScreen`. Buttons send `ShipConsoleActionPayload` (C2S); the server re-validates.
- `/starwake ship check <helmPos>` prints the report (useful for headless tests).
- **Assembled ships (3b):** `ShipAssembler` lifts blocks out silently (no drops or updates;
  block-entity NBT kept) into a `ShipEntity` holding a `ShipStructure` (local coords, origin =
  min corner = entity position). Physics run server-side from the pilot's
  `ServerPlayer.getLastClientInput()`: WASD = thrust per direction, Space = full lift, Sprint
  = cut lift, auto-hover otherwise, fuel burn. Tuning is in `ship_physics/default.json`.
  Unpiloted ships station-keep for free.
- Collision for everyone else: `EntityCollideMixin` adds ship block shapes to
  `Entity.collide`'s entity-collision list (works on client prediction and server alike).
- Carrying: the server moves mobs standing on a ship. The client moves the local player in
  `ShipCarry` (HEAD/TAIL of `LocalPlayer.tick`) by the ship's movement since the last
  player tick. `ServerGamePacketListenerImplMixin` accepts client positions near ships.
- Rendering: `ShipRenderer` submits each block via `submitMovingBlock` with a
  `MovingBlockRenderState` subclass that answers neighbour lookups from the ship (face culling).
- Interaction: ships are not pickable. `MinecraftMixin` raycasts ships at the start of every
  right-click (ship space, `BlockGetter.traverseBlocks`) and sends `ShipInteractPayload`.
- Admin/testing: `/starwake ship assemble <helmPos>` and `/starwake ship drift <x y z>`.
- **Space (3c; superseded by 3g below):** `ship/SpaceTravel`. A sealed ship above `maxY + 32` teleports (vanilla
  `Entity.teleport` carries the pilot; walkers aboard are moved with their offsets) to
  `starwake:space` at Y 192, orbiting that planet. Below Y 16 in space it re-enters the
  orbited planet at `maxY + 8`. "Set course" (console in space) costs
  `course_fuel_cost` and changes `orbiting`. The Overworld is a planet entry; space is a
  planet entry with `"destination": false` and 0.15g. Players falling out of space are
  returned home (return point or spawn). The space dimension is void, black-sky and
  fixed-time (`dimension_type/space.json`).
- **6DOF (3d):** entity position = pivot (structure centre); `world = pos + R(local - pivot)`.
  Old saves (no `frame` tag) are migrated on load. Orientation is a synced `QUATERNION`;
  the client slerps the previous and current tick for rendering. `steer()` turns the nose
  toward the pilot's look vector (shortest arc, eased, rate = `turn_rate` x agility).
  Z/C roll; in atmosphere, wings auto-level when not rolling. Rotations that would hit
  terrain are retried up to 1 block higher (self-righting). Collision shapes for other
  entities are rotated block boxes (exact when axis-aligned, shrunk 0.85 otherwise).
  Seats: `seatOf` (server), synced as a string in `SEATS`. Shift = boost (PlayerMixin
  stops vanilla shift-dismount on ships); X leaves the seat via `ShipControlPayload`.
- **Interiors (3e), client `ShipLocalMotion`:** once the local player stands on or is
  inside a ship, they move in the ship's frame. `PlayerTravelMixin` replaces travel with
  ship-local walking (vanilla-like friction/jump, 0.08 artificial gravity toward the deck,
  exact collision against `collectLocalShapes`, 0.6 step-up). `LocalPlayerMixin`
  re-anchors to the ship's new transform at tick start and adopts external teleports
  and look changes. `EntityTurnMixin`: mouse look is ship-relative, both walking and
  seated. `CameraMixin`: adds camera roll so the deck stays level on screen, and places
  the eye along the ship's up axis. EVA: off-ship in space, WASD thrusts along the view,
  Space up, Shift down, with drag 0.985 (no gravity). Leaving the hull (beyond 1.5 blocks,
  or airborne outside it) exits the frame with momentum preserved.
- Live interior (server): doors, trapdoors and gates toggle via `ShipEntity.updateBlock`
  (re-syncs the structure to trackers). Closed doors seal and open ones leak
  (`ShipEntity.sealsCabin`, shared with the pre-flight check), so a door is an airlock.
  Chests and barrels open a 27-slot menu backed by the ship's stored block-entity NBT
  (`ShipEntity.container`, written back on every change).
- Admin/testing: `/starwake ship spin <yaw pitch roll deg/s>` for unpiloted ships.
- **Components and power (3f):** `ship/systems/ShipSystems` sums component stats from
  `ship_stats` JSON (`power_output`, `heat`, `cooling`, `power_draw`, `shield_capacity`,
  `shield_regen`, `quantum_spool`), shared by pre-flight and the flying ship. Plants
  throttle to cooling/heat. Available power is split by `PowerAllocation` (engines,
  shields, quantum; default 60/20/20; saved per ship). Engine factor = given/wanted
  (max 1.25) and scales thrust, hover and agility. Shields regen by their factor and absorb
  crash energy (impact > 0.35 b/t, 40 HP per b/t, 10-tick cooldown); the excess hurts
  everyone aboard. A course from space spools the quantum drive (spool time / quantum
  factor), then `SpaceTravel.completeCourse` pays fuel and arrives. A Power Plant is
  **required** to fly.
- UI: `ShipConsoleScreen` shows `PowerSliders` (linked triangle, sends
  `ShipPowerPayload`; pilot only). `client/hud/ShipHud` (Fabric `HudElementRegistry`,
  top-left) shows speed, alt/orbit, HDG/PIT/ROL, fuel/shield bars, power split, engine
  %, overheat and QT spool, all from synced entity data (ship stats are server-only data).
- **Space as a real place (3g):** `galaxy/SolarSystem` + `galaxy/Body` (planet JSON `body`).
  Space is real block coordinates (1 block = 1 m): star Solace at the origin, Overworld at
  x = -1.8M, Veyra ~4M blocks away. `starwake:space` is a slab, Y -512..512 (skybox `none`).
  - *Leaving a planet* (sealed, above build limit + 32): the flat planet maps onto its sphere
    (longitude = X / world_radius, latitude = Z / world_radius, clamped to the slab). The ship
    appears 96 blocks outside the atmosphere radius, rotated by the local (east, up, north)
    frame, so it points away from the planet. Velocity and everyone's view rotate too.
  - *Entering*: any ship whose pivot crosses a planet's `atmosphere` radius lands above the
    matching X/Z at build limit + 8, rotated back (diving in = arriving nose-down). Ships are
    weightless in space (no hover burn); space speed cap is `max_speed_space`.
  - *Quantum drive* (ShipEntity "Quantum drive" section): B taps toggle quantum mode, the
    destination nearest the nose (within 10 degrees) or the console route becomes the target, the drive
    spools (Quantum power), and holding B engages once spooled + aligned (`quantum_align`) +
    unobstructed (segment vs every other body's sphere) + fuelled (`quantum_fuel_per_mm`) +
    far enough (`quantum_min_distance`). The hull stays put during the jump; a synced
    `QT_OFFSET` says how far it "really" is, and the client draws space from there. A
    trapezoid speed profile (`quantum_speed`, `quantum_ramp`) ends `quantum_standoff` outside
    the target's atmosphere, then `SpaceTravel.jump` teleports ship + crew. Chunks at the
    drop-out are ticketed 5 s early. A jump in progress is saved and resumes.
  - Client: `client/space/SpaceRenderer` (LevelRenderEvents.COLLECT_SUBMITS ->
    `submitCustomGeometry(RenderTypes.debugQuads())`): bodies are scaled toward the camera
    (`k = min(1, reach/distance)`) so direction and apparent size are exact; front hemisphere
    only, lit from the star; limb glow ring; far bodies become dots; a fixed starfield that
    streaks during quantum travel. `client/hud/SpaceHud`: body markers with km distances,
    nose reticle, quantum panel under the cockpit panel, blue tunnel tint. Console (R) lists
    routes with distance, ETA and fuel.
- **Physicalized thrust (3h):** `ship/systems/ShipDynamics` is the rigid-body model shared by
  pre-flight and the flying ship: centre of mass and inertia tensor from every block's mass
  (ship_stats), and every clear-exhaust thruster as a force at its block centre, pushing away
  from its nozzle (so it also makes torque about the centre of mass). New blocks: **Main
  Engine** (200 thrust, 8 t) and **Maneuvering Thruster** (12 thrust, 0.5 t); the Thruster is
  the medium one. All the same `ThrusterBlock` class; numbers in JSON.
  - *Flight computer* (`ShipDynamics.allocate`, Star Citizen IFCS style). Each tick the
    pilot's intent becomes requests, met in priority order, each scaled down by bisection
    until achievable: (1) hover: lift along world-up = weight minus vertical drift, zero
    torque; (2) rotation: torque = inertia x (wanted - current angular velocity) x 0.35; (3)
    translation along the commanded direction (over-asked = full thrust). Only those rows
    are pinned (vertical lift, three torques, push direction); other force directions are
    free, so a tilted VTOL ship drifts like a helicopter. The solve is a cascaded generalized
    inverse (min-norm throttles via a <=6x6 pseudo-inverse; out-of-range thrusters pinned at 0
    or 1 and the rest re-solved). Coupled mode: uncommanded axes get a braking request
    (12% of drift per tick), so stopping needs retro/side thrusters.
  - Rotation is dynamic: angular velocity (ship frame) integrates thruster torque through the
    inverse inertia, capped at `turn_rate`, with light passive damping (0.985 air, 0.998 space).
    No thrusters that can twist an axis = that axis doesn't turn. Mouse-aim and roll set the
    wanted angular velocity; atmospheric auto-level adds a roll request.
  - Pre-flight: centre of mass (world coords), lift that's usable without tipping (FAIL
    "off-centre" if raw lift would do but balanced lift won't; WARN when >15% is wasted),
    balanced acceleration per axis in m/s², turning authority per axis in deg/s² (weaker
    direction; WARN "can't pitch/yaw/roll").
  - Throttles sync as a string (`entryIndex:0-9;`) for `client/ship/ThrusterEffects`
    (flame for engines, smoke for mains, white puffs for maneuvering thrusters).
  - Test layouts: `build_sc_ship` (mc.sh) vs `build_powered_ship` (all-bottom thrusters).
- **3i additions:**
  - *Decoupled* (V, `ShipQuantumPayload.ACTION_DECOUPLE`, saved per ship): the flight computer
    stops braking uncommanded axes and stops nulling vertical drift (gravity is still cancelled).
  - *Wings* (`WingBlock`, a thin plate; AXIS = plate normal: Y = wing, X/Z = fin). Each panel in
    `ShipDynamics.aero` is a flat plate (force `-wing_drag * density * |v| * (v·n)` along n, at
    the panel's own velocity incl. spin, so fins weathervane and damp yaw) and horizontal
    panels are cambered (`wing_lift * density * forwardAirspeed²` along local up). Air density
    is per planet (`air_density`, 0 in space). Aero force/torque feed the flight computer:
    hover subtracts wing lift (it can push down if wings over-lift) and it counters aero
    torque where it can. Pre-flight reports the share of weight wings carry at 2/3 top speed.
  - *Blueprints* (`ship/data/ShipBlueprints`, `data/<ns>/starwake/blueprint/<id>.json`: palette +
    ASCII layers, nose north, fuel tanks filled): `/starwake ship blueprint <id> <pos>`.
    `starwake:kestrel` is an original light fighter in the SC style (pointed nose, bubble
    canopy, swept wings, twin tail fins, two rear mains, four belly VTOL mains, wing VTOLs,
    RCS at nose/tail/wingtips). Its JSON is generated by `tools/kestrel_blueprint.py <out.json>`.
  - *Cockpit HUD* (`client/hud/FlightHud`, replaces ShipHud): pitch ladder rolled with the
    ship, nose marker, velocity vector, heading tape, speed tape (SCM/NAV, CPLD/DCPL, BOOST),
    altitude tape (or range to the nearest planet in space) with vertical speed, systems
    strip (fuel, shields, power triangle, engines, heat, wing lift), alerts. Vanilla hotbar,
    hearts, food, armour, XP and crosshair are hidden while piloting (HudElementRegistry
    replaceElement). Crew/walkers get a small status card. `SpaceHud` markers + QT panel
    (top-left) remain. `HudProjection` is the shared world->screen helper.
  - *Wristcom* (G; `client/wristcom`): non-pausing holographic screen with an app bar.
    `WristcomApps.register(app)` adds or replaces apps by title. Built in: Home (location,
    gravity, ship summary, controls), Ship (live systems; pilot gets power sliders and the
    flight-mode toggle), Starmap (system map fitted to the bodies, distances, "Plot QT"
    buttons that send SET_COURSE when piloting in space). Placeholders reserve Contracts and
    Trade (M7), Cargo (M8), Comms (M9).
- **3j polish:**
  - *Ship console = Wristcom Ship app.* Helm use / R send `ShipConsolePayload`, which the
    client stores (`wristcom/ShipReports`) and shows in `ShipApp` (scrollable pre-flight or
    status report, Re-scan, Assemble/Disassemble, quantum routes, power, flight mode). The old
    `ShipConsoleScreen` is gone.
  - *Landing gear* (`LandingGearBlock`, DEPLOYED; N via `ShipQuantumPayload.ACTION_GEAR`):
    deployed struts reach half a block below their block (hull collision now uses each
    block's real shape bounds). Touchdowns: <= 0.3 b/t on deployed gear with the ship upright;
    otherwise belly limit 0.08 b/t and 5x shield damage. Disassembly needs gear down (if any).
    Coupled Ctrl = controlled 5 m/s descent; decoupled Ctrl = cut lift. HUD: AGL, GEAR, alert.
  - *Sounds* (`registry/ModSounds`, `client/ship/ShipSounds`; audio from
    `tools/synth_sounds.py`, original and procedural): engine/thruster loops per ship that
    follow synced throttles, RCS puffs, quantum spool/engage/travel/exit, gear, re-entry
    roar, pilot alarms (low fuel, overheat, shields down, gear up on approach).
  - *Chase camera* (F4, `client/ship/ChaseCamera`): vanilla THIRD_PERSON_BACK with the camera
    moved behind the whole ship in `CameraMixin`; survives atmosphere transfers.
  - *Re-entry* (`client/ship/ReentryEffects`): intensity from speed near an atmosphere edge
    plus a burst on every crossing; plasma particles, heat haze (FlightHud), camera shake.
  - *Ship-ship collision*: `clip`/`hullCollides` also test nearby ships' block boxes
    (`collectShapes`). Ships resting on a moving ship (`restsOn`) are carried and turned with it.
  - *Cached meshes* (`client/ship/ShipMesh`): vanilla `ModelBlockRenderer.tesselateBlock` runs
    once per structure into stored quads per layer; each frame replays them via
    `submitCustomGeometry` (moving-block render types) with per-block world light.
  - *Ownership* (`ShipEntity` "Ownership" section; `ShipCrewPayload`; Wristcom `CrewApp`):
    assembler owns the ship (ownerless ships are claimed by their first pilot). Owner + crew
    may pilot/disassemble; locked ships refuse strangers doors, seats and storage. Removing
    crew or locking unseats people who no longer qualify. Admin: `/starwake ship owner|crew`.
  - *Multiplayer fixes*: `ServerGamePacketListenerImplMixin` also exempts players near ships
    from the floating kick (`noBlocksAround`) and the "moved too quickly" check. A new pilot's
    view snaps to the nose (server `forceSetRotation` + client `facePilotForward`). The ship
    raycast used a mutable position (clicks hit the wrong block): fixed with `immutable()`.
## How ship editing works (Milestone 4)

- Server: `ship/edit/ShipEditing`. Owner + crew (`canPilot`) may edit; others get
  `edit.not_allowed`. Every edit builds a new `ShipStructure` and calls `ShipEntity.applyEdit`
  (unseats removed seats, drops stale containers, recomputes fuel capacity, re-syncs
  `ShipStructurePayload`). Mass, thrusters, seal and systems are recomputed from the structure.
- `ShipStructure` keeps a **fixed pivot** and real `min()`/`max()` bounds, so local coordinates
  never shift when blocks are added on the negative side (old saves without a pivot use the box
  centre). Everything that used `size()` as bounds now uses min/max.
- Breaking: the client times mining (`client/ship/ShipEditingClient`, vanilla
  `getDestroyProgress` against `ShipBlockView`, hit sounds, a progress bar under the crosshair;
  creative is instant, 5-tick pause between blocks) and sends `ShipEditPayload` START/ABORT/FINISH.
  The server re-checks reach, permission and elapsed time (>= 70% of needed, minus 2 ticks).
  Drops (plus container contents) go to the inventory; the tool takes durability. The Helm can't
  be broken while assembled. Door halves go together.
- Detached parts: BFS from the Helm after each break. If the ship is parked level on a planet,
  orphans become world blocks; otherwise they drop as items (`edit.detached`).
- Placing: right-click with a block item on a non-usable block (or sneak on usable ones like
  seats, doors, chests). `ShipInteractPayload` carries the face and the local hit point.
  `ShipPlaceContext` gives `getStateForPlacement` ship-local look directions, so stairs, logs
  and doors orient in the ship's frame. Double blocks take two cells; beds are refused; entity
  overlap and the 4096-block cap are checked. Held right-click repeats every 4 ticks (vanilla).
- Client hooks: `MinecraftMixin` `startAttack`/`continueAttack` HEAD (ship hit closer than the
  vanilla target wins); the outline is drawn in the ship's rotated frame from `COLLECT_SUBMITS`
  and vanilla's is suppressed via `BEFORE_BLOCK_OUTLINE`.
- Vanilla's 5x slower mining while airborne also applies in EVA (zero-g repairs are slow).
- Known limits (Milestone 9): ships don't push mobs or other ships
  (they stop against them); unattended ships in unloaded chunks freeze (expected).

## How procedural planets work (Milestone 5)

- **Archetypes** (`planet/proc/PlanetArchetype`, dynamic registry
  `data/<ns>/starwake/planet_archetype/*.json`, server-only): name, weight, orbit band (Mm),
  radius, `environment` ranges (gravity, air density, breathable chance, toxicity,
  temperature), sky styles (`dimension_types`), terrain styles (noise settings), rock, fluid,
  sea level, `biome_count`, `biomes` (each: biome, `required`, climate point, surface
  material rule) and orbit colours. All of it is generated by `tools/planet_data.py` (biomes,
  features, placed features, terrain styles, skies, clocks, timelines): edit the script and
  re-run, because biome feature lists must share one global order (vanilla rejects cycles).
- **The system** (`planet/proc/ProceduralPlanets`): on SERVER_STARTING the world seed picks
  4-6 orbits (`starwake:solace_<n>`), an archetype per orbit (weighted bag, no repeats until all
  are used), then every value in the archetype's ranges. Temperature follows the orbit: the
  inner edge of the archetype's band is its hottest. Names from `PlanetNames`. On
  SERVER_STARTED each planet is opened as a persistent **Fantasy** level with its own seed.
  Clients get the finished list in `PlanetListPayload` on join. Known limit: editing archetype
  JSON changes existing worlds' planets (already generated chunks keep their old look).
- **Planet lookups** (`planet/Planets`): `all(access)` merges the datapack registry with the
  procedural list on either side (cached); `forDimension(dim)` is the server's O(1) index.
  `PlanetRef` = key + planet. `SolarSystem.bodies` builds on `Planets.all`.
- **Generator** (`planet/proc/PlanetGenerators`): a vanilla `NoiseBasedChunkGenerator` from the
  archetype's terrain style (`starwake:planet/standard|large|rugged` = Overworld, large
  biomes, amplified density functions) with the planet's rock, fluid (air = dry, no
  aquifers), sea level and a surface rule built from each biome's rule. Biomes: the generator
  **partitions climate space** among the biomes the seed picked (oceans share the ocean
  continentalness band, land biomes split temperature), because authored climate *points*
  let the ocean win inland. Signature biomes are `required`.
- **Worldgen extras:** `worldgen/CraterFeature` (`starwake:crater`, impact craters with rims on
  airless worlds), selenite geodes and spires, fernwood trees (vanilla tree feature with our
  blocks), giant fungi (vanilla huge_fungus), and every mineral ore (targets
  `#starwake:planet_ore_replaceables`). Carvers reach our rocks via
  `#minecraft:overworld_carver_replaceables` (hand-written, `replace: false`).
- **Content blocks** (`registry/ModPlanetBlocks`): anorthosite, fine regolith, volcanic ash,
  permafrost, halite, selenite (+ cluster), mat turf, tuft grass, fernwood log/fronds, fungal
  stem/cap, puffball, and one ore block + raw item per mineral (data-driven). Textures:
  `tools/planet_textures.py` (rocks, flora, suit) and `tools/periodic_table.py` (ores, raw
  minerals, element items). `PlanetPlantBlock` grows on any sturdy ground.
- **Hazards** (`planet/Hazards` in planet JSON / generated; `planet/PlanetHazards`, once a
  second per player): no air = hypoxia, toxic = burns scaled by toxicity, < -25 C = vanilla
  freezing (+ damage below -100 C), > 55 C = heat damage. Shelter: a sealed ship cabin
  (`ShipEntity.isInPressurizedCabin`, from the sealing flood fill's air cells) or the
  **Survey Suit** (`SurveySuitItem`, chest slot; 5 min of oxygen in the `starwake:oxygen`
  component, refilled in breathable air or a cabin; rated -180..160 C). Damage types
  `starwake:hypoxia|toxic_atmosphere|extreme_heat` bypass armour. Veyra is airless at -35 C;
  space is airless at -120 C. HUD: `client/hud/EnvironmentHud` (top-right card); Wristcom Home
  shows type, air and temperature.
- **Chemistry** (`chem/Chemistry`, bundled `/starwake/chemistry/elements.json` +
  `minerals.json`, generated by `tools/periodic_table.py`; read at startup on both sides
  because they become items and blocks): all **118 elements** as items
  (`starwake:<name>_<ingot|powder|canister|flask|sample>`; Fe/Cu/Au use vanilla ingots;
  `chem/ElementItems`; "Starwake: Elements" creative tab). **43 minerals** with real formulas,
  mass-fraction compositions and trace by-products: every naturally occurring element (Z 1-94)
  has a source (a mineral, the atmosphere, water or brine; Tc, Pm, Np, Pu and the short-lived
  ones occur as traces in uraninite). Am onward are synthetic (reactors, Milestone 8). Vanilla
  blocks carry compositions too (iron ore = hematite, cinnabar, sulfur, calcite...). Minerals
  are placed where they form on Earth (`ARCH_MINERALS` in `planet_data.py`: pegmatites on
  crystal worlds, evaporites in deserts, KREEP rare earths and platinum on airless moons,
  manganese nodules on ocean worlds). Tooltips: `client/chem/ChemistryTooltips`. The future
  metallurgy system refines minerals with these numbers.
- Admin/testing: `/starwake planet list` shows procedural planets with their type;
  `/starwake planet tp starwake:solace_<n>`. The server log prints every planet's archetype,
  terrain, orbit, position, entry radius, gravity and temperature.

## How the galaxy works (Milestone 6)

- **Stars** (`galaxy/Galaxy`, both sides): an endless field generated sector by sector (10 ly
  cubes; Poisson, mean 4 stars = the real 0.004 stars/ly^3, modulated by a large-scale density
  field) from the galaxy seed (`Galaxy.seedFor(worldSeed)`; clients get it in
  `GalaxyInfoPayload` and never see the world seed). Coordinates are light-years; Solace is the
  origin (`Galaxy.HOME`). System ids: `s<x>_<y>_<z>_<i>`. Star classes are a **synced** dynamic
  registry (`galaxy/StarClass`, `data/<ns>/starwake/star_class/*.json`: O B A F G K M with
  real frequencies, white dwarfs, neutron stars; temperature, luminosity, radius, mass ranges,
  colour, planet count, binary chance, remnant flag).
- **Systems** (`galaxy/StarSystems`, server): `plan(star)` places the star (and binary
  companion) at the centre of the system's space and planets on log-spaced orbits scaled by the
  square root of the total luminosity (the habitable zone); each orbit gets an archetype whose
  scaled band fits (remnants: barren/frozen only). `open(server, id)` registers the system with
  `ProceduralPlanets`, opens its space (`starwake:<id>/space`, Fantasy void level with the space
  dimension type) and planets (`starwake:<id>/p<n>`), rebuilds the planet index, saves the id
  (`StarSystems.State`, SavedData `starwake:galaxy`) and broadcasts `PlanetListPayload`.
  Opened systems reopen on server start.
- **System-aware lookups:** every `Planet` has a `system` (default `solace`).
  `SolarSystem.bodies(level)` / `nearest(level, pos)` / `obstruction(level, ...)` only see the
  level's own system; `SolarSystem.systemOf(level)`, `spaceOf(system)`, `isSpace(dimension)`
  (any `starwake:*/space`, plus `starwake:space` for Solace). Leaving a planet goes to its
  system's space.
- **Jump Drive** (block `jump_drive`; `ship_stats` `jump_range` ly + `jump_spool` s;
  `ship/data/JumpTuning` from `starwake/jump_drive/default.json`: fuel per ly, alignment,
  gravity-well clearances, tunnel time, arrival distance). `ShipEntity` "Jump drive" section:
  target from the map (`JumpTargetPayload`, pilot only), J tap arms (exclusive with quantum
  mode), spool on the quantum power share, checks (range, fuel, clear of planets x12 entry
  radius / stars x25 radius, nose within 5 degrees of the target's true direction), hold J to
  engage; the hull holds still in the tunnel (200 + 40/ly ticks, max 1200), the target system is
  opened 5 s before arrival, then `SpaceTravel.transfer` moves ship + crew to the target's space
  on the side facing the origin, at `arrival x sqrt(L)` from the star. A tunnel in progress is
  saved and resumes. Synced: `JD_STATE`/`JD_TARGET`/`JD_INFO`/`JD_SPOOL`/`JD_REACH` (range).
- **Client:** `client/galaxy/GalaxyClient` (seed, `SystemInfoRequestPayload` ->
  `SystemInfoPayload` cache). `GalaxyMapScreen` (M, or the Starmap app's Galaxy button): 3D
  projected star field (left-drag orbit, right-drag pan, scroll zoom, click select, F focus, H
  here), jump-range ring, route line, side panel with class, temperature, luminosity, mass,
  radius, distance and the system's planets, and "Set Jump Target". `SpaceRenderer` draws every
  real star within 60 ly in its true direction (brightness from luminosity / distance^2), so the
  jump target is really in the sky; `SpaceHud` marks it with its distance and shows the jump
  panel and the gold tunnel wash.
- Admin/testing: `/starwake galaxy here|near [ly]|target <id>|tp <id>` (`near` prints each
  star's look yaw/pitch for `/rotate`).
- Known limits: a planet's sky colour comes from its archetype, not its star; a star's light
  doesn't tint its planets yet.

## How trading works (Milestone 7)

- **Factions** (`trade/Faction`, synced registry `data/<ns>/starwake/faction/*.json`): name, code,
  colours (uniform/accent for NPC skins), the hubs they run (`hubs`: kind, economy, template,
  archetypes, weight) and contract types. Four: Solace Accord, Meridian Mining Collective,
  Halcyon Free Traders, Verdant Research Institute.
- **Credits and reputation** (`trade/Economy`, SavedData `starwake:economy`): per player, 1000 cr
  to start, rep -1000..1000 per faction (ranks; < -500 hostile: guards attack, terminals refuse).
  Rep moves price by `1 - rep/10000`. Synced in `EconomyPayload` on join and after changes.
- **Hubs** (`trade/TradeHubs`): planned deterministically per system (`plan`, cached). Solace:
  a station 3 km outside the Overworld's atmosphere + up to 3 outposts (Veyra first); other
  systems: 50% station, outposts on ~1/3 of planets by archetype rules. A hub **builds when a
  player comes near** (station 700 blocks, outpost 160; outposts at the surface height), spawns
  its NPCs and registers pressurized zones (`trade/HubZones`, also used by `PlanetHazards`).
  HUD beacons: `client/hud/HubHud`. `/starwake hub list|place <template>|rebuild`.
- **Builds** (`tools/hub_templates.py` -> `starwake/hub_template/*.json`): composed procedurally
  from shaped modules (octagonal hulls with ribs and flared lips, domes, capsules, tubes) and
  furnished by helpers (terminal kiosks, catwalks, containers, landing pads with markings,
  planters, tables, bunks, masts, dishes, silos, headframe, solar fields). Palette characters
  are assigned automatically (any BMP char). `TradeHubs.place` sets blocks, then runs a
  **shape-update pass** (`Block.updateFromNeighbourShapes`) so panes/bars/walls/stairs connect.
  Outposts clear terrain only 2 blocks around what they build. Edit the script and re-run;
  after `processResources` + `/reload`, `/starwake hub rebuild` replaces built hubs.
- **Markets** (`trade/Markets`, `TradeData`: `commodity/` groups of items with base prices,
  `hub_economy/` what each hub kind produces/consumes, `shop/` outfitter lists). Element prices
  follow crustal rarity (`3 + 40·ppm^-0.3`). Unit price = base x mult x 2^(1 - 2·stock/cap),
  8% spread, rep factor; stock drifts back to equilibrium lazily (tau 6000 ticks; producers
  ~80% full, consumers ~15%). Mining camps sell their planet's own minerals. Rep +1 per 400 cr.
- **Contracts** (`trade/Contracts`): 4 offers per hub per day, seeded by (hub, day, slot):
  delivery, mining quota, courier (`sealed_cargo` tagged with the contract id), exploration
  (survey a star 3-12 ly away; completes on arrival). Max 3 active; abandon/expire costs 5 rep.
- **Terminals** (`trade/block/TerminalBlock`: Commodity Terminal, Outfitter, Contract Board) ->
  `Terminals.open` -> Market/Shop/ContractBoard payloads -> `client/trade/*Screen`; actions come
  back as `TradeActionPayload` and the server checks the player is inside that hub.
- **NPCs** (`trade/HubNpcEntity`, `HubNpcs`): guards (iron sword, attack monsters and hostile
  players, and whoever hurts them) and crew; faction uniforms from `tools/npc_skins.py`.
  Hurting one: -25 rep, killing: -150. Hubs near players are restocked every minute.
- Wristcom: Trade app (credits, standings, the system's hubs), Contracts app (active, abandon).

## How metallurgy works (Milestone 8a)

- **Composition** (`metal/Composition`, component `starwake:composition`): element symbol -> ppm,
  summing to 1,000,000, largest first (= base), kept to 10 ppm (0.001 wt%). `Composition.mix`
  melts compositions together by mass; `parse("Fe:72,Cr:18")`; `withBalance(base, wt%)`.
- **Metal items** (`metal/MetalItems`, `MetalItem`, `MetalForm`): `metal_ingot|plate|rod|wire|dust`;
  `MetalItems.stack(form, composition, n)` sets the composition and the tint (custom model data
  colour; item models use the `custom_model_data` tint source). `compositionOf(stack)` also
  answers for vanilla iron (AISI 1010), copper (C11000), gold, and element ingots/powders (pure).
  Names come from the analysis ("304 Stainless Steel Plate", "Off-spec 2024 Aluminium Dust").
- **Grades** (`metal/AlloyGrade`, synced registry `starwake/alloy_grade/*.json`, from
  `tools/metallurgy.py`): 47 real alloys (carbon/alloy/tool/stainless steels, cast and pig iron,
  Al 1100/2024/6061/7075/5083/2195, CP Ti and Ti-6Al-4V/6242, nickel/Monel/Inconel 625/718/
  Waspaloy/CMSX-4, Stellite, coppers/brass/bronze/BeCu/cupronickel, AZ31B, W, WC-Co, TZM, C-103,
  Ta, Zircaloy-4, solder, sterling) with spec ranges and handbook properties, plus generic
  family names (`exact: false`). **Base models** (`AlloyBase`, `starwake/alloy_base/*.json`):
  pure-metal properties and per-solute effects for compositions that match nothing.
- **Analysis** (`metal/Metallurgy.analyze`, cached): exact grade -> its properties; within 2.5
  range-widths of a grade -> **off-spec** (penalised properties, density scaled, the tooltip lists
  the elements outside spec); else estimated from the base model (strength ~ sqrt(wt%), ductility
  exp loss, conductivity 1/(1+sum k w), corrosion thresholds such as Cr >= 10.5% in iron,
  embrittlement such as C > 2.1%). `MaterialProperties`: density, yield, UTS, elongation, modulus,
  HV, solidus, service temperature, %IACS, corrosion 0-10. Bound from the registries on
  SERVER_STARTED and client JOIN (`client/metal/MetalTooltips`).
- Tooltips on every metal (grade/off-spec/purity, composition, properties; Shift for more).
  Creative tab "Starwake: Materials" (every grade in every form). Admin:
  `/starwake metal give <form> <n> <Fe:72,Cr:18,...>`, `grade <id> [form] [n]`, `info`, `grades`.

## How power and logistics work (Milestone 8b)

- **Units:** energy in joules (long), rates in J/tick (1 kW = 50 J/tick), storage in kWh. Numbers
  per block in `data/<ns>/starwake/machine/<block>.json` (`machine/MachineSpec`: power kW, buffer
  kJ, io kW, capacity kWh (tanks: buckets), speed, fuel multiplier, cable rating kW, rate).
- **Grid** (`machine/PowerNetworks`, per level, not saved): a network = connected cables +
  `EnergyNode` block entities (machines touching each other connect too). Built by a bounded flood
  fill (4096) when a node first asks (`networkOf` every node tick = a map lookup), dropped by
  `invalidate` when a cable or node is placed, removed, loaded or unloaded. Each level tick per
  network: generators -> consumers (proportional to need), then batteries cover the shortfall or
  take the surplus; all flow capped at the weakest cable's rating. Stats per network for screens.
- **Blocks** (`registry/ModMachines`): `CableBlock` (PipeBlock; copper 32 kW, aluminium 512 kW),
  `MachineBlock` + `MachineBlockEntity` (inventory, energy buffer, layout of slot roles, synced
  `MachineMenu` data, LIT, comparator = charge, auto-eject of outputs every 10 ticks into
  neighbouring inventories or onto conveyors leading away), `CombustionGeneratorBlockEntity`
  (furnace fuels x `fuel`, only lights when the buffer has room), `ElectricFurnaceBlockEntity`
  (vanilla smelting at `speed`, draws `power`), `PowerBlock`/`PowerBlockEntity` (Solar Panel:
  peak x daylight-detector sun model (full sun in space) x starlight = L / d^2 relative to the
  Overworld under Solace; Battery Bank: storage). Machines are `WorldlyContainer`s, so hoppers and
  Fabric item transfer work.
- **Fluids** (`logistics/`): `FluidPipeBlock` (EnumProperty `PipeSide` per side: none/connected/
  extract; use an arm touching a storage to toggle extract), a marker block entity that only
  ticks with an extract side, `FluidNetworks` (pipe flood fill, endpoints, pumps `rate`
  buckets/s per extract side into all other endpoints with `StorageUtil.move`).
  `FluidTankBlock` (Fabric `SingleFluidStorage`, buckets via `FluidStorageUtil`, comparator).
  Everything uses Fabric's `FluidStorage.SIDED`, so cauldrons and other mods' tanks connect.
- **Conveyor** (`logistics/ConveyorBlock`): `entityInside` pushes anything on it at `rate` b/s and
  centres it; at the end it inserts items into the `ItemStorage.SIDED` ahead (waiting if full).
- **Screens** (`client/machine/MachineScreen`, no texture): slot wells, energy bar, arrow or flame,
  power and grid totals. Data slots are shorts on the wire: 32-bit values use two slots.
- Assets: `tools/machine_assets.py` (textures + element models: cable/pipe arms, panel, belt, tank);
  datagen does blockstates (furnace-style LIT fronts, multipart cables/pipes, rotated conveyor),
  loot, tags (thin blocks stay out of `blocks_motion_no_leaves`), lang and the provisional recipes
  (`ModRecipeProvider`, vanilla materials until 8i).
- Known limits: tanks don't render their fluid yet; no aluminium recipe until 8c makes aluminium.

## How process chemistry works (Milestone 8c-1)

- **Species** (`process/Species`, bundled `/starwake/chemistry/species.json`, from
  `tools/chemistry_process.py`): ~200 species (gangue, fluxes, ore minerals, intermediates such as
  Cu2S/FeO/fayalite, gases, organics, every element) with formula (parsed: molar mass, element mass
  fractions), kind, density, melting/boiling point, flags (`floats`, `magnetic`, `weak_magnetic`,
  `toxic`), toxicity, colour. `ores.json`: what raw ore chunks (2 kg, mineral-rich: 60-90% mineral
  + gangue + trace elements in ppm), fuels, fluxes and logs (1 kg) are made of. Vanilla raw copper is
  supergene-enriched ore (chalcocite + chalcopyrite) so our chain matches a vanilla furnace's yield.
- **Assay** (`process/Assay`, component `starwake:assay`): species -> parts per billion (keeps
  traces like Ra at 0.3 ppm), rounded to 3 significant figures so steady production stacks.
- **Bulk items** (`process/BulkItem`, `ModProcess`): `bulk_crushed_ore|concentrate|tailings|calcine|
  matte|slag|flue_dust|coke`, 1 kg each, named by main species ("Chalcopyrite Concentrate",
  "Copper Matte"), tinted by its colour. Tooltips (`client/process/ProcessTooltips`): assay and
  the valuable elements in g/mg/µg; Shift lists every species.
- **Chemical fluids** (`process/Chemicals`, `ChemicalFluid`): one fluid `starwake:chemical` + a
  `starwake:species` component; water is vanilla water (H2O). 1 bucket = 1 kg. `ChemicalTank`: a
  multi-species machine tank exposing each species to pipes as its own fluid (transactional).
- **Reactions** (`process/Reactions`, datapack `starwake/reaction/*.json`): balanced equations
  checked on load (element balance), per machine, priority order, `draw` = reactants the machine
  feeds from reagent slots / inlet / air. **Separations** (`starwake/separation/*.json`):
  flotation (flag `floats`, recovery 0.62, 0.9 with pine-oil frother, 1.5 kg water/kg), magnetic
  (strong/weak), gravity (density >= 4.5).
- **Engine** (`process/ProcessEngine`): reactions run as far as the pool allows; drawn reactants are
  fed only to react with what's in the pool (exact stoichiometric coke, flux, oxygen). Routing by
  phase: room-temperature gases -> GAS (steam vented); volatile at the machine temperature: metals
  -> CONDENSATE (Zn, Cd), liquids -> LIQUID (Hg, pine oil, tar), others -> FLUE dust (As2O3, Se,
  Te); molten bath: sulfides -> MATTE, metals -> METAL except Goldschmidt lithophile oxide-formers
  (Ti, Al, REE, U...) -> SLAG and chalcophile traces (and noble metals when there's no real bath)
  -> MATTE; oxides/silicates -> SLAG; carbon saturates iron (~4.3%, drawing coke if needed) and the
  rest burns to CO2 (in air). Separation: selected minerals at recovery, the rest entrained; trace
  elements follow the selected minerals' recovery (locked in the grains).
- **Machines** (`process/ProcessMachineBlockEntity`, one block entity class, `Process` enum; numbers
  in `machine/<id>.json`: power, speed kg/s, temperature): Jaw Crusher (raw chunk -> 2 crushed),
  Flotation Cell (water + pine oil inlet), Magnetic Separator, Shaking Table (water), Roasting Kiln
  (650 C, air O2; off-gas + liquid outlets, flue dust), Coke Oven (1000 C pyrolysis: coke, CH4,
  tar; wood -> charcoal + pine oil), Electric Smelter (1550 C; charge + 2 reagent slots; metal,
  condensate, matte, slag, flue dust; gas + liquid outlets; CO flared to CO2), Converter (1250 C,
  O2 blow: matte -> blister copper; flux slot). Reagent items become **lots**: drawing C from coke
  also moves that coke's share of ash into the batch (mass-exact, nothing wasted). Oxygen is free in
  breathable air (not in space), otherwise from the inlet tank. Outputs leave 1 kg at a time from
  buffers (a full buffer stalls the machine); machines auto-eject to inventories/conveyors.
- **Hazards** (`process/GasHazards`): venting (full off-gas tank, breaking a machine or Fluid Tank
  with chemicals) makes a cloud for species with toxicity >= 0.2: coloured haze, damage once a second
  (0.5 + 2.5 x toxicity), Survey Suit with oxygen protects, creative immune.
- Screens: `MachineMenu.Layout` has an arrow position and up to three tank gauges (inlet, gas,
  liquid; hover for species and kg).
- Chains verified (2026-11-12): copper ore -> crusher -> flotation (81% chalcopyrite conc., Au/Ag/
  Se/Te follow) -> roaster (Cu2S/FeO calcine + SO2) -> smelter + quartz (99.95% Cu2S matte with Ag,
  fayalite slag, Se/Te flue dust) -> converter (Cu 99.94%, Ag 0.058%); iron ore + coke + limestone
  -> pig iron (Fe 94.7%, C 4.25%, Mn, V) + wollastonite slag + CO2; coal -> coke (93% C) + CH4 +
  tar; zinc calcine + coke -> condensed zinc (Zn 98.95%, Cd 1.05%); breaking a roaster with 6 kg SO2
  gassed the tester.

## How wet chemistry works (Milestone 8c-2)

- **Solutions are mixtures.** A `ChemicalTank` in mixture mode (`mixed()`) shows pipes one fluid:
  `Chemicals.mixture(amounts)` = a pure species variant when there's one, else `starwake:chemical`
  with an ASSAY component (named "Solution (main species)"). Inserting a mixture splits it by mass
  fraction; extracting takes a proportional slice. Machine liquid outlets are mixture tanks; inlets
  accept anything (pure or mixed) and keep species separately. **Fluid Tanks** hold a mixing
  ChemicalTank for water and chemicals plus a plain `SingleFluidStorage` for anything else (lava,
  other mods); old 8b saves move water/chemicals into the mixing tank on load. Using a tank with an
  empty hand shows the composition (top 4 species).
- **`ProcessMachineBlockEntity.Process`** now carries a `Mode` (CRUSH, SEPARATE, PYRO, DRY, WET,
  EVAPORATE, ELECTRO), `charge` (0 none, 1 required, 2 optional = inlet-fed), a `catalyst` slot,
  `reagents`, outputs and `inletFeed` (kg sliced from the inlet each cycle). Slot order:
  charge, catalyst, reagents, outputs (`chargeSlot()`, `catalystSlot()`, `firstReagent()`,
  `firstOutput()`).
- **Cycle:** `start()` peeks the feed (one charge item + an inlet slice); chemistry machines (WET,
  DRY except the coke oven, ELECTRO except refining) only start if `ProcessEngine.canReact` says a
  reaction can run (catalyst present, reactants in the pool or drawable), so charges wait for their
  reagents. Then the feed is taken and the products are computed up front and held as **pending**
  (saved in NBT) until the cycle's energy is delivered: max(time from `speed`, the reactions'
  electrical energy) for ELECTRO, 0.8 MJ per kg of water for EVAPORATE. Progress = energy done /
  needed; a weak grid just makes it slower.
- **Electrolysis energy** = moles of reaction x electrons x 96485 x cell voltage (`electrons`,
  `voltage` in reaction JSON): Hall-Heroult 12 e at 4.2 V (~12.5 kWh/kg Al), chlor-alkali 2 e at
  3.5 V, zinc 4 e per 2 Zn at 3.3 V. **Electrorefining** (a metal item as the charge, needs an
  electrolyte, any dissolved salt or acid, in the inlet): the base metal plates out pure, nobler elements (higher
  `potential` in species.json) plus Pb/Sn/Se/Te go to anode slimes, less noble ones dissolve into
  the liquid outlet; 0.3 V x 2 F per mole. An idle cell with metal sulfate in the inlet electrowins it.
- **Catalysts** (`catalyst` in reaction JSON) must be in the catalyst slot and are never used up:
  a species id or an element symbol found in the slot item's material (vanadium for the contact
  process, cryolite crystals for Hall-Heroult, KOH for the alkaline electrolyser).
- **Routing:** WET/ELECTRO use `routeWet` (25 C: water + soluble species -> liquid, gases -> gas
  unless soluble and there's more water, metals -> METAL in cells, other solids -> PRIMARY);
  EVAPORATE: water -> liquid (condensate), dissolved solids -> crystals; the Chlorinator and Kroll
  reactor use the dry route at their temperature, and the Kroll reactor moves metals to METAL.
- **Machines** (`machine/<id>.json`): Reactor 8 kW (charge optional, catalyst), Leach Tank 3 kW,
  Evaporator 40 kW (2 kg/cycle), Crystallizer 2 kW, Electrolysis Cell 60 kW (charge, catalyst,
  reagent; metal + slimes), Chlorinator 10 kW (950 C; charge + carbon, Cl2 inlet; TiCl4 to the
  liquid outlet), Kroll Reactor 15 kW (TiCl4 inlet + Mg reagent; Ti sponge ingot + MgCl2 crystals).
  `logistics/WaterSourceBlockEntity`: Water Pump (1.5 kW, 2 kg/s from a water source beside or
  below, not consumed), Atmospheric Condenser (6 kW, 0.004 kg/s = 1.5 MJ/kg, breathable worlds
  above 0 C only); both push into neighbouring fluid storage. New bulk kinds: residue, crystals,
  anode slimes.
- **Titanium route:** ilmenite + coal in the smelter -> pig iron + titania slag; slag + coal + Cl2 ->
  TiCl4; Kroll with Mg -> Ti + MgCl2; MgCl2 -> electrolysis -> Mg + Cl2 (both recycled).
- Testing headless: `pause-when-empty-seconds=-1` in `run/server.properties`, `/forceload`, fill
  machines with `/item replace ... with <item>[starwake:assay={...}]` (ppb) and `/data merge block
  ... {inlet:{SO2:162000L}}` (droplets, 81000 per kg), `/tick sprint`, then `/data get block`.

## How residues, brines, air and radiation work (Milestone 8c-3a)

- **Air** (`planet/Atmospheres`, datapack `starwake/atmosphere/<id>.json`, from
  `tools/chemistry_process.py` `ATMOSPHERES`): mass fractions per planet id (overworld = real Earth
  air incl. Ne/He/Kr/Xe, veyra = Mars-like) or archetype (frozen = Titan-like N2/CH4, toxic and
  volcanic = Venus/Io-like CO2/SO2/HCl, crystal = noble-gas rich). A breathable planet gets Earth's
  23% O2, a non-breathable one loses free O2, airless ones and space have none. Cached per dimension.
- **Air Separation Unit** (`Process.AIR_SEPARATOR`, Mode AIR): `rate` kg of air per cycle (scaled by
  air density), 0.4 MJ/kg; gases to the off-gas tank (one fluid per species), water to the liquid
  tank; stops when the gas tank is nearly full. **Pipe filters** (`FluidPipeBlockEntity.filter`):
  sneak-use an extract arm to cycle through the species the source holds; filtered pipes only pull
  that pure species (`/data merge block ... {filter:"O2"}` in tests).
- **Brines:** the Water Pump reads the biome of its water (`#starwake:seawater` = oceans, beaches and
  our ocean biomes; `#starwake:salt_lake` = desert biomes; `#starwake:acid_water` = toxic acid seas)
  and pumps the feeds `starwake:seawater` (real 35 g/kg salts + Br, B, Sr, Li, I, U),
  `salt_lake_brine` (Atacama-type, Li 0.16%) or `acid_lake_water` (pH ~0 Kawah Ijen-type); fresh
  water elsewhere. Its tank is a mixture tank.
- **Solubility** (`solubility` kg/kg water in species.json; unlimited when absent): `routeWet`
  crystallises whatever exceeds it (Li2CO3, NH4ReO4, gypsum precipitate in reactors). The
  **Evaporator** dries a 2 kg slice and cuts the crystals in real order: early salts (insolubles,
  carbonates, gypsum) before the main salt starts, the main salt (largest limited one, halite)
  until the next one starts, then the bittern (K, Mg, Li, Br, B salts). Water to the liquid tank.
- **Anhydrous reactions** (`anhydrous: true`): molten-salt cells (Downs Na, Li, Ca, Sr, Mg,
  Hall-Heroult) skip a wet charge (> 0.02 kg water), so brine goes to chlor-alkali instead.
- **By-product routes** (reaction JSON): Se: roast -> SeO2 flue -> water leach -> H2SeO3 + SO2 ->
  Se. Te: roast -> TeO2 -> caustic leach -> electrowin. Re: molybdenite roast -> Re2O7 flue -> leach
  -> HReO4 + NH3 -> NH4ReO4 -> H2 reduction. Mo: MoO3 + H2 (roaster with an H2 inlet). Ge:
  chlorinate -> GeCl4 -> hydrolyse -> GeO2 -> H2. Cd/In/Cu: acid leach then zinc cementation
  (reactor reagent slot). Ga: Bayer leach -> NaGa(OH)4 in the liquor -> electrowin. Li: bittern +
  NaOH (Mg out as brucite) + soda ash -> Li2CO3 -> HCl -> LiCl -> molten cell. K: KCl + Na (Kroll
  reactor). B: H3BO3 -> B2O3 -> Mg (Kroll) -> HCl wash. Br/I: Cl2 displacement. Sb/As: converter
  softening -> oxides -> smelter + C (As sublimes). NH3: Haber (iron catalyst). Pb bullion
  electrorefines generically (Bi, Sb, Ag to slimes).
- **Element products** (`ProcessMachineBlockEntity.elementProduct`): a solid output that is >= 50%
  one element comes out as that element's powder item (non-metals: Se, Te, Ge, B, I) or as
  `metal_dust` (metals: Mo, Re, Cd), carrying its full composition (purity-tracked).
- **Gas Cylinder** (`process/GasCylinderItem`, component `starwake:gas` = species + droplets, 5 kg,
  gases only; Fabric `FluidStorage.ITEM` via `SingleVariantItemStorage`): use on a tank or machine =
  fill only (a held right-click would otherwise toggle the gas back), sneak-use = empty into it;
  use in the air with O2 while wearing the Survey Suit refills it at 0.84 kg O2/day.
- **Radiation** (`process/Radiation`): species.json carries `activity` (Bq/kg, natural isotopes) and
  `gamma` (Sv m^2/(Bq h), with short-lived progeny) on element species; compounds derive them from
  their element fractions; item values are cached per item + components (element samples = 1 g,
  other element items 1 kg). Once a second per player: inventory at 0.4 m + containers, machines,
  tanks and dropped items within 8 blocks (inverse square, no shielding until 8h) + inhaled radon
  in gas clouds (9e-9 Sv/h per Bq/m^3; the suit on oxygen blocks it). Acute dose heals with a 1200 s
  half-life; 0.5 Sv hunger, 1 Sv weakness, 2 Sv sickness damage, 4 Sv severe + nausea, 8 Sv
  ~2 HP/s (`starwake:radiation`, bypasses armour). Synced player attachment `starwake:radiation`
  (dose, rate) -> HUD card under the environment card; item tooltips show activity and dose rate.

## How rare earths, PGMs and actinides work (Milestone 8c-3b)

- **Element audit** (`tools/chemistry_process.py` `audit()`, runs every generation): every natural
  element Z 1-94 has a reaction that makes it, or occurs as itself (noble gases from air or gas
  wells; Pm, Po, At, Rn, Fr, Ac, Pa, Np, Pu as real-abundance traces in uraninite, which round to
  nothing in an assay: practical amounts come from reactors in 8g). The tool fails if one is missing.
- **Solvent Extraction Battery** (`Process.SOLVENT_EXTRACTION`, Mode SX; families in datapack
  `starwake/solvent_extraction/*.json`: order of extraction + log10 of adjacent separation factors,
  stages, reagents per mole). Feed (1 kg slice of the inlet) splits per species by an ideal
  counter-current cascade around the **cut**: share to extract = 1 / (1 + (D_cut / D_i)^(N/2)) with
  D_cut the geometric middle of the cut pair; N = 20. Lanthanides (P507: La < Ce < Pr < Nd < Sm < Eu <
  Gd < Tb < Dy < Ho < Y < Er < Tm < Yb < Lu < Sc) consume 3 NaOH + 3 HCl per mole extracted (-> 3 NaCl
  + 3 H2O; less is extracted without them); Zr/Hf (MIBK, Hf extracted, beta 6) and Nb/Ta (MIBK/HF,
  Ta extracted, beta 100) need nothing. Extract (with half the water) -> the "gas" tank (a mixture
  tank here), raffinate -> the liquid tank. **Sneak-use** the block with an empty hand to step the cut
  (`cut` in NBT; STATUS data slot = species index + 1; the screen shows "Extract >= X"). Adjacent pairs
  with low beta (Nd/Pr) need several passes; tank labels are per process
  (`screen.starwake.machine.tank.<machine>.<role>`, falling back to the generic ones).
- **Rare-earth route:** monazite/xenotime + NaOH (leach) -> RE(OH)3 + Na3PO4; bastnaesite roasts to
  REF3 + RE2O3 (REF3 + NaOH -> RE(OH)3); HCl leach -> RECl3 (thoria stays) -> SX -> Na2CO3 (reactor)
  -> RE2(CO3)3 -> roaster -> RE2O3 -> La-Nd: electrolysis in LiF with carbon (anhydrous); all: + HF ->
  REF3 -> Kroll + Ca.
- **PGM route:** sperrylite roast (As2O3 fume, Pt sponge as metal dust) or Ni/Cu anode slimes ->
  aqua regia (HNO3 from Ostwald over a Pt catalyst + HCl) -> H2PtCl6, H2PdCl4, HAuCl4 (AgCl and
  Rh/Ru/Ir/Os stay) -> SO2 drops Au; NH4Cl drops (NH4)2PtCl6 -> roaster -> Pt; NH3 then HCl ->
  Pd(NH3)2Cl2 -> roaster + H2 -> Pd. Residue + Cl2 + NaOH (reactor) -> OsO4 and RuO4 (toxic "gas"
  species in the gas tank; separate them with pipe filters) -> Kroll + H2 -> Os, Ru. Remaining Rh/Ir +
  NaCl + Cl2 (chlorinator) -> Na3RhCl6/Na2IrCl6 -> reactor + NH4Cl: (NH4)2IrCl6 precipitates (-> H2
  -> Ir), rhodium stays dissolved (-> Zn cementation -> Rh). (Pd uses the industrial ammine route,
  not dimethylglyoxime.)
- **Uranium:** acid leach with O2 -> UO2SO4 (Ra -> RaSO4 stays in the tailings) -> NH3 -> yellowcake
  -> roaster -> UO3 -> H2 -> UO2 -> HF -> UF4 -> Kroll + Mg -> U. Thorium: ThO2 + HF -> ThF4 + Ca.
  Radium: RaSO4 + C -> RaS -> HCl -> RaCl2 -> molten cell. Fluorine: 2 HF -> H2 + F2 (catalyst KF,
  9 V).
- **U-235 tracking** (`process/Isotopes`, component `starwake:u235` = U-235 share of the uranium,
  absent = natural 0.72%): process machines keep a ledger (uranium in, U-235 in; reset when they hold
  no uranium) and stamp every uranium-bearing output item and their tanks; `ChemicalTank` keeps a
  `u235` share mixed by uranium mass and tags its chemical variants (non-natural only), so pipes carry
  it. Tooltips show it. Enrichment comes in 8g.
- **Gas Well** (`WaterSourceBlockEntity`, 3 kW, 0.2 kg/s natural gas: CH4, C2H6, N2, CO2, 0.3% He,
  H2S) on `#starwake:gas_field` biomes below sea level + 40. The **Air Separation Unit** separates
  piped-in gas first (helium recovery), otherwise the planet's air.
- **Dissolution leaching:** a Leach Tank also runs when its charge holds a soluble salt and there's
  water (no reaction needed). Metals are accepted as charges everywhere chemistry can use them
  (aqua regia on platinum sponge). Gaseous elements in a metal's composition become O2/N2/H2/F2/Cl2.

## How melting and refining work (Milestone 8d-1)

- **Bath** (`metal/Melt`): element kg + slag species kg. `liquidus()` = the analysed solidus + 50 C;
  `meltEnergyPerKg(T)` = 650 J/kg/K x (T - 25) + 300 kJ/kg (~1.35 MJ/kg for steel). Refining rules
  are data (`data/starwake/starwake/steelmaking/default.json`, from `tools/chemistry_process.py`
  `STEELMAKING`): oxidation order (Ca Mg Al Ti Zr Si B V Mn Nb Cr C P Fe; AOD moves C before Mn/Cr),
  oxides, residual floors (wt%), AOD carbon floor, O saturation, blow rate (kg O2/s), lime needed per
  P2O5, desulfurisation share and floor, deoxidised O level. `blow(o2, aod)` returns gases (CO,
  burned to CO2 above an open bath); `settle()` deoxidises with Al then Si and desulfurises under lime
  once deoxidised. Reactive bases (`Melt.REACTIVE`: Ti, Zr, Hf, Nb, Ta, V, U, Th, Be, REE, Li, Mg, Ca)
  pick up 0.5% O + 0.2% N per kg melted in air unless the inlet holds argon.
- **Assistant** (`Melt.plan(bath, grade)`): elements with a real minimum aim at the middle of their
  range; elements whose minimum is 0 are caps (never added, kept <= 80% of max); blowable elements
  (`BLOWABLE`: C, Si, Mn, P, Al, Ti, V, O) above target are listed as "blow down", others are
  diluted. Fixed-point solve for the final mass. Runs client-side on the synced bath.
- **Furnaces** (`metal/MeltFurnaceBlockEntity`, `registry/ModMetalworks`; numbers in
  `machine/<id>.json`): Induction Furnace (60 kW, 1750 C, 64 kg, open to air, can blow O2) and Vacuum
  Arc Furnace (120 kW, 3100 C, 32 kg, no pickup, no blowing). Slots: charge (any metal item, melts
  one at a time for its energy; refused when it would push the liquidus over the furnace limit or the
  bath past capacity), flux (lime/limestone/fluorspar -> slag; carbonates calcine), mould, metal out,
  slag out (1 kg slag items). Tanks: gas inlet (O2/Ar) and off-gas. The lance only blows once nothing
  is waiting to melt. Tapping (screen button) pours 1 kg ingots, or 10 kg billet/slab/casting with a
  mould (`MetalForm.BILLET/SLAB/CASTING`), stamped `starwake:condition` = "as_cast". Breaking a
  furnace drops the bath as ingots (a "skull") and vents the off-gas.
- **Screen** (`client/metal/MeltFurnaceScreen`, a `MachineScreen` with a side panel): bath mass,
  liquidus vs limit, analysed grade, composition, slag, status, target grade (< > x) with the
  assistant's lines, tapping toggle. Buttons are container button clicks (`MachineMenu.clickMenuButton`
  -> `MachineBlockEntity.onButton`); the bath arrives in `MeltStatePayload` twice a second, only for
  players with that furnace's menu open.
- **Sintering Press** (`metal/SinterPressBlockEntity`, 30 kW, 3 MJ/kg): blends everything in its four
  powder slots (metal dust, element powders, coke as carbon) once they've been unchanged for 2 s,
  drops H/O/N, and makes 1 kg "sintered" ingots (W, Mo, WC-Co, TZM).

## How forming and heat treatment work (Milestone 8d-2)

- **Temper** (`metal/Tempers.Temper`, component `starwake:temper`): state (annealed, as_cast, sintered,
  hot_worked, cold_worked, stress_relieved, normalized, quenched, tempered, solution, aged, cracked),
  cold work since the last anneal (thickness reduction 0..1), treatment temperature, current HV,
  forming progress (true strain done on a route) and the as-quenched HV (re-tempering starts from it).
  No component = the grade's **reference** temper (its handbook `properties`, e.g. 6061 = T6); the
  8d-1 `starwake:condition` string is still read as a legacy state. `Tempers.properties(stack)` is what
  tooltips (and later parts) use.
- **Data** (`tools/metallurgy.py`): every exact grade JSON has `tempers` (state -> yield, UTS,
  elongation, HV from handbooks), `reference_state` and `treatment` (program -> C).
  `starwake/heat_treatment/default.json`: program temperatures per family (fractions of the solidus in
  `default`), quench severities (water 1, oil 0.35, air 0.02), section sizes per form (inches),
  Grossmann multiplying factors per element, carbon ideal diameter, work-hardening factor per family,
  quench-crack carbon limits. `starwake/forming/<machine>.json`: `pass_strain` and routes {from, to,
  strain}. Both are server reload listeners.
- **Rules:** table entry for the state if the grade has one (scaled by the off-spec penalty), else
  physics: cold work UTS = uts0 (1 + 0.6 h (1 - e^-3cw)), yield -> 0.95 UTS, elongation e^-5cw; crack
  limit 0.25 + 1.2% per % annealed elongation (max 0.9; < 5% elongation = elongation/100). Steel
  (Fe base, 0.2-2.1% C, Ni < 6%) quench: D_I = 0.42 sqrt(C) x product(1 + f wt%), critical D = D_I (1 -
  e^(-1.4 H^0.6)), HRC = 35 + 50 C (max 66) -> HV, martensite share (D / section)^1.5 between normalised
  and full hardness; water quench cracks C >= 0.5%, oil quench C >= 0.9% in sections >= 2 in. Temper:
  HV falls toward annealed by ((T - 150) / 550)^0.9; UTS = 3.3 HV, yield 0.88 UTS (0.75 as quenched).
  Ageing only for grades with an `aged` temper: within 60 C of the grade's age temperature = peak,
  colder = under-aged, hotter = over-aged toward annealed. Hot-worked steel = normalised.
- **Forming machines** (`metal/FormingBlockEntity`, one BE for `rolling_mill`, `wire_drawer`,
  `extrusion_press`, `forging_press`; slots input / piece / output): one piece at a time, one pass per
  cycle; energy per pass = 2 x flow stress x strain x volume (hot flow stress 15% of UTS), min 1 s; hot
  mode (screen button, NBT `hot`) first reheats the piece to 0.75 x solidus (K) at 650 J/kg/K. A pass
  that cracks it ejects it as `cracked` scrap; a finished route splits the mass into the target form
  (slab -> 10 plates, billet -> 20 rods, rod -> 2 wires). Half-formed pieces keep their progress, so
  you can take one out, anneal it and put it back.
- **Heat Treatment Furnace** (`metal/HeatTreatFurnaceBlockEntity`, 250 kW, limit 1250 C): a whole stack
  per batch; programs (`Tempers.Program`: anneal, normalise, harden water/oil/air, temper, solution,
  age, stress relieve) cycle with < >, the setpoint (temper/age) with - +; other programs use the
  grade's temperature. Energy 650 J/kg/K x (T - 25) x 1.2. Changing the program mid-batch replans it.
  Tests: `/data merge block ... {program:5,setpoint:540}`, `{hot:1b}`; read results with
  `/starwake metal info <pos> <slot>` (prints temper + properties).
- Screens: `client/metal/MetalworkScreen` (data slots reuse the TANKS block: mode/strain/route/hot T,
  or program/setpoint/furnace T). `MetalForm.FORGING` (10 kg).

## How fabrication, electronics and hull materials work (Milestone 8e-1)

- **Silicon:** `Process.ARC_FURNACE` (Submerged Arc Furnace, PYRO at 2000 C; at >= 1800 C `ProcessEngine.route`
  keeps Si metallic instead of slagging it): quartz + coke -> metallurgical Si (Fe from the ash). Chlorinator:
  Si + 3 HCl -> SiHCl3 (bp 32 C, liquid outlet; impurities stay as residue). Kroll reactor (now has a gas
  outlet, and inlet-only reactions need no reagent): SiHCl3 + H2 -> Si + 3 HCl (Siemens). Reactions are in
  `tools/chemistry_process.py`.
- **Semiconductors** (`fab/SemiconductorBlockEntity`, one BE for two blocks; `layout()` reads the block state
  because it runs in the superclass constructor): **Crystal Puller** (100 kW, 30 MJ/kg, 0.05 kg Ar/kg from its
  argon tank) needs electronic-grade Si (no impurity above our 10 ppm composition resolution) and a dopant
  item (B/Ga/Al = p-type, P/As/Sb = n-type; one item = 100 doses); 10 wafers per kg, the dopant at 10 ppm in
  the wafer's composition. **Clean Fab** (50 kW, 5 MJ/wafer): wafer + opposite-type dopant + Al or Cu wire
  (99.99%+, one wire = 8 wafers) -> 8 microchips.
- **Fabricator** (`fab/FabricatorBlockEntity`, 30 kW): nine input slots, one output; blueprints are a synced
  dynamic registry (`fab/Blueprint`, `data/<ns>/starwake/fabrication/*.json`, generated by
  `tools/fabrication.py`). Inputs: a metal `form`, an `item` or an `element` (any item whose composition base is
  it), `count`, `base`, `main`, `min`/`max` gates (yield, tensile, elongation, hardness, service, conductivity,
  corrosion, density, modulus, purity), evaluated on `Tempers.properties`. `Blueprint.match` assigns slots
  (best margin first) and runs on both sides, so the screen (`client/fab/FabricatorScreen`, side panel, < >)
  shows each input as met / missing / failing a named gate with the present value. The product copies the
  main input's composition + temper; `starwake:quality` = the weakest gate margin (actual/required, capped at
  1.5) over inputs that have gates or carry a quality. Components (`ComponentItem`): pressure vessel,
  structural frame, combustion chamber, nozzle, turbopump, heat exchanger, magnet coil, control board.
- **Hull plating** (`ship/block/HullPlatingBlock` + `fab/MaterialBlockEntity`): item components (composition,
  temper, quality) stay on the block entity, ride in assembled ships' saved BE NBT (`components` key), and the
  loot table copies them back. `ShipEditing` places blocks with BE data from the item (`placedData`) and loads a
  throwaway BE from saved NBT so drops keep their components. `ship/data/ShipMaterials`: mass x density/7.87,
  heat limit = service temperature, armour = JSON armour x (UTS x min(elongation, 40)) / 10220; blocks without an
  alloy use ship_stats `heat_limit` (default 1000 C; hull 800, wood 300, wool 250, glass 700, thrusters 1400)
  and `armor` (default 6). Tuning: `starwake/hull/default.json` (`ship/data/HullTuning`).
- **Re-entry heating** (`ShipEntity.tickHull`, every 10 ticks): `ship/Reentry.intensity(ship, v)` (shared with
  the client effects; the server uses last tick's movement because the half-second ring isn't updated mid-tick)
  -> skin = 25 + 1650 x intensity^1.5 x sqrt(air density) C on the leading face (`outerFace` of the local
  direction of motion). Blocks over their limit soak 0.01 per degree-second and burn off at 1 (flames, chat
  message, `ShipEditing.destroy` with detachment). Synced SKIN_TEMP/HULL_LIMIT drive the FlightHud alert
  ("SKIN 640 C / 800", "HULL BURNING"). A steel hull survives intensity ~0.6 (about 2.4 b/t near the sky's top);
  aluminium needs to come in slow; a block under the Helm burning through cuts the Helm off (detach rule).
- **Impact armour** (`ShipEntity.hullImpact`): after shields, the energy is shared by the lowest layer facing
  the impact; each block soaks up to its armour and breaks (dropped, keeping its alloy) if its share is larger;
  the hull soaks at most `crumple` (50%), the rest hurts the crew as before. Logged on the server.

## How Mk tiers work (Milestone 8e-2)

- `registry/ModShipParts`: `Part` enum (thruster, main_engine, maneuvering_thruster, power_plant, cooler,
  shield_generator, quantum_drive, jump_drive, fuel_tank, landing_gear) -> blocks for Mk I (the original ids)
  and Mk II/III (`_mk2`, `_mk3`, same classes, properties copied). `get(part, mk)`, `tierOf(block)`.
- Every part is now an `EntityBlock`: `ThrusterBlock`, `LandingGearBlock` and `ship/block/ShipComponentBlock`
  (power plant, cooler, shield, quantum, jump) use `MaterialBlockEntity`; fuel tanks keep their own BE. Item
  components (composition, temper, quality) stay on the BE and in assembled ships' NBT.
- `ShipMaterials.withQuality(stats, q)`: thrust, power, cooling, shields, jump range, fuel capacity x (1 + 0.4 b);
  mass, fuel burn, power draw, heat, spool times x (1 - 0.3 b), b = q - 1 in 0..0.5. `rate(state, material,
  quality)` returns these stats in `Rating.stats`; `ShipDynamics.add(..., stats)` and `ShipSystems.add(...,
  stats)` use them (pre-flight from world BEs, the flying ship from saved NBT); fuel capacity too.
- Data: `tools/ship_parts.py` writes `ship_stats/<part>_mk2|mk3.json` from the Mk I file and
  `fabrication/<part>_mk1..3.json` (components gated by what they're made of: nozzle service >= 700/950/1300 C,
  chamber >= 700/950 C, turbopump yield >= 800/1000 MPa, coils >= 98/100% IACS, tanks >= 400/800 MPa...).
  `tools/mk_textures.py` derives the tier textures (gold band = Mk II, darker + two cyan bands = Mk III).

## 26.3 gotchas (learned the hard way)

- **`Component.translatable(key, fallback)` treats the second argument as a format arg**, not a
  fallback: use `Component.translatableWithFallback`.
- Fabric `FluidStorageUtil.interactWithFluidStorage` tries "fill the hand" then "empty the hand", and
  a held right-click repeats every 4 ticks, so a container item toggles back and forth. For
  multi-use items pick one direction per gesture (we fill on use, empty on sneak-use).
- **`MachineBlockEntity.layout()` runs inside the superclass constructor**: subclass fields aren't set yet, so
  derive anything it needs from `getBlockState()`. `ItemStack.EMPTY.getMaxStackSize()` is 1, so "room in the
  output" checks must test `isEmpty()` first.
- Non-implicit item components stay on a placed block entity (`BlockEntity.components()`), are saved with it and
  can be copied back by the loot function `copy_components` (source `block_entity`): no BE code needed.
- Fabric attachments: `AttachmentRegistry.create(id, b -> b.persistent(codec).syncWith(streamCodec,
  AttachmentSyncPredicate.targetOnly()))`; setting null removes it.

- **Fabric's `FluidVariant.of` rejects non-source fluids** ("Cannot convert flowing fluid"): a
  custom non-placeable fluid must return `isSource = true`.
- An enum constant's constructor arguments must not call the *enclosing* class's static methods if
  that class has static `EnumMap`s of the enum: the outer class initialises mid-enum-init and
  `EnumMap` throws on a null key universe. Put such helpers inside the enum.
- **`/item replace ... from block ...` copies** the slot (doesn't move it): clear the source in tests.
- The Friends List screen opens on the `friends` key (O): a test that types before chat opens can
  trigger it. `run/options.txt` unbinds `key_key.friends` and `key_key.socialInteractions`.
- **Menu data slots sync as shorts** (`ClientboundContainerSetDataPacket` writes shorts): split
  larger values across two slots.
- Fuel burn time is a data component in 26.3: `ResolvableInt.getFromItem(stack,
  DataComponents.COOKING_FUEL, CookingFuel::burnTime, lootContext, 0)`; `BaseContainerBlockEntity`
  provides `getLootContext(level)`. Block entities that are `Container`s drop their contents on
  removal automatically (`preRemoveSideEffects`).
- Render layers come from texture alpha (no `BlockRenderLayerMap` needed for cutout/translucent).
- The Friends List popup can reappear mid-test and swallow typed commands: check the chat log
  after each batch and dismiss it (`ingame`).
- Hex colours in data codecs: `ExtraCodecs.STRING_RGB_COLOR` ("#rrggbb"); `RGB_COLOR_CODEC` only
  takes ints or float triples. A bad entry in a dynamic registry aborts world loading.


- **`chain` is `iron_chain`** in 26.x (copper chains were added). Colour/copper block families are
  registered in loops, so grep `Blocks.java` for the family, not the id.
- `Level.setBlock` with `UPDATE_CLIENTS` updates neighbours' shapes but never the placed block's
  own: bulk placement needs a second `updateFromNeighbourShapes` pass. Avoid falling blocks and
  unsupported attachables (buttons, torches, grindstones) in templates; that pass removes them.


- **Mouse buttons are SDL numbers in screens:** `MouseButtonEvent.button()` is 1 for left and
  3 for right (`InputConstants.MOUSE_BUTTON_LEFT/RIGHT`), not GLFW's 0/1. Key constants are
  `InputConstants.KEY_*` (there is no `org.lwjgl.glfw` on the classpath).
- jspecify on nested types: `Galaxy.@Nullable Star`, not `@Nullable Galaxy.Star`.
- `SavedDataType` needs a `DataFixTypes` (null crashes on load); `server.getDataStorage()
  .computeIfAbsent(TYPE)`.
- Inside a ship the player's look is ship-relative, so `/tp ... facing` doesn't aim there; in
  tests use `/rotate @s <yaw> <pitch>` (seated pilots steer toward it).

- **Block states in JSON are `{"id": ..., "properties": {...}}`** (not `Name`/`Properties`).
- **Features are records implementing `Feature`** with a `MapCodec`, registered in
  `BuiltInRegistries.FEATURE_TYPE`; int providers come from `IntProviders.codec(min, max)`.
- **Material rules are data** (`worldgen/material_rule`, conditions like `minecraft:on_floor`,
  `minecraft:not_underwater`); in Java: `MaterialRules.sequence/ifTrue/isBiome/getRule`.
- **`@e` without a position argument selects entities in every dimension**, and only loaded
  ones: add `x=,y=,z=,distance=` to scope a query to one dimension.
- Console `/time set` changes the Overworld clock; use `execute in <dim> run time set noon`.
- F3 doesn't show the biome by default; test with `execute if biome ~ ~ ~ <id>` or `locate biome`.
- Fantasy (`xyz.nucleoid:fantasy`): `Fantasy.get(server).getOrOpenPersistentLevel(id, new
  RuntimeLevelConfig().setDimensionType(key).setGenerator(gen).setSeed(seed))`; open levels on
  SERVER_STARTED so players saved in them can log back in. Its Maven needs an
  `exclusiveContent` block, or Gradle tries Maven Central first and fails.
- `ServerPlayer.getTicksRequiredToFreeze()` is 140 and ticksFrozen thaws by 2 per tick:
  hold it above the threshold for vanilla freeze damage and the frost overlay.

- `ServerPlayer.drop` takes a `Prediction` (`drop(stack, false, Prediction.PREDICTED)`);
  `swing` takes `(hand, SwingAnimation, boolean)`. `Minecraft.rightClickDelay` is private: set
  it from a mixin when you cancel `startUseItem`, or a held click fires every tick.
- **Every solid block must be in `#minecraft:blocks_motion_no_leaves`.** In 26.3,
  heightmaps, ore placement, suffocation, fluid flow and teleport targets use this tag,
  not the block's shape. Without it, worldgen treats the block as hollow (our ores
  silently never generated). `ModBlockTagProvider` adds every `ModBlocks` entry.
- **The day/night cycle is data.** `world_clock` + `timeline` registries replace hardcoded
  time. Query a planet's time with `/time query starwake:veyra_day`; set it with
  `/time of starwake:veyra set <ticks>`.
- **Biome environment attributes take a plain value**, e.g.
  `"minecraft:gameplay/natural_mob_spawns": {"spawn_costs": {}, "spawns_by_category": {}}`.
  The `{"argument", "modifier"}` form also requires `modifier`.
- **Worldgen registries were renamed:** `worldgen/feature` (not `configured_feature`), and
  surface rules live in the `worldgen/material_rule` registry.
- **Datagen tag validation rejects references to tags the provider didn't define** (e.g.
  `#minecraft:dirt`). Put such tags in hand-written JSON under `src/main/resources`.
- **GUI API:** screens override `extractRenderState(GuiGraphicsExtractor, ...)` (not `render`).
  The current screen is `minecraft.gui.screen()` / `gui.setScreen(...)`. `ChatFormatting`
  no longer carries colours; use ARGB ints.
- **Blocks have no codecs** in 26.3; `HorizontalDirectionalBlock`/`DirectionalBlock` just
  need a constructor and `createBlockStateDefinition`.
- **Client entity position updates can land between ticks.** `commonTick()` saves the old
  position and interpolates before `tick()`. Measure per-tick movement against your own
  stored end-of-tick position.
- **Order-independent carrying:** carry riders at the start of *their* tick by the vehicle's
  movement since their last tick. Carrying after the vehicle moves (in either tick order)
  embeds the rider in the deck on upward motion, and collision ignores already-overlapping
  shapes, so they fall through.
- **Server movement validation** lives in `ServerGamePacketListenerImpl.handlePlayerPositionChange`
  (not `handleMovePlayer`). Mixin "Scanned 0 targets" = wrong method name.
- **Vanilla skips `useItem` (and Fabric's UseItemCallback) with an empty hand** when the crosshair
  hits nothing. Hook `Minecraft.startUseItem` for custom click targets.
- Fabric 26.3 names: `ClientTickEvents.END_LEVEL_TICK`, `ClientEntityEvents.ENTITY_LOAD`,
  `KeyMappingHelper.registerKeyMapping`, `InputConstants.KEY_*` (SDL-based key codes),
  `EntityRendererRegistry.register`, `PayloadTypeRegistry.clientboundPlay()/serverboundPlay()`.
- In xdotool client tests, use `mousedown 3; sleep 0.25; mouseup 3` for right-click.
  A bare `click 3` doesn't register in-game.
- **Entities only tick in "entity ticking" range**, and a level with no players barely
  ticks. Unattended ships in space won't move in headless server tests. Test with a pilot.
- **Screen background:** draw custom panels in `extractBackground` (after super). Drawing
  them in `extractRenderState` after super covers the widgets.
- **No compound-tag entity data serializer in 26.3**: encode small maps as STRING.
- `/data get entity <rider> Pos` shows the *vehicle's* position (vanilla saves riders at the
  vehicle). Log `passenger.position()` to see where a rider really is.
- PoseStack rotation is `poseStack.rotate(Quaternionfc)` (not `mulPose`).
- **Depth is reversed-Z** in 26.x (`DepthStencilState.DEFAULT` = GREATER_THAN_OR_EQUAL, depth
  cleared to 0). The camera far plane is `max(renderDistance*4, cloudRange*16)` blocks.
  Custom world geometry: `LevelRenderEvents.COLLECT_SUBMITS` ->
  `context.submitNodeCollector().submitCustomGeometry(poseStack, renderType, (pose, buffer) -> ...)`,
  in camera-relative coordinates (the pose stack starts at identity). `RenderTypes.debugQuads()`
  is position+colour, translucent, no depth write, sorted on upload.
- **World -> screen for HUDs:** `camera.getViewRotationProjectionMatrix(m).transformProject(relative)`
  gives NDC (y up); check `dot(dir, camera.forwardVector()) > 0` first.
- **Don't round-trip a look through yaw/pitch every frame with `Mth.sin/cos`** (lookup
  tables): the error accumulates into a visible drift. Skip zero mouse deltas, use `Math`.
- **Rider offsets:** vanilla subtracts the passenger's own `getVehicleAttachmentPoint` along
  world Y. On a rotated vehicle, add it back and subtract it rotated (`ShipEntity`), or an
  inverted ship seats you in the ceiling.
- **Plain CGI can pin a needed thruster at 0** (the Kestrel's slightly low retros read
  "reverse 0" until fixed): after the cascade, release the pinned thruster whose gradient
  helps most and re-solve (active-set step) while the request isn't met.
- `HudElement.extractRenderState(graphics, delta)` is the HUD callback; GUI transforms are
  `graphics.pose()` (a `Matrix3x2fStack`: pushMatrix/translate/rotate/popMatrix).
- **Control allocation:** projected coordinate descent on a weighted least-squares stalls
  when thrusters are near-duplicates and the torque weight is high (it reported 41% of lift
  wasted on a nearly balanced ship). Use a cascaded generalized inverse with priorities.
- Third-person screenshots of a ship from outside: `/attribute @s minecraft:camera_distance
  base set 16`, then F5.
- **traverseBlocks hands your callback a mutable BlockPos.** Anything kept beyond the call
  (e.g. a BlockHitResult) must use `pos.immutable()`.
- **Dedicated servers only:** "floating too long" kicks and "moved too quickly" checks don't
  run for singleplayer hosts, so ship bugs there only show up in multiplayer tests.
- After a long same-level teleport the client won't render an entity until its chunk arrives;
  ticket the destination ahead of time.
- **Ship stats are server data**: remote clients don't have them. Anything the client
  shows must be synced (entity data) or sent in a payload.
- When packing several percents into one int, give each value 3 digits (100 needs 3).
- `SimpleContainer` has no change listener in 26.3: subclass it and override `setChanged`.
- Camera internals: `Camera.setRotation(yRot, xRot)` builds `rotation` and the
  `forwards/up/left` vectors; `alignWithEntity(partial)` positions it. Both are mixable.
- Client harness: `ingame` dismisses the friends popup and the pause menu. Never press
  Escape to close a container screen in tests unless you're sure one is open.
- Teleporting a riding player with `/tp` dismounts them. In tests, re-board via right-click.
- `/place feature <ore>` fails on already-generated chunks (it needs worldgen heightmaps).
  Test ores by generating fresh chunks and counting with `/fill ... replace`.

## Dev workflow

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64   # cloud container; locally any JDK 25
./gradlew build          # compile + jar -> build/libs/
./gradlew runDatagen     # regenerate src/main/generated (commit the output)
./gradlew runClient      # dev client (playtest)
./gradlew runServer      # dedicated server: verifies the mod loads without client code
./gradlew genSources     # decompiled MC sources, for checking real API names
```

### Testing in the cloud container (no GPU)

- **Server:** create `run/eula.txt` (`eula=true`) and `run/server.properties`
  (`online-mode=false`), then `./gradlew runServer --args=--nogui`. Feed console commands
  through a FIFO (`setblock`, `execute if block`, `loot spawn ... mine ...`) and stop with `stop`.
- **Client:** OpenGL fails on Xvfb ("Couldn't find matching GLX visual"), but Minecraft
  26.x falls back to **Vulkan on Mesa lavapipe** (`mesa-vulkan-drivers`), which works.
  Run `xvfb-run -n 99 -f <auth> -s "-screen 0 1280x720x24 +extension GLX" ./gradlew runClient`
  with `XDG_RUNTIME_DIR` set. Drive the client with `xdotool` (`t` opens chat, then type
  commands) and capture with `import -window root shot.png`.
- Authlib "Services are unavailable" stack traces are expected offline and harmless.
- **Never `unset JAVA_TOOL_OPTIONS`.** It carries the proxy settings Gradle needs.

### Cloud environment

The `starfront` environment allows `maven.fabricmc.net`, `meta.fabricmc.net`, the Fabric
docs, and Mojang's download hosts. Its setup script installs `openjdk-25-jdk-headless`
(and the client-testing tools: `mesa-vulkan-drivers`, `xdotool`).
`maven.nucleoid.xyz` (Fantasy) was added to the allow-list on 2026-11-11. Any other new
dependency host must be added to the environment before it works.

## Conventions

- Java 25 language features are fine: records, sealed types, pattern matching.
- Mod id `starwake`. Every registry id is lowercase_snake (`starwake:hull_plating`).
- Register through the helpers in `registry/` (they apply `setId`, which 26.x requires).
- Lang, models, loot and tags come from datagen (`src/client/java/.../datagen`). Don't
  hand-edit `src/main/generated`. Textures in `src/main/resources/assets` are hand-made.
- Every class gets a short header comment explaining its role in its system.
- Commit after every working milestone. Message format: `M<n>: <summary>`.
- **Deliver a jar every milestone:** `./gradlew build`, then send `build/libs/starwake-<ver>.jar`
  to the user. CI (`.github/workflows/build.yml`) also uploads the jar as an artifact on every push.
- Bump `mod_version` in `gradle.properties` per milestone (0.<milestone>.0).
- Client test harness: `$scratchpad/mc.sh` (`start_client`, `join_world`, `ingame`, `cmd`,
  `shot`, `rclick`, `save_quit`, `build_powered_ship X Y Z [coolers]`, `build_sc_ship X Y Z`; the older
  `build_*_ship` helpers have no power plant, so they no longer pass pre-flight). Always `save_quit` before restarting, because a hard kill
  loses everything since the last autosave. Set `/gamerule fall_damage false` in test worlds.
- `srv` writes to a FIFO: with no server reading it, a plain `echo > fifo` blocks forever (it hung several test
  runs). `mp.sh`'s `srv` now checks the FIFO exists and wraps the write in `timeout 5`.
- Multiplayer tests: `$scratchpad/mp.sh` (`start_server`, `srv "cmd"`, `start_mp_client NAME
  DISPLAY GAMEDIR`, then `DISPLAY=:N xdotool ...`): a dedicated server plus clients launched
  straight from the captured java command line (`client_cmd.txt`) with `--username` and
  `--quickPlayMultiplayer localhost`, each on its own Xvfb display. `op` players only after
  they've joined once; the server has a whitelist (`whitelist off`).
- `run/options.txt`: keep `lang:en_us` and `realmsNotifications:false`; dismiss the offline
  Realms popup with Escape, never by clicking near 638,515 (the language button).
- In the cloud container, don't block on long waits: start servers and clients in the
  background and poll with short, bounded loops.
