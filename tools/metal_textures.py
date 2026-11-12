#!/usr/bin/env python3
"""Generates the metal form item textures (M8a): ingot, plate, rod, wire and dust.

They are light grey with shading only: the item model tints them by the metal's colour (from its
composition), so one texture serves every alloy. Original pixel art.

Usage: python3 tools/metal_textures.py
"""
import math
import os
import random

from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "starwake", "textures", "item")


def img():
    return Image.new("RGBA", (16, 16), (0, 0, 0, 0))


def put(im, x, y, v, a=255):
    if 0 <= x < 16 and 0 <= y < 16:
        v = max(0, min(255, int(v)))
        im.putpixel((x, y), (v, v, v, a))


def outline(im, v=70):
    px = im.load()
    filled = {(x, y) for x in range(16) for y in range(16) if px[x, y][3] > 0}
    for x, y in list(filled):
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if (x + dx, y + dy) not in filled:
                put(im, x, y, min(px[x, y][0], v + 40) if px[x, y][0] > v else px[x, y][0])
                break


def ingot():
    im = img()
    # Trapezoid bar, front-top view: sloped top, bright front edge, shaded front face.
    rows = {4: (5, 10, 236), 5: (4, 11, 228), 6: (4, 11, 220), 7: (3, 12, 252), 8: (2, 13, 205), 9: (2, 13, 198),
            10: (2, 13, 188), 11: (2, 13, 160)}
    for y, (x0, x1, v) in rows.items():
        for x in range(x0, x1 + 1):
            shade = v - (6 if x in (x0, x1) else 0) + (8 if y < 7 and x == x0 + 1 else 0)
            put(im, x, y, shade)
    put(im, 6, 5, 255); put(im, 7, 5, 250)
    for x in range(2, 14):
        put(im, x, 12, 110)
    outline(im)
    return im


def plate():
    im = img()
    # A sheet seen from above at an angle: brushed surface, bevelled near edge.
    for y in range(3, 12):
        off = (11 - y) // 3
        for x in range(2 + off, 14 - (y - 3) // 3):
            v = 215 + (8 if (x + y * 3) % 7 == 0 else 0) - (y - 3) * 2
            put(im, x, y, v)
    for y in (12, 13):
        for x in range(2, 11 + (13 - y)):
            put(im, x, y, 150 if y == 12 else 120)
    for x in range(3, 12):
        put(im, x, 3, 250)
    outline(im)
    return im


def rod():
    im = img()
    # A round bar lying diagonally: lit upper edge, highlight, shaded underside, flat end caps.
    for t in range(0, 11):
        x, y = 3 + t, 12 - t
        put(im, x, y - 1, 215)
        put(im, x, y, 250)
        put(im, x + 1, y, 205)
        put(im, x, y + 1, 175)
        put(im, x + 1, y + 1, 140)
    put(im, 2, 12, 190); put(im, 2, 13, 150); put(im, 3, 14, 130)
    put(im, 14, 1, 225); put(im, 15, 1, 180); put(im, 15, 2, 150)
    outline(im)
    return im


def wire():
    im = img()
    # A spool of wire: two flanges and tightly wound turns, with a loose end.
    for y in range(3, 14):
        for x in (2, 3, 12, 13):
            edge = y in (3, 13)
            put(im, x, y, 150 if x in (2, 12) else 190) if not (edge and x in (2, 13)) else None
    for y in range(4, 13):
        for x in range(4, 12):
            v = 240 if y % 2 == 0 else 200
            v -= abs(x - 7.5) * 6
            put(im, x, y, v)
    for x, y in ((12, 8), (13, 9), (14, 10), (14, 11), (15, 12)):
        put(im, x, y, 225)
    outline(im)
    return im


def dust():
    im = img()
    rnd = random.Random(7)
    for y in range(7, 14):
        half = (y - 6) * 1.0 + 0.5
        for x in range(16):
            if abs(x - 8) <= half:
                v = 225 - (y - 7) * 8 + rnd.randint(-18, 12)
                put(im, x, y, v)
    for _ in range(10):
        x, y = rnd.randint(4, 12), rnd.randint(9, 13)
        put(im, x, y, 255)
    outline(im)
    return im


def billet():
    """10 kg round billet (M8d): a cylinder lying across, end face toward the viewer."""
    im = img()
    for y in range(5, 12):
        for x in range(1, 12):
            put(im, x, y, 200 + (30 if y == 6 else 0) - abs(y - 7) * 10)
    for y in range(4, 13):
        for x in range(11, 16):
            if (x - 13) ** 2 + (y - 8) ** 2 <= 9:
                put(im, x, y, 175 + ((x * 7 + y * 3) % 5) * 4)
    outline(im)
    return im


def slab():
    """10 kg slab (M8d): a thick rectangular block with a cast skin."""
    im = img()
    for y in range(5, 12):
        for x in range(1, 15):
            top = y < 7
            put(im, x, y, (232 if top else 190) - (x % 3 == 0) * 6 + (y == 7) * 30)
    outline(im)
    return im


def casting():
    """10 kg shaped casting (M8d): a flanged body with a sprue stub."""
    im = img()
    for y in range(4, 13):
        for x in range(3, 13):
            if (x - 8) ** 2 / 25 + (y - 8) ** 2 / 16 <= 1:
                put(im, x, y, 210 - (y - 4) * 6 + (x == 6) * 25)
    for x in range(1, 15):
        put(im, x, 11, 160)
        put(im, x, 12, 140)
    for y in range(1, 4):
        put(im, 8, y, 220)
        put(im, 9, y, 200)
    outline(im)
    return im


def forging():
    """10 kg closed-die forging (M8d-2): a flanged shaft with a hub, grain-flow streaks."""
    im = img()
    for y in range(6, 11):
        for x in range(1, 15):
            put(im, x, y, 205 - abs(y - 8) * 12 + (y == 7) * 25 + ((x + y) % 4 == 0) * 6)
    for y in range(3, 14):
        for x in range(5, 9):
            put(im, x, y, 190 - abs(y - 8) * 6 + (x == 6) * 30)
    for y in range(4, 13):
        for x in range(12, 15):
            put(im, x, y, 180 - abs(y - 8) * 5 + (x == 12) * 25)
    outline(im)
    return im


def mould(kind):
    """Graphite moulds (M8d): dark block with the cavity shape in lighter grey (not tinted)."""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y in range(3, 14):
        for x in range(1, 15):
            im.putpixel((x, y), (52, 54, 58, 255) if (x + y) % 5 else (60, 62, 66, 255))
    if kind == "billet":
        for x in range(3, 13):
            for y in range(7, 10):
                im.putpixel((x, y), (120, 124, 130, 255))
    elif kind == "slab":
        for x in range(3, 13):
            for y in range(6, 11):
                im.putpixel((x, y), (120, 124, 130, 255))
    else:
        for x in range(4, 12):
            for y in range(5, 12):
                if (x - 8) ** 2 / 16 + (y - 8) ** 2 / 9 <= 1:
                    im.putpixel((x, y), (120, 124, 130, 255))
        im.putpixel((8, 4), (140, 144, 150, 255))
    return im


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, fn in (("ingot", ingot), ("plate", plate), ("rod", rod), ("wire", wire), ("dust", dust), ("billet", billet), ("slab", slab),
                     ("casting", casting), ("forging", forging)):
        fn().save(os.path.join(OUT, f"metal_{name}.png"))
    for kind in ("billet", "slab", "casting"):
        mould(kind).save(os.path.join(OUT, f"{kind}_mould.png"))
    print("9 metal textures + 3 moulds")


if __name__ == "__main__":
    main()
