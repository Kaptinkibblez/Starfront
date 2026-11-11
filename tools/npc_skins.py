#!/usr/bin/env python3
"""Generates hub NPC skins (M7): original 64x64 player-format textures in each faction's uniform.

For every faction (read from data/starwake/starwake/faction/*.json) x role (guard, crew) x 4
variants (skin tone and hair): textures/entity/npc/<faction>_<role>_<variant>.png.
Guards wear a helmet with a visor and shoulder plates; crew wear a cap or bare head and a
utility jacket with the faction accent.

Usage: python3 tools/npc_skins.py
"""
import json
import os
import random

import numpy as np
from PIL import Image

RES = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources")
FACTIONS = os.path.join(RES, "data", "starwake", "starwake", "faction")
OUT = os.path.join(RES, "assets", "starwake", "textures", "entity", "npc")

SKIN = ["#f1c9a5", "#d9a37a", "#a8704a", "#6e4630"]
HAIR = ["#2a1e16", "#6a4a2a", "#c8a050", "#1a1a1a"]


def hexrgb(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], dtype=float)


def fill(img, a, x0, y0, x1, y1, color, rng=None, jitter=0.0):
    for y in range(y0, y1):
        for x in range(x0, x1):
            c = color
            if rng is not None and jitter:
                c = color * (1 + rng.uniform(-jitter, jitter))
            img[y, x] = c
            a[y, x] = 255


def cube_faces(x, y, w, h, d):
    """Texture rectangles of a model cube at (x, y) with size w x h x d: top, bottom, right, front, left, back."""
    return {
        "top": (x + d, y, x + d + w, y + d),
        "bottom": (x + d + w, y, x + d + 2 * w, y + d),
        "right": (x, y + d, x + d, y + d + h),
        "front": (x + d, y + d, x + d + w, y + d + h),
        "left": (x + d + w, y + d, x + 2 * d + w, y + d + h),
        "back": (x + 2 * d + w, y + d, x + 2 * d + 2 * w, y + d + h),
    }


def paint_cube(img, a, x, y, w, h, d, color, rng, jitter=0.06):
    for rect in cube_faces(x, y, w, h, d).values():
        fill(img, a, *rect, color, rng, jitter)


def skin(faction, role, variant):
    rng = np.random.default_rng(hash((faction["code"], role, variant)) & 0xFFFFFFFF)
    img = np.zeros((64, 64, 3))
    a = np.zeros((64, 64))
    tone, hair = hexrgb(SKIN[variant]), hexrgb(HAIR[variant])
    uniform, accent = hexrgb(faction["uniform"]), hexrgb(faction["accent"])
    dark = uniform * 0.6

    # Head (8x8x8 at 0,0) and face.
    paint_cube(img, a, 0, 0, 8, 8, 8, tone, rng, 0.04)
    for face in ("top", "back"):
        fill(img, a, *cube_faces(0, 0, 8, 8, 8)[face], hair, rng, 0.08)
    fx, fy = 8, 8  # front face origin
    fill(img, a, fx, fy, fx + 8, fy + 2, hair, rng, 0.08)  # fringe
    for x in (fx + 1, fx + 5):  # eyes
        img[fy + 4, x] = hexrgb("#f4f4f4"); img[fy + 4, x + 1] = hexrgb("#2a3a5a")
    for x in range(fx + 3, fx + 5):
        img[fy + 6, x] = tone * 0.7  # mouth
    # Body (8x12x4 at 16,16), arms (4x12x4 at 40,16 and 32,48), legs (4x12x4 at 0,16 and 16,48).
    paint_cube(img, a, 16, 16, 8, 12, 4, uniform, rng)
    paint_cube(img, a, 40, 16, 4, 12, 4, uniform, rng)
    paint_cube(img, a, 32, 48, 4, 12, 4, uniform, rng)
    paint_cube(img, a, 0, 16, 4, 12, 4, dark, rng)
    paint_cube(img, a, 16, 48, 4, 12, 4, dark, rng)
    # Hands and boots.
    for ax, ay in ((40, 16), (32, 48)):
        for rect in cube_faces(ax, ay, 4, 12, 4).values():
            if rect[3] - rect[1] == 12:
                fill(img, a, rect[0], rect[3] - 2, rect[2], rect[3], tone, rng, 0.04)
    for lx, ly in ((0, 16), (16, 48)):
        for rect in cube_faces(lx, ly, 4, 12, 4).values():
            if rect[3] - rect[1] == 12:
                fill(img, a, rect[0], rect[3] - 3, rect[2], rect[3], hexrgb("#1e1e22"), rng, 0.05)
    # Faction accent: belt, chest stripe, insignia.
    body = cube_faces(16, 16, 8, 12, 4)
    x0, y0, x1, y1 = body["front"]
    fill(img, a, x0, y0 + 7, x1, y0 + 8, hexrgb("#202024"))  # belt
    fill(img, a, x0 + 1, y0 + 1, x0 + 3, y0 + 3, accent)    # insignia
    for x in range(x0, x1):
        img[y0, x] = accent                                    # collar
    if role == "guard":
        # Helmet over the head (hat layer at 32,0) with a dark visor, and shoulder plates.
        paint_cube(img, a, 32, 0, 8, 8, 8, uniform * 0.85, rng, 0.04)
        hx, hy = 40, 8
        fill(img, a, hx + 1, hy + 3, hx + 7, hy + 6, hexrgb("#14202c"))
        fill(img, a, hx + 1, hy + 3, hx + 7, hy + 4, hexrgb("#4fa3ff") * 0.6)
        for ax, ay in ((40, 16), (32, 48)):
            fill(img, a, *cube_faces(ax, ay, 4, 12, 4)["top"], accent)
            for face in ("front", "back", "left", "right"):
                r = cube_faces(ax, ay, 4, 12, 4)[face]
                fill(img, a, r[0], r[1], r[2], r[1] + 3, accent * 0.9, rng, 0.04)
        fill(img, a, x0 + 2, y0 + 2, x1 - 2, y0 + 6, uniform * 1.15, rng, 0.04)  # chest plate
    else:
        # Utility jacket: accent stripes down the front, pockets.
        for y in range(y0 + 1, y0 + 7):
            img[y, x0 + 3] = accent * 0.8; img[y, x0 + 4] = accent * 0.8
        fill(img, a, x0 + 5, y0 + 8, x0 + 7, y0 + 10, dark)
        fill(img, a, x0 + 1, y0 + 8, x0 + 3, y0 + 10, dark)
        if variant % 2 == 0:  # a cap
            paint_cube(img, a, 32, 0, 8, 8, 8, np.zeros(3), rng)
            a[0:16, 32:64] = 0
            top = cube_faces(32, 0, 8, 8, 8)
            fill(img, a, *top["top"], uniform, rng, 0.05)
            for face in ("front", "back", "left", "right"):
                r = top[face]
                fill(img, a, r[0], r[1], r[2], r[1] + 2, uniform, rng, 0.05)
            r = top["front"]
            fill(img, a, r[0], r[1] + 2, r[2], r[1] + 3, accent)
    rgba = np.dstack([np.clip(img, 0, 255).astype(np.uint8), a.astype(np.uint8)])
    return Image.fromarray(rgba, "RGBA")


def main():
    os.makedirs(OUT, exist_ok=True)
    n = 0
    for file in sorted(os.listdir(FACTIONS)):
        fid = file[:-5]
        faction = json.load(open(os.path.join(FACTIONS, file)))
        for role in ("guard", "crew"):
            for v in range(4):
                skin(faction, role, v).save(os.path.join(OUT, f"{fid}_{role}_{v}.png"))
                n += 1
    print(f"{n} skins")


if __name__ == "__main__":
    main()
