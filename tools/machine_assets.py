#!/usr/bin/env python3
"""Generates M8b power/logistics textures and the hand-shaped block models.

Textures (assets/starwake/textures/block): machine faces (generator, electric furnace, battery,
solar panel), cables, fluid pipe (+ extract flange), fluid tank (frame + glass, alpha), conveyor.
Models (assets/starwake/models/block): element models datagen can't express: cable core/arm,
pipe core/arm/extract arm, solar panel, conveyor, tank, and inventory variants. Blockstates, item
models, loot and lang come from datagen. These are functional placeholders: the visual pass
comes later.

Usage: python3 tools/machine_assets.py
"""
import json
import os
import random

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "starwake")
TEX = os.path.join(ROOT, "textures", "block")
MOD = os.path.join(ROOT, "models", "block")


def hexrgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)


def panel(base, seed, rivets=True, border="#2a2e35"):
    """A machine casing face: noisy plate, dark border, corner rivets."""
    rnd = random.Random(seed)
    im = Image.new("RGBA", (16, 16))
    b = hexrgb(base)
    for y in range(16):
        for x in range(16):
            c = shade(b, 1 + rnd.uniform(-0.05, 0.05))
            if x in (0, 15) or y in (0, 15):
                c = hexrgb(border)
            elif x in (1,) or y in (1,):
                c = shade(b, 1.18)
            elif x in (14,) or y in (14,):
                c = shade(b, 0.78)
            im.putpixel((x, y), c + (255,))
    if rivets:
        for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
            im.putpixel((x, y), shade(b, 0.55) + (255,))
    return im


def rect(im, x0, y0, x1, y1, color, alpha=255):
    c = hexrgb(color) if isinstance(color, str) else color
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            im.putpixel((x, y), c + (alpha,))


def save(im, name):
    os.makedirs(TEX, exist_ok=True)
    im.save(os.path.join(TEX, name + ".png"))


def textures():
    casing = "#6d737c"
    # Combustion generator: firebox grille front (glowing when on), vents on the side, exhaust on top.
    for on in (False, True):
        im = panel(casing, 1)
        rect(im, 3, 5, 12, 12, "#1b1d21")
        for x in range(4, 12, 2):
            rect(im, x, 6, x, 11, "#ff8a2a" if on else "#3a3d44")
        if on:
            for x in range(5, 12, 2):
                rect(im, x, 9, x, 11, "#ffd25a")
        rect(im, 3, 3, 12, 3, "#c84a2a")
        save(im, "combustion_generator_front" + ("_on" if on else ""))
    im = panel(casing, 2)
    for y in range(4, 13, 2):
        rect(im, 4, y, 11, y, "#3a3d44")
    save(im, "combustion_generator_side")
    im = panel(casing, 3)
    rect(im, 5, 5, 10, 10, "#2a2c31"); rect(im, 6, 6, 9, 9, "#14161a")
    save(im, "combustion_generator_top")
    # Electric furnace: window with coils, glowing when on.
    for on in (False, True):
        im = panel("#8a9099", 4)
        rect(im, 3, 4, 12, 11, "#202227")
        for y in (6, 9):
            rect(im, 4, y, 11, y, "#ff6a2a" if on else "#4b4f57")
        rect(im, 3, 13, 12, 13, "#55d8ff" if on else "#2d4f5a")
        save(im, "electric_furnace_front" + ("_on" if on else ""))
    im = panel("#8a9099", 5)
    rect(im, 3, 3, 12, 3, "#55d8ff")
    save(im, "electric_furnace_side")
    save(panel("#8a9099", 6), "electric_furnace_top")
    # Battery: cells with a charge strip.
    im = panel("#3f4a3f", 7, border="#1c201c")
    for x in (3, 7, 11):
        rect(im, x, 3, x + 2, 12, "#2b332b"); rect(im, x, 3, x + 2, 4, "#c9c9c9")
    rect(im, 2, 14, 13, 14, "#7cff6a")
    save(im, "battery_side")
    im = panel("#3f4a3f", 8, border="#1c201c")
    rect(im, 4, 4, 5, 5, "#d84040"); rect(im, 10, 10, 11, 11, "#333333")
    save(im, "battery_top")
    # Solar panel: blue cells in a silver grid.
    im = Image.new("RGBA", (16, 16))
    rnd = random.Random(9)
    for y in range(16):
        for x in range(16):
            grid = x in (0, 15) or y in (0, 15) or x % 5 == 0 or y % 5 == 0
            c = hexrgb("#c8ccd2") if grid else shade(hexrgb("#1d3b7a"), 1 + rnd.uniform(-0.1, 0.12) + (0.25 if (x + y) % 7 == 0 else 0))
            im.putpixel((x, y), c + (255,))
    save(im, "solar_panel_top")
    save(panel("#9aa0a8", 10, rivets=False), "solar_panel_side")
    # Cables: insulated copper (orange stripe) and aluminium HV (grey with yellow band).
    for name, body, band in (("copper_cable", "#3a2a20", "#d9823b"), ("aluminium_cable", "#4a4f57", "#e8c84a")):
        im = Image.new("RGBA", (16, 16))
        for y in range(16):
            for x in range(16):
                c = hexrgb(body)
                if y % 8 in (3, 4):
                    c = hexrgb(band)
                if x in (0, 15):
                    c = shade(c, 0.7)
                im.putpixel((x, y), c + (255,))
        save(im, name)
    # Fluid pipe and its extract flange.
    im = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            c = shade(hexrgb("#b9763f"), 1.15 if y in (5, 6) else 0.9 if y in (10, 11) else 1.0)
            im.putpixel((x, y), c + (255,))
    save(im, "fluid_pipe")
    im = panel("#5a6068", 11, rivets=False)
    rect(im, 4, 4, 11, 11, "#2f7fd0"); rect(im, 6, 6, 9, 9, "#9fd4ff")
    save(im, "fluid_pipe_extract")
    # Fluid tank: steel frame with glass panes (alpha).
    im = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            if x in (0, 1, 14, 15) or y in (0, 1, 14, 15):
                c = shade(hexrgb("#7d848d"), 0.8 if x in (0, 15) or y in (0, 15) else 1.1)
                im.putpixel((x, y), c + (255,))
            elif x == y + 2 or x == y + 3:
                im.putpixel((x, y), (230, 245, 255, 120))
            else:
                im.putpixel((x, y), (190, 220, 240, 60))
    save(im, "fluid_tank")
    # Conveyor: belt top with chevrons pointing north (up in the texture), side rails.
    im = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            c = hexrgb("#2b2d31")
            if x in (0, 1, 14, 15):
                c = hexrgb("#9aa0a8") if x in (1, 14) else hexrgb("#5e636b")
            elif (y + abs(x - 7.5) // 1) % 6 < 2:
                c = hexrgb("#e8c84a")
            im.putpixel((x, y), c + (255,))
    save(im, "conveyor_top")
    im = panel("#5e636b", 12, rivets=False)
    save(im, "conveyor_side")


def element(fr, to, tex, faces=("north", "south", "east", "west", "up", "down"), uv=None):
    return {"from": fr, "to": to, "faces": {f: ({"texture": tex, "uv": uv} if uv else {"texture": tex}) for f in faces}}


def model(name, data):
    os.makedirs(MOD, exist_ok=True)
    with open(os.path.join(MOD, name + ".json"), "w") as f:
        json.dump(data, f, indent=1)


def models():
    for cable, r in (("copper_cable", 2), ("aluminium_cable", 3)):
        lo, hi = 8 - r, 8 + r
        tex = f"starwake:block/{cable}"
        model(cable + "_core", {"parent": "minecraft:block/block", "textures": {"particle": tex, "c": tex},
                                "elements": [element([lo, lo, lo], [hi, hi, hi], "#c")]})
        model(cable + "_arm", {"textures": {"particle": tex, "c": tex},
                               "elements": [element([lo, lo, 0], [hi, hi, lo], "#c", ("east", "west", "up", "down", "north"))]})
        model(cable + "_inventory", {"parent": "minecraft:block/block", "textures": {"particle": tex, "c": tex},
                                     "elements": [element([0, lo, lo], [16, hi, hi], "#c")]})
    tex = "starwake:block/fluid_pipe"
    flange = "starwake:block/fluid_pipe_extract"
    model("fluid_pipe_core", {"parent": "minecraft:block/block", "textures": {"particle": tex, "p": tex},
                              "elements": [element([5, 5, 5], [11, 11, 11], "#p")]})
    model("fluid_pipe_arm", {"textures": {"particle": tex, "p": tex},
                             "elements": [element([5.5, 5.5, 0], [10.5, 10.5, 5], "#p", ("east", "west", "up", "down", "north"))]})
    model("fluid_pipe_extract", {"textures": {"particle": tex, "p": tex, "f": flange},
                                 "elements": [element([5.5, 5.5, 2], [10.5, 10.5, 5], "#p", ("east", "west", "up", "down")),
                                              element([3, 3, 0], [13, 13, 2], "#f")]})
    model("fluid_pipe_inventory", {"parent": "minecraft:block/block", "textures": {"particle": tex, "p": tex},
                                   "elements": [element([0, 5.5, 5.5], [16, 10.5, 10.5], "#p")]})
    model("solar_panel", {"parent": "minecraft:block/block", "textures": {"particle": "starwake:block/solar_panel_top",
                                                                           "top": "starwake:block/solar_panel_top", "side": "starwake:block/solar_panel_side"},
                          "elements": [
                              {"from": [0, 0, 0], "to": [16, 2, 16], "faces": {f: {"texture": "#side"} for f in ("north", "south", "east", "west", "down")} | {"up": {"texture": "#side"}}},
                              {"from": [6, 2, 6], "to": [10, 4, 10], "faces": {f: {"texture": "#side"} for f in ("north", "south", "east", "west")}},
                              {"from": [0, 4, 0], "to": [16, 6, 16], "faces": {"up": {"texture": "#top"}, "down": {"texture": "#side"},
                                                                              "north": {"texture": "#side"}, "south": {"texture": "#side"},
                                                                              "east": {"texture": "#side"}, "west": {"texture": "#side"}}}]})
    model("conveyor", {"parent": "minecraft:block/block", "textures": {"particle": "starwake:block/conveyor_side",
                                                                        "top": "starwake:block/conveyor_top", "side": "starwake:block/conveyor_side"},
                       "elements": [{"from": [0, 0, 0], "to": [16, 3, 16],
                                     "faces": {"up": {"texture": "#top"}, "down": {"texture": "#side"},
                                               "north": {"texture": "#side", "uv": [0, 13, 16, 16]}, "south": {"texture": "#side", "uv": [0, 13, 16, 16]},
                                               "east": {"texture": "#side", "uv": [0, 13, 16, 16]}, "west": {"texture": "#side", "uv": [0, 13, 16, 16]}}}]})
    model("fluid_tank", {"parent": "minecraft:block/cube_all", "textures": {"all": "starwake:block/fluid_tank"}})


def main():
    textures()
    models()
    print("machine assets written")


if __name__ == "__main__":
    main()
