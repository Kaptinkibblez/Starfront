#!/usr/bin/env python3
"""Mk II / Mk III textures (M8e-2), derived from the Mk I part textures: Mk II gets one gold band,
Mk III a darker finish and two cyan bands, so tiers are recognisable at a glance. Writes
<part>_mk2_<face>.png / <part>_mk3_<face>.png next to the originals."""
import os
from PIL import Image

DIR = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "starwake", "textures", "block")
PARTS = ["thruster", "main_engine", "maneuvering_thruster", "power_plant", "cooler", "shield_generator", "quantum_drive",
         "jump_drive", "fuel_tank", "landing_gear"]


def band(im, y, color):
    for x in range(im.width):
        r, g, b, a = im.getpixel((x, y))
        if a > 0:
            im.putpixel((x, y), color + (a,))


def main():
    n = 0
    for part in PARTS:
        for f in sorted(os.listdir(DIR)):
            if not f.startswith(part + "_") or "_mk" in f or not f.endswith(".png"):
                continue
            face = f[len(part) + 1:-4]
            if face not in ("front", "side", "top"):
                continue
            src = Image.open(os.path.join(DIR, f)).convert("RGBA")
            mk2 = src.copy()
            band(mk2, 13, (220, 180, 60))
            mk2.save(os.path.join(DIR, f"{part}_mk2_{face}.png"))
            mk3 = src.copy()
            px = mk3.load()
            for y in range(mk3.height):
                for x in range(mk3.width):
                    r, g, b, a = px[x, y]
                    px[x, y] = (int(r * 0.72), int(g * 0.76), int(b * 0.85), a)
            band(mk3, 12, (90, 220, 240))
            band(mk3, 14, (90, 220, 240))
            mk3.save(os.path.join(DIR, f"{part}_mk3_{face}.png"))
            n += 2
    print(f"{n} Mk textures")


if __name__ == "__main__":
    main()
