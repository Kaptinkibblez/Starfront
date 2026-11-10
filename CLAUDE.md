# Starbound Frontier (working title)

A Fabric overhaul mod for Minecraft, inspired by No Man's Sky: fly between procedurally
generated planets, live in a walkable starship, trade with faction NPCs, and climb a
fuel/upgrade/crafting progression.

> Working title. "Starbound" is also the name of an existing game, so pick a final name
> before any public release. The **mod id is `starfront`** and must not change once
> worlds exist: changing it breaks every saved block and item.

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
com.starfront
├── StarfrontMod            common entrypoint, wires every system's init()
├── registry/               ModBlocks, ModItems, ModItemGroups, ModEntities, ...
├── planet/                 planet definitions, dimensions, gravity, atmosphere
├── worldgen/               procedural terrain, biomes, and ore features
├── ship/                   ship entity, launch sequence, interior pocket dimension
├── galaxy/                 seeded star systems, galaxy coordinates, travel
├── trade/                  factions, NPCs, price model, trade menus
├── progression/            fuel, upgrades, crafting chains
├── network/                custom payloads (C2S/S2C), one record per packet
├── config/                 server/client config (Milestone 9)
└── datagen/                data generators (run via `runDatagen`)
com.starfront.client        (client source set)
├── StarfrontClient         client entrypoint
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
| Walkable moving ship | Valkyrien Skies 2 | Official: 1.20.1 only (unofficial port: 1.21.11). Kotlin plus a custom physics engine. | **Build.** Learn from its "shipyard" idea (ship blocks live in a reserved region and are rendered elsewhere), but don't copy the physics model. See open decision A. |
| Planets, rockets, gravity, oxygen | Ad Astra | Latest Fabric build is 1.21.1 | **Build.** Learn from its data-driven planet JSON (gravity, temperature, oxygen, tier) and its rocket flow (fly up, threshold altitude, destination screen). |
| Space progression | Galacticraft 5 | Pre-alpha, no release | **Build.** Use only as a reference for Fabric transfer/energy patterns. |
| Runtime dimensions (one per procedural planet) | NucleoidMC **Fantasy** | Supports 26.3 (v0.8.4, LGPL-3.0) | **Depend (proposed).** Creating and unloading dimensions at runtime is fiddly mixin work that Fantasy already maintains. Bundle it with jar-in-jar. Needs the `maven.nucleoid.xyz` host allowed. See open decision B. |
| Items, blocks, networking, menus, datagen, game tests | Fabric API | 26.3 | **Depend.** |

---

## Open decisions (ask before implementing)

- **A. Ship interior model.** The recommendation is a **pocket dimension**: each ship
  gets a plot in a shared `starfront:ship_interiors` dimension, and the exterior is an
  entity model that flies. A real moving block structure (Valkyrien Skies style) would
  be months of physics, collision and sync work. Decide before Milestone 4.
- **B. Planet dimensions.** The recommendation is **runtime dimensions via Fantasy**
  (unlimited seeded planets). The alternative is a fixed pool of datapack dimensions
  that get re-skinned per planet (simpler, but limited). Decide before Milestone 5.
  Milestone 2 uses a static datapack dimension either way.

---

## Milestones

Work on one milestone at a time. After each: build, run, fix, summarize how to test,
commit. **Don't start the next milestone until the user confirms the current one works.**

- [ ] **1. Scaffold.** The mod loads, with one test block (Hull Plating) and one test
      item (Raw Ferrite), a creative tab, datagen for models/lang/loot, and a
      dedicated server that boots cleanly.
- [ ] **2. First planet.** A static custom dimension with its own sky, gravity and
      terrain, reachable by command (`/starfront planet tp <id>`).
- [ ] **3. Rocket.** A rocket entity with a launch sequence that travels to the
      planet and back.
- [ ] **4. Ship interior.** A walkable interior linked to the ship (decision A).
- [ ] **5. Procedural planets.** Terrain, biomes and ores seeded by galaxy
      coordinates (decision B).
- [ ] **6. Star map.** A galaxy/star map UI for choosing seeded destinations.
- [ ] **7. Trading.** NPCs with factions, pricing and trade GUIs.
- [ ] **8. Progression.** Fuel, upgrades and crafting chains.
- [ ] **9. Polish.** Performance, multiplayer sync, config, balance.

---

## Dev workflow

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64   # cloud container; locally any JDK 25
./gradlew build          # compile + jar -> build/libs/
./gradlew runDatagen     # regenerate src/main/generated (commit the output)
./gradlew runClient      # dev client (playtest)
./gradlew runServer      # dedicated server: verifies the mod loads without client code
./gradlew genSources     # decompiled MC sources, for checking real API names
```

### Cloud environment

The `starfront` environment allows `maven.fabricmc.net`, `meta.fabricmc.net`, the Fabric
docs, and Mojang's download hosts. Its setup script installs `openjdk-25-jdk-headless`.
New dependency hosts, such as `maven.nucleoid.xyz` for Fantasy, must be added to the
environment before they work.

## Conventions

- Java 25 language features are fine: records, sealed types, pattern matching.
- Mod id `starfront`. Every registry id is lowercase_snake (`starfront:hull_plating`).
- Every class gets a short header comment explaining its role in its system.
- Commit after every working milestone. Message format: `M<n>: <summary>`.
