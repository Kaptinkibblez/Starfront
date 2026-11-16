# Starwake

A Fabric overhaul mod for Minecraft, inspired by No Man's Sky: fly between procedurally
generated planets, live in a walkable starship, trade with factions at their hubs, and climb a
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
├── trade/                  factions, hubs, price model, industrial market, trade menus
├── progression/            fuel, upgrades, crafting chains
├── network/                custom payloads (C2S/S2C), one record per packet
├── comms/                  Wristcom Comms (messages, hails)
└── config/                 server config (StarwakeConfig)
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
- [x] **8. Progression + metallurgy.** User's choices (2026-11-11; "the biggest one yet, the full
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
  - [x] 8f. Real propellants and fusion fuel. Defaults chosen overnight: fuel tanks take H2, O2, CH4, D2 and
        He3 from pipes and Gas Cylinders and burn matching pairs into units (hydrolox O/F 6 = 150 units/kg,
        methalox O/F 3.6 = 120 units/kg); thrusters keep burning propellant units; quantum and jump drives now
        burn **fusion units** (D + He-3 at the atom ratio, 1 unit = 0.1 mg) from a separate pool (10x the
        propellant capacity); coal/blaze items stay as a provisional fallback until 8i; heavy water from a new
        Isotope Column (natural D/H, ~2.4 kWh per g of D2O), D2 by alkaline electrolysis of D2O, He-3 from
        primordial helium on noble-gas-rich (crystal) worlds through the ASU. *(Verified 2026-11-12 on a
        dedicated server: 10 kg of water -> 1.73 g D2O per cycle; 0.2 kg D2O -> 0.0402 kg D2 + 0.16 kg O2; a
        Fluid Tank of 1 kg H2 + 6 kg O2 + 1 g D2 + 1.5 g He3 piped into a Fuel Tank -> 1049 fuel units and
        fusion filled to its 20000 cap, leftovers kept; a tank's 777 fusion units moved into the assembled
        ship; `/starwake ship refuel` adds both.)*
  - [x] 8g. Nuclear. Defaults chosen overnight: Gas Centrifuge (UF6 lots, heads ratio x1.6 per pass, cut 1/2,
        U-235 stamped on both outputs; UF4 + F2 -> UF6 and UF6 + H2 -> UF4 in the Roasting Kiln); Fission Reactor
        (500 kW grid generator; critical at >= 3% U-235 with light water or natural uranium with heavy water;
        redstone = SCRAM; real 8.2e13 J/kg burn x1000 time compression; Pu bred at 0.6 per U-235 burned and 20
        fission-product elements by real yields written into the fuel's composition; spent fuel ejected;
        irradiation slot along real capture chains U -> Pu -> Am -> Cm -> Bk -> Cf -> Es -> Fm, Bi -> Po, Th -> Pa
        -> U, Ra -> Ac, Np -> Pu); Fusion Reactor (2 MW, D + He3 at 3.5e14 J/kg x 0.4); Particle Accelerator
        (Md...Og from the real target/beam pairs, 2-30 GJ per sample). *(Verified 2026-11-12 on a dedicated
        server: natural UF6 -> heads 1.147% / tails 0.293% U-235; 5% uranium + water ran at 500 kW, U-235 fell
        and the ingot gained Pu 60 ppm plus Cs, Ce, Mo, Ru, Nd, Zr, Xe; natural uranium stayed subcritical in
        light water and ran in heavy water; a bismuth target became a polonium sample; the fusion reactor ran
        on 1 mg D2 + 1.5 mg He3 and powered the accelerator into a mendelevium sample (Es + He).)*
  - [x] 8h. Material gear and suit upgrades. Defaults chosen overnight: Fabricator-made pickaxe, axe, shovel,
        sword and four armour pieces whose every stat comes from the main plate's alloy per stack (mining tier
        from hardness, speed 3 + HV/50, durability from UTS x elongation x quality, damage + UTS/300; armour from
        yield, toughness from UTS x elongation, knockback for dense alloys, a speed penalty by density), tinted
        and named by the alloy; Survey Suit upgrades (lead lining halves external gamma, oxygen tank +5 min,
        thermal layer -100/+240 C, armour insert +4), max 2 each. Weapons beyond the sword wait for M9.
        *(Verified 2026-11-12 on a dedicated server: tempered 4140 pickaxe = diamond tier, speed 9.2, 1633
        durability; mild-steel pickaxe = stone tier, 4.9, 848; Ti-6Al-4V chestplate 11.5 -> capped design (now
        ~8.4) with 1.2 toughness and -1.1% speed; a suit took a lead lining from Pb plates; a third lining refused.)*
  - [x] 8i. Wristcom Codex and the recipe ladder. Defaults chosen overnight: a **Codex** app (12 illustrated-by-text
        entries from ore to fusion, the whole ladder in order); the recipe ladder is **vanilla iron -> our plates ->
        components**: tier-1 machines (power, logistics, crusher, flotation, roaster, coke oven, smelter, converter,
        induction furnace, rolling mill, wire drawer, Fabricator) stay vanilla-material crafts; every later machine
        (wet chemistry, separation, SX, VAR, sintering, extrusion, forging, heat treatment, arc furnace, crystal
        puller, clean fab, isotope column) needs metal plates (any alloy, from the Rolling Mill); nuclear machines
        (centrifuge, fission, fusion, accelerator) are **fabricated from components only** (no crafting recipe);
        ship parts Mk I stay craftable, Mk II/III fabricated. Balance numbers were left as verified in each part.
        *(Verified 2026-11-12 in the client: the Codex app opens from the Wristcom app bar and lists every entry;
        the world loads with the new blueprints; datagen recipes regenerated.)* **Milestone 8 is complete, awaiting
        the user's playtest.**
- [ ] **9. Polish.** Performance, multiplayer sync, config, balance.
  - **User notes for M9 (2026-11-12):** conveyors move items the wrong way; ship flight is buggy
    all around (overhaul it); hub NPCs (guards/crew) add little value: **remove them**.
  - **M9 plan (user, 2026-11-12):** go through every system one by one (ships, planets/space/galaxy,
    industry, trade/UI/multiplayer/config) with **audit -> ask -> fix**; ask about nearly everything; one jar
    at the end (0.9.0). Also: **more distinct, higher-quality block models and item sprites**. Other mods
    (VS2, Sable, Create Aeronautics, Rayon...) are **references for design only; we write our own code**
    (Sable = PolyForm Shield, Aeronautics = custom/ARR, VS2 = LGPL-3.0; Starwake is ARR).
  - **M9 ships (decisions 2026-11-12):**
    - *Shipyard:* ship blocks move into **real chunks in one hidden `starwake:shipyard` dimension** (VS #319's
      never-built "store ships outside every world" idea): crossings move only the ship entity; machines,
      redstone and every block run aboard; machines read the environment of where the ship really is. Old
      ships are migrated. Size cap **65,536 blocks**.
    - *Flight feel:* Star Citizen, refined. Mouse-aim **and** a joystick mode (mouse = turn rates, view locked
      to the nose), toggled by a key and remembered. Top speeds raised (~100 m/s atmosphere, ~250 m/s space).
    - *Unpiloted ships* hover at an **idle burn (~1/10 of takeoff)**; out of fuel they land if the ground is
      near, else fall. *Descent (Ctrl, coupled):* up to ~25 m/s, easing to ~3 m/s in the last 15 m.
    - *Netcode:* the **pilot's client predicts** the flight, the server corrects smoothly; others interpolate.
    - *Contacts:* **rigid-body contact solver** (our own): rotated block boxes vs terrain/ships, friction,
      a little bounce; ships slide, tip onto gear and settle.
    - *Walking aboard:* players are tracked **in ship coordinates on both server and client**.
    - *Pilot view:* eye raised above the dash; vanilla's "Shift to dismount" replaced by our own hint.
    - Audit findings (2026-11-12): 26.3 moved grass/podzol/mycelium out of `#minecraft:dirt` (into
      `#minecraft:grass_blocks`, under `#minecraft:substrate_overworld`), so ships built on grass swallowed the
      ground (fixed in `ship_excluded`); one test lost a Kestrel (blocks gone server-side, still drawn, no
      entity; not reproduced yet; likely the plot-sharing bug fixed in S5).
    - [x] **Ships rebuilt (S1-S6, 2026-11-12/13).** *(Verified: singleplayer + a dedicated server with two clients:
      pilot climbs/flies at ~30 m/s while a second player walks aboard and stays in the cabin, no kicks; shipyard
      migration of 6 old ships; real chest menu aboard (emeralds taken, plot chest emptied); furnace smelting
      aboard and its ingots kept through disassembly; breaking/placing through the plot; wall impact stops the
      hull flush with a small bounce, diagonal push slides along the wall; forced drop lands and settles without
      jitter; parked ships sleep; prediction matches the server exactly in singleplayer.)* Details in "How ships
      work after M9" below.
    - **Ships pass 2 decisions (user, 2026-11-13):** audit found rain falling through hulls, chests/signs invisible
      aboard, parked unpiloted ships still burning idle fuel, observers snapping instead of interpolating, a caged
      canopy view, and a weak, over-winged, boxy Kestrel. Choices: **remove wings entirely** ("a starship doesn't have
      traditional wings": the Wing block, lift and wing HUD/pre-flight go; saved wings become hull plating) with
      **drag only** in atmospheres (scaled by air density and the hull's cross-section); a frameless **Canopy Glass**
      block; **every block-entity renderer aboard, with animations** (chest lids, sign edits); HUD **scale/opacity
      settings** and **contact markers** for nearby ships; **Kestrel redesign + rebalance** (sloped hull, no wings,
      stronger RCS, enough power).
    - [x] **Ships pass 2 (2026-11-14).** *(Verified: singleplayer + a dedicated server with two clients: a parked ship
      keeps its exact position and fuel for minutes; chest, sign and banner render aboard; the chest opens aboard and its
      lid closes again; a sign placed aboard opens the editor and takes text; a spectator watching the pilot sees the
      Kestrel move smoothly with the pilot in the canopy; contact bracket "SHIP 44 m 0 m/s" on a ship ahead; new Kestrel
      passes pre-flight (342 blocks, 473 t, lift 1.78x, accel fwd 19 / back 3.8 / side 3.8 / up 36 / down 1.9 m/s²,
      pitch 211 / yaw 73 / roll 291 °/s², drag-limited top speed 100 m/s, cabin sealed) and flies at 46 m/s.)* Details in
      "How ships work after M9" below. Rain under ship roofs was only checked in stills (inconclusive in the headless
      client).
    - **Ships pass 3 decisions (user, 2026-11-14): custom hulls and Reclaimer-class ships, now.** Goal: build something like
      Star Citizen's Reclaimer with complex detail. Choices: **shape-cycling Hull Panels** (one panel item per material that
      keeps its alloy; a Hull Tool cycles the placed panel's shape) with **all four shape families**: slopes and wedges (1:1,
      1:2, 1:4 + corner, inner-corner, peak), curves and rounds (quarter-rounds, rounded corners, convex/concave, cylinders),
      thin panels and plates (half/quarter plates, angled armour, fins), frames and trusses (open frames, lattice beams,
      ribs); **greebles/decorative parts** (vents, pipes, conduits, seams, antennas, railings, ladders, catwalks, ribbed
      panels, hazard trims, cargo racks); **lighting** (running, interior strip, flood, landing lights, nav strobes); **big
      functional parts** via a **general Hinge (two set angles) and Rotor (free turn) block**: everything built beyond it is
      a sub-assembly that rotates as a unit, and thrusters on it push along their real direction (Reclaimer-style side pods
      swinging from down to back); hinges are **linked to the landing gear** (gear down = VTOL pose, gear up = cruise pose);
      **true scale** (1 block = 1 m, ~155 m Reclaimer) with the ship cap raised to **262,144 blocks** plus the big-ship
      performance work (delta block updates instead of full resyncs, per-section meshes, a simplified collision hull).
      Paint/liveries not chosen. Build order: 3a big-ship performance + cap, 3b hull panels + Hull Tool, 3c greebles +
      lighting, 3d hinges/rotors + gear-linked articulation, 3e a large demo blueprint; one jar at the end of M9 still.
      *Demo ship (user, 2026-11-14: "as close as you can to the Reclaimer while still original"):* an **original** heavy
      salvage ship of the same class and scale (long industrial hull with exposed frame, big articulated salvage claw,
      gear-linked swinging engine pods, processing/cargo bay, raised command deck, tractor mounts, hazard-striped greebles),
      not a copy of the Reclaimer's silhouette, proportions or signature details. **Ships pass 3 done (3a-3e, 2026-11-15).**
    - [x] **Ships pass 4 (2026-11-15):** auto-connecting hull pieces (slopes, curves, coves at 1:1/1:2/1:4, wedge, plates,
      pillar, frame) replace Hull Panels and Hull Forms; pre-flight checks the gear-up pose. Details below.
    - The user wants to revisit hull shapes later ("im picky"); moved on to planets 2026-11-15.
  - **M9 planets, space and galaxy (decisions 2026-11-15).** Audit: airless worlds lit like night, items/arrows at Earth
    gravity, overlapping space HUD markers, another system's space listed as a planet, vanilla sun on every planet, Earth
    plants on lethal worlds, endless flat planets, empty space between planets. Choices:
    - *Real sky:* the system's own star (colour/size from its class, both stars of a binary), the other planets and moons at
      their true direction and size with phases; day length follows the planet.
    - *Full alien pass:* per-archetype palettes and flora that fit the conditions (no Earth plants where it's lethal), new
      terrain features (canyons, spires, lava fields, ice shelves), more variety between planets of one type.
    - *Finite wrapping planets:* a square surface sized from the radius, **8-32 km around** (moon ~8, Earth-size ~32);
      walking off one edge brings you back at the other; the space position matches exactly.
    - *Moons:* **0-3 per planet** (bigger planets more), airless barren/frozen/volcanic, landable, shown in both skies.
    - *Asteroid belts:* **real 3D block fields** in space between the rocky and outer planets, generated on approach, mined
      with tools in EVA or from a ship; real compositions (S silicate, C carbonaceous, M metal, icy).
    - *Moving orbits:* **real Kepler period ratios, sped up** (innermost planet a few in-game days, outer ones weeks);
      positions from game time on both sides.
    - Not chosen: gas giants.
  - **M9 industry (decisions 2026-11-16).** Audit: belt chevrons pointed opposite to the real movement (the "wrong way"
    report); hoppers and belts dropped items into the first free slot (coke could block a smelter's charge slot); machines
    never fed each other; tanks didn't show their fluid; no way to see pipe or cable flow; plain screens. Choices: **fix the
    arrows** plus **items drawn riding on belts**, **splitter + filter belts**, **inclines and an item lift** (the user pointed at
    Mekanism, MIT, as a design reference: own code); **smart slot routing**; **direct machine chaining**; **tanks show their
    fluid**, **pipe/cable readout**, **textured machine screens** (the rest of the visuals wait for the art pass).
    - [x] **Industry pass (2026-11-16).** *(Verified in the client: belts -> incline -> raised belts -> decline -> splitter
      round-robin into three chests; filter sends cobblestone right, dirt straight; a 3-high lift delivers to a chest at the top;
      a hopper of coal, quartz and iron sorts itself into the smelter (iron charge, coal + quartz reagents); crusher output chains
      into a flotation cell; items lie flat on moving belts; tanks fill visibly; pipe readout "1.0 kg/s Water"; cable readout
      "Grid: 10.0 kW generated, 3.0 kW wanted"; machine screens with the new panel, wells, output slot and arrow.)* Details in
      "How industry works after M9".
  - **M9 trade, UI, multiplayer and config (decisions 2026-11-16).** Audit: hub NPCs still in; markets priced by item id, so
    nothing M8 makes (alloys, refined metals, concentrates, components, Mk parts; all composition variants of a few ids) could
    be sold; no server config; "Coming soon" Cargo and Comms apps. Choices: **remove NPCs, nothing replaces them**; **value by
    composition** (industrial market); a **server config** with **hazards, ships, travel/time and economy** groups plus an op
    command; **build Cargo and Comms**.
    - [x] **Trade/UI/config pass (2026-11-16).** *(Verified on a dedicated server with two clients: `/starwake config` lists 24
      settings, sets numbers and switches, rejects unknown keys and rewrites `config/starwake-server.json`; appraisals: 304 plate
      12.9 cr, Inconel 718 wire 6.7, C11000 copper ingot 23.5, Mk III main engine 9000, control board 600; at the Overworld Trade
      Port the Industrial tab lists the carried products and selling 8 plates paid 88 cr; Cargo shows the Kestrel's fuel/fusion
      and hold; Alice's Comms message reached Bob (inbox + action bar); hailing the Trade Port and Veyra Mining Camp returned
      location, standing, docking and services.)* Details in "How trade, Comms, Cargo and config work after M9".
  - **M9 economy (decisions 2026-11-16).** User: "more like Star Citizen's dynamic but also somewhat player driven; fun with 1 or
    1000 people on", and **no NPCs or NPC-flown ships out and about**: everything is simulated in the background. Choices:
    **production chains** (hubs need inputs to produce), **simulated haulers** (virtual, no entities), **hub growth/decline**
    (**physical growth, soft decline**: three tiers; reaching one builds designed expansion modules; declining never removes
    blocks, the modules' lights go dark; building and dimming happen **only while no player is near**); **player shops**
    (Trade Kiosk), **player-posted contracts**, **supplying hubs** (deliveries fill their stock); **NPC fill-in + deeper
    markets** with more traders; market info is **what you've seen** (prices remembered with their age, plus history). Hub
    look overhauled in the **Star Citizen industrial** style (stations: core, spoked arms to enclosed lit hangars, hab ring,
    solar trusses; outposts: modular habs on tubes, raised pads, mast, cargo yard, signature facility).
    - [x] **Economy pass (2026-11-16).** *(Verified: singleplayer: the new Overworld Trade Port at tier 1 (core, east/west
      hangars, roof solar), `/starwake hub tier "solace/station" 3 now` added the hab ring, north/south hangars, solar trusses and
      comms; set back to tier 1 it kept tier 3 lit while the player was near (`modules 3 (lit 3)`) and dimmed once they were 600
      blocks away (`lit 1`), the dimmed north arm dark inside; outpost tiers 1-3 placed together (hall, crew hab, processing
      hall, silos, two pads, watchtower). Dedicated server with two clients: Bob bought 8 diamonds from Alice's kiosk (Alice paid
      200 cr offline-safe); Alice posted "Supply 16 Emerald" (300 cr escrowed), Bob accepted, handed in and was paid, Alice
      collected 16 emeralds at the board; the Trade app's Prices tab listed what Alice saw at the terminal "just now"; an
      outpost placed on a slope stood all 14 perimeter posts on footings.)* Details in "How the economy works after M9".

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
  `#starwake:ship_excluded` (hand-written tag JSON in `src/main/resources`). Cap 262,144 blocks (M9 pass 3; lower it with
  the `ships.max_blocks` config).
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
  - *Wings* (removed in M9 pass 2; kept here for history) (`WingBlock`, a thin plate; AXIS = plate normal: Y = wing, X/Z = fin). Each panel in
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
## How ships work after M9 (shipyard, contacts, prediction, walking)

- **Shipyard** (`ship/yard/Shipyard`): every assembled ship keeps its blocks as real blocks in the void dimension
  `starwake:shipyard` (datapack `dimension/shipyard.json`, type `shipyard`: no skylight, Y -256..255). Plot `i` is a
  1024x1024 column at x, z >= 2^20 (`centre(i)` = ship-local (0,0,0)). Saved `State` (`starwake:shipyard`): next index,
  free list, **owner UUID per plot** (only the owner may clear/free; a ship whose plot belongs to another becomes a new
  plot from its snapshot). Ships renew a 5 s `starwake:ship_plot` ticket (loading + simulation + keep-dimension-active),
  so block entities tick while the ship is loaded.
  - The `ShipStructure` stays as a **snapshot** for physics, collision, rendering and clients. `ServerLevelShipyardMixin`
    hooks `updatePOIOnBlockStateChange` (HEAD) -> `Shipyard.onBlockChanged` -> `ShipEntity.onPlotChange`; changes are
    folded in once per tick (`applyPlotChanges`: block-entity NBT read back, detachment check, `applyEdit` resync).
    On load a ship compares its bounds (+1) with the plot (`plotSynced`). Old ships migrate into a plot on first tick.
  - Entities spawned in the shipyard (drops, XP) are recreated at the ship's real position (`Shipyard.relocate`), and
    sounds/level events are replayed there. `PlayerMixin.isWithinBlockInteractionRange` measures reach to where a plot
    block is on the flying ship, so vanilla menus stay open aboard.
  - Interaction (`ShipInteraction.useInPlot`): Helm/Seat/fuel stay special; everything else is vanilla `useItemOn` /
    `useWithoutItem` / `useOn` in the plot. Block items place with `ShipPlaceContext` (plot level, ship-local look).
    Breaking (`ShipEditing.finishBreak`) and damage (`destroy`) remove plot blocks (`Shipyard.remove`), drops to the
    player (container contents always). Assembly writes the plot (`Shipyard.write`), disassembly reads it back.
  - Machines aboard read the real environment through `Shipyard.locate` (solar panels: sky and star of the ship's world;
    process machines: that world's air; pumps: the biome under the ship).
  - Admin/testing: `/starwake ship info [local]` (plot, helm, a cell snapshot vs plot), `pilot` (own + seat at the Helm),
    `disassemble`. Plots are visible with `/execute in starwake:shipyard ...`.
- **Contacts** (`ship/physics/ShipMotion`, our own design after VS2/Sable's ideas): the ship is a rigid body about its
  **centre of mass**; probes = every corner of the outer hull blocks' collision boxes (`hullProbes`). Per substep (fast
  ships substep, <= 0.4 blocks per step): detect probes inside terrain/other ships (normal = nearest face not buried
  against a full neighbour) -> sequential impulses (8 iterations; Coulomb friction 0.7 on two fixed tangents, clamped
  as a 2D vector; restitution 0.15 only for real impacts near the surface) -> integrate -> split-impulse push-out (4
  iterations, capped 0.25 blocks / 0.02 rad per substep). Contacts can't add more than 0.3 b/t or 0.03 rad/t (overlaps
  push apart gently). Other ships get equal and opposite impulses. Unpiloted grounded ships sleep. `Result` -> impact
  damage (`onImpact`), touchdown rules, `grounded`. `ShipMotion.DEBUG` logs every solve.
- **Flight** (S1): eye 1.4 above the Helm; seat hint replaces vanilla's dismount text; **joystick mode** (K;
  `client/ship/FlightControls`, saved in `config/starwake-client.properties`): mouse deflects a virtual stick (HUD ring),
  view locked to the nose, Alt free-look. Coupled Ctrl descends up to 25 m/s easing to 3 m/s in the last 15 m
  (`clearance` probe). Unpiloted ships hover at a tenth of the hover burn, land on the reserve, fall when dry. Speed
  caps 5 b/t (atmosphere, 1.25x boost) / 12.5 b/t (space), drag 0.992; chunks ~3 s ahead get a ticket. Landed ships
  don't steer. Re-entry heating now needs > 2.5 b/t and, in an atmosphere, a descent.
- **Prediction** (S5): the pilot's client flies its own copy: `ShipPhysicsPayload` (tuning, on join/reload) and
  `ShipDynamicsPayload` (the server's rigid-body model) make the client simulation identical. Each client tick:
  `ShipInputPayload(seq, input, controls, stick, look)` -> local `fly()` + `ShipMotion.step` -> history. The server
  queues inputs and flies **one step per input** (max 5 per tick; flies on with the last input after 10 silent ticks),
  then sends `ShipStatePayload(seq, state)`. The client adds the difference to its state and later predictions (> 4
  blocks or 0.6 rad snaps). While predicting, the ship ignores server position/motion packets (custom
  `InterpolationHandler`, `lerpMotion`) and keeps its own orientation (`predictedOrientation`). Observers use vanilla
  per-tick tracking. `Planets.gravityOf/airDensityOf(Level)` work on clients.
- **Walking aboard** (S6): the walker's client (ShipLocalMotion) sends `ShipFootingPayload(ship, local pos, onGround)`
  every tick (-1 when stepping off); `ShipFooting.carry(ship)` places those players from the server's ship transform
  after each ship tick, so other players see walkers glued to the deck.
- **Pass 2 (2026-11-14):**
  - *No wings:* the Wing block is gone (a Fabric registry alias loads saved `starwake:wing` as hull plating). Air drag is
    `ShipDynamics.drag`: per local axis `air_drag x density x cross-section x |v| x v / mass` (cross-sections = the
    hull's projected silhouette, counted in `finish()`, sent in `ShipDynamicsPayload`), never more than stops the ship in
    a tick; `drag_atmosphere` is now a light 0.998. Pre-flight reports the frontal area and the drag-limited top speed.
  - *Parked ships sleep:* an unpiloted ship resting on the ground (`grounded` or clearance < 0.05) stops simulating and
    burning until moved, edited (`applyEdit`) or piloted (fixes slow creep and idle burn while parked).
  - *Observers:* the custom `InterpolationHandler` queues server positions and the ORIENTATION data (separately,
    `onSyncedDataUpdated`) and plays one of each per tick (max 3 queued; > 16 blocks snaps). The pilot predicts as before.
  - *Block entities aboard* (`client/ship/ShipBlockEntities`): `ShipStructure.STREAM_CODEC` now sends each block
    entity's `getUpdateTag` (sign text, banner patterns; never inventories). The client keeps "ghost" block entities per
    ship (kept across snapshots so animation survives) and `ShipRenderer` extracts/submits them with vanilla's renderers
    in the ship frame (camera moved into ship space; light from the world cell). Chest lids: the server relays block
    events from the plot (`ServerLevelShipyardMixin.doBlockEvent` -> `ShipBlockEventPayload`) and ghosts tick
    `lidAnimateTick`. `ContainerOpenersMixin` counts a plot chest's viewers around the ship's real position (vanilla
    looked in the empty shipyard and the lid stuck). Sign text edits: `sendBlockUpdated` without a state change ->
    `onPlotDataChange` resyncs; `SignEditorMixin` opens the editor on a ghost copy at the plot position once the new
    snapshot arrives; `SignBlockEntityMixin` finds the editor server-wide; the sign update packet is applied in the yard.
    Synced `PLOT` index lets clients map plot positions to ships.
  - *Plots reused cleanly:* `Shipyard.writeFresh` wipes the new ship's bounds + 2 before writing (a previous ship's
    leftover block above a chest kept it from opening, vanilla's solid-block-above rule).
  - *Rain:* `client/ship/ShipRain` records per tick the top of each ship over every nearby column; `WeatherMixin`
    (rain columns) and `ClientLevelWeatherMixin` (splashes, rain-above sound) stop there.
  - *Blocks:* **Canopy Glass** (frameless tinted glass, 1200 C, armour 4, seals; 8 from 8 glass + 1 iron) and **Hull
    Plating Stairs / Slab** (stonecutter-style recipes; plain steel, 1.1 t / 0.75 t).
  - *HUD:* Wristcom **Settings** app (steering mode, cockpit HUD size 60-160%, opacity 30-100%, saved in
    `config/starwake-client.properties`); **contact markers** (bracket, owner name or SHIP, range, closing speed; red when
    closing > 20 m/s) for up to 8 ships in view, limited to ships the client tracks (~160 blocks); the skin-temperature
    alert shows from half the hull's limit. `/starwake ship assemble` lists the failed checks when it refuses.
  - *Kestrel* (`tools/kestrel_blueprint.py`): wedge nose and sloped shoulders from hull stairs/slabs, a Canopy Glass
    bubble (2 clear blocks over the Helm), a spine with 6 plants/coolers, shield, quantum drive and two tanks, side
    sponsons with medium strafe/retro thrusters, four belly VTOL mains around the centre of mass, two rear mains, RCS.
- **Pass 3a, big ships (2026-11-15):** cap **262,144 blocks** (`ShipScanner.MAX_BLOCKS`; cabin flood 1M cells).
  - *Delta sync:* `ShipEntity.applyEdit(next, changes)`: up to 4096 changed cells are folded in incrementally and sent as
    `ShipDeltaPayload` (cells + client tags, `version = previous + 1`); more, or no change list, resends the whole
    `ShipStructurePayload` (now versioned). A client that misses a version asks with `ShipResyncPayload`.
    `ShipStructure.withChanges` patches clones of the entry list, state map and index (removals swap the last entry into the
    hole; changes apply in position order so both sides get the same entry order, which synced throttles index into).
    `outerFace` is built lazily.
  - *Incremental model:* `ShipDynamics` keeps running sums (mass, first and second moments, shadow counts) with
    `add`/`remove`/`addThruster`/`removeThruster`; `finish()` derives the centre of mass and inertia. `applyIncremental`
    removes and adds the changed cells, re-checks thrusters whose nozzle cell changed, refreshes the hull map around them,
    and rebuilds systems only from `components` (functional parts). The cabin seal is computed lazily (`ensureSealed`).
  - *Probes:* above `PROBE_BUDGET` (6000) the hull corners are thinned to one per lattice cell (the farthest from the
    centre of mass; gear keeps all). On big hulls an edit marks probes stale and they're rebuilt 10 ticks later.
  - *Detachment:* `ShipEditing.detached(structure, helm, removed)` floods from the removed cells' neighbours, at most 4096
    cells per group; a group that closes off without the Helm breaks away; two big groups that never meet fall back to the
    full flood.
  - *Rendering:* `ShipMesh.buildSections` tessellates 16-block sections with face culling between ship blocks;
    `ShipRenderer` keeps sections per ship and rebuilds only those around changed cells (`ShipEntity.takeMeshChanges`),
    skips sections behind the camera, and re-samples light round-robin (16k blocks per frame).
  - Ships skip vanilla's `updateFluidInteraction` and `applyEffectsFromBlocks` (both scanned the whole bounding box
    every tick: 15 s of server lag for a 212k ship in the ocean).
  - Measured (212,065 blocks, 150x60x80): assembly ~3.5 s once (lift + plot write 2.5 s), model 0.4 s server / 0.36 s
    client on load, full mesh 0.5-1 s, physics ~1 ms/step (1071 probes), a block edit 0-2 ms on the server and ~9 ms
    for its mesh section. Admin: `/starwake ship assemble <helm> force` skips the pre-flight verdict.
- **Pass 3b, Hull Panels (2026-11-15; replaced in pass 4):** the single shape-cycling `starwake:hull_panel` was replaced by
  the auto-connecting hull pieces below. What stays from 3b: `PanelGeometry` (shapes as unions of solids answering
  `inside(x, y, z)` and listing faces wound outward by a probe bounded to the cube; buried faces dropped; collision 16^3 and
  outline 4^3 voxelisations built straight into a `BitSetDiscreteVoxelShape`; 24 rotations from vanilla `OctahedralGroup`,
  model up = +Y, front = -Z) and baked rendering through a Fabric `ModelLoadingPlugin` block-state resolver. Hull stairs
  and slabs seal.
- **Pass 3c, greebles and lights (2026-11-15):** `PanelGeometry` is generic: anything implementing `PanelGeometry.Shape`
  (a list of `Solid`s, each with a texture key) gets faces, collision, outline and volume (caches keyed by shape). Helpers:
  `px(...)` (box in sixteenths), `extrude`, `arc`, `poly`, `placement(ctx)`, `rotated(orient, rotation)`.
  - `starwake:greeble` (`ship/block/GreebleBlock`, `ship/panel/GreebleShape`): vent, pipe, twin pipes, conduit, seam,
    antenna, railing, ladder rungs (decor: not climbable), catwalk grate, ribbed panel, hazard trim, cargo rack, external
    tank, heatsink. Mounted on the clicked face; wall pieces run along model Z (vertical on a wall).
  - `starwake:ship_light` (`ship/block/ShipLightBlock`, `ship/panel/LightType`): strip, panel, running, flood, landing,
    beacon; colours white/red/green/amber/blue; `on` (empty-hand use toggles) and `flash` (the beacon flashes 3 on / 27 off
    by scheduled ticks). Block light per type while lit; the lens is emissive.
  - `client/ship/ShapedBlockModels` (was HullPanelModel) bakes all three blocks from one `Spec` per block (shape, orient,
    texture per key, emissive keys); texture transparency comes from the sprite (the grate is cutout).
  - Hull Tool: greebles = next detail, lights = next colour; sneak-use turns both. Loot keeps the shape / type and colour
    (`copy_state`). Textures: `tools/greeble_textures.py`. Recipes: stonecutter plating -> 4 greebles; 4 lights from a glass
    pane, glowstone dust and iron.
- **Pass 3d, hinges and rotors (2026-11-15):** `starwake:hinge` / `starwake:rotor` (`ship/block/JointBlock`, rendered by
  `ShapedBlockModels` from `ship/panel/JointShape`): `facing` = the side the moving part is built on and the turning axis;
  hinge `down_angle` / `up_angle` (15-degree steps; empty-hand use steps the gear-up angle, sneak-use the gear-down one);
  rotor `speed` (0, 2, 6, 15, 30 rpm; use steps it).
  - `ship/articulation/Articulations` (rebuilt in `afterModelChange` on both sides): each joint's group = the cells reached
    from its facing neighbour without passing the joint (invalid, so rigid, if it reaches the Helm, another group or 65,536
    cells). Pivot = centre of the joint's facing face. Hinges move at 45 degrees/s toward the gear-down angle when the gear is
    down (or the ship has none) and the gear-up angle otherwise; the server syncs them in `JOINTS` ("x,y,z=deg;"), clients
    interpolate. Rotors turn from game time on both sides (no sync).
  - Thrust: `ShipDynamics.articulate(poses)` re-poses thrusters (position + push) and rebuilds the allocation columns; the
    server, the client copy and the pilot's predicted model (`setServerDynamics`) all apply it. `pushesAlong` (raw thrust,
    burn by direction) uses posed pushes.
  - Rendering: mesh sections are keyed by (group, section) (`ShipMesh.Key`); faces between groups aren't culled
    (`ShipBlockRenderState` treats other groups as air); each group is drawn with its joint's rotation about the pivot.
    Thruster flames follow (`ShipEntity.posed` / `posedDirection`).
  - Collision, probes, walking, raycasts, mass and block entities use the as-built pose: **build parts in their gear-down
    pose** (the landed one). Nested joints turn with their outer group only. A lone pod on one side unbalances the flight
    computer (it trades translation for torque balance); build them in pairs (mirror the angles: a west hinge at 90 degrees
    pairs with an east hinge at 270).
  - Verified 2026-11-15: a Kestrel with two hinged main-engine pods passes pre-flight; gear up swings both nozzles from down
    to back (seen from outside), gear down swings them back.
- **Pass 3e, the Magpie (2026-11-15):** `starwake:magpie` (`tools/magpie_blueprint.py <out.json>`), an original heavy
  salvage ship at true scale: 152 x 40 x 34 m, ~20,600 blocks, ~25,600 t. A tapered hexagonal hull (narrow keel, bevelled
  shoulders) with ribbed side belts and sponsons; an off-centre bridge island (helm, four seats, radar rotor, mast and
  beacon); an exposed lattice spine with ribs and pipes; a hinged salvage claw (boom, two jaws, floodlights) under the bow;
  two hinged engine pods (15 Mk III mains each) at the centre of mass, pointing down with gear down and back with gear up;
  a processing bay (two catwalk levels, cargo racks, heatsinks, tanks, ceiling lights, belly-door hazard trim); tractor
  rotors under the bow; aft systems decks (50 plants, 17 coolers, 30 tanks, 6 shields, 2 quantum drives); belly lift
  banks, stern mains, bow retro mains, RCS; 10 gear struts. Pre-flight: lift 2.24x (2.44x since pass 4), accel fwd 18 / back 4 / side 2.8 / up
  45 m/s^2, pitch 15 / yaw 7 / roll 105 deg/s^2, cabin sealed, ready for space. The generator replicates
  `PanelGeometry.orient` (up x clockwise fronts) and keeps a clear gap around hinged parts (`KEEP_CLEAR`) so details on
  the hull don't weld them on. Verified: placed, assembled in one go, climbed straight into orbit.
- **Pass 4, auto-connecting hull pieces (2026-11-15).** The user tried a deformable Hull Form (four corner heights, raised
  and lowered with the tool) and rejected it as "too liberal for mc, plus bad for symmetry", choosing instead separate
  block types with dynamic shapes that connect like stairs. Hull Form and Hull Panel are gone.
  - Blocks (`registry/ModBlocks.hullPieces()`, all extend `HullPlatingBlock`: alloy kept, mass x the shape's filled volume):
    `hull_slope`, `hull_slope_2`, `hull_slope_4` (1:1, 1:2, 1:4), `hull_curve[_2|_4]` (convex quarter round), `hull_cove[_2|_4]`
    (concave), `hull_wedge` (a peaked ridge ending in a point), `hull_plate` (4 px), `hull_half_plate` (8 px),
    `hull_pillar` (round column, `axis`), `hull_frame` (truss node with rails toward connected neighbours; alone = an open
    cube). Frames and pillars don't seal; every profile piece and plate does.
  - Profiles (`ship/block/ProfileHullBlock`, geometry `ship/panel/ProfileShape`): state = `orient` (up x front), `conn`
    (straight / outer / inner / end), `mirror`, `part` (and `part2` for corners) = the piece's section of an N-block run.
    `connect` runs on placement and every neighbour update: the vanilla stairs rule generalised to any up direction (a
    matching piece behind, turned across = outer corner; in front = inner corner), the run section counted from same-block,
    same-orient neighbours toward the front (mod N), a corner's second section from the neighbour. Height over (x, z):
    t = (part + z) / N through the profile (slope t, curve sqrt(1-(1-t)^2), cove 1-sqrt(1-t^2)); outer corners take the
    lower of the two runs (curves round off spherically), inner the higher. The surface is a triangle grid (curves 8x8,
    ridge 2-4, slopes 1-4) and `facetHeight` (the drawn surface) is the inside test, so curved chords aren't dropped as
    buried. Wedges end in a point (`end`) where the ridge stops.
  - `ship/block/ShapedBlock`: the interface for every model-baked block (pieces, greebles, lights, joints): shared `ORIENT`
    (0-23), `shapeOf`, `orientOf`, `seals`, `texture(state, key)`, `emissive`. `ShapedBlockModels` registers every
    `ShapedBlock` in `ModBlocks.all()`. `ShipMaterials` fill and `ShipEntity.sealsCabin` go through it.
  - Hull Tool (`ship/item/HullToolItem`): use = turn a quarter about up (greebles: next detail; lights: next colour;
    pillars: next axis); sneak-use = flip upside down with the same front. Profiles re-fit after every change.
  - Blueprints: `ShipBlueprints.place` runs two `updateFromNeighbourShapes` passes over profile pieces and frames after
    placing, so runs and corners connect. The Kestrel uses `hull_slope`; the Magpie maps its old panel shapes to slopes,
    curves and frames.
  - Recipes: stonecutter plating -> 1 piece (plate 4, half plate and frame 2); Fabricator 2 plates -> 4 (plate -> 8), alloy
    kept.
  - **Gear-up pre-flight** (found while checking the Magpie): with the gear up its pods swung back and the hull sank (only
    48 fixed lift engines, 0.86x). `Articulations.gearUpPoses` poses every hinged part of the world blocks at its gear-up
    angle (same grouping rule as `detect`); pre-flight re-runs the balanced lift solve in that pose: "Gear up: lift X (Nx)",
    WARN below 1.15x. The Magpie's belly banks are now twice as dense (lift 2.44x gear down, 1.30x gear up).
  - Verified 2026-11-15 in the client: slope rings form crisp hip corners, curve rings a pillow with spherical corners; 1:2
    and 1:4 runs read smooth; an L of slopes forms an inner corner; the wedge ends in a point. Kestrel (342 blocks, 1.89x)
    and Magpie (20,683 blocks) pass pre-flight; the Magpie hovers at the same altitude with the gear up, and its pods
    swing from down to aft and back (seen from outside).
- Known limits: assembly and the first load of a 200k ship still take seconds; lights aboard ships glow but don't light
  the world around the ship (no moving light sources yet); radiation from machines aboard isn't counted for nearby players; block entities aboard only tick
  chest lids on the client (no bell swing or campfire smoke yet); contacts beyond entity tracking range aren't shown.

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

## How planets, space and the galaxy work after M9

- **Fixes:** airless archetypes get `fog_start_distance` 1024 / `fog_end_distance` 2048 (`tools/planet_data.py`; the
  near-black fog had hidden the sunlit ground). `EntityGravityMixin` scales `Entity.getGravity()` (final) for non-living
  entities by `Planets.gravityFactor(level)` (planet gravity, 0 in space; cached per level). World-marker labels share
  `client/hud/HudLabels` (per-frame layout; SpaceHud and HubHud). `/starwake planet list` shows destinations only.
- **Moving orbits, local frames** (`galaxy/SolarSystem` "Orbits"; user's choice after the conflict was explained: at the
  chosen speed the Overworld moves ~1,300 m/s, faster than any ship, and would leave stations and builds behind):
  - Each planet circles its system's centre (mass-weighted centre of its stars) in the slab's plane, starting at its JSON
    position at time 0. Period = `PERIOD_AT_1MM` (72,000 ticks) x (a / 1 Mm)^1.5 / sqrt(M) (Overworld 7.2 days, Veyra 40,
    inner Solace worlds ~4). `Body.mass` = a star's solar masses (generated systems pass the real one). Stars stay put.
  - Orbit time = the **Overworld clock** (`level.getOverworldClockTime()`, both sides): sleeping or `execute in
    minecraft:overworld run time add` moves the planets; with the daylight cycle off they stand still.
  - **Local frames:** the JSON position is the body's fixed **neighbourhood** in the space dimension (radius
    `entryRadius + 60 km`, blending out to twice that): flying, entering, stations and builds stay there. Inside it, space
    is the body's frame carried along its orbit (`toSystem(physical)` adds the body's `drift(t)`; `toPhysical(body, p)`
    removes it). Everything that looks or aims across the system uses system coordinates: `SpaceRenderer` (eye =
    `systemPosition(camera)`, bodies at `Entry.at(t)`, lit from the star, so phases change), SpaceHud markers, Starmap
    (bodies and orbit circles where they are now), quantum auto-target, alignment and obstruction.
  - **Quantum routes** (`SpaceTravel.route`): from = the ship's system position; aims where the target will be when the
    jump ends (two passes); `Route.arrival` = the physical drop-out point in the target's neighbourhood. `QuantumJump`
    holds from/to in system coordinates plus `arrival` (saved as `quantum_arrival`; older saves fall back to `to`).
    `QT_OFFSET` = the travelled system point minus the hull's own system position.
  - Admin: `/starwake galaxy orbits` (radius, period, position now, distance from the neighbourhood; for moons, where they
    appear in their planet's neighbourhood and their capture radius).
- **Moons** (`ProceduralPlanets.moons`, called for every planet at home, hand-made ones included, and per planet in
  `StarSystems.plan`): 0-1 (radius < 1.8 km), 0-2 (< 3.5 km) or 1-3 moons; barren, frozen or volcanic (the first may be
  tidally heated, +40 C), radius 12-30% of the parent's (300-2500), gravity from size, airless, the barren (black, vacuum)
  sky whatever the terrain. Ids `<parent path>_m<n>` (`starwake:overworld_m1`), names from `PlanetNames`, archetype label
  "Barren/Frozen/Volcanic moon"; `Planet.parent` links them. Each is a Fantasy level like any procedural planet.
  - `Body.moonOrbit` (distance, phase): first moon 12-18 entry radii out (>= 30 km), each next x1.6-2.4. Period =
    `MOON_PERIOD_AT_100KM` (96,000 ticks) x (d / 100 km)^1.5 / sqrt(g r^2 relative to the Overworld). `Orbit.around` = the
    parent's orbit, so moons ride along with it.
  - A moon's `position` is only its neighbourhood, ~900 km + 350 km per index from the parent's (>= 400 km from
    everything). Frames: every point of space belongs to the body whose neighbourhood centre is nearest (`frameOf`).
  - **Moon transfer** (`SpaceTravel.moonTransfer`, in `checkAltitude`): a ship in a planet's frame whose system position
    comes within `captureRadius` (moon entry radius + 20 km) of where a moon really is jumps (same level, crew carried) to
    the matching point of the moon's neighbourhood; a ship more than 1.25x that from the moon's centre jumps back into the
    planet's. The system position doesn't change, so it's invisible (overlay "Approaching <moon/planet>"). Quantum travel
    to a moon lands in its neighbourhood directly.
  - Verified 2026-11-15: 17 moons around Solace (Overworld 3, Veyra 1); a Kestrel flown from 22 km toward Brameolvax moved
    into its neighbourhood at 16 km with every other marker continuous; frozen and volcanic moon surfaces load.

- **Planet skies** (`client/sky/PlanetSky`, COLLECT_SUBMITS on any planet or moon whose system has a star; the vanilla
  sun and moon are cancelled by `client/mixin/SkyRendererMixin`). Day and night stay vanilla (the planet's timeline drives
  the sun angle and light level), so the system's star is drawn exactly where the vanilla sun would be. `skyRotation`
  maps system directions into the sky: planet -> star becomes the vanilla sun direction (-sin a, cos a, 0), the next
  direction along the orbital plane follows the sun's daily path, system up becomes north (-Z) (an observer on the
  equator). Bodies come from `SpaceRenderer.draws(bodies, eye, t, reach, sky, skip, alpha)` (eye = the planet's moving
  position; the planet itself skipped) and `SpaceRenderer.submit`: true direction and apparent size, lit from the star
  (phases), sphere geometry turned with the sky (poles stay north), the unlit side see-through (`lit`), planets faded by
  daylight (`0.3 + 1.4 x star brightness`), only the star in rain, a wide soft glare around the star. Verified: Solace
  in the Overworld's day sky; from the moon Brameolvax the star with its glare and the Overworld rising as a lit globe.
  Known limit: everything stays on the equatorial path (no latitude or axial tilt); vanilla's starfield stays.

- **Finite wrapping planets.** `Body.wrap` + `Body.circumference()` (= 2 pi `world_radius`, rounded to 16). Size from the
  radius (`Body.worldRadiusFor`): 8 km around at radius <= 500 up to 32 km at >= 4000, rounded to 512 so the edges fall on
  chunk boundaries. Every procedural planet and moon wraps; Veyra too (22,528; JSON `wrap` + world_radius 3585.443, and its
  noise router wrapped in its noise_settings JSON); the **Overworld stays endless** (vanilla). The surface is the square
  [-L/2, L/2) in X and Z; longitude = X / world_radius, so leaving and entering match the sphere exactly around the equator.
  - Terrain: `worldgen/WrapDensity` (density function type `starwake:wrap`, `input`, `period`, `blend` 96), applied to all
    eight `NoiseRouter` functions (`WrapDensity.wrap`, by `PlanetGenerators.create(..., period)`), so terrain and the climate
    that picks biomes repeat. Over the last `blend` blocks before a tile's upper edge, the input cross-fades into itself one
    period back, so the edges meet exactly. 26.3 samples terrain as volumes (`DensitySampler.sampleVolume`): a volume in one
    tile copy is passed to the input shifted by whole periods (interpolation and caches intact); in the blend band it's
    sampled as whole volumes here and one period back (two to four calls) and cross-faded per point; only volumes that
    straddle an edge go point by point. (Point-by-point sampling of the band stalled the server for minutes: interpolated
    functions re-sample their input for every point.) Not wrapped: surface-rule noise, aquifers, features, structures.
  - Crossing (`planet/PlanetWrap`, END_LEVEL_TICK on wrapping planets): ships (with crew, `SpaceTravel.jump`) and players
    every tick, everything else once a second, move one circumference back (same velocity and look); players within 16
    blocks of a ship are left to the ship. Within 64 blocks of an edge the far side's chunks get a ticket.
  - Verified 2026-11-15 on Veyra: the views from x = 11,240 and x = -11,288 match exactly; flying east across x = 11,264 put
    the player at -11,250; the seam from above is continuous. Known limits: HUD distances (hub beacons) don't take the short
    way around; chunks generated beyond the new edges in older worlds stay, unreachable.

- **Asteroid belts** (`galaxy/AsteroidBelts`): each system has a belt at 2.5 Mm x sqrt(luminosity) (+/-10%), pushed 300 km
  at a time until it's 250 km clear of every planet orbit, with three **fields** (`Body.field`, radius 800 m), planet entries
  `starwake:<system>_belt_a|b|c` ("Solace Belt, Field A", archetype label "Asteroid field") in the system's space dimension,
  added as extras next to the stars (home: `generateHome`; others: `StarSystems.plan`). Fields are quantum destinations
  (arrival just outside), don't orbit (static frames), aren't drawn as spheres and aren't entered like planets.
  - Asteroids: ~150 per field (one every ~120 m), radius 3-25 (mostly small), lumpy and often elongated
    (`Asteroid.radiusToward`), types S 45% (olivine ore with kamacite flecks under a fine-regolith skin), C 35% (serpentine
    ore with packed-ice pockets), M 12% (kamacite ore, olivine, 1.5% sperrylite), icy 8% (packed + blue ice, serpentine
    skin). They're placed into space chunks the first time those generate (`ServerChunkEvents.CHUNK_LOAD`, `generated`):
    the parts inside the chunk are queued and placed at the end of the level tick (24 per tick). (Placing during the event,
    even through `server.execute`, which runs inline on the server thread, asks for the loading chunk and deadlocks.)
  - New asteroid minerals (`tools/periodic_table.py`, no planet worldgen; `tools/chemistry_process.py` species + ores):
    **kamacite** (Fe 91.5 / Ni 7.5 / Co 1 metal with troilite and magnetite, Pt 12 ppm, Ir/Os 3, Pd 4, Ru 5, Ge 100, Ga 50),
    **olivine** (forsterite, fayalite, enstatite with Fe-Ni grains and troilite), **serpentine** (Mg3Si2O5(OH)4 with
    magnetite, water and organic carbon). Species Mg2SiO4, MgSiO3, Mg3Si2O5(OH)4.
  - HUD: SpaceHud labels nearer bodies (and the quantum target) first, drops labels with no free spot
    (`HudLabels.tryPlace`) and only labels moons within 300 km. Verified 2026-11-15: Field C full of S, C, M and icy rocks.

- **Alien pass** (`tools/planet_data.py`, `tools/planet_textures.py`):
  - *No Earth life where it's lethal:* the toxic world's mycelium fungal forest became **thermophile springs**
    (`starwake:thermophile_mat`, banded heat-loving microbial mats around acid pools, with sulfur chimneys); the old
    `toxic_fungal_forest` biome stays registered (`LEGACY`) so saved chunks load. Frozen seas lost kelp and seagrass.
  - *Terrain features:* `worldgen/SpireFeature` (`starwake:spire`: tapered lumpy cone, optional caprock, lean, base sunk
    into slopes, radius <= 6): desert **hoodoos** (red sandstone, sandstone cap), barren **crags** (anorthosite), toxic
    **sulfur chimneys** (tuff cap), frozen **penitentes** (packed ice); vanilla **basalt columns** on volcanic lava and ash
    plains (`planet/basalt_columns` = `minecraft:large_basalt_columns` on the surface). Canyons were already carved.
  - *Variety, palettes:* `PlanetArchetype.BiomeOption.palette`; each planet draws one palette (its own random stream) and
    keeps only biomes with that palette or none. `PALETTES` clones every biome whose surface uses a replaced block: lush
    and ocean purple / teal (`teal_turf`) / rust (`rust_turf`) mats; desert red / ochre / black (`basalt_sand`, smooth
    basalt) dunes; frozen water ice / methane frost (`methane_frost`); barren grey / dark (`dark_regolith`) regolith. The
    first palette keeps the biome's old name (saved chunks still find it). `expand_biome_tags` rewrites the hand-written
    biome tags (seawater, salt lake, gas field, acid water) with every clone and drops ids that no longer exist.
  - New blocks (`registry/ModPlanetBlocks`): Teal Turf, Rust Turf, Thermophile Mat, Dark Regolith, Basalt Sand (falling),
    Methane Frost. 50 planet biomes, 55 features.
  - Verified 2026-11-16: basalt columns over a volcanic moon's lava; a dark-regolith barren world with pale crags; desert
    hoodoos; toxic sulfur flats with chimneys and no trees; frozen moon coasts without kelp. (`/locate` didn't find
    thermophile springs on Thuneth: not in its seeded biome set.)

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
  player comes near** (station 700 blocks, outpost 160; outposts at the surface height) and registers pressurized zones (`trade/HubZones`, also used by `PlanetHazards`).
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
- **NPCs** (M7, removed in M9): hubs had guards and crew; see "How trade, Comms, Cargo and config work after M9".
- Wristcom: Trade app (credits, standings, the system's hubs), Contracts app (active, abandon).

## How trade, Comms, Cargo and config work after M9

- **NPCs removed:** `HubNpcEntity`, `HubNpcs`, the renderer, skins and the template `npcs` field are gone (saved NPCs are
  skipped on load). Hostile reputation only locks terminals.
- **Industrial market** (`trade/Industrial`, data `starwake/industrial/default.json` from `tools/trade_data.py` `industrial()`):
  `appraise(stack)` -> (category, value per item). Metals (`MetalItem`): element mass x per-kg element price (the commodity
  prices; samples x1000) x kind (certified grade 1.6, off-spec 1, pure 1 with purity steps 1.1/1.25/1.5, estimated alloy 0.9)
  x form (wire 1.3, plate/rod 1.15, ...) x temper (cracked 0.5, worked/treated 1.1). Bulk items: non-gangue element value x a
  share by kind (concentrate 0.4, matte 0.6, slag 0.08...). Components, electronics and Mk I-III parts from a table x quality.
  Categories: ferrous, light, nickel_cobalt, copper, precious, refractory, other_metal, ore, component, ship_part,
  electronics; `demand` = what each hub economy buys and its multiplier. Saturation per hub and category in `Markets.State`
  (key `ind:<category>`): price x 2^(-recently sold value / capacity), recovering with tau 12000 ticks. `MarketPayload` carries
  `industrial` offers (inventory slot, stack, price); `TradeActionPayload` action `isell` (slot, count). `MarketScreen` has
  Goods / Industrial tabs in the title row. Admin: `/starwake hub appraise` (held item: category, value, what each hub kind pays).
- **Server config** (`config/StarwakeConfig`, `config/starwake-server.json`): flat settings in groups hazards (enabled,
  hypoxia/toxic/temperature damage, radiation, gas leaks, suit oxygen), ships (max blocks, re-entry and impact damage, locking,
  idle burn, fuel use), travel (quantum speed, jump time, orbit speed: 0 freezes the planets, changing it moves them) and
  economy (starting credits, buy/sell prices, contract rewards, reputation gain/loss). Read on SERVER_STARTING and rewritten
  with every key; `/starwake config [key] [value]` (op) changes them live and saves. Clients get the orbit speed in
  `ConfigSyncPayload` (join and change). `ShipScanner.MAX_BLOCKS` is now the ceiling; the live cap is `maxShipBlocks()`.
  Not configurable: planet sizes (they shape terrain already generated).
- **Cargo app** (`client/wristcom/apps/CargoApp`; `CargoPayload` both ways; `ship/ShipCargo.manifest`): the ship you're on, or
  the nearest you own/crew within 64 blocks; the server sums every container and fluid storage in its shipyard plot, plus fuel
  and fusion (owner/crew, or anyone on an unlocked ship). Refreshes every 2 s while open.
- **Comms app** (`client/wristcom/apps/CommsApp`; `CommsPayload` C2S, `CommsMessagePayload` S2C; `comms/Comms`): contacts =
  online players, the system's hubs, ships within 2 km (not your own). Messages go to any online player; a hub hail answers
  with where it is (distance, orbit of / on a planet, beacon), your standing, docking instructions and services; a ship hail (or
  text) goes to its pilot and owner. Inbox per contact on the client (40 lines), unread dots, action-bar notice.

## How the economy works after M9

- **Simulation** (`trade/EconomySim`, tuning `starwake/economy/default.json` from `tools/trade_data.py`; SavedData
  `starwake:economy_sim`): every `step_ticks` (600) over the home system's hubs and every opened system's:
  - *Fill:* market entries are now fill levels (0..1.5 of capacity; `Markets.State` [fill, time, 1]; old [stock, time] entries
    convert). Capacity = listing stock x `tier_capacity[tier]` x **depth**; depth = sqrt(1 + `depth_per_trader` x (active
    traders - 1)), traders = players who traded within `trader_window`, so one player sees normal markets and a busy server
    deep ones.
  - *Production chains:* producer fill += `produce` x supply x `tier_rate[tier]` (capped at 1), supply = average saturation of
    the economy's `inputs` groups (hub_economy JSON: trade port needs food + fuel, refinery raw minerals + fuel, mining camp
    food + fuel + supplies...); consumers drain by `consume` x rate.
  - *Virtual haulers:* per item, the fullest producer ships `haul` to the emptiest consumer when the gap exceeds `haul_gap`,
    backing off by recent player trade in that item ("played", decaying with `player_tau`) so players who haul matter.
  - *Health and tiers* (0..2 = tier 1..3): health = EMA of consumer saturation; `grow_points` steps at >= `grow_health` rises a
    tier, `decline_points` at <= `decline_health` falls one (`TradeHubs.onTier` logs). Every 10 steps a price sample per
    item (48 kept) feeds the history graph.
  - Deliveries to hub contracts and supply requests fill the hub (`Markets.deliver`).
- **Physical tiers** (`TradeHubs` "Expansions"): templates `<template>_t2/_t3` hold only what each tier adds on the same origin
  and list their `lights`. `tickExpansions` (every 5 s) brings each built hub to its tier only while no player is within 128
  blocks of its box: missing expansions are placed (lit), declined ones dim (full-block lights -> unlit redstone lamps, small
  fixtures removed; nothing else changes), regrowth relights them from the template. Saved per hub: `placed`, `lit` (State is
  now a record; old bare-origin saves still load). A hub that grew before anyone came near is built with its expansions.
  Pressurized zones include placed expansions (`allZones`). Surface templates get tuff-brick footings under every column that
  would hang over air (`underpin`, up to 40 blocks). Admin: `/starwake hub econ` (depth; tier, health, points, modules placed and
  lit), `hub tier "<id>" <1-3> [now]` (now = build immediately even with players near), `hub simulate <steps>`, `hub rebuild`
  (keeps the old origin).
- **Designs** (`tools/hub_designs.py`, run it; it imports the helpers in `tools/hub_templates.py`, whose `Grid` tags each cell
  with `g.tier` and whose `write_tiers` writes the three files): signed-distance solids (`Solid`: tubes `seg_sdf`, rounded halls
  `rbox_sdf`, cylinders, tori) built shells first then interiors, so later tubes open earlier walls. Station (121x62x121): core
  cylinder (reactor column, concourse deck 22 with terminals on the diagonals, control deck 32, crown, keel), arms to 24x27
  hangars with open lit mouths, pads, catwalks, containers; port T1 east/west + roof solar, T2 hab ring on four spokes + north
  hangar, T3 south hangar + solar trusses + comms; refinery T2 north hangar + spherical tanks, T3 south hangar + radiator fins +
  tank farm. Outposts (64x32x64, ground 4): T1 trade hall (airlock, five terminals), raised pad, mast, generator, containers,
  perimeter lights; T2 crew/workshop hab on a tube, second pad, solar; T3 settlement greenhouse dome, mining processing hall +
  headframe + silos, or research observatory + dish, and a watchtower.
- **Player shops** (`trade/kiosk/TradeKioskBlock` + `TradeKioskBlockEntity`, block `starwake:trade_kiosk`): owner set on placement;
  owner sneak-use opens a 9-slot chest menu, use opens `KioskScreen` (owner: price per slot; others: Buy 1/8/64). Buyers pay
  through `Economy.spend`, owners are paid with `Economy.payTo` (works offline); hoppers can insert, never extract; non-owners
  can't break it. Works aboard ships (shipyard position resolved).
- **Player contracts** (`trade/PlayerContracts`, SavedData `starwake:player_contracts`, ids `p-...`): at a Contract Board, hold
  the item, enter count + reward, Post: the reward is escrowed, the request (exact item + components) shows on every board in the
  system for 3 days (max 5 per poster). One taker accepts (counts as an active contract), hands in at that hub and is paid; the
  goods wait as pickups for the poster ("Collect (n)" on any board). Cancel refunds an untaken request; expiry refunds the
  poster and drops it from the taker.
- **Market info** (`TradeIntelPayload`, Wristcom Trade app "Prices" tab): every terminal visit or hub hail remembers that player's
  quotes per hub with the time; the tab lists known hubs (age, tier), their items (buy / sell / stock as seen), a price history
  sparkline for the picked item, and the best known sale and cheapest known purchase across hubs. It re-asks every 3 s while
  open.

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
  (`ModRecipeProvider`; since 8i tier-1 only, see "How the Codex and recipe ladder work").
- Known limits: no aluminium recipe until 8c makes aluminium. (Tanks render their fluid since M9.)

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

## How propellants and fusion fuel work (Milestone 8f)

- `ship/fuel/Propellants`: species H2, O2, CH4, D2, He3; `convert(buffer, fuelRoom, fusionRoom)` burns pairs
  (hydrolox O/F 6.0 at 150 units/kg of mixture, methalox O/F 3.6 at 120; D2 + He3 at 2 x 3.016 / 4.028 by mass,
  1 fusion unit = 1e-7 kg). `fusionCapacity(fuelCapacity)` = 10x.
- `FuelTankBlockEntity`: insert-only `ChemicalTank` ("gases", 40 kg) exposed through `FluidStorage.SIDED` (pipes,
  sneak-use with a Gas Cylinder); its change callback converts pairs into `fuel`/`fusion` (guarded against
  re-entry). Assembly drains both into the ship; disassembly pours both back.
- `ShipEntity`: synced `FUSION`, `fusionCapacity()` = 10 x fuel capacity, `useFusion`/`addFusion`, a saved
  `propellantBuffer` for gas poured into a flying ship's tanks (`ShipInteraction`: right-click a fuel tank aboard
  with a Gas Cylinder). Quantum routes and jumps check and spend fusion units (`QT_NO_FUEL`/`JD_NO_FUEL` now
  mean "no fusion fuel"); the console, Space HUD and Ship app show it. Admin: `/starwake ship refuel <fuel> <fusion>`.
- Chemistry: species `D2O` (kind water), `D2`, `He3` are written as their element (so reactions balance) with a
  `molar` override (real isotopic mass). `Process.ISOTOPE_COLUMN` (Mode ISOTOPE, 50 kW): 10 kg of inlet water per
  cycle -> its D2O (1.73e-4 kg/kg) to the liquid outlet, 1.5 MJ/kg. Electrolysis `2 D2O -> 2 D2 + O2` (KOH).
  Crystal-world air carries He3 = He x 1.66e-4 by atoms (protosolar).

## How the nuclear machines work (Milestone 8g)

- Data: `nuclear/Nuclear` reads `data/starwake/starwake/nuclear/default.json` (from `tools/nuclear.py`):
  centrifuge beta and energy, minimum enrichment per moderator, J/kg, efficiency, burn scale, conversion ratio,
  spent fraction, fission-product yields, irradiation chains (target, product, seconds) and accelerator routes
  (target, beam, product, joules), fusion J/kg and efficiency. `Nuclear.elementOf(stack)` = the element an item
  is or is mostly made of (any element item form, or a composition's base).
- `GasCentrifugeBlockEntity` (20 kW): 2 x 1 kg UF6 lots per cycle -> 1 heads + 1 tails; heads ratio R x beta,
  tails by mass balance; shares shown on the screen. UF6 is a solid at room temperature (the kiln's sublimate).
- `FissionReactorBlockEntity` (500 kW generator): 4 fuel slots (uranium-base metal items), moderator tank (H2O or
  D2O; D2O wins at >= 1 kg), target slot, spent and product outputs. Burn-up is accumulated per slot and folded
  into the composition every 100 ppm (the composition resolves 10 ppm). Stops when its buffer is full.
- `FusionReactorBlockEntity` (2 MW generator, D2/He3 tank, redstone shutdown); `AcceleratorBlockEntity` (2 MW
  consumer; target + beam slots, a beam item lasts 16 samples). Screens: `client/fab/NuclearScreen`.

## How material gear and suit upgrades work (Milestone 8h)

- `fab/MaterialCrafted`: items whose stats come from their material; `Blueprint.product` copies the main input's
  composition/temper, calls `applyMaterial(stack, properties, quality)` and sets the tint colour.
  `gear/MaterialGear` sets `minecraft:tool` (tier tag from HV, speed), `max_damage`, attribute modifiers (attack
  damage/speed; armour, toughness, knockback, movement speed). Tool rules resolve tags through the frozen
  `BuiltInRegistries.BLOCK` (the bootstrap lookup throws after registry freeze). Items: `registry/ModGear`
  (`material_pickaxe|axe|shovel|sword|helmet|chestplate|leggings|boots`; iron-like defaults until fabricated;
  worn armour uses the vanilla iron look). Names "<alloy> Pickaxe".
- Suit upgrades (`gear/SuitUpgrades`, component `starwake:suit_upgrades`): blueprints with an `upgrade` field
  return the main input (the suit) with it fitted (`Blueprint.match` skips a suit already at the limit).
  `SurveySuitItem.maxOxygen/minTemperature/maxTemperature/protectsFrom(stack, t)`; `Radiation.rate` multiplies
  external gamma by `SuitUpgrades.shielding`.
- `tools/fabrication.py` writes only its own blueprints (the Mk parts come from `tools/ship_parts.py` into the
  same folder; it used to wipe them).

## How the Codex and recipe ladder work (Milestone 8i)

- **Codex** (`client/wristcom/apps/CodexApp`, Wristcom app "Codex"): entries from the bundled
  `/starwake/codex.json` (generated by `tools/codex.py`: title, category, paragraphs). The left column lists
  entries (titles shortened to fit, full title as tooltip); the right pane wraps the text and scrolls with the
  wheel. Add an entry by editing the script and re-running it.
- **Recipe ladder** (`ModRecipeProvider`): tier 1 = vanilla materials (generators, cables, pipes, tanks,
  conveyors, crusher, flotation, roaster, coke oven, smelter, converter, induction furnace, rolling mill, wire
  drawer, Fabricator). Tier 2 = the `process` machines built through `machine(...)` plus the later metalworking,
  electronics and isotope machines use `MetalItems.item(PLATE)` for 'I' (any alloy's plate: roll an ingot).
  Tier 3 = blueprints only (`tools/fabrication.py`): gas centrifuge, fission and fusion reactors, particle
  accelerator, Mk II/III ship parts, material gear, suit upgrades.

## How industry works after M9

- **Belts** (`logistics/ConveyorBlock` with a `Kind`, `ConveyorBlockEntity`; blocks `conveyor`, `conveyor_splitter`,
  `conveyor_filter`, `conveyor_incline`, `conveyor_decline`, `item_lift`, one BE type `starwake:conveyor`): each belt holds up to 4
  stacks with a progress 0..1 (queued 0.25 apart), moving `rate` b/s (machine spec, sent to clients with the cargo because specs
  are server data). Both sides advance; only the server hands on: at progress 1 the front stack goes into
  `ItemStorage.SIDED` of the block ahead (belts expose an insertion-only storage per side, so belts, machines, chests, hoppers
  and other mods connect the same way). Entry: plain belts take from the back (progress 0), the sides and above (0.5); splitter,
  filter and ramps from the back; lifts from below and the non-front sides. Splitters pick front/left/right in turn among sides
  that have a receiver (re-picking when one disappears); filters send items matching their filter item (same item type) right
  (or left); inclines hand to the block ahead and one up; a belt whose ahead is empty continues into a decline one step down;
  lifts pass up while there's lift above and the top one hands out toward FACING. Loose item entities touching a flat belt are
  absorbed; living things are pushed. Every change sends the cargo (`getUpdateTag`); the client keeps the previous positions of
  items it already drew. `client/logistics/ConveyorRenderer` draws them flat (FIXED context, 0.4 scale) and the filter item
  above a filter belt. Filter: use with an item = set, empty hand = swap side, sneak + empty hand = clear.
- Textures: `tools/machine_assets.py` `belt_strip` writes 6-frame animated tops (chevrons point north = FACING and scroll that
  way; rail colours: grey belt, cyan splitter, orange filter); ramps are a 45-degree rescaled plate.
- **Smart routing** (`ProcessMachineBlockEntity.reagentFor` + `canPlaceItemThroughFace`): automation (hoppers, belts,
  neighbouring machines) puts an item in the reagent slots if its main species is drawn by one of the machine's reactions
  (`draw`) or is a flux (SiO2, CaCO3, CaO, CaF2) in a furnace; everything else goes to the charge slot; catalysts are hand-set.
  Players placing by hand are unrestricted. The Electric Furnace only accepts smeltables through faces; the Fabricator only
  inputs the selected blueprint uses.
- **Chaining** (`MachineBlockEntity.eject`): outputs go into any neighbouring item storage (not upward), machines included,
  through their routing rules. A machine remembers neighbours that fed it (200 ticks) and never pushes back into them.
- **Tanks** (`FluidTankBlockEntity`): the update tag carries only `view_fill` and `view_color` (water blue, lava orange,
  chemicals mixed from their species' colours by mass), sent when the fill moves 1% or the colour changes;
  `client/logistics/FluidTankRenderer` draws a translucent box (debug quads, shaded by the light at the tank).
- **Readout** (`LogisticsProbePayload` -> `logistics/LogisticsProbe` -> `LogisticsInfoPayload`; `client/logistics/LogisticsReadout`):
  while the crosshair is on a cable or pipe, twice a second; cables show generation, demand, delivered, storage, size and the
  weakest rating; pipes show kg/s per fluid over the last second (`FluidNetworks` now moves fluid by fluid and records it).
- **Screens:** GUI sprites from `tools/gui_textures.py` (`textures/gui/sprites/machine/`: nine-slice `panel`, `slot`,
  `slot_output`, nine-slice `gauge`, `arrow`/`arrow_full`, `flame`/`flame_full`); `MachineScreen.bar` draws gauge fills. The
  Fabricator and furnace side panels use the same panel.

## 26.3 gotchas (learned the hard way)

- **`Component.translatable(key, fallback)` treats the second argument as a format arg**, not a
  fallback: use `Component.translatableWithFallback`.
- Fabric `FluidStorageUtil.interactWithFluidStorage` tries "fill the hand" then "empty the hand", and
  a held right-click repeats every 4 ticks, so a container item toggles back and forth. For
  multi-use items pick one direction per gesture (we fill on use, empty on sneak-use).
- **An unknown species `kind` breaks the whole species table** (enum `valueOf` throws on first use, and every
  ChemicalTank then fails to load: "An unexpected error occurred" on `/data merge`). `tools/chemistry_process.py`
  now asserts kinds, and `StarwakeMod` loads the table at startup.
- **`MachineBlockEntity.layout()` runs inside the superclass constructor**: subclass fields aren't set yet, so
  derive anything it needs from `getBlockState()`. `ItemStack.EMPTY.getMaxStackSize()` is 1, so "room in the
  output" checks must test `isEmpty()` first.
- Non-implicit item components stay on a placed block entity (`BlockEntity.components()`), are saved with it and
  can be copied back by the loot function `copy_components` (source `block_entity`): no BE code needed.
- Fabric attachments: `AttachmentRegistry.create(id, b -> b.persistent(codec).syncWith(streamCodec,
  AttachmentSyncPredicate.targetOnly()))`; setting null removes it.

- **26.3 grass tags:** grass/podzol/mycelium are `#minecraft:grass_blocks`, not `#minecraft:dirt`
  (`#minecraft:substrate_overworld` has both).
- A mixin **TAIL injection into `ServerLevel.updatePOIOnBlockStateChange` never fired**; HEAD does.
- **Vanilla entity-motion packets (`lerpMotion`) overwrite a client entity's velocity**: a client-predicted entity must
  ignore them (and position packets, via its `InterpolationHandler`).
- **Client input arrives at the server in bursts** (slow client frames run several ticks at once): server-side stepping
  for a predicted vehicle must consume one input per step, not "the latest input once per tick".
- Sequential impulses: **accumulate friction on fixed tangent axes**; re-deriving the tangent each iteration while
  accumulating a scalar fights itself (friction saturates and stops nothing).

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
