#!/usr/bin/env python3
"""Generates Starwake's procedural-planet datapack content (M5).

Writes, under src/main/resources/data/starwake/:
  starwake/planet_archetype/<kind>.json   the 8 archetypes (what the seed picks from)
  worldgen/biome/planet/<kind>_<name>.json  ~30 biomes
  worldgen/feature/planet/*.json, worldgen/placed_feature/planet/*.json
  worldgen/noise_settings/planet/{standard,large,rugged}.json  terrain styles
  dimension_type/planet/<kind>.json, world_clock/<kind>.json, timeline/<kind>_day.json,
  tags/timeline/in_<kind>.json                                  skies and day lengths

Why a script: biome feature lists must keep one global order (vanilla rejects "feature order
cycles"), and the archetypes, biomes and skies share palettes. The JSON it writes is the data;
tweak it here and re-run, or edit the JSON directly for one-off experiments.

Everything aims at real planetary science: real minerals, airless worlds with black skies and
craters, hot toxic greenhouse worlds near the star, frozen worlds far out.
Usage: python3 tools/planet_data.py
"""
import json
import os
import shutil

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "data", "starwake")
VANILLA_NOISE = None  # filled from the vanilla overworld noise settings below (inline copy)

# ------------------------------------------------------------------------------------------
# Helpers
# ------------------------------------------------------------------------------------------


def write(rel, data):
    path = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def block(state):
    return {"type": "minecraft:block", "result_state": state}


def cond(condition, then):
    return {"type": "minecraft:condition", "if_true": condition, "then_run": then}


def seq(*rules):
    return {"type": "minecraft:sequence", "sequence": [r for r in rules if r is not None]}


def surface(top, under, underwater=None, steep=None, deep=None):
    """A biome's surface material rule: top layer, a few blocks under it, the sea floor, and
    optionally bare rock on steep slopes and a deeper layer."""
    floor = seq(
        cond({"type": "minecraft:steep"}, block(steep)) if steep else None,
        cond("minecraft:not_underwater", block(top)),
        block(underwater or under),
    )
    return seq(
        cond("minecraft:on_floor", floor),
        cond("minecraft:under_floor", block(under)),
        cond("minecraft:deep_under_floor", block(deep)) if deep else None,
    )


def ore_feature(state, size, air_discard=0.0):
    return {
        "type": "minecraft:ore",
        "discard_chance_on_air_exposure": air_discard,
        "size": size,
        "targets": [{"state": state, "target": {"predicate_type": "minecraft:tag_match", "tag": "starwake:planet_ore_replaceables"}}],
    }


def ore_placed(feature, count, low, high, shape="trapezoid"):
    return {
        "feature": feature,
        "placement": [
            {"type": "minecraft:count", "count": count},
            {"type": "minecraft:in_square"},
            {"type": "minecraft:height_range", "height": {"type": f"minecraft:{shape}",
                                                         "min_inclusive": {"absolute": low}, "max_inclusive": {"absolute": high}}},
            {"type": "minecraft:biome"},
        ],
    }


def patch_placed(feature, tries, spread=7, rarity=None, heightmap="WORLD_SURFACE_WG", predicate=None):
    placement = []
    if rarity:
        placement.append({"type": "minecraft:rarity_filter", "chance": rarity})
    placement += [
        {"type": "minecraft:in_square"},
        {"type": "minecraft:heightmap", "heightmap": heightmap},
        {"type": "minecraft:biome"},
        {"type": "minecraft:count", "count": tries},
        {"type": "minecraft:offset",
         "x": {"type": "minecraft:trapezoid", "min": -spread, "max": spread, "plateau": 0},
         "y": {"type": "minecraft:trapezoid", "min": -3, "max": 3, "plateau": 0},
         "z": {"type": "minecraft:trapezoid", "min": -spread, "max": spread, "plateau": 0}},
        {"type": "minecraft:block_predicate_filter", "predicate": predicate or {
            "type": "minecraft:all_of", "predicates": [
                {"type": "minecraft:matching_block_tag", "tag": "minecraft:air"},
                {"type": "minecraft:solid", "offset": [0, -1, 0]},
            ]}},
    ]
    return {"feature": feature, "placement": placement}


def surface_placed(feature, count=None, rarity=None, on=None, heightmap="WORLD_SURFACE_WG"):
    placement = []
    if rarity:
        placement.append({"type": "minecraft:rarity_filter", "chance": rarity})
    if count is not None:
        placement.append({"type": "minecraft:count", "count": count})
    placement += [{"type": "minecraft:in_square"}, {"type": "minecraft:heightmap", "heightmap": heightmap},
                  {"type": "minecraft:biome"}]
    if on:
        placement.append({"type": "minecraft:block_predicate_filter",
                          "predicate": {"type": "minecraft:matching_blocks", "offset": [0, -1, 0], "blocks": on}})
    return {"feature": feature, "placement": placement}


# ------------------------------------------------------------------------------------------
# Features. FEATURES lists every feature in the one global order biomes must follow:
# (id, generation step, feature json or None for vanilla, placed json or None for vanilla)
# ------------------------------------------------------------------------------------------
P = "starwake:planet/"
STEPS = 11
FEATURES = []


def feature(name, step, feat, placed):
    FEATURES.append((name, step, feat, placed))
    return name


# Step 1: lakes
feature("minecraft:lake_lava_surface", 1, None, None)
feature("minecraft:sulfur_pool", 1, None, None)

# Step 2: local modifications (craters, geodes, icebergs, lava deltas)
feature(P + "crater_small", 2, {"type": "starwake:crater", "radius": {"type": "minecraft:uniform", "min_inclusive": 3, "max_inclusive": 7},
                                "depth": 0.25, "rim": 0.1},
        surface_placed(P + "crater_small", rarity=3))
feature(P + "crater_large", 2, {"type": "starwake:crater", "radius": {"type": "minecraft:uniform", "min_inclusive": 9, "max_inclusive": 14},
                                "depth": 0.2, "rim": 0.08},
        surface_placed(P + "crater_large", rarity=12))
feature("minecraft:amethyst_geode", 2, None, None)
feature(P + "selenite_geode", 2, {
    "type": "minecraft:geode",
    "blocks": {
        "filling_provider": {"id": "minecraft:air"},
        "inner_layer_provider": {"id": "starwake:selenite"},
        "alternate_inner_layer_provider": {"id": "starwake:selenite"},
        "middle_layer_provider": {"id": "minecraft:calcite"},
        "outer_layer_provider": {"id": "minecraft:tuff"},
        "inner_placements": [{"id": "starwake:selenite_cluster", "properties": {"facing": "up", "waterlogged": "false"}}],
        "cannot_replace": "#minecraft:features_cannot_replace",
        "invalid_blocks": "#minecraft:geode_invalid_blocks",
    },
    "crack": {"generate_crack_chance": 0.6},
    "invalid_blocks_threshold": 1,
    "layers": {"filling": 1.7, "inner_layer": 2.4, "middle_layer": 3.2, "outer_layer": 4.4},
    "outer_wall_distance": {"type": "minecraft:uniform", "min_inclusive": 5, "max_inclusive": 7},
    "use_alternate_layer0_chance": 0.0,
    "use_potential_placements_chance": 0.6,
}, {"feature": P + "selenite_geode", "placement": [
    {"type": "minecraft:rarity_filter", "chance": 4}, {"type": "minecraft:in_square"},
    {"type": "minecraft:height_range", "height": {"type": "minecraft:uniform", "min_inclusive": {"above_bottom": 10}, "max_inclusive": {"absolute": 60}}},
    {"type": "minecraft:biome"}]})
feature("minecraft:iceberg_packed", 2, None, None)
feature("minecraft:iceberg_blue", 2, None, None)
feature("minecraft:delta", 2, None, None)

# Step 6: ores. Real minerals; abundances loosely follow where they form.
ORE_SPECS = {
    # Vanilla-block ores: (block state, vein size, veins per chunk, min y, max y)
    "iron": ("minecraft:iron_ore", 9, 18, -48, 96),
    "copper": ("minecraft:copper_ore", 10, 12, -16, 112),
    "gold": ("minecraft:gold_ore", 8, 3, -64, 32),
    "coal": ("minecraft:coal_ore", 15, 16, 0, 160),
    "cinnabar": ("minecraft:cinnabar", 24, 6, -32, 96),
    "sulfur": ("minecraft:sulfur", 28, 6, -32, 96),
    "magma": ("minecraft:magma_block", 24, 6, -48, 48),
}
# Every Starwake mineral ore, from the chemistry tables (tools/periodic_table.py).
with open(os.path.join(ROOT, "..", "..", "starwake", "chemistry", "minerals.json")) as _f:
    MINERALS = [m for m in json.load(_f) if m["registered"]]
for _m in MINERALS:
    _g = _m["worldgen"]
    ORE_SPECS[_m["id"]] = (_m["block"], _g["size"], _g["count"], _g["min_y"], _g["max_y"])

# Minerals each archetype adds to all of its biomes, chosen by where they form on Earth:
# laterites and placers on wet worlds, evaporites in deserts, pegmatites (Li, Be, Cs, Nb, Ta,
# rare earths) on hydrothermal crystal worlds, KREEP rare earths and platinum on airless
# moons, seafloor manganese nodules on ocean worlds, epithermal tellurides on volcanic ones.
ARCH_MINERALS = {
    "lush": ["galena", "sphalerite", "apatite", "monazite", "pyrolusite"],
    "frozen": ["galena", "molybdenite", "zircon", "uraninite", "magnesite"],
    "barren": ["sperrylite", "apatite", "monazite", "xenotime", "zircon"],
    "toxic": ["stibnite", "barite", "sphalerite", "calaverite", "bismuthinite"],
    "volcanic": ["sperrylite", "molybdenite", "bismuthinite", "calaverite", "acanthite"],
    "ocean": ["pyrolusite", "celestine", "barite", "monazite", "zircon"],
    "desert": ["sylvite", "borax", "nitratine", "celestine", "uraninite", "fluorite", "magnesite", "acanthite"],
    "crystal": ["spodumene", "beryl", "pollucite", "columbite", "xenotime", "fluorite", "bastnaesite", "molybdenite"],
}
for name, (state, size, count, low, high) in ORE_SPECS.items():
    feature(P + "ore_" + name, 6, ore_feature(state, size), ore_placed(P + "ore_" + name, count, low, high))

# Step 7: underground decoration
feature("minecraft:dripstone_cluster", 7, None, None)

# Step 8: springs
feature("minecraft:spring_lava", 8, None, None)
feature("minecraft:spring_water", 8, None, None)

# Step 9: vegetation and surface decoration
FERNWOOD = {
    "type": "minecraft:tree",
    "below_trunk_provider": "minecraft:soil_beneath_tree",
    "decorators": [],
    "foliage_placer": {"type": "minecraft:acacia_foliage_placer", "offset": 0, "radius": 3},
    "foliage_provider": {"id": "starwake:fernwood_fronds", "properties": {"distance": "7", "persistent": "false", "waterlogged": "false"}},
    "ignore_vines": True,
    "minimum_size": {"type": "minecraft:two_layers_feature_size", "upper_size": 2},
    "trunk_placer": {"type": "minecraft:straight_trunk_placer", "base_height": 6, "height_rand_a": 3, "height_rand_b": 2},
    "trunk_provider": {"id": "starwake:fernwood_log", "properties": {"axis": "y"}},
}
feature(P + "fernwood_dense", 9, FERNWOOD, surface_placed(P + "fernwood_dense", count=6, on="starwake:mat_turf", heightmap="OCEAN_FLOOR"))
feature(P + "fernwood_sparse", 9, FERNWOOD, surface_placed(P + "fernwood_sparse", rarity=3, on="starwake:mat_turf", heightmap="OCEAN_FLOOR"))
feature(P + "tuft_grass", 9, {"type": "minecraft:simple_block", "to_place": {"id": "starwake:tuft_grass"}},
        patch_placed(P + "tuft_grass", 24))
feature(P + "tuft_grass_sparse", 9, {"type": "minecraft:simple_block", "to_place": {"id": "starwake:tuft_grass"}},
        patch_placed(P + "tuft_grass_sparse", 6, rarity=2))
feature(P + "giant_fungus", 9, {
    "type": "minecraft:huge_fungus",
    "decor_state": "starwake:fungal_cap",
    "hat_state": "starwake:fungal_cap",
    "stem_state": "starwake:fungal_stem",
    "valid_base_block": "minecraft:mycelium",
    "replaceable_blocks": {"type": "minecraft:matching_blocks", "blocks": ["starwake:puffball"]},
}, surface_placed(P + "giant_fungus", count=3, heightmap="OCEAN_FLOOR"))
feature(P + "puffball", 9, {"type": "minecraft:simple_block", "to_place": {"id": "starwake:puffball"}}, patch_placed(P + "puffball", 10))
feature(P + "selenite_spire", 9, {
    "type": "minecraft:block_column",
    "allowed_placement": {"type": "minecraft:matching_block_tag", "tag": "minecraft:air"},
    "direction": "up",
    "layers": [
        {"height": {"type": "minecraft:uniform", "min_inclusive": 2, "max_inclusive": 9}, "provider": {"id": "starwake:selenite"}},
        {"height": 1, "provider": {"id": "starwake:selenite_cluster", "properties": {"facing": "up", "waterlogged": "false"}}},
    ],
    "prioritize_tip": False,
}, surface_placed(P + "selenite_spire", count=2))
feature(P + "selenite_cluster", 9, {"type": "minecraft:simple_block",
                                    "to_place": {"id": "starwake:selenite_cluster", "properties": {"facing": "up", "waterlogged": "false"}}},
        patch_placed(P + "selenite_cluster", 8))
feature("minecraft:sulfur_spike_cluster", 9, None, None)
feature("minecraft:ice_spike", 9, None, None)
feature("minecraft:warm_ocean_vegetation", 9, None, None)
feature("minecraft:kelp_warm", 9, None, None)
feature("minecraft:kelp_cold", 9, None, None)
feature("minecraft:seagrass_normal", 9, None, None)
feature("minecraft:seagrass_cold", 9, None, None)

# Step 10: top layer
feature("minecraft:freeze_top_layer", 10, None, None)

ORDER = {name: i for i, (name, *_rest) in enumerate(FEATURES)}
STEP = {name: step for name, step, *_rest in FEATURES}

# ------------------------------------------------------------------------------------------
# Archetypes
# ------------------------------------------------------------------------------------------
LAND = [-0.19, 1.0]
OCEAN = [-1.2, -0.19]


def climate(t=0.0, h=0.0, c=None, e=0.0, w=0.0):
    return {"temperature": t, "humidity": h, "continentalness": c if c is not None else LAND, "erosion": e,
            "weirdness": w, "depth": 0.0, "offset": 0.0}


SKY = {}  # kind -> sky style


def sky(kind, sky_color, fog, cloud, particles=None, airless=False, period=24000, sunset="#ffd27a"):
    SKY[kind] = dict(sky=sky_color, fog=fog, cloud=cloud, particles=particles, airless=airless, period=period, sunset=sunset)


ARCHETYPES = {}


def archetype(kind, name, weight, orbit, radius, env, terrain, stone, fluid, sea_level, biome_count, colors, biomes):
    ARCHETYPES[kind] = dict(name=name, weight=weight, orbit=orbit, radius=radius, env=env, terrain=terrain,
                            stone=stone, fluid=fluid, sea_level=sea_level, biome_count=biome_count, colors=colors, biomes=biomes)


def biome(name, params, surf, features, temperature=0.8, precipitation=False, water="#3f76e4", water_fog="#050533",
          particles=None, required=False, carvers=True, fog=None):
    return dict(name=name, params=params, surface=surf, features=features, temperature=temperature,
                precipitation=precipitation, water=water, water_fog=water_fog, particles=particles, required=required,
                carvers=carvers, fog=fog)


ORES = lambda *names: [P + "ore_" + n for n in names]  # noqa: E731

# Lush: temperate, wet, alive. Dark-pigmented photosynthesis (purple mats, tree ferns).
sky("lush", "#7fb6e8", "#b9d4e6", "#e8ffffff", period=30000)
archetype("lush", "Lush world", 1.0, [1.4, 3.2], [3000, 4500],
          dict(gravity=[0.8, 1.15], air_density=[0.9, 1.3], breathable=0.8, toxicity=[0.0, 0.04], temperature=[8, 32]),
          ["standard", "large"], ["minecraft:stone", "minecraft:andesite", "minecraft:tuff"], ["minecraft:water"], [58, 66], [3, 4],
          dict(surface=["#2F5D3A", "#3D4F7A", "#4B3A5E"], secondary=["#6A5A3A", "#2E6E5E", "#5B3B6A"], atmosphere=["#8EC5FF", "#9FE0D0"]),
          [
              biome("fernwood_forest", climate(0.3, 0.5), surface("starwake:mat_turf", "minecraft:dirt", "minecraft:gravel", steep="minecraft:stone"),
                    ORES("iron", "copper", "coal", "bauxite", "cassiterite") + [P + "fernwood_dense", P + "tuft_grass", "minecraft:spring_water",
                                                                                "minecraft:seagrass_normal"],
                    temperature=0.8, precipitation=True, water="#3a7a8a", required=True),
              biome("mat_plains", climate(0.5, -0.4), surface("starwake:mat_turf", "minecraft:dirt", "minecraft:sand"),
                    ORES("iron", "copper", "coal", "bauxite") + [P + "fernwood_sparse", P + "tuft_grass", "minecraft:seagrass_normal"],
                    temperature=0.9, precipitation=True),
              biome("highlands", climate(-0.3, 0.0, e=-0.7), surface("starwake:mat_turf", "minecraft:dirt", "minecraft:gravel", steep="minecraft:stone"),
                    ORES("iron", "copper", "gold", "cassiterite", "pentlandite") + [P + "fernwood_sparse", P + "tuft_grass_sparse"],
                    temperature=0.4, precipitation=True),
              biome("wetlands", climate(0.6, 0.9, e=0.6), surface("minecraft:mud", "minecraft:mud", "minecraft:clay"),
                    ORES("iron", "coal", "bauxite") + [P + "fernwood_sparse", P + "tuft_grass", "minecraft:seagrass_normal"],
                    temperature=0.9, precipitation=True, water="#4a6a3a"),
              biome("ocean", climate(0.3, 0.0, c=OCEAN), surface("minecraft:sand", "minecraft:sand", "minecraft:gravel"),
                    ORES("iron", "copper") + ["minecraft:kelp_warm", "minecraft:seagrass_normal"],
                    temperature=0.7, precipitation=True, required=True),
          ])

# Frozen: far from the star. Ice sheets, permafrost tundra, frozen seas.
sky("frozen", "#a9c4e0", "#dfe9f2", "#f0ffffff", particles={"type": "minecraft:white_ash"}, period=60000)
archetype("frozen", "Frozen world", 1.0, [6.0, 9.5], [2500, 4200],
          dict(gravity=[0.6, 1.0], air_density=[0.4, 1.0], breathable=0.15, toxicity=[0.0, 0.08], temperature=[-110, -40]),
          ["standard", "rugged"], ["minecraft:stone", "minecraft:andesite", "minecraft:tuff"], ["minecraft:water"], [55, 64], [3, 4],
          dict(surface=["#D8E6F2", "#C4D8EA", "#E8EEF4"], secondary=["#8FA8C4", "#9AB4C8"], atmosphere=["#BFE0FF", "#DDF0FF"]),
          [
              biome("ice_sheet", climate(-0.6, 0.3), surface("minecraft:snow_block", "minecraft:packed_ice", "minecraft:gravel", deep="minecraft:packed_ice"),
                    ORES("iron", "pentlandite", "cobaltite") + ["minecraft:ice_spike", "minecraft:freeze_top_layer"],
                    temperature=-0.7, precipitation=True, required=True),
              biome("tundra", climate(-0.2, -0.4), surface("starwake:permafrost", "starwake:permafrost", "minecraft:gravel", steep="minecraft:stone"),
                    ORES("iron", "copper", "pentlandite", "cobaltite") + ["minecraft:freeze_top_layer"],
                    temperature=-0.5, precipitation=True),
              biome("glacier_peaks", climate(-0.4, 0.0, e=-0.8), surface("minecraft:snow_block", "minecraft:blue_ice", "minecraft:gravel", steep="minecraft:packed_ice"),
                    ORES("iron", "gold", "cobaltite") + ["minecraft:freeze_top_layer"],
                    temperature=-0.9, precipitation=True),
              biome("frozen_sea", climate(-0.4, 0.0, c=OCEAN), surface("minecraft:gravel", "minecraft:gravel", "minecraft:gravel"),
                    ORES("iron", "pentlandite") + ["minecraft:iceberg_packed", "minecraft:iceberg_blue", "minecraft:kelp_cold",
                                                   "minecraft:seagrass_cold", "minecraft:freeze_top_layer"],
                    temperature=-0.6, precipitation=True, required=True, water="#3d57d6"),
          ])

# Barren: small airless worlds. Black sky, regolith, impact craters, mare basalts rich in ilmenite.
sky("barren", "#000000", "#08080c", "#00000000", airless=True, period=80000)
archetype("barren", "Barren world", 1.2, [0.9, 8.0], [1200, 2600],
          dict(gravity=[0.15, 0.45], air_density=[0.0, 0.0], breathable=0.0, toxicity=[0.0, 0.0], temperature=[-160, 110]),
          ["standard", "large"], ["starwake:anorthosite", "minecraft:smooth_basalt", "minecraft:stone"], ["minecraft:air"], [-60, -60], [2, 3],
          dict(surface=["#8E8C88", "#6E6C69", "#A8A49C"], secondary=["#5A5856", "#7A7672"], atmosphere=["#000000"]),
          [
              biome("highlands", climate(0.0, 0.5), surface("starwake:fine_regolith", "starwake:anorthosite", deep="starwake:anorthosite"),
                    [P + "crater_small", P + "crater_large"] + ORES("iron", "ilmenite", "chromite"),
                    temperature=0.5, carvers=True, required=True),
              biome("maria", climate(0.0, -0.5), surface("starwake:fine_regolith", "minecraft:smooth_basalt", deep="minecraft:smooth_basalt"),
                    [P + "crater_small"] + ORES("iron", "ilmenite", "chromite", "pentlandite"),
                    temperature=0.5),
              biome("crater_fields", climate(0.5, 0.0, e=-0.5), surface("starwake:fine_regolith", "starwake:fine_regolith", deep="starwake:anorthosite"),
                    [P + "crater_small", P + "crater_large"] + ORES("ilmenite", "wolframite", "chromite"),
                    temperature=0.5),
          ])

# Toxic: dense, hot, sulfurous greenhouse worlds near the star; acid seas and fungal growth.
sky("toxic", "#c9c26a", "#a8a24a", "#ccd8d070", particles={"type": "minecraft:white_ash"}, period=48000, sunset="#ffb347")
archetype("toxic", "Toxic world", 1.0, [0.9, 1.8], [2800, 4200],
          dict(gravity=[0.8, 1.1], air_density=[1.5, 3.0], breathable=0.0, toxicity=[0.4, 0.9], temperature=[45, 140]),
          ["standard", "large"], ["minecraft:tuff", "minecraft:andesite", "minecraft:stone"], ["minecraft:water"], [55, 63], [3, 4],
          dict(surface=["#B8B04A", "#9C9A3A", "#C9A94A"], secondary=["#7A6A2A", "#A0A060"], atmosphere=["#E8E07A", "#D8F07A"]),
          [
              biome("sulfur_flats", climate(0.5, -0.4), surface("minecraft:sulfur", "minecraft:tuff", "minecraft:gravel"),
                    ["minecraft:sulfur_pool"] + ORES("copper", "cinnabar", "sulfur", "cobaltite") + ["minecraft:sulfur_spike_cluster"],
                    temperature=1.5, water="#9ad04a", water_fog="#2a4010", required=True),
              biome("fungal_forest", climate(0.2, 0.6), surface("minecraft:mycelium", "minecraft:dirt", "minecraft:gravel"),
                    ORES("copper", "cinnabar", "wolframite") + [P + "giant_fungus", P + "puffball"],
                    temperature=1.2, water="#8ac04a", water_fog="#2a4010"),
              biome("cinnabar_badlands", climate(0.7, 0.0, e=-0.6), surface("minecraft:cinnabar", "minecraft:tuff", "minecraft:gravel", steep="minecraft:tuff"),
                    ORES("copper", "gold", "cinnabar", "wolframite"),
                    temperature=1.6, water="#9ad04a", water_fog="#2a4010"),
              biome("acid_sea", climate(0.4, 0.0, c=OCEAN), surface("minecraft:gravel", "minecraft:tuff", "minecraft:gravel"),
                    ORES("copper", "sulfur"),
                    temperature=1.3, required=True, water="#a8e04a", water_fog="#3a5a10"),
          ])

# Volcanic: Io-like. Lava seas, basalt, ash plains, sulfur vents.
sky("volcanic", "#3a2420", "#5a3020", "#40302a90", particles={"type": "minecraft:ash"}, period=36000, sunset="#ff6a2a")
archetype("volcanic", "Volcanic world", 1.0, [0.8, 2.0], [1800, 3800],
          dict(gravity=[0.5, 1.0], air_density=[0.2, 1.0], breathable=0.0, toxicity=[0.2, 0.6], temperature=[60, 150]),
          ["rugged", "standard"], ["minecraft:blackstone", "minecraft:smooth_basalt"], ["minecraft:lava"], [30, 48], [3, 5],
          dict(surface=["#2A2420", "#3A2E28", "#1E1A18"], secondary=["#C8501E", "#E07A2A", "#B8401A"], atmosphere=["#FF8A4A", "#E06A3A"]),
          [
              biome("ash_plains", climate(0.3, -0.3), surface("starwake:volcanic_ash", "starwake:volcanic_ash", "minecraft:blackstone"),
                    ORES("chromite", "pentlandite", "magma") + ["minecraft:spring_lava"],
                    temperature=2.0, particles={"type": "minecraft:white_ash"}),
              biome("lava_fields", climate(0.7, 0.2), surface("minecraft:basalt", "minecraft:blackstone", "minecraft:magma_block"),
                    ["minecraft:lake_lava_surface", "minecraft:delta"] + ORES("chromite", "gold", "magma") + ["minecraft:spring_lava"],
                    temperature=2.0, required=True),
              biome("obsidian_coast", climate(0.4, 0.6, e=0.5), surface("minecraft:obsidian", "minecraft:blackstone", "minecraft:magma_block"),
                    ORES("ilmenite", "chromite", "magma"),
                    temperature=2.0),
              biome("sulfur_vents", climate(0.6, -0.6, e=-0.5), surface("minecraft:sulfur", "minecraft:blackstone", "minecraft:magma_block", steep="minecraft:basalt"),
                    ORES("sulfur", "cinnabar", "wolframite", "pentlandite") + ["minecraft:sulfur_spike_cluster"],
                    temperature=2.0),
              biome("lava_sea", climate(0.5, 0.0, c=OCEAN), surface("minecraft:magma_block", "minecraft:blackstone", "minecraft:magma_block"),
                    ORES("magma"), temperature=2.0, required=True),
          ])

# Ocean: deep global oceans with scattered island chains.
sky("ocean", "#6aa8f0", "#c0dcf4", "#f8ffffff", period=28000)
archetype("ocean", "Ocean world", 1.0, [1.4, 3.5], [3500, 5000],
          dict(gravity=[0.9, 1.3], air_density=[0.9, 1.4], breathable=0.6, toxicity=[0.0, 0.04], temperature=[5, 34]),
          ["standard", "large"], ["minecraft:stone", "minecraft:andesite", "minecraft:diorite"], ["minecraft:water"], [92, 110], [2, 3],
          dict(surface=["#1E4A8A", "#18407A", "#2A5A9A"], secondary=["#3A7A5A", "#C8B88A"], atmosphere=["#8EC5FF", "#A8D8FF"]),
          [
              biome("deep_ocean", climate(0.4, 0.0, c=OCEAN), surface("minecraft:gravel", "minecraft:gravel", "minecraft:gravel"),
                    ORES("copper", "cassiterite") + ["minecraft:kelp_cold", "minecraft:seagrass_normal"],
                    temperature=0.6, precipitation=True, required=True),
              biome("reef", climate(0.8, 0.3, c=[-0.6, -0.19]), surface("minecraft:sand", "minecraft:sand", "minecraft:sand"),
                    ORES("copper", "bauxite") + ["minecraft:warm_ocean_vegetation", "minecraft:seagrass_normal"],
                    temperature=0.9, precipitation=True, water="#43d5ee"),
              biome("islands", climate(0.5, 0.4), surface("starwake:mat_turf", "minecraft:dirt", "minecraft:sand", steep="minecraft:stone"),
                    ORES("iron", "copper", "cassiterite", "bauxite") + [P + "fernwood_sparse", P + "tuft_grass"],
                    temperature=0.8, precipitation=True),
              biome("shoals", climate(0.2, -0.4), surface("minecraft:sand", "minecraft:sandstone", "minecraft:sand"),
                    ORES("iron", "cassiterite") + [P + "tuft_grass_sparse", "minecraft:seagrass_normal", "minecraft:kelp_warm"],
                    temperature=0.8, precipitation=True),
          ])

# Desert: dry rust-coloured worlds; dunes, salt flats from vanished seas, banded mesas.
sky("desert", "#d8a878", "#e0b890", "#f0e8d0a0", particles={"type": "minecraft:white_ash"}, period=40000, sunset="#6aa8ff")
archetype("desert", "Desert world", 1.0, [1.6, 4.5], [2400, 4000],
          dict(gravity=[0.35, 0.8], air_density=[0.05, 0.6], breathable=0.05, toxicity=[0.0, 0.04], temperature=[-60, 48]),
          ["standard", "large", "rugged"], ["minecraft:sandstone", "minecraft:red_sandstone", "minecraft:stone"], ["minecraft:air"], [-60, -60], [3, 4],
          dict(surface=["#C2703A", "#B8603A", "#D8A060"], secondary=["#8A4A2A", "#E8D0A0"], atmosphere=["#E8B888", "#F0C8A0"]),
          [
              biome("dunes", climate(0.6, -0.6), surface("minecraft:red_sand", "minecraft:red_sandstone", deep="minecraft:red_sandstone"),
                    ORES("iron", "copper", "gold"), temperature=2.0, required=True),
              biome("salt_flats", climate(0.3, 0.4, e=0.7), surface("starwake:halite", "minecraft:sandstone"),
                    ORES("copper", "bauxite"), temperature=2.0),
              biome("mesas", climate(0.5, 0.0, e=-0.6), seq(cond("minecraft:on_floor", {"type": "minecraft:bandlands"}),
                                                           cond("minecraft:under_floor", {"type": "minecraft:bandlands"})),
                    ORES("iron", "gold", "cassiterite", "copper"), temperature=2.0),
              biome("canyons", climate(0.2, -0.2, e=-0.3), surface("minecraft:sand", "minecraft:sandstone", steep="minecraft:red_sandstone"),
                    ORES("iron", "copper", "bauxite", "wolframite"), temperature=2.0),
          ])

# Crystal (rare): hydrothermal worlds where mineral-rich water grew giant gypsum crystals.
sky("crystal", "#b0c8f0", "#e0e8f8", "#ffffffff", period=32000, sunset="#c8a0ff")
archetype("crystal", "Crystal world", 0.35, [1.2, 6.0], [2000, 3500],
          dict(gravity=[0.6, 1.0], air_density=[0.3, 1.0], breathable=0.1, toxicity=[0.0, 0.3], temperature=[-20, 70]),
          ["rugged", "standard"], ["minecraft:calcite", "minecraft:diorite", "minecraft:tuff"], ["minecraft:water"], [40, 60], [3, 4],
          dict(surface=["#E8E4DC", "#D0D8E8", "#E0D0E8"], secondary=["#A890C8", "#8AA8D0"], atmosphere=["#D0E0FF", "#E8D8FF"]),
          [
              biome("selenite_fields", climate(0.3, -0.3), surface("starwake:selenite", "minecraft:calcite", "minecraft:gravel", steep="minecraft:calcite"),
                    [P + "selenite_geode"] + ORES("wolframite", "cobaltite") + [P + "selenite_spire", P + "selenite_cluster"],
                    temperature=0.8, required=True),
              biome("geode_hills", climate(-0.2, 0.3, e=-0.5), surface("minecraft:calcite", "minecraft:calcite", "minecraft:gravel", steep="minecraft:tuff"),
                    ["minecraft:amethyst_geode", P + "selenite_geode"] + ORES("pentlandite", "cobaltite", "gold") + ["minecraft:dripstone_cluster",
                                                                                                                    P + "selenite_cluster"],
                    temperature=0.6),
              biome("calcite_terraces", climate(0.5, 0.6, e=0.5), surface("minecraft:calcite", "minecraft:dripstone_block", "minecraft:clay"),
                    [P + "selenite_geode"] + ORES("copper", "wolframite") + ["minecraft:dripstone_cluster", "minecraft:spring_water"],
                    temperature=0.7, water="#6ad8e8"),
              biome("crystal_lake", climate(0.3, 0.0, c=OCEAN), surface("minecraft:calcite", "minecraft:calcite", "minecraft:calcite"),
                    ORES("cobaltite") + ["minecraft:seagrass_cold"], temperature=0.6, required=True, water="#5ae0e0"),
          ])

# ------------------------------------------------------------------------------------------
# Writers
# ------------------------------------------------------------------------------------------


def biome_json(kind, b):
    steps = [[] for _ in range(STEPS)]
    for f in sorted(set(b["features"]), key=lambda n: ORDER[n]):
        steps[STEP[f]].append(f)
    attributes = {"minecraft:gameplay/natural_mob_spawns": {"spawn_costs": {}, "spawns_by_category": {}},
                  "minecraft:visual/water_fog_color": b["water_fog"]}
    if b["particles"]:
        attributes["minecraft:visual/ambient_particles"] = [{"particle": b["particles"], "probability": 0.01}]
    return {
        "attributes": attributes,
        "carvers": ["minecraft:cave", "minecraft:cave_extra_underground", "minecraft:canyon"] if b["carvers"] else [],
        "downfall": 0.5 if b["precipitation"] else 0.0,
        "effects": {"water_color": b["water"]},
        "features": steps,
        "has_precipitation": b["precipitation"],
        "temperature": b["temperature"],
    }


def range_json(r):
    return r[0] if r[0] == r[1] else r


def archetype_json(kind, a):
    biomes = []
    for b in a["biomes"]:
        biomes.append({"biome": f"starwake:planet/{kind}_{b['name']}", "required": b["required"], "parameters": b["params"],
                       "surface": b["surface"]})
    env = a["env"]
    return {
        "name": {"translate": f"archetype.starwake.{kind}"},
        "weight": a["weight"],
        "orbit": range_json(a["orbit"]),
        "radius": range_json(a["radius"]),
        "environment": {"gravity": range_json(env["gravity"]), "air_density": range_json(env["air_density"]),
                        "breathable": env["breathable"], "toxicity": range_json(env["toxicity"]),
                        "temperature": range_json(env["temperature"])},
        "dimension_types": [f"starwake:planet/{kind}"],
        "terrain": [f"starwake:planet/{t}" for t in a["terrain"]],
        "stone": [{"id": s} if s != "minecraft:basalt" else {"id": s, "properties": {"axis": "y"}} for s in a["stone"]],
        "fluid": [{"id": f} if f == "minecraft:air" else {"id": f, "properties": {"level": "0"}} for f in a["fluid"]],
        "sea_level": range_json(a["sea_level"]),
        "biome_count": range_json(a["biome_count"]),
        "biomes": biomes,
        "colors": a["colors"],
    }


def dimension_type_json(kind, s):
    attributes = {
        "minecraft:audio/ambient_sounds": {"mood": {"block_search_extent": 8, "offset": 2.0, "sound": "minecraft:ambient.cave", "tick_delay": 6000}},
        "minecraft:gameplay/bed_rule": {"can_set_spawn": "always", "can_sleep": "when_dark",
                                        "error_message": {"translate": "block.minecraft.bed.no_sleep"}},
        "minecraft:gameplay/respawn_anchor_works": False,
        "minecraft:visual/sky_color": s["sky"],
        "minecraft:visual/fog_color": s["fog"],
        "minecraft:visual/cloud_color": s["cloud"],
        "minecraft:visual/cloud_height": 220.0,
    }
    if s["particles"]:
        attributes["minecraft:visual/ambient_particles"] = [{"particle": s["particles"], "probability": 0.006}]
    return {
        "ambient_light": 0.0,
        "attributes": attributes,
        "coordinate_scale": 1.0,
        "default_clock": f"starwake:{kind}",
        "has_ceiling": False,
        "has_ender_dragon_fight": False,
        "has_skylight": True,
        "height": 384,
        "infiniburn": "#minecraft:infiniburn_overworld",
        "logical_height": 384,
        "min_y": -64,
        "monster_spawn_block_light_limit": 0,
        "monster_spawn_light_level": {"type": "minecraft:uniform", "max_inclusive": 7, "min_inclusive": 0},
        "timelines": f"#starwake:in_{kind}",
    }


def timeline_json(kind, s, veyra):
    """Veyra's day curve scaled to this sky's day length; airless skies keep the stars out."""
    k = s["period"] / veyra["period_ticks"]
    t = json.loads(json.dumps(veyra))
    t["clock"] = f"starwake:{kind}"
    t["period_ticks"] = s["period"]
    for track in t["tracks"].values():
        for kf in track["keyframes"]:
            kf["ticks"] = int(round(kf["ticks"] * k))
    for name, marker in list(t["time_markers"].items()):
        if isinstance(marker, dict):
            marker["ticks"] = int(round(marker["ticks"] * k))
        else:
            t["time_markers"][name] = int(round(marker * k))
    if s["airless"]:
        for kf in t["tracks"]["minecraft:visual/star_brightness"]["keyframes"]:
            kf["value"] = 0.9
        t["tracks"].pop("minecraft:visual/sunrise_sunset_color", None)
    else:
        for kf in t["tracks"]["minecraft:visual/sunrise_sunset_color"]["keyframes"]:
            kf["value"] = kf["value"][:3] + s["sunset"].lstrip("#")
    return t


def noise_settings(style):
    """Vanilla terrain shapes as styles: standard (Overworld), large (large biomes), rugged
    (amplified). Rock, fluid, sea level and surface are replaced per planet in code."""
    base = {
        "standard": ("overworld", "overworld"),
        "large": ("overworld_large_biomes", "overworld_large_biomes"),
        "rugged": ("overworld_amplified", "overworld"),
    }[style]
    shape, climate_fns = base
    o = "minecraft:" + shape + "/"
    c = "minecraft:" + climate_fns + "/"
    ow = "minecraft:overworld/"
    has_own = {"overworld_amplified": {"chunk_surface_level", "depth", "final_density", "preliminary_surface_level"}}
    def fn(name, climate_kind=False):
        if style == "rugged":
            return ("minecraft:overworld_amplified/" + name) if name in has_own["overworld_amplified"] else ow + name
        return (c if climate_kind else o) + name
    return {
        "aquifers": {
            "barrier": {"type": "minecraft:noise", "noise": "minecraft:aquifer_barrier", "xz_scale": 1.0, "y_scale": 0.5},
            "exclusion": {"type": "minecraft:min", "left": {"type": "minecraft:sub", "left": -0.225, "right": fn("erosion", True)},
                          "right": {"type": "minecraft:max", "left": {"type": "minecraft:sub", "left": fn("depth"), "right": 0.9}, "right": 0.0}},
            "fluid_level_floodedness": {"type": "minecraft:noise", "noise": "minecraft:aquifer_fluid_level_floodedness", "xz_scale": 1.0, "y_scale": 0.67},
            "fluid_level_spread": {"type": "minecraft:noise", "noise": "minecraft:aquifer_fluid_level_spread", "xz_scale": 1.0, "y_scale": 0.7142857142857143},
            "lava": {"type": "minecraft:noise", "noise": "minecraft:aquifer_lava", "xz_scale": 1.0, "y_scale": 1.0},
            "surface_level": fn("preliminary_surface_level"),
        },
        "debug_functions": [],
        "default_block": {"id": "minecraft:stone"},
        "default_fluid": {"id": "minecraft:water", "properties": {"level": "0"}},
        "disable_mob_generation": True,
        "legacy_random_source": False,
        "material_rule": "minecraft:bedrock_floor",
        "noise": {"height": 384, "min_y": -64},
        "noise_router": {
            "chunk_surface_level": fn("chunk_surface_level"),
            "continents": fn("continents", True),
            "depth": fn("depth"),
            "erosion": fn("erosion", True),
            "final_density": fn("final_density"),
            "ridges": ow + "ridges",
            "temperature": fn("temperature", True),
            "vegetation": fn("vegetation", True),
        },
        "sea_level": 63,
        "spawn_target": [],
    }


def main():
    # Clean previously generated planet content.
    for rel in ("starwake/planet_archetype", "worldgen/biome/planet", "worldgen/feature/planet", "worldgen/placed_feature/planet",
                "worldgen/noise_settings/planet", "dimension_type/planet"):
        shutil.rmtree(os.path.join(ROOT, rel), ignore_errors=True)

    for name, step, feat, placed in FEATURES:
        if feat is not None:
            write(f"worldgen/feature/{name.split(':')[1]}.json", feat)
        if placed is not None:
            write(f"worldgen/placed_feature/{name.split(':')[1]}.json", placed)

    for style in ("standard", "large", "rugged"):
        write(f"worldgen/noise_settings/planet/{style}.json", noise_settings(style))

    with open(os.path.join(ROOT, "timeline", "veyra_day.json")) as f:
        veyra = json.load(f)
    for kind, a in ARCHETYPES.items():
        write(f"starwake/planet_archetype/{kind}.json", archetype_json(kind, a))
        for b in a["biomes"]:
            b["features"] = b["features"] + [P + "ore_" + m for m in ARCH_MINERALS.get(kind, [])]
            write(f"worldgen/biome/planet/{kind}_{b['name']}.json", biome_json(kind, b))
        s = SKY[kind]
        write(f"dimension_type/planet/{kind}.json", dimension_type_json(kind, s))
        write(f"world_clock/{kind}.json", {})
        write(f"timeline/{kind}_day.json", timeline_json(kind, s, veyra))
        write(f"tags/timeline/in_{kind}.json", {"values": ["#minecraft:universal", f"starwake:{kind}_day"]})
    placed = {f.split("ore_", 1)[1] for a in ARCHETYPES.values() for b in a["biomes"] for f in b["features"] if "/ore_" in f}
    missing = [m["id"] for m in MINERALS if m["id"] not in placed]
    assert not missing, f"minerals never generated: {missing}"
    print(f"{len(ARCHETYPES)} archetypes, {sum(len(a['biomes']) for a in ARCHETYPES.values())} biomes, "
          f"{sum(1 for f in FEATURES if f[2] is not None)} features")


if __name__ == "__main__":
    main()
