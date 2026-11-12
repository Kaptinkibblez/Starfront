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


def process_textures():
    """M8c-1 unit operations: a front that shows the machine's job (lit when working), side, top."""
    def front(name, base, draw):
        for on in (False, True):
            im = panel(base, sum(map(ord, name)) & 0xFF)
            draw(im, on)
            save(im, f"{name}_front" + ("_on" if on else ""))
        side = panel(base, (sum(map(ord, name)) * 7) & 0xFF)
        rect(side, 3, 3, 12, 3, "#c8a040")
        save(side, f"{name}_side")
        top = panel(base, (sum(map(ord, name)) * 13) & 0xFF)
        rect(top, 5, 5, 10, 10, "#2a2c31")
        save(top, f"{name}_top")

    def crusher(im, on):
        rect(im, 3, 3, 12, 12, "#2a2c31")
        for i in range(4):
            rect(im, 4 + i * 2, 4 + i, 5 + i * 2, 11 - i, "#8a8f98")
        rect(im, 12, 2, 13, 3, "#ff4a3a" if on else "#5a2a2a")

    def flotation(im, on):
        rect(im, 3, 3, 12, 12, "#1a3a5a")
        for x, y in ((5, 9), (8, 6), (10, 10), (6, 5), (11, 4)):
            rect(im, x, y, x, y, "#bfe8ff" if on else "#4a7aa0")
        rect(im, 3, 3, 12, 4, "#c8b060" if on else "#7a6a40")

    def magnetic(im, on):
        rect(im, 3, 4, 12, 11, "#2a2c31")
        for x in range(4, 12):
            rect(im, x, 5, x, 10, "#c87a3a" if x % 2 else "#a85a2a")
        rect(im, 7, 3, 8, 12, "#55d8ff" if on else "#3a5a6a")

    def gravity(im, on):
        rect(im, 2, 5, 13, 11, "#c8c0a8")
        for y in (6, 8, 10):
            rect(im, 3, y, 12 - (y - 6), y, "#6a5a40")
        rect(im, 10, 6, 12, 7, "#3a2e2a" if on else "#8a7a60")

    def roaster(im, on):
        rect(im, 3, 4, 12, 12, "#2a1e1a")
        for y in range(6, 12, 2):
            rect(im, 4, y, 11, y, "#ff7a2a" if on else "#4a3a34")
        rect(im, 3, 3, 12, 3, "#8a4a2a")

    def coke_oven(im, on):
        for y in range(2, 14):
            for x in range(2, 14):
                im.putpixel((x, y), (hexrgb("#6a3a2e") if (x + (y // 2) * 2) % 4 else hexrgb("#4a2a22")) + (255,))
        rect(im, 5, 6, 10, 13, "#1a1414")
        if on:
            rect(im, 6, 10, 9, 12, "#ff6a1a")

    def smelter(im, on):
        rect(im, 3, 3, 12, 12, "#5a4a44")
        rect(im, 5, 5, 10, 10, "#1a1414")
        rect(im, 6, 7, 9, 10, "#ffb03a" if on else "#3a2a24")
        rect(im, 7, 11, 8, 13, "#ff7a1a" if on else "#2a2420")

    def converter(im, on):
        for y in range(3, 13):
            half = 3 + min(y - 3, 12 - y, 2)
            for x in range(8 - half, 8 + half):
                im.putpixel((x, y), hexrgb("#4a4e56") + (255,))
        rect(im, 6, 3, 9, 4, "#ffd25a" if on else "#2a2420")

    front("crusher", "#6a6e66", crusher)
    front("flotation", "#5e7080", flotation)
    front("magnetic", "#6a6e78", magnetic)
    front("gravity", "#7a7468", gravity)
    front("roaster", "#6e5a50", roaster)
    front("coke_oven", "#5e4a44", coke_oven)
    front("smelter", "#6a6460", smelter)
    front("converter", "#5a5e68", converter)

    # M8c-2: wet chemistry, electrochemistry, water.
    def reactor(im, on):
        rect(im, 4, 2, 11, 13, "#3a4a3e")
        rect(im, 5, 4, 10, 12, "#7ac87a" if on else "#2e3a30")
        rect(im, 7, 2, 8, 6, "#c8c8c8")
        rect(im, 5, 8, 10, 8, "#c8c8c8")

    def leach(im, on):
        rect(im, 2, 4, 13, 12, "#4a4440")
        rect(im, 3, 6, 12, 11, "#5aa0c8" if on else "#2e4450")
        rect(im, 7, 2, 8, 9, "#b0b0b0")
        rect(im, 5, 9, 10, 9, "#b0b0b0")

    def evaporator(im, on):
        rect(im, 3, 6, 12, 12, "#5a5450")
        rect(im, 4, 9, 11, 11, "#a8d8f0" if on else "#3a4a54")
        if on:
            for x, y in ((5, 3), (8, 2), (10, 4), (6, 6)):
                rect(im, x, y, x, y, "#e8f4ff")

    def crystallizer(im, on):
        rect(im, 3, 3, 12, 12, "#2e3a44")
        for x, y in ((5, 9), (7, 6), (9, 10), (10, 7), (6, 11)):
            rect(im, x, y, x + 1, y + 1, "#f0f0ff" if on else "#8a9aa8")

    def electrolysis(im, on):
        rect(im, 2, 4, 13, 13, "#3a3a44")
        rect(im, 3, 6, 12, 12, "#4a7ab0" if on else "#2a3444")
        rect(im, 4, 2, 5, 11, "#c87a3a")
        rect(im, 10, 2, 11, 11, "#9a9aa4")
        if on:
            rect(im, 7, 5, 8, 5, "#ffff80")

    def chlorinator(im, on):
        rect(im, 4, 2, 11, 13, "#4a4a3a")
        rect(im, 5, 4, 10, 12, "#c8d84a" if on else "#3a3e2a")
        rect(im, 5, 2, 10, 3, "#8a8a7a")

    def kroll(im, on):
        rect(im, 4, 3, 11, 13, "#6a5a54")
        rect(im, 5, 5, 10, 12, "#ff8a3a" if on else "#3a2e2a")
        rect(im, 4, 2, 11, 3, "#4a4a4e")
        rect(im, 7, 1, 8, 2, "#9a9aa0")

    def water_pump(im, on):
        rect(im, 3, 4, 12, 12, "#3a4450")
        rect(im, 5, 6, 10, 10, "#3a7ac8" if on else "#2a3a50")
        rect(im, 7, 2, 8, 6, "#9aa4b0")

    def atmospheric_condenser(im, on):
        for y in range(3, 13, 2):
            rect(im, 3, y, 12, y, "#b0b8c0")
        if on:
            for x, y in ((5, 12), (8, 13), (10, 12)):
                rect(im, x, y, x, y, "#60b0ff")

    front("reactor", "#5a6460", reactor)
    front("leach", "#5e6066", leach)
    front("evaporator", "#6a6660", evaporator)
    front("crystallizer", "#5a6470", crystallizer)
    front("electrolysis", "#5a5a66", electrolysis)
    front("chlorinator", "#646456", chlorinator)
    front("kroll", "#6a605a", kroll)
    front("water_pump", "#566270", water_pump)
    front("atmospheric_condenser", "#646e78", atmospheric_condenser)


def bulk_textures():
    """Bulk materials (tinted by their main species): crushed ore, concentrate, tailings, calcine,
    matte, slag, flue dust, coke. Light grey with shading only."""
    out = os.path.join(ROOT, "textures", "item")
    rnd = random.Random(42)

    def pile(seed, lumpy, grain, base=215):
        r = random.Random(seed)
        im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        for y in range(5, 15):
            half = (y - 4) * 0.8 + 1
            for x in range(16):
                if abs(x - 7.5) <= half and (not lumpy or r.random() > 0.08 or y > 10):
                    v = base - (y - 5) * 6 + r.randint(-grain, grain)
                    im.putpixel((x, y), (max(0, min(255, v)),) * 3 + (255,))
        return im

    def lumps(seed, n, size, base=210):
        r = random.Random(seed)
        im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        for _ in range(n):
            cx, cy = r.randint(3, 12), r.randint(4, 13)
            for y in range(cy - size, cy + size):
                for x in range(cx - size, cx + size):
                    if 0 <= x < 16 and 0 <= y < 16 and (x - cx) ** 2 + (y - cy) ** 2 <= size * size:
                        v = base + (cy - y) * 8 + r.randint(-15, 10)
                        im.putpixel((x, y), (max(0, min(255, v)),) * 3 + (255,))
        return im

    def crystals(seed):
        r = random.Random(seed)
        im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        for _ in range(7):
            cx, cy, h = r.randint(3, 12), r.randint(8, 14), r.randint(3, 6)
            for y in range(cy - h, cy + 1):
                w = 1 if y < cy - h + 2 else 2
                for x in range(cx - w + 1, cx + w):
                    if 0 <= x < 16 and 0 <= y < 16:
                        v = 240 - (cy - y) * 4 + (20 if x == cx else 0) + r.randint(-10, 5)
                        im.putpixel((x, y), (max(0, min(255, v)),) * 3 + (255,))
        return im

    images = {"crushed_ore": lumps(1, 9, 2), "concentrate": pile(2, False, 12), "tailings": pile(3, True, 25, 190),
              "calcine": lumps(4, 14, 1, 225), "matte": lumps(5, 3, 4, 170), "slag": lumps(6, 4, 3, 150),
              "flue_dust": pile(7, False, 6, 235), "coke": lumps(8, 7, 2, 120),
              "residue": pile(9, True, 18, 180), "crystals": crystals(10), "slimes": pile(11, False, 20, 160)}
    for name, im in images.items():
        im.save(os.path.join(out, f"bulk_{name}.png"))


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
    process_textures()
    bulk_textures()
    models()
    print("machine assets written")


if __name__ == "__main__":
    main()
