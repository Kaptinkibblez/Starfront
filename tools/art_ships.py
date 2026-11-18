"""Starwake ship-part art (M9 art pass): engines, thrusters and functional components in Mk I-III.

Uses the texture kit and Model builder of tools/art_machines.py. Writes
  textures/block/ship/<part>[_mk2|_mk3]_*.png   (detailed 16x faces; *_glow overlays are cutout + animated)
  models/block/ship/<part>[_mk2|_mk3].json
Engines face up in the model (the nozzle points +Y); datagen rotates them by FACING. Components stay full cubes (they're
hull: they seal and cull like any block) with a second, identical element carrying an emissive cutout overlay (the same
trick as vanilla's grass side overlay), so cores and emitters glow without changing the shape.
Run: python3 tools/art_ships.py (noise seeds come from crc32 of the name, so reruns are stable; textures made before M11 used
Python's per-run hash() and differ in their noise from what a rerun writes)
"""
import json
import math
import os
import random
import zlib

from PIL import Image

from art_machines import (DARK, DARKER, LIGHT, MID, ORANGE, ROOT, TEAL, WHITE, YELLOW, Model, bevel, clamp, img, noise,
                          rivets, shade)

TEX = os.path.join(ROOT, "textures", "block", "ship")
MODELS = os.path.join(ROOT, "models", "block", "ship")
TIERS = {"": ORANGE, "_mk2": YELLOW, "_mk3": TEAL}


def save(name, im, frametime=None):
    os.makedirs(TEX, exist_ok=True)
    im.save(os.path.join(TEX, name + ".png"))
    meta = os.path.join(TEX, name + ".png.mcmeta")
    if frametime:
        with open(meta, "w") as f:
            json.dump({"animation": {"frametime": frametime}}, f)
    elif os.path.exists(meta):
        os.remove(meta)


def frame_face(base, accent, tier, seed):
    """An armoured face: bevelled plate, corner bolts, an accent band (two for Mk III)."""
    col = shade(base, 0.82) if tier == "_mk3" else base
    im = img(col)
    noise(im, 5, seed)
    bevel(im)
    rivets(im, col)
    rows = (2, 13) if tier == "_mk3" else (13,)
    for y in rows:
        for x in range(1, 15):
            im.putpixel((x, y), shade(accent, 1.0) + (255,))
    if tier == "_mk2":
        for x in range(1, 15):
            im.putpixel((x, 2), shade(accent, 0.85) + (255,))
    return im


def window(im, x0, y0, x1, y1, inner):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            edge = x in (x0, x1) or y in (y0, y1)
            im.putpixel((x, y), (DARKER if edge else inner) + (255,))


def overlay(mask, color, frames=6, seed=0):
    """An animated emissive overlay: the pixels in `mask` pulse; everything else is transparent."""
    out = img(h=16 * frames)
    r = random.Random(seed)
    for f in range(frames):
        k = 0.8 + 0.2 * math.sin(f / frames * 2 * math.pi)
        for (x, y), w in mask.items():
            out.putpixel((x, f * 16 + y), shade(color, k * w + r.uniform(-0.04, 0.04)) + (255,))
    return out


# ---------------------------------------------------------------------------------------------
# Components
# ---------------------------------------------------------------------------------------------

def component(part, tier):
    accent = TIERS[tier]
    name = part + tier
    seed = zlib.crc32(name.encode()) & 0xffff
    side = frame_face(MID if part != "fuel_tank" else WHITE, accent, tier, seed)
    top = frame_face(DARK, accent, tier, seed + 1)
    glow_side, glow_top, color = {}, {}, None
    if part == "power_plant":
        window(side, 4, 4, 11, 10, (40, 20, 10))
        for y in range(5, 10):
            for x in range(5, 11):
                glow_side[(x, y)] = 1.1 - 0.35 * math.hypot(x - 7.5, y - 7) / 3
        for y in range(3, 13):
            for x in range(3, 13):
                if (x + y) % 2 == 0 and 4 <= x <= 11 and 4 <= y <= 11:
                    top.putpixel((x, y), DARKER + (255,))
                    if math.hypot(x - 7.5, y - 7.5) < 3:
                        glow_top[(x, y)] = 1.0
        color = (255, 150, 50)
    elif part == "cooler":
        for x in range(2, 14, 2):
            for y in range(3, 12):
                side.putpixel((x, y), shade(LIGHT, 1.2) + (255,))
                side.putpixel((x + 1, y), DARKER + (255,))
        for x in range(2, 14):
            glow_side[(x, 11)] = 0.9
        for y in range(16):
            for x in range(16):
                rr = math.hypot(x - 7.5, y - 7.5)
                if 2 < rr < 6.5:
                    a = math.atan2(y - 7.5, x - 7.5)
                    top.putpixel((x, y), (LIGHT if math.cos(3 * a) > 0.3 else DARKER) + (255,))
                elif rr <= 2:
                    top.putpixel((x, y), MID + (255,))
        color = (90, 200, 255)
    elif part == "shield_generator":
        for y in (4, 7, 10):
            for x in range(3, 13):
                side.putpixel((x, y), (190, 112, 62, 255))
            for x in range(4, 12):
                glow_side[(x, y + 1)] = 0.7
        for y in range(16):
            for x in range(16):
                rr = math.hypot(x - 7.5, y - 7.5)
                if rr < 5.5:
                    top.putpixel((x, y), (60, 80, 110, 255))
                    if abs(rr - 4.5) < 0.6 or rr < 1.6:
                        glow_top[(x, y)] = 1.0
        color = (100, 190, 255)
    elif part == "quantum_drive":
        for y in range(16):
            for x in range(16):
                rr = math.hypot(x - 7.5, y - 7.5)
                if rr < 6:
                    side.putpixel((x, y), DARKER + (255,))
                    if abs(rr - 5) < 0.55 or abs(rr - 2.5) < 0.55:
                        glow_side[(x, y)] = 1.0 if rr > 4 else 0.8
                if rr < 6.5:
                    top.putpixel((x, y), (30, 40, 46, 255))
                    if abs(rr - 3.5) < 0.6:
                        glow_top[(x, y)] = 0.9
        color = (60, 240, 230)
    elif part == "jump_drive":
        window(side, 3, 3, 12, 12, (20, 10, 30))
        for y in range(4, 12):
            for x in range(4, 12):
                d = math.hypot(x - 7.5, y - 7.5)
                if d < 4:
                    glow_side[(x, y)] = 1.2 - d / 5
        for y in range(16):
            for x in range(16):
                if abs(x - y) <= 1 or abs(x + y - 15) <= 1:
                    if 2 <= x <= 13:
                        top.putpixel((x, y), (60, 30, 80, 255))
                        glow_top[(x, y)] = 0.7
        color = (200, 110, 255)
    elif part == "fuel_tank":
        for y in range(16):
            for x in range(16):
                p = side.getpixel((x, y))
                side.putpixel((x, y), shade(p[:3], 0.85 + 0.25 * math.sin(math.pi * (x + 0.5) / 16)) + (255,))
        for y in range(4, 12):
            side.putpixel((12, y), DARKER + (255,))
            side.putpixel((13, y), (240, 120, 40, 255) if y > 6 else DARKER + (255,))
        for x in range(1, 15):
            side.putpixel((x, 7), shade(WHITE, 0.7) + (255,))
        for y in range(16):
            for x in range(16):
                if 16 < (x - 7.5) ** 2 + (y - 7.5) ** 2 < 30:
                    top.putpixel((x, y), MID + (255,))
        color = None
    save(name + "_side", side)
    save(name + "_top", top)
    m = Model()
    m.box((0, 0, 0), (16, 16, 16), "ship/" + name + "_side", top="ship/" + name + "_top", bottom="ship/" + name + "_top", full=True)
    if color:
        save(name + "_glow_side", overlay(glow_side, color, seed=seed), 2)
        save(name + "_glow_top", overlay(glow_top, color, seed=seed + 2), 2)
        m.box((0, 0, 0), (16, 16, 16), "ship/" + name + "_glow_side", top="ship/" + name + "_glow_top",
              bottom="ship/" + name + "_glow_top", full=True)
        m.emit[len(m.parts) - 1] = 12
    return m


# ---------------------------------------------------------------------------------------------
# Engines (nozzle up)
# ---------------------------------------------------------------------------------------------

def engine(part, tier):
    accent = TIERS[tier]
    name = part + tier
    seed = zlib.crc32(name.encode()) & 0xffff
    side = frame_face(MID, accent, tier, seed)
    save(name + "_side", side)
    bell = img(DARK)
    noise(bell, 4, seed + 3)
    for y in range(16):
        for x in range(16):
            # Heat bluing toward the rim of the bell.
            t = y / 15
            c = shade(DARK, 0.9 + 0.3 * t)
            bell.putpixel((x, y), clamp((c[0] + 20 * t, c[1] + 10 * t, c[2] + 35 * t)) + (255,))
    for x in range(16):
        bell.putpixel((x, 0), shade(LIGHT, 1.1) + (255,))
    save(name + "_bell", bell)
    throat = img(DARKER)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5)
            if d < 7.5:
                k = max(0, 1 - d / 7.5)
                throat.putpixel((x, y), clamp((40 + 180 * k ** 2, 22 + 70 * k ** 3, 18 + 10 * k)) + (255,))
            if abs(d - 7) < 0.6:
                throat.putpixel((x, y), shade(LIGHT, 0.9) + (255,))
    save(name + "_throat", throat)
    S, B, T = "ship/" + name + "_side", "ship/" + name + "_bell", "ship/" + name + "_throat"
    m = Model()
    if part == "main_engine":
        m.box((0, 0, 0), (16, 8, 16), S, top="frame", full=False)
        steps = [(8, 10, 5.0), (10, 12.5, 6.0), (12.5, 16, 7.0)]
    elif part == "thruster":
        m.box((0, 0, 0), (16, 9, 16), S, top="frame")
        steps = [(9, 12, 4.5), (12, 16, 5.5)]
    else:  # maneuvering thruster: a compact RCS block with one short nozzle
        m.box((0, 0, 0), (16, 10, 16), S, top="frame")
        m.box((3, 10, 3), (13, 11, 13), "panel_dark")
        steps = [(11, 13, 2.6), (13, 15, 3.2)]
    # The bell, stepped outward; its top face is the throat (spread across the round end), so no plate sits on it.
    for k, (y0, y1, r) in enumerate(steps):
        m.cyl("y", (8, 8), r, y0, y1, B, cap=T if k == len(steps) - 1 else "frame")
    # Mounting struts at the housing corners.
    hy = steps[0][0]
    for x, z in ((1, 1), (13, 1), (1, 13), (13, 13)):
        m.box((x, hy, z), (x + 2, hy + 1.5, z + 2), "frame")
    return m


def weapon(part, tier, gimbal):
    """M11 guns, barrel up (+Y; datagen turns them by FACING). The barrel reaches past the block (the cell in front must
    be clear to fire). Gimballed mounts sit on a ball ring; fixed mounts on a square cradle."""
    accent = TIERS[tier]
    name = part + tier
    seed = zlib.crc32(name.encode()) & 0xffff
    side = frame_face(MID if part == "ballistic_cannon" else DARK, accent, tier, seed)
    save(name + "_side", side)
    barrel = img(DARK)
    noise(barrel, 4, seed + 5)
    for y in range(16):
        for x in (0, 15):
            barrel.putpixel((x, y), shade(DARKER, 1.0) + (255,))
        if y % 5 == 0:
            for x in range(16):
                barrel.putpixel((x, y), shade(LIGHT, 0.8) + (255,))
    save(name + "_barrel", barrel)
    S, Bt = "ship/" + name + "_side", "ship/" + name + "_barrel"
    m = Model()
    m.box((0, 0, 0), (16, 5, 16), S, top="frame")
    if gimbal:
        m.cyl("y", (8, 8), 6.0, 5, 7, "frame", cap="panel_dark")
        m.cyl("y", (8, 8), 4.5, 7, 10, S, cap="frame")
    else:
        for x, z in ((1, 1), (12, 1), (1, 12), (12, 12)):
            m.box((x, 5, z), (x + 3, 9, z + 3), "frame")
        m.box((3, 5, 3), (13, 10, 13), S, top="frame")
    if part == "ballistic_cannon":
        m.box((5, 10, 5), (11, 14, 11), "panel_dark", top="frame")
        m.box((10, 10.5, 6.5), (13, 13, 9.5), "frame")  # ammo feed
        m.cyl("y", (8, 8), 1.6, 14, 25, Bt, cap="pipe_dark")
        m.cyl("y", (8, 8), 2.4, 25, 27, "frame", cap="panel_dark")  # muzzle brake
    else:
        m.cyl("y", (8, 8), 3.4, 10, 13, "coil", cap="frame")
        m.cyl("y", (8, 8), 2.6, 13, 15, S, cap="frame")
        m.cyl("y", (8, 8), 1.4, 15, 23, Bt, cap="pipe")
        m.cyl("y", (8, 8), 2.2, 18, 19, "coil", cap="coil")
        m.cyl("y", (8, 8), 2.2, 21, 22, "coil", cap="coil")
        m.box((7, 23, 7), (9, 23.5, 9), "frame", glow="red", only=("up", "north", "south", "east", "west"))
    return m


def ammo_sprite():
    """M11: Autocannon Rounds item, a short linked belt of brass cases with steel tips, diagonal."""
    im = img()
    brass, brass_d, tip, link = (214, 168, 66), (150, 110, 40), (150, 156, 166), (70, 74, 82)
    for k in range(4):
        cx, cy = 3 + k * 3, 12 - k * 3
        for t in range(-2, 4):  # each round runs up-right from its link
            for w in (0, 1):
                x, y = cx + t + w, cy - t
                if 0 <= x < 16 and 0 <= y < 16:
                    c = tip if t >= 2 else (brass if w == 0 else brass_d)
                    im.putpixel((x, y), c + (255,))
        for x, y in ((cx - 2, cy + 1), (cx - 1, cy + 2), (cx - 3, cy + 2)):
            if 0 <= x < 16 and 0 <= y < 16:
                im.putpixel((x, y), link + (255,))
    out = os.path.join(ROOT, "textures", "item")
    os.makedirs(out, exist_ok=True)
    im.save(os.path.join(out, "autocannon_rounds.png"))


def dry_dock():
    """M10: the Dry Dock gantry block (a ship's scan stops at it): open steel lattice with hazard-striped rails."""
    side = img(DARKER)
    noise(side, 4, 77)
    for y in range(16):
        for x in range(16):
            rail = y < 3 or y > 12
            post = x < 2 or x > 13
            brace = abs(x - y) <= 1 or abs(x + y - 15) <= 1
            if rail:
                c = YELLOW if ((x + y) // 3) % 2 == 0 else DARKER
                side.putpixel((x, y), shade(c, 0.9 if y in (0, 15) else 1.0) + (255,))
            elif post:
                side.putpixel((x, y), shade(MID, 1.1 if x in (0, 15) else 0.9) + (255,))
            elif brace:
                side.putpixel((x, y), shade(LIGHT, 0.95) + (255,))
    for x, y in ((3, 4), (12, 4), (3, 11), (12, 11)):
        side.putpixel((x, y), shade(LIGHT, 1.25) + (255,))
    top = img(DARK)
    noise(top, 5, 78)
    for y in range(16):
        for x in range(16):
            if x in (0, 15) or y in (0, 15):
                top.putpixel((x, y), (YELLOW if ((x + y) // 2) % 2 == 0 else DARKER) + (255,))
            elif x % 3 == 0 or y % 3 == 0:
                top.putpixel((x, y), shade(MID, 1.05) + (255,))
    out = os.path.join(ROOT, "textures", "block")
    side.save(os.path.join(out, "dry_dock_side.png"))
    top.save(os.path.join(out, "dry_dock_top.png"))


def write(name, m):
    os.makedirs(MODELS, exist_ok=True)
    data = m.json(True)
    # Texture keys that point into ship/ live under textures/block/ship, not art/.
    data["textures"] = {k: (v.replace("starwake:block/art/ship/", "starwake:block/ship/")) for k, v in data["textures"].items()}
    renamed = {}
    for k in list(data["textures"]):
        if "/" in k:
            nk = k.split("/")[-1]
            renamed[k] = nk
            data["textures"][nk] = data["textures"].pop(k)
    for el in data["elements"]:
        for face in el["faces"].values():
            key = face["texture"][1:]
            if key in renamed:
                face["texture"] = "#" + renamed[key]
    data["textures"]["particle"] = next((v for k, v in data["textures"].items() if k.endswith("_side")), data["textures"]["particle"])
    with open(os.path.join(MODELS, name + ".json"), "w") as f:
        json.dump(data, f, indent=1)


def main():
    n = 0
    for tier in TIERS:
        for part in ("power_plant", "cooler", "shield_generator", "quantum_drive", "jump_drive", "fuel_tank"):
            write(part + tier, component(part, tier))
            n += 1
        for part in ("main_engine", "thruster", "maneuvering_thruster"):
            write(part + tier, engine(part, tier))
            n += 1
        for part in ("ballistic_cannon", "laser_repeater"):
            write(part + tier, weapon(part, tier, True))
            write(part + tier + "_fixed", weapon(part, tier, False))
            n += 2
    dry_dock()
    ammo_sprite()
    print(f"{n} ship-part models, dry dock textures")


if __name__ == "__main__":
    main()
