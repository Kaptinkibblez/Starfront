# Starwake modpack

An engineering adventure built on **Create** for Minecraft 1.21.1 (NeoForge). Start with nothing, build
water wheels and presses, then brass factories, a foundry, an electric grid, trains, your own currency,
airships, warships and finally rockets to the Moon. A 121-quest book (12 chapters) guides you the whole
way and pays out XP and Numismatics coins. Works solo or with friends.

## Install (Prism Launcher)

1. Download `Starwake-<version>.mrpack` from this release.
2. In Prism Launcher: **Add Instance → Import**, choose the `.mrpack` file (or paste its download link), **OK**.
3. Give the instance at least **6 GB** of memory (Edit → Settings → Java). 8 GB is better for big airships.
4. Launch. Java 21 is required (Prism can download it for you).

Press **U** (or use the Quest Book you start with) to open the quests. A lost Quest Book can be crafted
from a book and a cogwheel.

## Playing with friends

- **Same network:** one player opens their world to LAN (Esc → Open to LAN).
- **Dedicated server:** download `Starwake-<version>-server.zip`, unzip it, run `start.sh` (Linux/macOS)
  or `start.bat` (Windows) once, accept the EULA in `eula.txt`, run it again. Open TCP 25565 and UDP 24454
  (voice chat). Everyone needs the same pack version.
- Make a party and claim your base with **Open Parties and Claims** (default key: apostrophe `'`).
- Quest progress is per player; coins can be traded, banked and spent at each other's shops.

## Quest chapters

1. Planetfall - survival basics, zinc, backpacks, waystones
2. First Rotations - Create kinetics, presses, belts, fans, crushing, contraptions, fluids
3. The Brass Age - blaze burners, brass, precision mechanisms, steam engines, logistics, packages
4. Foundry and Metallurgy - Create: Metallurgy foundry, casting, coke, steel, tungsten
5. Electricity - Crafts & Additions: wire, alternators, power lines, motors, accumulators
6. Rails and Logistics - trains, stations, signals, schedules, Steam 'n' Rails
7. Trade and Coin - Numismatics coins, bank accounts, vendors, blaze bankers
8. Taking Flight - Create Aeronautics: physics assembler, levitite, hot air, propellers, steering
9. Big Guns - Create Big Cannons: casting, cannons, mounts, shells, autocannons, warships
10. To the Stars - Create Cosmonautics: titanium, rocket thrusters, staging, space suit, orbit, the Moon
11. Farmer's Delight - cooking, new crops, feasts, automated farms
12. Crew and Colonies - parties, claims, shared storage, voice chat, the Nether and the End

## Mods

Create, Create Aeronautics (Sable), Create Big Cannons, Create: Metallurgy, Create Crafts & Additions,
Steam 'n' Rails, Create: Numismatics, Create Cosmonautics, Create: Enchantment Industry, Create: Connected,
Copycats+, Create: Power Loader, Odyssey Quests (Heracles), KubeJS + KubeJS Create, JEI + Just Enough
Resources, Jade, AppleSkin, Xaero's Minimap + World Map, Waystones, Sophisticated Backpacks + Storage,
Farmer's Delight, Comforts, Open Parties and Claims, Corpse, Simple Voice Chat, Explorer's Compass,
Polymorph, TrashSlot, Mouse Tweaks, Controlling, Better Advancements, Terralith, NetherPortalFix,
Packet Fixer, and performance mods (Sodium, Lithium, ModernFix, FerriteCore, ImmediatelyFast, Entity
Culling, spark, Chunky). Iris shaders are not included: Aeronautics has visual issues with them.

## For pack developers

The pack is a [packwiz](https://packwiz.infra.link/) project. Quests come from `tools/pack_quests.py`
(run it, then `packwiz refresh` in `pack/`); `tools/pack_server.py` builds the server zip. CI
(`.github/workflows/pack.yml`) exports the `.mrpack`, boots a real client with every mod, and publishes the
release tagged `pack-v<version>`. Bump `version` in `pack.toml` for a new release.
