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

    # M8c-3a: cryogenic air separation (cold box with a distillation column and frost).
    def air_separator(im, on):
        rect(im, 6, 1, 9, 14, "#c8d0d8")
        for y in (3, 6, 9, 12):
            rect(im, 6, y, 9, y, "#8a949e")
        rect(im, 2, 8, 5, 13, "#3a4450")
        rect(im, 10, 8, 13, 13, "#3a4450")
        if on:
            for x, y in ((3, 9), (11, 10), (4, 12), (12, 12), (7, 2)):
                rect(im, x, y, x, y, "#d8f4ff")

    front("air_separator", "#5e6a76", air_separator)

    # M8c-3b: mixer-settler battery (stacked boxes with two liquid layers) and a gas wellhead.
    def solvent_extraction(im, on):
        for i, x in enumerate((2, 6, 10)):
            rect(im, x, 4, x + 3, 12, "#2e3a44")
            rect(im, x, 6, x + 3, 8, "#d8b84a" if on else "#6a5a3a")
            rect(im, x, 9, x + 3, 11, "#4a8ac8" if on else "#2a3a50")
        rect(im, 2, 3, 13, 3, "#9aa4b0")

    def gas_well(im, on):
        rect(im, 6, 2, 9, 13, "#c83a2a")
        rect(im, 4, 5, 11, 6, "#9aa4b0")
        rect(im, 4, 9, 11, 10, "#9aa4b0")
        rect(im, 7, 0, 8, 1, "#ffb03a" if on else "#4a4a4a")

    front("solvent_extraction", "#5a6470", solvent_extraction)
    front("gas_well", "#5e6066", gas_well)

    # M8d-1: induction furnace (coil around a crucible), vacuum arc furnace (chamber + electrode),
    # sintering press (ram over a die).
    def induction_furnace(im, on):
        rect(im, 4, 3, 11, 13, "#3a3e46")
        for y in range(4, 13, 2):
            rect(im, 3, y, 12, y, "#c87a3a")
        rect(im, 6, 5, 9, 12, "#ffb03a" if on else "#2a2420")

    def vacuum_arc_furnace(im, on):
        rect(im, 4, 2, 11, 13, "#6a6e78")
        rect(im, 7, 1, 8, 7, "#9aa4b0")
        rect(im, 5, 9, 10, 12, "#e8f4ff" if on else "#2a2e36")
        rect(im, 3, 7, 12, 7, "#3a3e46")

    def sinter_press(im, on):
        rect(im, 6, 1, 9, 6, "#9aa4b0")
        rect(im, 3, 7, 12, 8, "#5a5e66")
        rect(im, 5, 9, 10, 13, "#ff8a3a" if on else "#3a3e46")

    front("induction_furnace", "#5e6066", induction_furnace)
    front("vacuum_arc_furnace", "#5a5e68", vacuum_arc_furnace)
    front("sinter_press", "#646056", sinter_press)

    # M8d-2: rolling mill (two work rolls), wire drawer (die + capstan), extrusion press (ram, container,
    # die with a rod coming out), forging press (frame, ram, anvil), heat treatment furnace (box + door).
    def rolling_mill(im, on):
        rect(im, 2, 2, 13, 13, "#3a3e46")
        for cy in (5, 10):
            rect(im, 3, cy - 2, 12, cy + 1, "#b8c0ca")
            rect(im, 3, cy - 2, 12, cy - 2, "#e0e6ee")
        rect(im, 1, 7, 14, 7, "#ff8a3a" if on else "#7a7e86")

    def wire_drawer(im, on):
        rect(im, 2, 6, 5, 9, "#9aa4b0")
        rect(im, 6, 7, 14, 7, "#d8a060" if on else "#8a7a60")
        for y in range(3, 13):
            for x in range(8, 15):
                if (x - 11) ** 2 + (y - 8) ** 2 <= 9:
                    im.putpixel((x, y), hexrgb("#6a7280") + (255,))
        rect(im, 11, 8, 11, 8, "#2a2e36")

    def extrusion_press(im, on):
        rect(im, 1, 6, 4, 9, "#9aa4b0")
        rect(im, 5, 4, 10, 11, "#4a4e56")
        rect(im, 6, 6, 9, 9, "#ff8a3a" if on else "#5a5e66")
        rect(im, 11, 7, 14, 8, "#d8dee6")

    def forging_press(im, on):
        rect(im, 2, 1, 13, 2, "#4a4e56")
        rect(im, 2, 1, 3, 14, "#4a4e56")
        rect(im, 12, 1, 13, 14, "#4a4e56")
        rect(im, 5, 3, 10, 7, "#9aa4b0")
        rect(im, 5, 9, 10, 10, "#ff7a2a" if on else "#6a6e76")
        rect(im, 4, 11, 11, 14, "#5a5e66")

    def heat_treat_furnace(im, on):
        rect(im, 2, 2, 13, 13, "#5a4e46")
        rect(im, 4, 4, 11, 11, "#ffb03a" if on else "#2a2420")
        rect(im, 4, 4, 11, 4, "#8a7a6a")
        rect(im, 6, 13, 9, 14, "#3a3e46")

    front("rolling_mill", "#5e6670", rolling_mill)
    front("wire_drawer", "#5e6266", wire_drawer)
    front("extrusion_press", "#606068", extrusion_press)
    front("forging_press", "#5c6068", forging_press)
    front("heat_treat_furnace", "#665a52", heat_treat_furnace)

    # M8e-1: submerged-arc furnace (three electrodes into a glowing charge), crystal puller (a tall
    # chamber with the boule on its seed rod), clean fab (a white cleanroom panel with a wafer stage),
    # fabricator (robot arm over a work bed).
    def arc_furnace(im, on):
        rect(im, 2, 9, 13, 13, "#3a3e46")
        for x in (4, 7, 10):
            rect(im, x, 1, x + 1, 9, "#2a2c31")
        rect(im, 3, 10, 12, 12, "#ffd84a" if on else "#4a3a30")

    def crystal_puller(im, on):
        rect(im, 4, 1, 11, 14, "#8a949e")
        rect(im, 7, 1, 8, 6, "#c8d0d8")
        rect(im, 6, 6, 9, 10, "#b8c0d0" if on else "#5a6070")
        rect(im, 5, 11, 10, 13, "#ff8a3a" if on else "#3a3e46")

    def clean_fab(im, on):
        rect(im, 1, 1, 14, 14, "#d8dde4")
        rect(im, 3, 3, 12, 8, "#2a3a50")
        for y in range(4, 8):
            for x in range(4, 12):
                if (x + y) % 2 == 0:
                    im.putpixel((x, y), hexrgb("#c8a0ff" if on else "#4a5a70") + (255,))
        rect(im, 4, 10, 11, 12, "#9aa4b0")

    def fabricator(im, on):
        rect(im, 1, 11, 14, 14, "#4a4e56")
        rect(im, 3, 2, 4, 10, "#9aa4b0")
        rect(im, 3, 2, 11, 3, "#9aa4b0")
        rect(im, 10, 3, 11, 7, "#c8a040")
        rect(im, 9, 8, 12, 9, "#ff5a3a" if on else "#5a5e66")
        rect(im, 6, 10, 12, 10, "#7a8088")

    def isotope_column(im, on):
        rect(im, 6, 0, 9, 15, "#b8c4d0")
        for y in range(2, 15, 3):
            rect(im, 5, y, 10, y, "#6a7480")
        rect(im, 7, 4, 8, 12, "#7ab8ff" if on else "#3a4a5a")

    front("isotope_column", "#5a6672", isotope_column)
    front("arc_furnace", "#5a5650", arc_furnace)
    front("crystal_puller", "#606a74", crystal_puller)
    front("clean_fab", "#b8c0c8", clean_fab)
    front("fabricator", "#5e6268", fabricator)


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


def gas_cylinder():
    """M8c-3a: a steel pressure cylinder with a valve; the bar shows the gas."""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y in range(4, 15):
        for x in range(5, 11):
            shade_v = 150 + (10 - abs(x - 7) * 18)
            if y in (4, 14) and x in (5, 10):
                continue
            im.putpixel((x, y), (shade_v, shade_v + 6, shade_v + 12, 255))
    rect(im, 7, 2, 8, 3, "#5a5e66")
    rect(im, 6, 1, 9, 1, "#3a3e46")
    rect(im, 5, 6, 10, 6, "#2a6aa8")
    im.save(os.path.join(ROOT, "textures", "item", "gas_cylinder.png"))


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
    gas_cylinder()
    models()
    print("machine assets written")


if __name__ == "__main__":
    main()
