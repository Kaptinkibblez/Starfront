"""Starwake item sprites (M9 art pass): components, metal forms and a few tools, rendered from tiny 3D shapes.

Each sprite is a signed-distance scene ray-marched at 16x16 (one sample per pixel, orthographic 3/4 view), lit by one
light from the upper left, shaded in a few discrete steps (pixel-art look) and outlined in a darker tone of each
silhouette's colour, so every item shares one consistent style. Metal forms are greyscale on purpose: the game tints
them by alloy (custom_model_data). Components are coloured (they show their function, not their alloy).
Writes src/main/resources/assets/starwake/textures/item/<name>.png. Run: python3 tools/art_items.py
"""
import math
import os

from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "starwake", "textures", "item")


# --- vector helpers ----------------------------------------------------------------------------

def add(a, b): return (a[0] + b[0], a[1] + b[1], a[2] + b[2])
def sub(a, b): return (a[0] - b[0], a[1] - b[1], a[2] - b[2])
def mul(a, k): return (a[0] * k, a[1] * k, a[2] * k)
def dot(a, b): return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
def length(a): return math.sqrt(dot(a, a))
def norm(a):
    n = length(a)
    return mul(a, 1 / n)
def cross(a, b): return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def rot(p, axis, deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    x, y, z = p
    if axis == "x":
        return (x, c * y - s * z, s * y + c * z)
    if axis == "y":
        return (c * x + s * z, y, -s * x + c * z)
    return (c * x - s * y, s * x + c * y, z)


# --- primitives: each returns distance --------------------------------------------------------

def box(p, b, r=0.0):
    q = (abs(p[0]) - b[0] + r, abs(p[1]) - b[1] + r, abs(p[2]) - b[2] + r)
    o = length((max(q[0], 0), max(q[1], 0), max(q[2], 0)))
    return o + min(max(q[0], q[1], q[2]), 0) - r


def cyl(p, r, h):
    """Cylinder along Y."""
    d = (math.hypot(p[0], p[2]) - r, abs(p[1]) - h)
    return min(max(d[0], d[1]), 0) + length((max(d[0], 0), max(d[1], 0), 0))


def torus(p, R, r):
    q = (math.hypot(p[0], p[2]) - R, p[1])
    return math.hypot(*q) - r


def cone(p, r0, r1, h):
    """Frustum along Y from radius r0 at -h to r1 at +h."""
    t = (p[1] + h) / (2 * h)
    r = r0 + (r1 - r0) * max(0, min(1, t))
    d = (math.hypot(p[0], p[2]) - r, abs(p[1]) - h)
    return min(max(d[0] * 0.8, d[1]), 0) + length((max(d[0] * 0.8, 0), max(d[1], 0), 0))


def capsule(p, r, h):
    y = max(-h, min(h, p[1]))
    return length((p[0], p[1] - y, p[2])) - r


# --- renderer ---------------------------------------------------------------------------------

FORWARD = norm((-0.62, -0.55, -1.0))
UP0 = (0, 1, 0)
RIGHT = norm(cross(FORWARD, UP0))
UP = cross(RIGHT, FORWARD)
LIGHT = norm((-0.5, 0.85, 0.45))


def render(scene, scale=1.0, shift=(0, 0), view=None, n=64):
    """scene(p) -> (distance, rgb). Renders at n x n, then box-averages to 16x16 (alpha threshold 50%), posterises the
    brightness and adds a dark outline. Returns RGBA."""
    fwd = norm(view) if view else FORWARD
    right = norm(cross(fwd, UP0))
    up = cross(right, fwd)
    big = [[None] * n for _ in range(n)]
    for py in range(n):
        for px in range(n):
            sx = ((px + 0.5) / n * 2 - 1) * scale + shift[0]
            sy = -((py + 0.5) / n * 2 - 1) * scale + shift[1]
            o = add(add(mul(right, sx), mul(up, sy)), mul(fwd, -4))
            t = 0
            for _ in range(80):
                p = add(o, mul(fwd, t))
                d, col = scene(p)
                if d < 0.003:
                    e = 0.006
                    nn = norm((scene(add(p, (e, 0, 0)))[0] - scene(sub(p, (e, 0, 0)))[0],
                               scene(add(p, (0, e, 0)))[0] - scene(sub(p, (0, e, 0)))[0],
                               scene(add(p, (0, 0, e)))[0] - scene(sub(p, (0, 0, e)))[0]))
                    lam = max(0, dot(nn, LIGHT))
                    spec = max(0, dot(norm(sub(LIGHT, fwd)), nn)) ** 20
                    k = 0.45 + 0.65 * lam
                    big[py][px] = (tuple(v * k + 90 * spec for v in col), col)
                    break
                t += max(d * 0.9, 0.004)
                if t > 9:
                    break
    f = n // 16
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    base = {}
    for y in range(16):
        for x in range(16):
            cells = [big[y * f + j][x * f + i] for j in range(f) for i in range(f)]
            hits = [c for c in cells if c]
            if len(hits) * 2 < len(cells):
                continue
            avg = [sum(h[0][k] for h in hits) / len(hits) for k in range(3)]
            colour = max(set(h[1] for h in hits), key=lambda c: sum(1 for h in hits if h[1] == c))
            lum = sum(avg) / max(1, sum(colour))
            lum = round(lum * 6) / 6
            im.putpixel((x, y), tuple(max(0, min(255, int(v * lum))) for v in colour) + (255,))
            base[(x, y)] = colour
    for (x, y), col in list(base.items()):
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            q = (x + dx, y + dy)
            if 0 <= q[0] < 16 and 0 <= q[1] < 16 and q not in base and im.getpixel(q)[3] == 0:
                im.putpixel(q, tuple(int(v * 0.3) for v in col) + (255,))
    return im


def union(*items):
    def f(p):
        best = (1e9, (0, 0, 0))
        for fn, col in items:
            d = fn(p)
            if d < best[0]:
                best = (d, col)
        return best
    return f


def at(fn, offset=(0, 0, 0), axis=None, deg=0):
    def g(p):
        q = sub(p, offset)
        if axis:
            q = rot(q, axis, -deg)
        return fn(q)
    return g


# --- palette ------------------------------------------------------------------------------------

G = (200, 200, 200)          # greyscale metal (tinted in game)
GD = (150, 150, 150)
STEEL = (170, 176, 186)
DARK = (78, 84, 94)
ORANGE = (232, 124, 40)
COPPER = (205, 120, 70)
WHITE = (226, 230, 234)
TEAL = (40, 196, 200)
RED = (210, 60, 50)


def sprites():
    s = {}
    # Metal forms (greyscale).
    s["metal_ingot"] = (union((lambda p: box(p, (0.62, 0.22, 0.32), 0.06), G)), 0.8, (0, 0), (-0.55, -0.95, -0.85))
    s["metal_plate"] = (union((lambda p: box(p, (0.7, 0.05, 0.7), 0.02), G)), 0.8, (0, 0), (-0.55, -0.95, -0.85))
    s["metal_rod"] = (union((at(lambda p: cyl(p, 0.2, 0.95), axis="z", deg=60), G)), 0.8, (0, 0))
    s["metal_wire"] = (union((lambda p: cyl(p, 0.22, 0.45), GD),
                             *[(at(lambda p, y=y: torus(p, 0.36, 0.09), (0, y, 0)), G) for y in (-0.33, -0.11, 0.11, 0.33)],
                             (at(lambda p: cyl(p, 0.62, 0.05), (0, 0.5, 0)), GD), (at(lambda p: cyl(p, 0.62, 0.05), (0, -0.5, 0)), GD)), 0.8, (0, 0))
    s["metal_dust"] = (union((lambda p: cone(add(p, (0, 0.2, 0)), 0.75, 0.05, 0.35), G)), 0.8, (0, 0.05), (-0.55, -0.95, -0.85))
    s["metal_billet"] = (union((at(lambda p: box(p, (0.22, 0.95, 0.22), 0.04), axis="z", deg=55), G)), 0.85, (0, 0))
    s["metal_slab"] = (union((lambda p: box(p, (0.72, 0.18, 0.55), 0.04), G)), 0.8, (0, 0), (-0.55, -0.95, -0.85))
    s["metal_casting"] = (union((lambda p: max(cyl(p, 0.7, 0.16), -cyl(p, 0.22, 0.3)), G),
                                *[(at(lambda p: cyl(p, 0.09, 0.2), (0.45 * math.cos(a), 0, 0.45 * math.sin(a))), GD)
                                  for a in (0.5, 2.1, 3.7, 5.3)]), 0.8, (0, 0), (-0.55, -0.95, -0.85))
    s["metal_forging"] = (union((at(lambda p: cyl(p, 0.14, 0.75), axis="z", deg=90), G),
                                (at(lambda p: box(p, (0.12, 0.32, 0.28), 0.03), (-0.3, -0.12, 0)), GD),
                                (at(lambda p: box(p, (0.12, 0.32, 0.28), 0.03), (0.3, 0.12, 0)), GD)), 0.62, (0, 0))
    # Components (coloured).
    s["combustion_chamber"] = (union((lambda p: cyl(add(p, (0, -0.2, 0)), 0.42, 0.42), STEEL),
                                     (lambda p: cone(add(p, (0, 0.45, 0)), 0.2, 0.42, 0.22), STEEL),
                                     (at(lambda p: cyl(p, 0.52, 0.06), (0, 0.62, 0)), DARK),
                                     (at(lambda p: torus(p, 0.43, 0.05), (0, 0.15, 0)), ORANGE)), 0.8, (0, 0))
    s["nozzle"] = (union((lambda p: max(cone(p, 0.62, 0.22, 0.55), -cone(p, 0.55, 0.15, 0.62)), STEEL),
                         (at(lambda p: cyl(p, 0.3, 0.08), (0, 0.6, 0)), DARK),
                         (at(lambda p: torus(p, 0.6, 0.05), (0, -0.52, 0)), ORANGE)), 0.8, (0, 0))
    s["turbopump"] = (union((lambda p: cyl(p, 0.36, 0.5), STEEL),
                            (at(lambda p: torus(p, 0.45, 0.17), (0, 0.15, 0)), DARK),
                            (at(lambda p: cyl(p, 0.12, 0.35), (0.62, 0.15, 0), "z", 90), STEEL),
                            (at(lambda p: cyl(p, 0.44, 0.05), (0, -0.5, 0)), ORANGE)), 0.85, (0, 0))
    s["heat_exchanger"] = (union((lambda p: box(p, (0.55, 0.3, 0.4), 0.03), STEEL),
                                 *[(at(lambda p: box(p, (0.05, 0.42, 0.46)), (x, 0, 0)), COPPER) for x in (-0.36, 0, 0.36)],
                                 (at(lambda p: cyl(p, 0.09, 0.2), (-0.38, 0.5, 0)), DARK), (at(lambda p: cyl(p, 0.09, 0.2), (0.38, 0.5, 0)), DARK)), 0.8, (0, 0))
    s["magnet_coil"] = (union((lambda p: cyl(p, 0.18, 0.7), STEEL),
                              *[(at(lambda p: torus(p, 0.38, 0.13), (0, y, 0)), COPPER) for y in (-0.36, -0.12, 0.12, 0.36)],
                              (at(lambda p: cyl(p, 0.55, 0.05), (0, 0.62, 0)), DARK), (at(lambda p: cyl(p, 0.55, 0.05), (0, -0.62, 0)), DARK)), 0.8, (0, 0))
    s["pressure_vessel"] = (union((lambda p: capsule(p, 0.4, 0.38), WHITE),
                                  (at(lambda p: torus(p, 0.4, 0.05), (0, 0.05, 0)), ORANGE),
                                  (at(lambda p: cyl(p, 0.1, 0.15), (0, 0.85, 0)), DARK)), 0.8, (0, 0))
    def frame(p):
        d = 1e9
        for a in (-0.5, 0.5):
            for b in (-0.5, 0.5):
                d = min(d, box(sub(p, (a, 0, b)), (0.07, 0.55, 0.07)),
                        box(sub(p, (a, b * 1.1, 0)), (0.07, 0.07, 0.55)),
                        box(sub(p, (0, b * 1.1, a)), (0.55, 0.07, 0.07)))
        return d
    s["structural_frame"] = (union((frame, STEEL)), 0.85, (0, 0))
    s["gas_cylinder"] = (union((lambda p: capsule(p, 0.3, 0.55), (90, 130, 170)),
                               (at(lambda p: cyl(p, 0.1, 0.12), (0, 0.95, 0)), DARK),
                               (at(lambda p: box(p, (0.18, 0.04, 0.04)), (0, 1.05, 0)), RED),
                               (at(lambda p: torus(p, 0.3, 0.04), (0, 0.3, 0)), WHITE)), 0.98, (0, 0.1))
    return s


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, spec in sprites().items():
        scene, scale, shift = spec[:3]
        view = spec[3] if len(spec) > 3 else None
        render(scene, scale, shift, view).save(os.path.join(OUT, name + ".png"))
    print(f"{len(sprites())} item sprites")


if __name__ == "__main__":
    main()
