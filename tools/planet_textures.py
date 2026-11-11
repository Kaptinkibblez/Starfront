#!/usr/bin/env python3
"""Generates Starwake's planet content textures (M5): original, procedural 16x16 pixel art.

Every texture is computed from seeded noise, so re-running reproduces the same files.
Usage: python3 tools/planet_textures.py  (writes into src/main/resources/assets/starwake/textures)

Palettes follow real materials: anorthosite is pale grey feldspar, ilmenite black and metallic,
bauxite rust-brown pisolites, cobaltite silvery with a pink tint, and so on.
"""
import os
import random

import numpy as np
from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "starwake", "textures")
BLOCK = os.path.join(ROOT, "block")
ITEM = os.path.join(ROOT, "item")
EQUIP = os.path.join(ROOT, "entity", "equipment", "humanoid")


def hexrgb(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], dtype=float)


def value_noise(seed, size=16, cell=4):
    """Tileable smooth noise in 0..1."""
    rng = np.random.default_rng(seed)
    n = size // cell
    grid = rng.random((n, n))
    out = np.zeros((size, size))
    for y in range(size):
        for x in range(size):
            gx, gy = x / cell, y / cell
            x0, y0 = int(gx) % n, int(gy) % n
            x1, y1 = (x0 + 1) % n, (y0 + 1) % n
            fx, fy = gx - int(gx), gy - int(gy)
            fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
            a = grid[y0, x0] * (1 - fx) + grid[y0, x1] * fx
            b = grid[y1, x0] * (1 - fx) + grid[y1, x1] * fx
            out[y, x] = a * (1 - fy) + b * fy
    return out


def ramp(t, colors):
    """Map 0..1 onto a list of hex colours."""
    cs = [hexrgb(c) for c in colors]
    t = np.clip(t, 0, 0.9999) * (len(cs) - 1)
    i = t.astype(int)
    f = (t - i)[..., None]
    lo = np.array(cs)[i]
    hi = np.array(cs)[np.minimum(i + 1, len(cs) - 1)]
    return lo * (1 - f) + hi * f


def save(path, rgb, alpha=None):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    rgb = np.clip(rgb, 0, 255).astype(np.uint8)
    a = np.full(rgb.shape[:2], 255, dtype=np.uint8) if alpha is None else alpha.astype(np.uint8)
    Image.fromarray(np.dstack([rgb, a]), "RGBA").save(path)


def stone(seed, colors, grain=0.35, cell=4):
    rng = np.random.default_rng(seed)
    t = 0.55 * value_noise(seed, cell=cell) + 0.25 * value_noise(seed + 1, cell=2) + grain * rng.random((16, 16)) * 0.6
    return ramp(t / (0.8 + grain * 0.6), colors)


def speckle(rgb, seed, color, count, size=1):
    rng = random.Random(seed)
    c = hexrgb(color)
    for _ in range(count):
        x, y = rng.randrange(16), rng.randrange(16)
        for dx in range(size):
            for dy in range(size):
                rgb[(y + dy) % 16, (x + dx) % 16] = c * (0.85 + 0.3 * rng.random())
    return rgb


def ore(seed, base, mineral):
    """Stone matrix with mineral grain clusters (2-3 px blobs with a highlight)."""
    rgb = stone(seed, base)
    rng = random.Random(seed * 7)
    dark, light = hexrgb(mineral[0]), hexrgb(mineral[1])
    for _ in range(6):
        cx, cy = rng.randrange(1, 15), rng.randrange(1, 15)
        for _ in range(rng.randrange(3, 6)):
            x, y = (cx + rng.randrange(-1, 2)) % 16, (cy + rng.randrange(-1, 2)) % 16
            rgb[y, x] = dark * (0.9 + 0.2 * rng.random())
        rgb[cy, cx] = light
    return rgb


def cross_plant(seed, colors, blades=6, height=13, puff=None):
    """A cross-model sprite: thin blades (or stalks with a round cap) on transparency."""
    rgb = np.zeros((16, 16, 3))
    a = np.zeros((16, 16))
    rng = random.Random(seed)
    for b in range(blades):
        x = 2 + int(b * 12 / max(1, blades - 1)) + rng.randrange(-1, 2)
        h = rng.randrange(height - 5, height + 1)
        lean = rng.choice([-1, 0, 1])
        for i in range(h):
            y = 15 - i
            xx = int(round(x + lean * i / 6)) % 16
            rgb[y, xx] = ramp(np.array(i / max(1, h)), colors)
            a[y, xx] = 255
    if puff:
        for _ in range(3):
            cx, cy, r = rng.randrange(4, 12), rng.randrange(5, 10), rng.randrange(2, 4)
            for y in range(16):
                for x in range(16):
                    d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
                    if d <= r:
                        rgb[y, x] = ramp(np.array(1 - d / (r + 0.01) * 0.8), puff)
                        a[y, x] = 255
    return rgb, a


def crystal_sprite(seed, colors):
    rgb = np.zeros((16, 16, 3))
    a = np.zeros((16, 16))
    rng = random.Random(seed)
    for _ in range(5):
        x0 = rng.randrange(3, 13)
        h = rng.randrange(6, 15)
        w = rng.choice([1, 2])
        lean = rng.choice([-1, 0, 1])
        for i in range(h):
            y = 15 - i
            for dx in range(w):
                xx = int(x0 + dx + lean * i / 5) % 16
                shade = 0.55 + 0.45 * (i / h) + (0.1 if dx == 0 else -0.05)
                rgb[y, xx] = ramp(np.array(shade), colors)
                a[y, xx] = 230
    return rgb, a


def leaves(seed, colors):
    rng = np.random.default_rng(seed)
    t = value_noise(seed, cell=2) * 0.6 + rng.random((16, 16)) * 0.4
    rgb = ramp(t, colors)
    a = np.where(rng.random((16, 16)) < 0.18, 0, 255)
    return rgb, a


def log_side(seed, colors):
    rng = np.random.default_rng(seed)
    t = np.tile(value_noise(seed, cell=2)[0], (16, 1)) * 0.6 + rng.random((16, 16)) * 0.25
    # Ring scars every few rows, like a tree fern's leaf-base pattern.
    for y in range(0, 16, 4):
        t[y] *= 0.55
    return ramp(t, colors)


def log_top(seed, colors):
    rgb = np.zeros((16, 16, 3))
    for y in range(16):
        for x in range(16):
            d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
            rgb[y, x] = ramp(np.array((d / 8.0 + (0.15 if int(d) % 3 == 0 else 0)) % 1.0), colors)
    return rgb


def raw_item(seed, colors):
    """A lumpy nugget of raw mineral."""
    rgb = np.zeros((16, 16, 3))
    a = np.zeros((16, 16))
    rng = random.Random(seed)
    blobs = [(rng.uniform(5, 11), rng.uniform(5, 11), rng.uniform(3, 4.5)) for _ in range(3)]
    for y in range(16):
        for x in range(16):
            v = max(r - ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5 for cx, cy, r in blobs)
            if v > 0:
                light = 0.35 + 0.65 * min(1, v / 3) - 0.25 * (y - 4) / 12 + rng.uniform(-0.08, 0.08)
                rgb[y, x] = ramp(np.array(light), colors)
                a[y, x] = 255
    # Dark outline.
    edge = (a > 0)
    for y in range(16):
        for x in range(16):
            if edge[y, x] and any(not edge[(y + dy) % 16, (x + dx) % 16] for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                rgb[y, x] *= 0.55
    return rgb, a


MINERALS = {
    "bauxite": ("#7a3b1e", "#d08a52"),
    "ilmenite": ("#1e1b24", "#6a6280"),
    "pentlandite": ("#8a7330", "#e2c97a"),
    "chromite": ("#15110f", "#4a3d34"),
    "cassiterite": ("#3e2614", "#9a6a40"),
    "wolframite": ("#2c2826", "#7a736d"),
    "cobaltite": ("#8f8590", "#e8d4e2"),
}
STONE = ["#5d5d60", "#76767a", "#8d8d90", "#a0a0a2"]


def main():
    save(f"{BLOCK}/anorthosite.png", stone(11, ["#9c9b97", "#b9b8b3", "#cfcdc6", "#e2e0d8"], grain=0.5))
    save(f"{BLOCK}/fine_regolith.png", speckle(stone(12, ["#6f6e6b", "#82817d", "#94938e", "#a6a49f"], grain=0.9, cell=2), 12, "#c8c6c0", 10))
    save(f"{BLOCK}/volcanic_ash.png", speckle(stone(13, ["#262424", "#332f2f", "#403b3a", "#4d4746"], grain=0.9, cell=2), 13, "#6b5e58", 8))
    perma = stone(14, ["#4a4038", "#5a4e44", "#6b5e52", "#7c6e60"], grain=0.6)
    perma = speckle(perma, 14, "#cfe6f2", 18)
    save(f"{BLOCK}/permafrost.png", perma)
    save(f"{BLOCK}/halite.png", speckle(stone(15, ["#d8d0cc", "#e6e0dc", "#f2eeea", "#fffcf8"], grain=0.4, cell=8), 15, "#e8c9c4", 6))
    sel = stone(16, ["#c9c6bd", "#dcd9d0", "#ecebe4", "#fbfbf6"], grain=0.2, cell=8)
    for y in range(16):  # fibrous blades
        for x in range(0, 16, 3):
            sel[y, (x + y // 4) % 16] *= 1.04
    save(f"{BLOCK}/selenite.png", sel)
    save(f"{BLOCK}/selenite_cluster.png", *crystal_sprite(17, ["#bdb9ae", "#e4e2da", "#ffffff"]))

    # Lush: dark purple photosynthetic mats and tree ferns.
    save(f"{BLOCK}/mat_turf_top.png", speckle(stone(21, ["#2a1530", "#3c1d42", "#4f2856", "#63336a"], grain=0.8, cell=2), 21, "#7a4a3a", 6))
    side = stone(22, ["#4b3a2c", "#5a4635", "#69523e", "#785e48"], grain=0.6)
    top = stone(21, ["#2a1530", "#3c1d42", "#4f2856", "#63336a"], grain=0.8, cell=2)
    rng = random.Random(22)
    for x in range(16):
        depth = 3 + rng.randrange(0, 3)
        side[:depth, x] = top[:depth, x]
    save(f"{BLOCK}/mat_turf_side.png", side)
    save(f"{BLOCK}/tuft_grass.png", *cross_plant(23, ["#1d0f22", "#4a2350", "#7a3d6e"], blades=7))
    save(f"{BLOCK}/fernwood_log.png", log_side(24, ["#2a1d14", "#3d2a1c", "#544030", "#6b5240"]))
    save(f"{BLOCK}/fernwood_log_top.png", log_top(25, ["#6b5240", "#8a6c52", "#5a4535", "#7c6048"]))
    save(f"{BLOCK}/fernwood_fronds.png", *leaves(26, ["#1a0d20", "#2f1838", "#46244f", "#5e3166"]))

    # Toxic: sulfur-loving fungi.
    save(f"{BLOCK}/fungal_stem.png", log_side(31, ["#b8ad8a", "#cfc49e", "#ddd3b0", "#e8e0c2"]))
    save(f"{BLOCK}/fungal_stem_top.png", log_top(32, ["#e8e0c2", "#c9bd93", "#ddd3b0", "#b8ad8a"]))
    cap = stone(33, ["#8a8a1e", "#a6a02a", "#c2b83a", "#d8cf5a"], grain=0.5)
    save(f"{BLOCK}/fungal_cap.png", speckle(cap, 33, "#f2ecb0", 9, size=2))
    save(f"{BLOCK}/puffball.png", *cross_plant(34, ["#a69c6a", "#c9bd84"], blades=3, height=7, puff=["#b3a85a", "#e6dd9a", "#fff8c8"]))

    # Ore and raw mineral textures are made by tools/periodic_table.py (it owns the mineral list).

    survey_suit()


def survey_suit():
    """Survey Suit: off-white pressure suit with orange stripes and a chest O2 unit."""
    white, grey, orange, dark = hexrgb("#e6e4de"), hexrgb("#9a9893"), hexrgb("#e5762a"), hexrgb("#3a3d42")
    rng = np.random.default_rng(70)
    # Item icon: a torso with arms.
    rgb = np.zeros((16, 16, 3))
    a = np.zeros((16, 16))
    for y in range(2, 15):
        for x in range(2, 14):
            inside = (4 <= x <= 11) or (y <= 9 and (x <= 3 or x >= 12))
            if y == 2 and 6 <= x <= 9:
                inside = False
            if inside:
                rgb[y, x] = white * (0.9 + 0.1 * rng.random())
                a[y, x] = 255
    for y in range(3, 15):
        rgb[y, 5] = orange
        rgb[y, 10] = orange
    for y, x in ((6, 7), (6, 8), (7, 7), (7, 8)):
        rgb[y, x] = dark
    rgb[8, 7] = hexrgb("#55d8ff")
    save(f"{ITEM}/survey_suit.png", rgb, a)

    # Worn layer (64x32 humanoid layout): body 16..40 x 16..32, arms 40..56 x 16..32.
    rgb = np.zeros((32, 64, 3))
    a = np.zeros((32, 64))
    for y in range(16, 32):
        for x in range(16, 56):
            rgb[y, x] = white * (0.92 + 0.08 * rng.random())
            a[y, x] = 255
    for y in range(20, 32):  # orange side stripes on the torso front/back
        for x in (21, 26, 33, 38):
            rgb[y, x] = orange
    for y in range(20, 32):  # orange arm cuffs and seams
        for x in range(40, 56):
            if y >= 29:
                rgb[y, x] = grey
            elif x in (44, 52):
                rgb[y, x] = orange
    for y in range(22, 26):  # chest O2 unit on the front (20..28)
        for x in range(22, 26):
            rgb[y, x] = dark
    rgb[23, 23] = hexrgb("#55d8ff")
    for y in range(20, 32):  # backpack (32..40)
        for x in range(34, 38):
            rgb[y, x] = grey if y < 30 else dark
    save(f"{EQUIP}/survey_suit.png", rgb, a)


if __name__ == "__main__":
    main()
