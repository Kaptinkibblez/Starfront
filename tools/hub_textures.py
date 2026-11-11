#!/usr/bin/env python3
"""Generates Starwake's trade hub textures (M7): station panels, floor, lights, landing pads,
the three terminals and the sealed cargo item. Original procedural 16x16 pixel art.

Usage: python3 tools/hub_textures.py
"""
import os
import random

import numpy as np
from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "starwake", "textures")


def hexrgb(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], dtype=float)


def save(rel, rgb, a=None):
    path = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    a = np.full(rgb.shape[:2], 255) if a is None else a
    Image.fromarray(np.dstack([np.clip(rgb, 0, 255).astype(np.uint8), a.astype(np.uint8)]), "RGBA").save(path)


def noise(seed, amp):
    return np.random.default_rng(seed).uniform(-amp, amp, (16, 16, 1))


def panel():
    base = np.zeros((16, 16, 3)) + hexrgb("#d6d8db")
    base += noise(1, 6)
    for i in range(16):  # bevel: light top/left, dark bottom/right
        base[0, i] = hexrgb("#eef0f2"); base[i, 0] = hexrgb("#eef0f2")
        base[15, i] = hexrgb("#9ea2a8"); base[i, 15] = hexrgb("#9ea2a8")
    for i in range(3, 13):  # inset seam
        base[8, i] = hexrgb("#b4b8be")
    for p in ((2, 2), (2, 13), (13, 2), (13, 13)):
        base[p] = hexrgb("#8a8e94")
    return base


def floor():
    base = np.zeros((16, 16, 3)) + hexrgb("#4a4e55") + noise(2, 5)
    for y in range(16):
        for x in range(16):
            if (x + y) % 4 == 0 or (x - y) % 4 == 0:
                base[y, x] = hexrgb("#3a3d43")
    for i in range(16):
        base[0, i] = base[15, i] = base[i, 0] = base[i, 15] = hexrgb("#5c6068")
    return base


def light():
    base = np.zeros((16, 16, 3)) + hexrgb("#c8ccd2")
    for y in range(3, 13):
        for x in range(3, 13):
            d = max(abs(x - 7.5), abs(y - 7.5))
            base[y, x] = hexrgb("#ffffff") if d < 3.5 else hexrgb("#eef6ff")
    return base


def pad():
    base = np.zeros((16, 16, 3)) + hexrgb("#3c3f44") + noise(3, 4)
    for y in range(16):
        for x in range(16):
            if y < 2 or y > 13:
                base[y, x] = hexrgb("#f2c230") if ((x + y) // 3) % 2 == 0 else hexrgb("#1e1e1e")
    for x in range(5, 11):  # centre marking
        base[7, x] = base[8, x] = hexrgb("#e8e8e8")
    return base


def terminal_side(accent):
    base = panel()
    for y in range(4, 12):
        base[y, 7] = hexrgb(accent) * 0.7
    return base


def terminal_front(accent, kind):
    base = np.zeros((16, 16, 3)) + hexrgb("#2a2e35")
    for i in range(16):
        base[0, i] = base[15, i] = base[i, 0] = base[i, 15] = hexrgb("#b4b8be")
    screen = hexrgb("#08121c")
    for y in range(2, 11):
        for x in range(2, 14):
            base[y, x] = screen
    c = hexrgb(accent)
    rng = random.Random(kind)
    if kind == "commodity":  # rising bar chart
        for i, h in enumerate((3, 5, 4, 7, 6)):
            for y in range(10 - h, 10):
                base[y, 3 + i * 2] = c
    elif kind == "outfitter":  # crate glyph
        for y in range(4, 9):
            for x in range(5, 11):
                base[y, x] = c if (y in (4, 8) or x in (5, 10)) else c * 0.4
        base[6, 5:11] = c
    else:  # contract list
        for y in (3, 5, 7, 9):
            for x in range(3, 3 + rng.randrange(6, 10)):
                base[y, x] = c * (1.0 if x < 5 else 0.7)
    for x in range(3, 13):  # keypad row
        base[12, x] = hexrgb("#6a7078") if x % 2 else hexrgb("#4a5058")
        base[13, x] = hexrgb("#4a5058")
    return base


def cargo():
    rgb, a = np.zeros((16, 16, 3)), np.zeros((16, 16))
    for y in range(3, 14):
        for x in range(2, 14):
            rgb[y, x] = hexrgb("#8a8f96") if 3 < y < 13 and 2 < x < 13 else hexrgb("#4a4f56")
            a[y, x] = 255
    for x in range(2, 14):
        rgb[7, x] = hexrgb("#e8a23a")
    for y in range(3, 14):
        rgb[y, 7] = hexrgb("#e8a23a")
    rgb[7, 7] = hexrgb("#ffffff")
    return rgb, a


def main():
    save("block/station_panel.png", panel())
    save("block/station_floor.png", floor())
    save("block/light_panel.png", light())
    save("block/landing_pad.png", pad())
    for kind, accent in (("commodity", "#55d8ff"), ("outfitter", "#ffb347"), ("contract", "#c27bff")):
        name = "contract_board" if kind == "contract" else f"{kind}_terminal"
        save(f"block/{name}_front.png", terminal_front(accent, kind))
        save(f"block/{name}_side.png", terminal_side(accent))
    save("item/sealed_cargo.png", *cargo())
    print("ok")


if __name__ == "__main__":
    main()
