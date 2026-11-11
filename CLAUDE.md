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
├── galaxy/                 seeded star systems, galaxy coordinates, travel
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
  Milestone 2 uses one static datapack dimension; Milestone 5 swaps in Fantasy.

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
  - [ ] 3f. Components and power: Power Plant, Shield Generator, Cooler, Quantum Drive
        blocks; power routing screen; more pre-flight conditions.
  - [ ] 3g. Space as a real place: planets rendered as spheres in space, enter by flying
        into them, quantum travel between them.
- [ ] **4. Ship editing.** Place and break blocks on assembled ships (turning moved to 3d).
- [ ] **5. Procedural planets.** Terrain, biomes and ores seeded by galaxy
      coordinates (decision B).
- [ ] **6. Star map.** A galaxy/star map UI for choosing seeded destinations.
- [ ] **7. Trading.** NPCs with factions, pricing and trade GUIs.
- [ ] **8. Progression.** Fuel, upgrades and crafting chains.
- [ ] **9. Polish.** Performance, multiplayer sync, config, balance.

---

## How planets work (Milestone 2)

A planet is a set of JSON files. Copy Veyra's and rename them to add one:

| File | Controls |
|---|---|
| `data/starwake/starwake/planet/<id>.json` | Starwake's planet registry: display name, dimension, `gravity` (multiplier) |
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
- **Space (3c):** `ship/SpaceTravel`. A sealed ship above `maxY + 32` teleports (vanilla
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
- Known limits (Milestone 4/9): no in-flight editing or turning; ships don't collide with
  each other or push mobs; unattended ships in unloaded chunks freeze (expected); the
  per-frame block re-tessellation needs a cached mesh for big ships.

## 26.3 gotchas (learned the hard way)

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
New dependency hosts, such as `maven.nucleoid.xyz` for Fantasy, must be added to the
environment before they work.

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
- Client test harness: `$scratchpad/mc.sh` (`start_client`, `join_world`, `cmd`, `shot`,
  `save_quit`, `build_test_ship X Y Z`). Always `save_quit` before restarting, because a hard kill
  loses everything since the last autosave. Set `/gamerule fall_damage false` in test worlds.
- In the cloud container, don't block on long waits: start servers and clients in the
  background and poll with short, bounded loops.
