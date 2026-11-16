"""Starwake machine art (M9 art pass): 16x textures in the SC industrial palette and shaped block models for every
process/power/metalworking/fabrication/nuclear machine.

Writes
  src/main/resources/assets/starwake/textures/block/art/*.png (+ .mcmeta for animated ones)
  src/main/resources/assets/starwake/models/block/machine/<id>.json      (idle)
  src/main/resources/assets/starwake/models/block/machine/<id>_on.json   (running: glowing parts emit light, fans spin)

Models face north (front = -Z), like vanilla's orientable; datagen rotates them by FACING and switches on LIT.
Collision stays the full cube (the blocks are noOcclusion so neighbours aren't culled behind the open shapes).
Run: python3 tools/art_machines.py
"""
import json
import math
import os
import random

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "starwake")
TEX = os.path.join(ROOT, "textures", "block", "art")
MODELS = os.path.join(ROOT, "models", "block", "machine")

# ---------------------------------------------------------------------------------------------
# Palette (grey/white panels, orange/teal accents)
# ---------------------------------------------------------------------------------------------
WHITE = (214, 218, 222)
LIGHT = (160, 166, 174)
MID = (112, 118, 128)
DARK = (62, 66, 74)
DARKER = (38, 41, 47)
ORANGE = (232, 124, 40)
TEAL = (40, 196, 200)
YELLOW = (238, 188, 40)
COPPER = (198, 112, 62)
BRICK = (150, 78, 52)

GLOWS = {"orange": (255, 150, 50), "blue": (90, 200, 255), "purple": (200, 110, 255), "green": (110, 255, 140),
         "white": (240, 248, 255), "red": (255, 70, 60), "teal": (60, 240, 230)}


def clamp(c):
    return tuple(max(0, min(255, int(v))) for v in c)


def shade(c, f):
    return clamp(tuple(v * f for v in c))


def img(fill=(0, 0, 0, 0), h=16):
    return Image.new("RGBA", (16, h), fill if len(fill) == 4 else fill + (255,))


def noise(im, amt, seed, y0=0, y1=None):
    r = random.Random(seed)
    for y in range(y0, y1 if y1 is not None else im.height):
        for x in range(16):
            p = im.getpixel((x, y))
            if p[3] == 0:
                continue
            d = r.uniform(-amt, amt)
            im.putpixel((x, y), clamp((p[0] + d, p[1] + d, p[2] + d)) + (p[3],))


def bevel(im, y0=0):
    for i in range(16):
        for (x, y, f) in ((i, y0, 1.18), (0, y0 + i, 1.1), (i, y0 + 15, 0.72), (15, y0 + i, 0.78)):
            p = im.getpixel((x, y))
            im.putpixel((x, y), shade(p[:3], f) + (p[3],))


def rivets(im, col, y0=0):
    for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
        im.putpixel((x, y0 + y), shade(col, 1.35) + (255,))
        im.putpixel((x + 1, y0 + y + 1), shade(col, 0.6) + (255,))


def plate(col, seed, seams=True, riv=True):
    im = img(col)
    noise(im, 5, seed)
    if seams:
        for x in range(1, 15):
            im.putpixel((x, 8), shade(col, 0.78) + (255,))
            im.putpixel((x, 9), shade(col, 1.08) + (255,))
    bevel(im)
    if riv:
        rivets(im, col)
    return im


def save(name, im, frametime=None):
    os.makedirs(TEX, exist_ok=True)
    im.save(os.path.join(TEX, name + ".png"))
    meta = os.path.join(TEX, name + ".png.mcmeta")
    if frametime:
        with open(meta, "w") as f:
            json.dump({"animation": {"frametime": frametime, "interpolate": False}}, f)
    elif os.path.exists(meta):
        os.remove(meta)


def textures():
    save("panel_white", plate(WHITE, 1))
    save("panel_light", plate(LIGHT, 2))
    save("panel_dark", plate(DARK, 3))
    save("frame", plate(DARKER, 4, seams=False))
    # Plinth: dark with a hazard band along the bottom.
    im = plate(DARKER, 5, seams=False, riv=False)
    for x in range(16):
        for y in range(11, 15):
            im.putpixel((x, y), (YELLOW if (x + y) // 2 % 2 == 0 else (30, 30, 30)) + (255,))
    save("plinth", im)
    im = img(YELLOW)
    for x in range(16):
        for y in range(16):
            if (x + y) // 3 % 2:
                im.putpixel((x, y), (30, 30, 30, 255))
    noise(im, 6, 6)
    save("hazard", im)
    for name, col in (("stripe_orange", ORANGE), ("stripe_teal", TEAL)):
        im = plate(LIGHT, 7)
        for x in range(16):
            for y in (5, 6, 7):
                im.putpixel((x, y), shade(col, 1.0 if y != 7 else 0.8) + (255,))
        save(name, im)
    # Vent: light frame, dark louvres.
    im = plate(LIGHT, 8, seams=False, riv=False)
    for y in range(3, 14, 2):
        for x in range(2, 14):
            im.putpixel((x, y), DARKER + (255,))
            im.putpixel((x, y + 1), shade(LIGHT, 0.85) + (255,))
    save("vent", im)
    im = img(DARKER)
    for x in range(16):
        for y in range(16):
            if x % 3 == 0 or y % 3 == 0:
                im.putpixel((x, y), MID + (255,))
    save("grille", im)
    # Pipe / roll / tank metals.
    for name, col, seed in (("pipe", (150, 156, 166), 9), ("pipe_dark", (80, 86, 96), 10), ("pipe_orange", ORANGE, 11)):
        im = img(col)
        for y in range(16):
            f = 0.75 + 0.45 * math.sin(math.pi * (y + 0.5) / 16)
            for x in range(16):
                im.putpixel((x, y), shade(col, f) + (255,))
        for y in range(16):
            for x in (0, 8):
                im.putpixel((x, y), shade(col, 0.6) + (255,))
        noise(im, 4, seed)
        save(name, im)
    im = img((190, 196, 204))
    for y in range(16):
        for x in range(16):
            f = 0.7 + 0.5 * math.sin(math.pi * (x + 0.5) / 16)
            im.putpixel((x, y), shade((190, 196, 204), f) + (255,))
    save("roll", im)
    for name, col in (("tank_white", WHITE), ("tank_steel", (140, 146, 156))):
        im = img(col)
        noise(im, 4, 12)
        for x in range(16):
            for y in (4, 12):
                im.putpixel((x, y), shade(col, 0.72) + (255,))
        for y in range(4, 12):
            im.putpixel((8, y), shade(col, 0.8) + (255,))
        for y in range(16):
            for x in range(16):
                p = im.getpixel((x, y))
                im.putpixel((x, y), shade(p[:3], 0.82 + 0.25 * math.sin(math.pi * (x + 0.5) / 16)) + (255,))
        save(name, im)
    im = img(BRICK)
    for y in range(16):
        for x in range(16):
            if y % 4 == 3 or (x + (4 if y // 4 % 2 else 0)) % 8 == 7:
                im.putpixel((x, y), (70, 40, 32, 255))
    noise(im, 10, 13)
    save("brick", im)
    im = img(COPPER)
    for y in range(16):
        for x in range(16):
            f = 0.8 + 0.4 * math.sin(math.pi * (x + 0.5) / 16)
            im.putpixel((x, y), shade(COPPER if y % 3 else (90, 50, 30), f) + (255,))
    save("coil", im)
    im = img((20, 60, 70, 150))
    for i in range(16):
        for d in (0, 1):
            if 0 <= i + d < 16:
                im.putpixel((i, 15 - i - d if 15 - i - d >= 0 else 0), (190, 240, 250, 120))
    for i in range(16):
        im.putpixel((i, 0), (70, 80, 90, 255))
        im.putpixel((i, 15), (70, 80, 90, 255))
        im.putpixel((0, i), (70, 80, 90, 255))
        im.putpixel((15, i), (70, 80, 90, 255))
    save("glass", im)
    im = img((26, 28, 32))
    noise(im, 3, 14)
    save("rubber", im)
    # Gauge: white dial, red needle, on a dark plate.
    im = plate(DARK, 15, seams=False, riv=False)
    for y in range(16):
        for x in range(16):
            if (x - 7.5) ** 2 + (y - 7.5) ** 2 < 30:
                im.putpixel((x, y), (230, 232, 225, 255))
    for i in range(5):
        im.putpixel((7 + i // 2, 8 - i), (220, 40, 30, 255))
    save("gauge", im)
    im = plate(DARKER, 16, seams=False, riv=False)
    for y in range(16):
        for x in range(16):
            if 16 < (x - 7.5) ** 2 + (y - 7.5) ** 2 < 36:
                im.putpixel((x, y), MID + (255,))
    save("cap", im)
    # Animated: glow (8-frame breathing flicker), screen (scrolling bars), fan (4-frame spin).
    for name, col in GLOWS.items():
        frames = img(h=16 * 8)
        r = random.Random(name)
        for f in range(8):
            k = 0.82 + 0.18 * math.sin(f / 8 * 2 * math.pi)
            for y in range(16):
                for x in range(16):
                    d = math.hypot(x - 7.5, y - 7.5) / 11
                    c = shade(col, k * (1.15 - 0.35 * d) + r.uniform(-0.05, 0.05))
                    frames.putpixel((x, f * 16 + y), c + (255,))
        save("glow_" + name, frames, 2)
        dim = img(shade(col, 0.22))
        noise(dim, 4, 17)
        save("dim_" + name, dim)
    frames = img(h=16 * 8)
    r = random.Random(18)
    heights = [r.randint(3, 12) for _ in range(24)]
    for f in range(8):
        for y in range(16):
            for x in range(16):
                frames.putpixel((x, f * 16 + y), (8, 22, 26, 255))
        for x in range(1, 15):
            h = heights[(x + f) % 24]
            for y in range(15 - h, 15):
                frames.putpixel((x, f * 16 + y), TEAL + (255,) if x % 2 else (30, 120, 130, 255))
        for x in range(16):
            frames.putpixel((x, f * 16 + 1), (60, 220, 220, 255) if (x + f) % 5 else (8, 22, 26, 255))
    save("screen_on", frames, 3)
    im = img((14, 16, 18))
    im.putpixel((2, 2), (40, 50, 52, 255))
    save("screen_off", im)
    for name, frames_n in (("fan_on", 4), ("fan_off", 1)):
        frames = img(h=16 * frames_n)
        for f in range(frames_n):
            for y in range(16):
                for x in range(16):
                    dx, dy = x - 7.5, y - 7.5
                    rr = math.hypot(dx, dy)
                    a = math.atan2(dy, dx) - f * math.pi / 8
                    if rr > 7.6:
                        c = DARK
                    elif rr < 1.8:
                        c = MID
                    elif math.cos(3 * a) > 0.35:
                        c = LIGHT
                    else:
                        c = DARKER
                    frames.putpixel((x, f * 16 + y), c + (255,))
        save(name, frames, 1 if frames_n > 1 else None)


# ---------------------------------------------------------------------------------------------
# Models
# ---------------------------------------------------------------------------------------------

DIRS = ("north", "south", "east", "west", "up", "down")


_AXIS = {"north": (2, 0), "south": (2, 1), "west": (0, 0), "east": (0, 1), "down": (1, 0), "up": (1, 1)}


def separate_coplanar(elements, step=0.02):
    """Z-fighting guard: where two unrotated elements have faces pointing the same way in the same plane and overlapping,
    the element with the smaller face is pushed out by `step` on that side, so the detail always draws in front.
    Identical bounds are intentional overlays (left alone). Repeats until clean (bounded)."""
    for _ in range(6):
        moved = False
        faces = []
        for i, e in enumerate(elements):
            if "rotation" in e:
                continue
            for d in e["faces"]:
                a, side = _AXIS[d]
                plane = round(e["to"][a] if side else e["from"][a], 4)
                o = [k for k in range(3) if k != a]
                faces.append((i, d, plane, [(e["from"][k], e["to"][k]) for k in o], a, side))
        seen = set()
        for x in range(len(faces)):
            for y in range(x + 1, len(faces)):
                f1, f2 = faces[x], faces[y]
                if f1[0] == f2[0] or f1[1] != f2[1] or f1[2] != f2[2]:
                    continue
                e1, e2 = elements[f1[0]], elements[f2[0]]
                if e1["from"] == e2["from"] and e1["to"] == e2["to"]:
                    continue
                if not all(min(r1[1], r2[1]) - max(r1[0], r2[0]) > 1e-3 for r1, r2 in zip(f1[3], f2[3])):
                    continue
                area = lambda f: (f[3][0][1] - f[3][0][0]) * (f[3][1][1] - f[3][1][0])
                small = f1 if area(f1) <= area(f2) else f2
                key = (small[0], small[1])
                if key in seen:
                    continue
                seen.add(key)
                e = elements[small[0]]
                a, side = small[4], small[5]
                if side:
                    e["to"][a] = round(e["to"][a] + step, 4)
                else:
                    e["from"][a] = round(e["from"][a] - step, 4)
                face = e["faces"].get(small[1])
                if face is not None:
                    face.pop("cullface", None)
                moved = True
        if not moved:
            return


class Model:
    def __init__(self):
        self.parts = []
        self.emit = {}    # part index -> light_emission for elements that always glow (overlays)   # (from, to, faces-dict tex keys, glow, rot)

    def box(self, f, t, tex, sides=None, top=None, bottom=None, front=None, glow=None, light=15, full=False, rot=None, only=None):
        """tex = texture for every face; sides/top/bottom/front override. glow = colour name: the faces given by `only`
        (default all) become glow_<c> (lit, emissive) / dim_<c> (idle)."""
        faces = {}
        for d in DIRS:
            k = tex
            if d in ("east", "west", "south") and sides:
                k = sides
            if d == "north":
                k = front or sides or tex
            if d == "up" and top:
                k = top
            if d == "down" and bottom:
                k = bottom
            faces[d] = k
        if only is not None:
            faces = {d: faces[d] for d in only}
        self.parts.append((list(f), list(t), faces, glow, light, full, rot, {}))
        return self

    def cyl(self, axis, c, r, a0, a1, tex, cap=None, glow=None, only=None):
        """A stepped (pixel-art) cylinder: non-overlapping bands across the circle, so no faces fight. The cap texture is
        spread over the whole round end (each band's cap face gets its slice of it). axis 'y': c = (x, z); 'x': c = (y, z);
        'z': c = (x, y)."""
        if r <= 1.2:
            edges = [(0, 1.0, 1.0)]
        elif r <= 2.6:
            edges = [(0, 0.55, 1.0), (0.55, 1.0, 0.7)]
        else:
            edges = [(0, 0.45, 1.0), (0.45, 0.8, 0.88), (0.8, 1.0, 0.58)]
        bands = []
        for a, b, w in edges:
            if a == 0:
                bands.append((-b * r, b * r, w * r))
            else:
                bands.append((a * r, b * r, w * r))
                bands.append((-b * r, -a * r, w * r))
        cap = cap or tex

        def slice_uv(u0, u1, v0, v1):
            k = 16 / (2 * r)
            return [round((u0 + r) * k, 3), round((v0 + r) * k, 3), round((u1 + r) * k, 3), round((v1 + r) * k, 3)]

        for v0, v1, hw in bands:
            u0, u1 = -hw, hw
            if axis == "y":
                f, t = (c[0] + u0, a0, c[1] + v0), (c[0] + u1, a1, c[1] + v1)
                self.box(f, t, tex, top=cap, bottom=cap, glow=glow, only=only)
                ends = {"up": slice_uv(u0, u1, v0, v1), "down": slice_uv(u0, u1, v0, v1)}
            elif axis == "x":
                f, t = (a0, c[0] + u0, c[1] + v0), (a1, c[0] + u1, c[1] + v1)
                self.box(f, t, tex, sides=tex, glow=glow, only=only)
                for d in ("east", "west"):
                    if d in self.parts[-1][2]:
                        self.parts[-1][2][d] = cap
                ends = {"east": slice_uv(v0, v1, -u1, -u0), "west": slice_uv(v0, v1, -u1, -u0)}
            else:
                f, t = (c[0] + u0, c[1] + v0, a0), (c[0] + u1, c[1] + v1, a1)
                self.box(f, t, tex, glow=glow, only=only)
                for d in ("north", "south"):
                    if d in self.parts[-1][2]:
                        self.parts[-1][2][d] = cap
                ends = {"north": slice_uv(u0, u1, -v1, -v0), "south": slice_uv(u0, u1, -v1, -v0)}
            if cap != tex or glow:
                self.parts[-1][7].update({d: uv for d, uv in ends.items() if d in self.parts[-1][2]})
        return self

    def json(self, on):
        textures, elements = {}, []
        for index, (f, t, faces, glow, light, full, rot, uvs) in enumerate(self.parts):
            el = {"from": f, "to": t, "faces": {}}
            emits = False
            for d, k in faces.items():
                key = k
                if glow:
                    key = ("glow_" if on else "dim_") + glow
                    emits = on
                if k in ("fan", "screen"):
                    key = k + ("_on" if on else "_off")
                    emits = emits or (on and k == "screen")
                textures[key] = "starwake:block/art/" + key
                face = {"texture": "#" + key}
                if d in uvs:
                    face["uv"] = uvs[d]
                elif full or glow or k in ("fan", "screen", "gauge", "cap"):
                    face["uv"] = [0, 0, 16, 16]
                axis = {"north": (2, f[2], 0), "south": (2, t[2], 16), "west": (0, f[0], 0), "east": (0, t[0], 16),
                        "down": (1, f[1], 0), "up": (1, t[1], 16)}[d]
                if axis[1] == axis[2] and not rot:
                    face["cullface"] = d
                el["faces"][d] = face
            if emits:
                el["light_emission"] = light if glow else 10
            if index in self.emit:
                el["light_emission"] = self.emit[index]
            if rot:
                el["rotation"] = rot
            elements.append(el)
        textures["particle"] = "starwake:block/art/panel_light"
        separate_coplanar(elements)
        return {"parent": "minecraft:block/block", "textures": textures, "elements": elements}


def base(m, h=2):
    m.box((0, 0, 0), (16, h, 16), "frame", sides="plinth", top="panel_dark")
    return m


def stack(m, x, z, h0, h1, r=1.5):
    m.cyl("y", (x, z), r, h0, h1, "pipe_dark", cap="cap")
    m.box((x - r - 0.5, h1 - 1, z - r - 0.5), (x + r + 0.5, h1, z + r + 0.5), "frame")
    return m


def motor(m, x, y, z, axis="x", r=2, length=4):
    if axis == "x":
        m.cyl("x", (y, z), r, x, x + length, "panel_dark", cap="fan")
    else:
        m.cyl("y", (x, z), r, y, y + length, "panel_dark", cap="fan")
    return m


def screen(m, x0, y0, x1, y1, z=1):
    """A console screen on the front face (front = -Z), set into a frame."""
    m.box((x0 - 0.5, y0 - 0.5, z - 0.5), (x1 + 0.5, y1 + 0.5, z), "frame")
    m.box((x0, y0, z - 0.75), (x1, y1, z - 0.5), "panel_dark", front="screen", only=("north",))
    return m


def window(m, x0, y0, x1, y1, color, z=1):
    m.box((x0 - 0.5, y0 - 0.5, z - 0.5), (x1 + 0.5, y1 + 0.5, z), "frame")
    m.box((x0, y0, z - 0.75), (x1, y1, z - 0.5), "frame", glow=color, only=("north",))
    return m


def lamp(m, x, y, z, color="orange"):
    m.box((x - 0.75, y - 0.75, z - 0.75), (x + 0.75, y + 0.75, z + 0.75), "frame", glow=color, light=8)
    return m


def flange(m, axis, c, at, r=2.2):
    if axis == "y":
        m.box((c[0] - r, at, c[1] - r), (c[0] + r, at + 0.75, c[1] + r), "frame")
    return m


# --- Designs ---------------------------------------------------------------------------------

def box_machine(color_stripe="stripe_orange", glow="orange", gl=(4, 4, 12, 9)):
    m = base(Model())
    m.box((1, 2, 1), (15, 14, 15), "panel_light", sides=color_stripe, top="vent")
    window(m, *gl, glow)
    return m


def combustion_generator():
    m = base(Model())
    m.box((1, 2, 2), (13, 11, 15), "panel_dark", sides="stripe_orange", top="vent")
    window(m, 3, 3.5, 10, 7, "orange", z=2)
    screen(m, 3, 8, 10, 10, z=2)
    motor(m, 13, 6, 8, "x", r=2.5, length=2.5)
    stack(m, 4, 12, 11, 16)
    stack(m, 9, 12, 11, 15, r=1.2)
    return m


def electric_furnace():
    m = base(Model())
    m.box((1, 2, 1), (15, 13, 15), "panel_white", sides="stripe_orange", top="panel_light")
    window(m, 4, 4, 12, 10, "orange")
    m.box((3, 13, 3), (13, 14, 13), "vent")
    lamp(m, 13.5, 11.5, 0.75)
    return m


def water_pump():
    m = base(Model())
    m.cyl("y", (8, 9), 4.5, 2, 9, "tank_steel", cap="cap")
    motor(m, 6, 9, 9, "y", r=2.5, length=5)
    m.cyl("z", (5, 8), 1.6, 0, 6, "pipe", cap="cap")
    m.cyl("x", (5, 9), 1.6, 12, 16, "pipe", cap="cap")
    m.box((5, 4, 0), (11, 7, 1), "frame")
    lamp(m, 12, 11, 4, "blue")
    return m


def atmospheric_condenser():
    m = base(Model())
    m.box((2, 2, 2), (14, 10, 14), "panel_white", sides="stripe_teal")
    for x in range(3, 14, 2):
        m.box((x, 10, 2), (x + 1, 14, 14), "pipe")
    m.box((4, 14, 4), (12, 15, 12), "frame", top="fan")
    window(m, 5, 4, 11, 8, "blue", z=2)
    return m


def gas_well():
    m = base(Model())
    m.box((3.5, 2, 3.5), (12.5, 3.5, 12.5), "frame", top="panel_dark")
    m.cyl("y", (8, 8), 2.6, 3.5, 14, "pipe_orange", cap="cap")
    for y in (5.5, 10):
        m.box((4.5, y, 4.5), (11.5, y + 2.2, 11.5), "panel_dark", sides="pipe_dark")
        m.box((1.5, y + 0.6, 7), (4.5, y + 1.6, 9), "pipe")            # wing outlet
        m.box((7, y + 0.4, 0.75), (9, y + 1.8, 1.25), "coil")          # handwheel
        m.box((7.5, y + 0.8, 1.25), (8.5, y + 1.4, 4.5), "roll")
    m.box((11.5, 6, 6.5), (15.5, 7.5, 9.5), "pipe")
    m.box((5.5, 14, 5.5), (10.5, 15, 10.5), "frame", top="cap")
    lamp(m, 8, 15.6, 8, "red")
    m.box((12, 2, 11), (15, 9, 15), "panel_dark")
    m.box((12.5, 4, 10.75), (14.5, 7, 11), "frame", front="gauge", only=("north",))
    return m


def crusher():
    m = base(Model())
    m.box((2, 2, 2), (14, 9, 14), "panel_dark", sides="stripe_orange")
    # Feed hopper: stepped funnel.
    m.box((3, 9, 3), (13, 11, 13), "panel_light")
    m.box((1.5, 11, 1.5), (14.5, 13, 14.5), "panel_light", top="grille")
    m.box((1, 13, 1), (15, 14, 15), "hazard", top="grille")
    motor(m, 14, 5.5, 8, "x", r=2.2, length=2)
    m.box((4, 2, 0.5), (12, 5, 2), "rubber")   # discharge chute
    lamp(m, 3, 8, 1.5)
    return m


def flotation():
    m = base(Model())
    m.box((1.5, 2, 1.5), (14.5, 10.5, 14.5), "tank_white", top="frame")
    m.box((2.5, 10.5, 2.5), (13.5, 10.75, 13.5), "frame", glow="white", only=("up",), light=6)
    m.box((7, 11, 1.5), (9, 13, 14.5), "panel_dark")
    motor(m, 8, 13, 8, "y", r=2, length=3)
    m.box((0.5, 8, 6), (1.5, 10.5, 10), "pipe_orange")
    return m


def magnetic():
    m = base(Model())
    m.box((1, 2, 1), (3, 14, 15), "panel_dark")
    m.box((13, 2, 1), (15, 14, 15), "panel_dark")
    m.cyl("x", (9, 8), 5, 3, 13, "coil", cap="cap")
    m.box((3, 3, 1), (13, 4.5, 15), "rubber")
    lamp(m, 14, 13, 0.5, "blue")
    m.box((3, 14, 2), (13, 15, 14), "panel_light")
    return m


def gravity():
    m = base(Model())
    for x in (2, 13):
        for z in (2, 13):
            m.box((x, 2, z), (x + 1, 7, z + 1), "pipe_dark")
    deck = {"origin": [8, 7.75, 8], "x": 0, "y": 0, "z": 5}
    m.box((6, 2, 6), (10, 7, 10), "panel_dark")
    m.box((1, 7, 1), (15, 8.5, 15), "panel_light", top="grille", rot=deck)
    for x in range(3, 14, 3):
        m.box((x, 8.5, 2), (x + 1, 9, 14), "pipe", rot=deck)
    motor(m, 0.5, 4, 8, "x", r=1.8, length=2.5)
    m.box((6, 9, 0.5), (10, 12, 2), "pipe_orange")
    return m


def roaster():
    m = base(Model())
    for x in (3, 11):
        m.box((x, 2, 3), (x + 2, 5, 13), "panel_dark")
        m.box((x - 0.5, 5, 2.5), (x + 2.5, 6, 13.5), "roll")
    m.cyl("x", (9.5, 8), 4.5, 0.5, 15.5, "brick", cap="frame")
    m.box((0, 7.5, 5.5), (0.75, 11.5, 10.5), "frame", glow="orange", only=("west",))
    for x in (2, 7, 12):
        m.box((x, 4.5, 3), (x + 1, 14.5, 13), "pipe_orange")
    stack(m, 13, 8, 13, 16, r=1.3)
    return m


def coke_oven():
    m = base(Model())
    m.box((1, 2, 1), (15, 12, 15), "brick", top="brick")
    m.box((3, 3, 0.5), (13, 11, 1), "frame")
    window(m, 5, 4, 11, 9, "orange")
    m.box((4, 9.5, 0.25), (12, 10, 0.75), "pipe_dark")
    stack(m, 12, 11, 12, 16, r=1.6)
    m.box((1, 12, 1), (15, 13, 15), "panel_dark")
    return m


def smelter():
    m = base(Model())
    m.cyl("y", (8, 9), 6, 2, 12, "brick", cap="panel_dark")
    m.box((3, 12, 4), (13, 13.5, 14), "panel_dark")
    m.box((5, 13.5, 6), (11, 15, 12), "frame", top="grille")
    stack(m, 8, 9, 15, 16, r=1.4)
    # Tap-hole and runner.
    m.box((6, 3, 1.5), (10, 6, 3), "frame")
    m.box((7, 3.5, 1.25), (9, 5.5, 1.5), "frame", glow="orange", only=("north",))
    m.box((7, 2, 0), (9, 2.75, 2), "brick", glow="orange", only=("up",), light=10)
    lamp(m, 14, 9, 3)
    return m


def converter():
    m = base(Model())
    for x in (1, 13):
        m.box((x, 2, 6), (x + 2, 11, 10), "panel_dark")
        m.cyl("x", (9, 8), 1.5, x - 0.5 if x == 1 else x, x + 2.5 if x == 1 else x + 2.5, "roll", cap="cap")
    tilt = {"origin": [8, 9, 8], "x": -15, "y": 0, "z": 0}
    m.box((4, 3, 4), (12, 13, 12), "brick", sides="pipe_dark", top="panel_dark", rot=tilt)
    m.box((5, 13, 5), (11, 15, 11), "pipe_dark", rot=tilt)
    m.box((6, 15, 6), (10, 15.25, 10), "frame", glow="orange", only=("up",), rot=tilt)
    m.box((3.5, 7, 3.5), (12.5, 8, 12.5), "stripe_orange", rot=tilt)
    return m


def reactor():
    m = base(Model())
    m.cyl("y", (8, 8), 5.5, 2, 12, "tank_white", cap="tank_white")
    m.cyl("y", (8, 8), 4, 12, 13.5, "tank_white", cap="cap")
    motor(m, 8, 13.5, 8, "y", r=1.8, length=2.5)
    m.box((2, 6, 2), (14, 7, 14), "stripe_orange", only=("north", "south", "east", "west"))
    window(m, 6, 7.5, 10, 10.5, "teal", z=2.6)
    m.cyl("x", (4, 8), 1, 13, 16, "pipe", cap="cap")
    return m


def leach():
    m = base(Model())
    m.box((1.5, 2, 1.5), (14.5, 11, 14.5), "tank_steel", top="frame")
    m.box((2.5, 11, 2.5), (13.5, 11.25, 13.5), "frame", glow="green", only=("up",), light=5)
    m.box((1, 12, 7), (15, 13, 9), "panel_dark")
    m.cyl("y", (8, 8), 0.6, 8, 12, "roll")
    motor(m, 8, 13, 8, "y", r=1.8, length=2.5)
    return m


def evaporator():
    m = base(Model())
    m.cyl("y", (6, 8), 4.5, 2, 13, "tank_steel", cap="cap")
    for y in (4, 7, 10):
        m.box((10.5, y, 4), (15, y + 1, 12), "coil")
    m.box((12, 2, 6), (14, 13, 10), "panel_dark")
    m.cyl("y", (6, 8), 1.2, 13, 16, "pipe", cap="cap")
    window(m, 4, 5, 8, 9, "orange", z=3.5)
    return m


def crystallizer():
    m = base(Model())
    for x in (3, 12):
        for z in (3, 12):
            m.box((x, 2, z), (x + 1, 6, z + 1), "pipe_dark")
    m.cyl("y", (8, 8), 2.5, 3, 6, "tank_white", cap="cap")
    m.cyl("y", (8, 8), 5, 6, 13, "tank_white", cap="tank_white")
    m.cyl("y", (8, 8), 3, 13, 14, "tank_white", cap="cap")
    window(m, 6, 8, 10, 11, "white", z=3.3)
    motor(m, 8, 14, 8, "y", r=1.4, length=2)
    return m


def electrolysis():
    m = base(Model())
    m.box((1, 2, 2), (15, 9, 14), "rubber", top="frame")
    m.box((2, 8.5, 3), (14, 9.25, 13), "frame", glow="teal", only=("up",), light=7)
    for x in (3, 6, 9, 12):
        m.box((x, 9, 4), (x + 1, 12, 12), "panel_light")
    m.box((2, 12, 7), (14, 13, 9), "coil")
    m.box((2, 12, 5), (14, 12.75, 6), "coil")
    screen(m, 3, 4, 13, 7, z=2)
    return m


def chlorinator():
    m = base(Model())
    m.cyl("y", (8, 8), 4, 2, 15, "panel_white", cap="cap")
    for y in (5, 9, 13):
        m.cyl("y", (8, 8), 4.6, y, y + 0.75, "pipe_dark")
    m.box((1, 2, 1), (4, 9, 4), "tank_steel")
    m.box((1.5, 9, 1.5), (3.5, 10, 3.5), "cap")
    m.cyl("x", (11, 8), 1, 12, 16, "pipe_orange", cap="cap")
    window(m, 6, 6, 10, 8.5, "green", z=4.2)
    return m


def kroll():
    m = base(Model())
    m.box((1, 2, 1), (15, 10, 15), "brick", top="panel_dark")
    m.cyl("y", (8, 8), 3.5, 10, 14, "tank_steel", cap="cap")
    m.box((6.5, 14, 6.5), (9.5, 15.5, 9.5), "pipe_dark")
    window(m, 4, 4, 12, 7, "orange")
    m.cyl("z", (13, 13), 1, 0, 16, "pipe", cap="cap")
    return m


def air_separator():
    m = base(Model())
    m.box((1, 2, 1), (9, 16, 9), "panel_white", sides="stripe_teal", top="vent")
    m.cyl("y", (12, 11), 2.6, 2, 16, "tank_white", cap="cap")
    m.cyl("y", (5, 12.5), 2, 2, 12, "tank_steel", cap="cap")
    for y in (5, 11):
        m.box((8.5, y, 4), (12, y + 1, 5), "pipe")
    screen(m, 2, 9, 8, 13, z=1)
    lamp(m, 8, 15, 0.75, "blue")
    return m


def solvent_extraction():
    m = base(Model())
    for i, z in enumerate((1, 6, 11)):
        y1 = 9 - i * 2
        m.box((1, 2, z), (15, y1, z + 4), "tank_white", top="frame")
        m.box((2, y1, z + 0.5), (14, y1 + 0.2, z + 3.5), "frame", glow="green" if i % 2 else "orange", only=("up",), light=5)
        motor(m, 4, y1, z + 2, "y", r=1, length=2)
    m.cyl("x", (12, 13), 0.8, 0, 16, "pipe", cap="cap")
    return m


def arc_furnace():
    m = base(Model())
    m.cyl("y", (8, 8), 6.5, 2, 9, "brick", cap="panel_dark")
    m.cyl("y", (8, 8), 6, 9, 10.5, "pipe_dark", cap="panel_dark")
    for (x, z) in ((5.5, 6.5), (10.5, 6.5), (8, 10.5)):
        m.cyl("y", (x, z), 0.9, 10.5, 16, "roll", cap="cap")
        m.box((x - 1.2, 13, z - 1.2), (x + 1.2, 14, z + 1.2), "coil")
    m.box((6, 3, 1), (10, 6, 2), "frame", glow="white", only=("north",))
    return m


def isotope_column():
    m = base(Model())
    for x in (4, 11):
        m.cyl("y", (x, 8), 2.2, 2, 16, "panel_white", cap="cap")
        for y in (6, 11):
            m.cyl("y", (x, 8), 2.6, y, y + 0.6, "pipe_orange")
    m.box((6, 9, 7), (9, 10, 9), "pipe")
    m.box((6, 2, 1), (10, 7, 4), "panel_dark")
    screen(m, 6.5, 3.5, 9.5, 6.5, z=1)
    return m


def induction_furnace():
    m = base(Model())
    m.box((1, 2, 1), (15, 6, 15), "panel_dark", sides="stripe_orange")
    m.cyl("y", (8, 8), 5, 6, 13, "coil", cap="brick")
    m.cyl("y", (8, 8), 3.6, 12.75, 13.25, "brick", glow="orange", only=("up",))
    m.box((13, 6, 2), (15, 14, 4), "panel_dark")
    lamp(m, 14, 14.5, 3)
    screen(m, 3, 2.75, 13, 5.25, z=1)
    return m


def vacuum_arc_furnace():
    m = base(Model())
    m.box((1, 2, 1), (15, 5, 15), "panel_dark")
    m.cyl("y", (8, 8), 4.5, 5, 13, "tank_steel", cap="cap")
    m.cyl("y", (8, 8), 1, 13, 16, "roll", cap="cap")
    for y in (7, 11):
        m.cyl("y", (8, 8), 5, y, y + 0.75, "coil")
    window(m, 6.5, 8, 9.5, 10.5, "blue", z=3.6)
    m.cyl("x", (3.5, 12), 1, 12, 16, "pipe", cap="cap")
    return m


def sinter_press():
    m = base(Model())
    for x in (1, 13):
        m.box((x, 2, 3), (x + 2, 16, 13), "panel_dark")
    m.box((1, 13, 3), (15, 16, 13), "panel_light", sides="stripe_orange")
    m.cyl("y", (8, 8), 2.2, 9, 13, "roll")
    m.box((4, 8, 5), (12, 9, 11), "pipe_dark")
    m.box((4, 2, 4), (12, 5, 12), "frame", top="panel_dark")
    m.box((5, 5, 5), (11, 5.5, 11), "frame", glow="orange", only=("up", "north"))
    return m


def rolling_mill():
    m = base(Model())
    for z in (1, 13):
        m.box((2, 2, z), (14, 15, z + 2), "panel_dark", front="stripe_orange")
    for y in (6.5, 10):
        m.cyl("z", (8, y), 1.7, 3, 13, "roll", cap="cap")
    m.box((0, 4.5, 3), (16, 5, 13), "rubber")
    motor(m, 14, 9, 8, "x", r=1.8, length=2)
    m.box((2, 15, 1), (14, 16, 15), "panel_light")
    return m


def wire_drawer():
    m = base(Model())
    m.box((1, 2, 1), (15, 7, 15), "panel_dark", sides="stripe_orange", top="panel_light")
    m.cyl("y", (10, 9), 4, 7, 11, "roll", cap="cap")
    m.cyl("y", (10, 9), 4.4, 10.5, 11, "coil")
    m.box((2, 7, 3), (5, 10, 6), "panel_light")
    m.box((2.5, 8, 2.75), (4.5, 9, 3), "frame", glow="orange", only=("north",))
    screen(m, 3, 3, 13, 6, z=1)
    return m


def extrusion_press():
    m = base(Model())
    m.box((1, 2, 3), (15, 5, 13), "panel_dark")
    m.cyl("x", (9, 8), 4, 1, 10, "tank_steel", cap="cap")
    m.cyl("x", (9, 8), 1.6, 10, 13, "roll", cap="cap")
    m.box((13, 5, 4), (15, 13, 12), "panel_light", sides="stripe_orange")
    m.box((14, 8, 7), (15.25, 10, 9), "frame", glow="orange", only=("east",))
    lamp(m, 2, 14, 8)
    return m


def forging_press():
    m = base(Model())
    for x in (1, 13):
        m.box((x, 2, 4), (x + 2, 16, 12), "panel_dark", sides="hazard")
    m.box((1, 13, 4), (15, 16, 12), "panel_light", sides="stripe_orange")
    m.box((4, 9, 5), (12, 13, 11), "pipe_dark")
    m.box((4, 2, 4), (12, 5, 12), "frame", top="roll")
    m.box((5, 5, 5), (11, 5.4, 11), "frame", glow="orange", only=("up",), light=8)
    return m


def heat_treat_furnace():
    m = base(Model())
    m.box((1, 2, 1), (15, 14, 15), "panel_white", sides="stripe_orange", top="vent")
    m.box((2, 3, 0.5), (14, 11, 1), "frame")
    window(m, 3.5, 4.5, 12.5, 9.5, "orange")
    screen(m, 9, 11.5, 13, 13.5, z=1)
    lamp(m, 3, 12.5, 0.75)
    stack(m, 12, 11, 14, 16, r=1)
    return m


def fabricator():
    m = base(Model())
    m.box((1, 2, 1), (15, 8, 15), "panel_dark", sides="stripe_teal", top="grille")
    # Robot arm: base, two links, head.
    m.cyl("y", (11, 11), 1.8, 8, 9.5, "panel_light", cap="cap")
    m.box((10.3, 9.5, 10.3), (11.7, 14, 11.7), "panel_light", rot={"origin": [11, 9.5, 11], "x": 20, "y": 0, "z": 0})
    m.box((10.4, 12.5, 6), (11.6, 13.7, 11), "panel_light")
    m.box((10.2, 10.5, 5.5), (11.8, 12.5, 7), "frame", glow="teal", only=("down",), light=7)
    m.box((3, 8, 3), (8, 8.5, 8), "frame", glow="teal", only=("up",), light=5)
    screen(m, 3, 3, 13, 7, z=1)
    return m


def crystal_puller():
    m = base(Model())
    m.box((2, 2, 2), (14, 7, 14), "panel_white", sides="stripe_teal")
    m.cyl("y", (8, 8), 4, 7, 12, "tank_white", cap="tank_white")
    m.cyl("y", (8, 8), 1.6, 12, 16, "panel_white", cap="cap")
    window(m, 6.5, 8, 9.5, 11, "orange", z=4.6)
    screen(m, 4, 3, 12, 6, z=2)
    return m


def clean_fab():
    m = base(Model())
    m.box((1, 2, 1), (15, 15, 15), "panel_white", top="vent")
    m.box((3, 6, 0.5), (13, 13, 1), "glass")
    m.box((3.5, 6.5, 1), (12.5, 12.5, 1.25), "frame", glow="teal", only=("north",), light=9)
    m.box((4, 3, 0.5), (12, 5, 1), "panel_dark")
    lamp(m, 14, 14, 0.75, "green")
    return m


def gas_centrifuge():
    m = base(Model())
    for x in (4, 8, 12):
        for z in (4, 9):
            m.cyl("y", (x, z), 1.5, 2, 15, "panel_white", cap="cap")
            m.box((x - 0.4, 15, z - 0.4), (x + 0.4, 16, z + 0.4), "pipe_orange")
    m.box((2, 12, 2), (14, 12.75, 11), "pipe_dark")
    m.box((2, 2, 12), (14, 8, 15), "panel_dark")
    m.box((2, 8, 12.5), (14, 8.5, 14.5), "frame", glow="green", only=("up",), light=6)
    return m


def fission_reactor():
    m = base(Model())
    m.cyl("y", (8, 8), 6.5, 2, 12, "tank_steel", cap="panel_dark")
    m.cyl("y", (8, 8), 5, 11.75, 12.25, "frame", glow="blue", only=("up",))
    for (x, z) in ((5.5, 5.5), (10.5, 5.5), (5.5, 10.5), (10.5, 10.5), (8, 8)):
        m.cyl("y", (x, z), 0.8, 12, 15.5, "roll", cap="cap")
    m.box((1, 5, 1), (15, 6, 15), "hazard", only=("north", "south", "east", "west"))
    window(m, 6, 7, 10, 10, "blue", z=1.7)
    return m


def fusion_reactor():
    """A tokamak: a torus of eight rotated segments with magnet coils on every other one, a central solenoid and a plasma
    glow inside the ring."""
    m = base(Model())
    for i in range(8):
        a = i * 45
        cx, cz = 8 + 5 * math.cos(math.radians(a)), 8 + 5 * math.sin(math.radians(a))
        phi = ((-a + 90) % 180) - 90          # segment runs along the ring's tangent
        rot = {"origin": [cx, 7.5, cz], "x": 0, "y": phi, "z": 0} if a % 90 else None
        long_x = (a % 180) == 90 if not rot else True
        hx, hz = (2.1, 1.5) if long_x else (1.5, 2.1)
        if rot:
            hx, hz = 1.5, 2.1
        m.box((cx - hx, 5, cz - hz), (cx + hx, 10, cz + hz), "tank_steel", rot=rot)
        if i % 2 == 0:
            m.box((cx - hx - 0.4, 4.6, cz - hz + 0.6), (cx + hx + 0.4, 10.4, cz + hz - 0.6), "coil", rot=rot)
    m.cyl("y", (8, 8), 1.8, 2, 13, "roll", cap="cap")
    m.cyl("y", (8, 8), 2.6, 12, 13.5, "coil")
    m.box((4.5, 7, 4.5), (11.5, 7.6, 11.5), "frame", glow="purple", only=("up",), light=12)
    m.box((2, 2, 2), (14, 4.5, 14), "panel_dark", sides="stripe_teal")
    return m


def particle_accelerator():
    m = base(Model())
    m.cyl("x", (9, 8), 1.4, 0, 16, "roll", cap="cap")
    for x in (2, 7, 12):
        m.box((x, 5, 4), (x + 2.5, 13, 12), "panel_dark", sides="stripe_teal", front="coil")
        m.box((x + 0.5, 12.5, 6), (x + 2, 13.25, 10), "frame", glow="purple", only=("up",), light=8)
    m.cyl("x", (9, 8), 1.6, 4.5, 7, "frame", glow="purple")
    m.cyl("x", (9, 8), 1.6, 9.5, 12, "frame", glow="purple")
    return m


# --- Hub terminals (always lit; single model each) -------------------------------------------------------

def _console(accent_tex, holo=None):
    m = Model()
    m.box((2, 0, 2), (14, 1.5, 14), "frame", sides="plinth", top="panel_dark")
    m.box((5, 1.5, 6), (11, 9, 12), "panel_light", sides=accent_tex)
    m.box((3, 9, 4), (13, 11, 13), "panel_dark")
    tilt = {"origin": [8, 11, 5], "x": 22.5, "y": 0, "z": 0}
    m.box((2.5, 11, 4), (13.5, 16, 5.5), "frame", rot=tilt)
    m.box((3.25, 11.6, 3.75), (12.75, 15.4, 4), "panel_dark", front="screen", only=("north",), rot=tilt)
    m.box((3.5, 10, 2.5), (12.5, 11, 4.5), "panel_dark", top="grille")       # keyboard shelf
    if holo:
        m.box((6, 11, 9), (10, 11.5, 13), "frame", glow=holo, only=("up",), light=10)
        m.box((7, 11.5, 10), (9, 15, 12), "glass")
    return m


def commodity_terminal():
    return _console("stripe_orange")


def outfitter_terminal():
    return _console("stripe_teal", holo="teal")


def contract_board():
    m = Model()
    m.box((1, 0, 6), (15, 1.5, 11), "frame", sides="plinth")
    for x in (1.5, 13.5):
        m.box((x, 1.5, 7.5), (x + 1, 15, 9.5), "pipe_dark")
    m.box((1, 4, 7), (15, 15.5, 9), "panel_dark", sides="stripe_teal")
    m.box((2, 5, 6.5), (14, 14.5, 7), "panel_dark", front="screen", only=("north",))
    m.box((1, 15.5, 6.5), (15, 16, 9.5), "frame", glow="green", only=("up", "north"), light=8)
    return m


def trade_kiosk():
    m = Model()
    m.box((1, 0, 3), (15, 8, 13), "panel_light", sides="stripe_teal", top="panel_dark")
    m.box((0.5, 8, 2.5), (15.5, 9, 13.5), "frame")
    m.box((2, 8, 1.5), (14, 9, 2.5), "frame", glow="green", only=("north", "up"), light=8)
    for x in (1.5, 13.5):
        m.box((x, 9, 11), (x + 1, 16, 12), "pipe_dark")
    m.box((1, 15, 3), (15, 16, 13), "panel_light", sides="stripe_orange")
    tilt = {"origin": [8, 9, 9], "x": -15, "y": 0, "z": 0}
    m.box((5, 9, 8), (11, 13, 9), "frame", rot=tilt)
    m.box((5.5, 9.5, 7.75), (10.5, 12.5, 8), "panel_dark", front="screen", only=("north",), rot=tilt)
    return m


TERMINALS = {"commodity_terminal": commodity_terminal, "outfitter_terminal": outfitter_terminal,
             "contract_board": contract_board, "trade_kiosk": trade_kiosk}


DESIGNS = {
    "combustion_generator": combustion_generator, "electric_furnace": electric_furnace, "water_pump": water_pump,
    "atmospheric_condenser": atmospheric_condenser, "gas_well": gas_well, "crusher": crusher, "flotation": flotation,
    "magnetic": magnetic, "gravity": gravity, "roaster": roaster, "coke_oven": coke_oven, "smelter": smelter,
    "converter": converter, "reactor": reactor, "leach": leach, "evaporator": evaporator, "crystallizer": crystallizer,
    "electrolysis": electrolysis, "chlorinator": chlorinator, "kroll": kroll, "air_separator": air_separator,
    "solvent_extraction": solvent_extraction, "arc_furnace": arc_furnace, "isotope_column": isotope_column,
    "induction_furnace": induction_furnace, "vacuum_arc_furnace": vacuum_arc_furnace, "sinter_press": sinter_press,
    "rolling_mill": rolling_mill, "wire_drawer": wire_drawer, "extrusion_press": extrusion_press,
    "forging_press": forging_press, "heat_treat_furnace": heat_treat_furnace, "fabricator": fabricator,
    "crystal_puller": crystal_puller, "clean_fab": clean_fab, "gas_centrifuge": gas_centrifuge,
    "fission_reactor": fission_reactor, "fusion_reactor": fusion_reactor, "particle_accelerator": particle_accelerator,
}


def main():
    textures()
    os.makedirs(MODELS, exist_ok=True)
    for name, design in DESIGNS.items():
        m = design()
        for on in (False, True):
            with open(os.path.join(MODELS, f"{name}{'_on' if on else ''}.json"), "w") as f:
                json.dump(m.json(on), f, indent=1)
    for name, design in TERMINALS.items():
        with open(os.path.join(MODELS, f"{name}.json"), "w") as f:
            json.dump(design().json(True), f, indent=1)
    print(f"{len(DESIGNS)} machine models, {len(TERMINALS)} terminals, {len(os.listdir(TEX))} art textures")


if __name__ == "__main__":
    main()
