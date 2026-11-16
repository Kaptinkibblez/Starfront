#!/usr/bin/env python3
"""Generates Starwake's trade hub builds (M7) as blueprint-style JSON:
data/starwake/starwake/hub_template/<id>.json

The builds are composed procedurally from shaped modules (octagonal hulls with ribs and bevelled
lips, domes, capsules, tubes) and then furnished room by room, so they read as designed places
instead of boxes: layered hull plating with seams and greebles, window bands, catwalks, cargo,
landing pads with markings, terminal kiosks, lounges, quarters, plants and lights.

Format (shared with ship blueprints, plus hub metadata):
  palette: char -> block state (assigned automatically; any BMP character).
  layers bottom first; rows north (z=0) to south; chars west to east.
  ' ' = leave the world as it is, '.' = force air (clears terrain inside outposts).
  pressurized: [[x0,y0,z0,x1,y1,z1], ...] inclusive boxes with breathable air.
  beacon: [x,y,z] where the HUD marker points (hangar mouth / airlock).
  ground: for outposts, the layer at the surface (the first air layer; layers below replace terrain).

Placement runs a shape-update pass afterwards, so panes, bars, walls, fences and stairs connect.

Usage: python3 tools/hub_templates.py
"""
import json
import math
import os
import random

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "data", "starwake", "starwake", "hub_template")
AIR = "."


def S(block, **props):
    """A block state string; plain names are minecraft:, 'sw:' names are starwake:."""
    if block.startswith("sw:"):
        block = "starwake:" + block[3:]
    elif ":" not in block:
        block = "minecraft:" + block
    if props:
        block += "[" + ",".join(f"{k}={v}" for k, v in props.items()) + "]"
    return block


OPP = {"north": "south", "south": "north", "east": "west", "west": "east"}


class Grid:
    """Cells of a build. M9: every cell remembers the tier that set it last (``self.tier``), so one design writes a
    base template and its tier-2/3 expansion templates."""
    def __init__(self, sx, sy, sz):
        self.sx, self.sy, self.sz = sx, sy, sz
        self.c = {}
        self.t = {}
        self.tier = 1

    def inb(self, x, y, z):
        return 0 <= x < self.sx and 0 <= y < self.sy and 0 <= z < self.sz

    def set(self, x, y, z, s):
        if s is not None and self.inb(x, y, z):
            self.c[(x, y, z)] = s
            self.t[(x, y, z)] = self.tier

    def only(self, tier):
        """A copy holding only the cells this tier set."""
        g = Grid(self.sx, self.sy, self.sz)
        g.c = {k: v for k, v in self.c.items() if self.t.get(k, 1) == tier}
        return g

    def get(self, x, y, z):
        return self.c.get((x, y, z))

    def box(self, x0, y0, z0, x1, y1, z1, s):
        for y in range(min(y0, y1), max(y0, y1) + 1):
            for z in range(min(z0, z1), max(z0, z1) + 1):
                for x in range(min(x0, x1), max(x0, x1) + 1):
                    self.set(x, y, z, s)

    def encode(self):
        states = sorted(set(v for v in self.c.values() if v != AIR))
        chars = [chr(c) for c in range(33, 127) if chr(c) not in '."\\'] + [chr(c) for c in range(0xC0, 0x2FF)]
        assert len(states) <= len(chars), "too many block states"
        palette = {chars[i]: s for i, s in enumerate(states)}
        rev = {s: ch for ch, s in palette.items()}
        rev[AIR] = "."
        layers = []
        for y in range(self.sy):
            rows = []
            for z in range(self.sz):
                rows.append("".join(rev[self.c[(x, y, z)]] if (x, y, z) in self.c else " " for x in range(self.sx)))
            layers.append(rows)
        return palette, layers


LIGHTS = ("light_panel", "sea_lantern", "lantern", "end_rod", "froglight", "glowstone", "redstone_lamp", "shroomlight")


def write(name, grid, pressurized, beacon, ground=0, lights=False):
    os.makedirs(OUT, exist_ok=True)
    palette, layers = grid.encode()
    data = {"name": {"translate": f"hub_template.starwake.{name}"}, "palette": palette, "layers": layers,
            "pressurized": pressurized, "beacon": beacon, "ground": ground}
    if lights:
        # Expansions list their light blocks: a hub that declines below this tier dims them.
        data["lights"] = sorted([list(k) for k, v in grid.c.items() if any(l in v for l in LIGHTS)])
    with open(os.path.join(OUT, f"{name}.json"), "w") as f:
        json.dump(data, f, separators=(",", ":"))
    blocks = sum(1 for v in grid.c.values() if v != AIR)
    print(f"{name}: {grid.sx}x{grid.sy}x{grid.sz}, {blocks} blocks, {len(palette)} states")


# ---------------------------------------------------------------------------------------------
# Materials
# ---------------------------------------------------------------------------------------------

def style(**over):
    m = dict(
        hull=S("sw:station_panel"), hull2=S("white_concrete"), inner=S("sw:station_panel"), trim=S("smooth_quartz"),
        trim_slab="smooth_quartz_slab", trim_stairs="smooth_quartz_stairs",
        dark=S("polished_deepslate"), dark_slab="polished_deepslate_slab", dark_stairs="polished_deepslate_stairs",
        dark_wall=S("polished_deepslate_wall"), tile=S("deepslate_tiles"),
        belly=S("gray_concrete"), metal=S("iron_block"), accent=S("light_blue_concrete"), accent_dark=S("cyan_terracotta"),
        glass=S("light_blue_stained_glass"), pane=S("light_blue_stained_glass_pane"), screen=S("black_stained_glass"),
        deck=S("sw:station_floor"), seam=S("gray_concrete"), floor=S("smooth_stone"), floor2=S("polished_andesite"),
        light=S("sw:light_panel"), lamp=S("sea_lantern"), rim=S("sea_lantern"), core=S("sea_lantern"),
        panel=S("blue_concrete"), panel_line=S("light_gray_concrete"), panel_frame=S("iron_block"),
        door="iron_door", button="polished_blackstone_button",
    )
    m.update(over)
    return m


PORT = style()
REFINERY = style(accent=S("orange_concrete"), accent_dark=S("brown_terracotta"), glass=S("light_gray_stained_glass"),
                 pane=S("light_gray_stained_glass_pane"), hull2=S("light_gray_concrete"), rim=S("ochre_froglight"))


def stairs(mat, facing, half="bottom"):
    return S(mat, facing=facing, half=half)


def slab(mat, t="bottom"):
    return S(mat, type=t)


def hazard(x, z):
    return S("yellow_concrete") if ((x + z) // 2) % 2 == 0 else S("black_concrete")


# ---------------------------------------------------------------------------------------------
# Shapes
# ---------------------------------------------------------------------------------------------

def chamfer(k):
    return math.floor(k * 1.42 + 0.5)


def oct_xy(u, y, k, w, yb, yt, cb, ct):
    """Octagonal cross-section (x-y) grown by k layers."""
    d = chamfer(k)
    return (abs(u) <= w + k and yb - k <= y <= yt + k
            and abs(u) + y - (yt - ct) <= w + d and abs(u) + (yb + cb) - y <= w + d)


def oct_plan(dx, dz, k, r, c):
    """Octagonal footprint (x-z) of half-size r and chamfer c grown by k layers."""
    d = chamfer(k)
    return abs(dx) <= r + k and abs(dz) <= r + k and abs(dx) + abs(dz) <= 2 * r - c + d


def layer_of(fn, maxk):
    for k in range(0, maxk + 1):
        if fn(k):
            return k
    return None


# ---------------------------------------------------------------------------------------------
# Furniture and details
# ---------------------------------------------------------------------------------------------

def table(g, x, y, z, m, chairs=("north", "south", "east", "west")):
    """A one-block table (top slab) with stair chairs around it, backs away from the table."""
    g.set(x, y, z, slab(m["trim_slab"], "top"))
    for d in chairs:
        ox, oz = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}[d]
        g.set(x + ox, y, z + oz, stairs("spruce_stairs", d))


def planter(g, x0, y, z0, w=2, d=2, plants=("flowering_azalea", "azalea", "fern", "flowering_azalea")):
    """A raised planter with a quartz rim and shrubs on moss."""
    for x in range(x0 - 1, x0 + w + 1):
        for z in range(z0 - 1, z0 + d + 1):
            if x0 <= x < x0 + w and z0 <= z < z0 + d:
                g.set(x, y, z, S("moss_block"))
                g.set(x, y + 1, z, S(plants[(x * 3 + z) % len(plants)]))
            else:
                g.set(x, y, z, slab("smooth_quartz_slab", "bottom"))


def container(g, x0, y0, z0, color, axis="z", length=6):
    """A shipping container: 3x3 section, ribbed sides, dark corner frame, barred doors at the ends."""
    for i in range(length):
        for a in range(3):
            for b in range(3):
                x, z = (x0 + a, z0 + i) if axis == "z" else (x0 + i, z0 + a)
                y = y0 + b
                edge_a, edge_b, end = a in (0, 2), b in (0, 2), i in (0, length - 1)
                if end and (edge_a or edge_b):
                    s = S("polished_deepslate")
                elif end:
                    s = S("iron_block")
                elif edge_a and edge_b:
                    s = S("polished_deepslate")
                else:
                    s = S(f"{color}_concrete") if i % 2 == 0 else S(f"{color}_wool")
                g.set(x, y, z, s)


def terminal_bank(g, m, cx, y, cz, facing, kind, n=3, spacing=3):
    """A kiosk wall: plinth counter, n terminals at eye height, screens above, an accent header with
    downlights. (cx, cz) is the middle of the counter; the bank runs along the wall."""
    along = (1, 0) if facing in ("north", "south") else (0, 1)
    half = (n - 1) * spacing // 2 + 2
    term = S(f"sw:{kind}", facing=facing)
    for i in range(-half, half + 1):
        x, z = cx + along[0] * i, cz + along[1] * i
        on_term = i % spacing == 0 and abs(i) <= (n - 1) * spacing // 2
        g.set(x, y, z, m["dark"])
        if on_term:
            g.set(x, y + 1, z, term)
            g.set(x, y + 2, z, m["screen"])
            g.set(x, y + 3, z, m["screen"])
            g.set(x, y + 4, z, m["light"])
        else:
            g.set(x, y + 1, z, slab(m["trim_slab"]))
            edge = abs(i) == half
            g.set(x, y + 2, z, m["dark"] if edge else None)
            g.set(x, y + 3, z, m["dark"] if edge else None)
            g.set(x, y + 4, z, m["accent"])


def light_post(g, x, y, z, h=3, top=None, wall="polished_deepslate_wall"):
    """A slim post: a wall column with a cap and an end-rod light."""
    for i in range(h):
        g.set(x, y + i, z, S(wall))
    g.set(x, y + h, z, top or S("polished_deepslate_slab", type="bottom"))
    g.set(x, y + h + 1, z, S("end_rod", facing="up"))


def dish(g, x, y, z, r, m, stalk=3):
    """An upward-facing parabolic dish on a pedestal with a feed horn."""
    for i in range(stalk):
        g.set(x, y + i, z, m["dark"])
    base = y + stalk
    for dx in range(-r - 1, r + 2):
        for dz in range(-r - 1, r + 2):
            rr = math.hypot(dx, dz)
            if rr > r + 0.4:
                continue
            h = int(round(rr * rr / (2.2 * r)))
            rim = rr > r - 0.6
            g.set(x + dx, base + h, z + dz, slab(m["trim_slab"]) if rim else m["trim"])
    for i in range(1, r // 2 + 2):
        g.set(x, base + i, z, S("lightning_rod", facing="up"))
    g.set(x, base + r // 2 + 2, z, S("end_rod", facing="up"))


def mast(g, x, y, z, h, m):
    for i in range(h):
        g.set(x, y + i, z, m["dark_wall"])
        if i % 4 == 2:
            for d, (ox, oz) in (("east", (1, 0)), ("west", (-1, 0)), ("south", (0, 1)), ("north", (0, -1))):
                if i < h - 3:
                    g.set(x + ox, y + i, z + oz, S("lightning_rod", facing=d))
    g.set(x, y + h, z, S("lightning_rod", facing="up"))
    g.set(x, y + h + 1, z, S("end_rod", facing="up"))


def landing_marks(g, cx, y, cz, half, m, surface=None):
    """A square pad: hazard-striped border, corner lights, a target circle and a centre light."""
    for dx in range(-half, half + 1):
        for dz in range(-half, half + 1):
            x, z = cx + dx, cz + dz
            edge = max(abs(dx), abs(dz)) == half
            rr = math.hypot(dx, dz)
            if edge:
                s = m["lamp"] if abs(dx) == half and abs(dz) == half else hazard(x, z)
            elif abs(rr - (half - 2.2)) < 0.5:
                s = S("white_concrete")
            elif dx == 0 and dz == 0:
                s = m["light"]
            elif (dx == 0 or dz == 0) and rr < half - 3:
                s = S("light_gray_concrete")
            else:
                s = surface or S("gray_concrete")
            g.set(x, y, z, s)


# ---------------------------------------------------------------------------------------------
# Surface outposts
# ---------------------------------------------------------------------------------------------

class Module:
    """A volume: inside() is open interior, shell() gives a wall material (or None)."""

    def __init__(self, inside, shell, box):
        self.inside, self.shell, self.box = inside, shell, box


def dome_module(cx, fy, cz, r, m, glass_band=(0.35, 0.65), all_glass=False, ribs=8):
    """A hemispherical dome on floor y fy: meridian ribs, a window band and a lit oculus."""
    def dist(x, y, z):
        return math.sqrt((x - cx) ** 2 + (y - fy) ** 2 + (z - cz) ** 2)

    def inside(x, y, z):
        return y > fy and dist(x, y, z) < r - 0.5

    def shell(x, y, z):
        if y < fy:
            return None
        d = dist(x, y, z)
        if y == fy:
            return m["dark"] if r - 0.5 <= d < r + 0.6 else None
        if not (r - 0.5 <= d < r + 0.6):
            return None
        h = (y - fy) / r
        ang = math.degrees(math.atan2(z - cz, x - cx)) % (360 / ribs)
        rib = ang < 360 / ribs * 0.18 or ang > 360 / ribs * 0.82
        if h > 0.9:
            return m["light"] if math.hypot(x - cx, z - cz) < 1.5 else m["dark"]
        if y == fy + 1:
            return m["dark"]
        if rib:
            return m["dark"]
        if all_glass:
            return m["glass"]
        if glass_band[0] <= h <= glass_band[1]:
            return m["glass"]
        return m["hull"] if (y - fy) % 4 else m["hull2"]
    return Module(inside, shell, (cx - r - 1, fy, cz - r - 1, cx + r + 1, fy + r + 1, cz + r + 1))


def capsule_module(x0, x1, fy, cz, r, m, axis="x"):
    """A horizontal cylinder (along x or z) standing on floor fy: hull rings every 4, a window row."""
    ay = fy + r - 1.5

    def coords(x, z):
        return (x, z) if axis == "x" else (z, x)

    def dist(x, y, z):
        a, b = coords(x, z)
        return math.hypot(y - ay, b - cz), a

    def inside(x, y, z):
        d, a = dist(x, y, z)
        return y > fy and x0 < a < x1 and d < r - 1

    def shell(x, y, z):
        if y < fy:
            return None
        d, a = dist(x, y, z)
        if not (x0 <= a <= x1) or d >= r + 0.3:
            return None
        if a in (x0, x1):
            return m["dark"] if d >= r - 1 else (m["hull"] if y > fy else m["dark"])
        if y == fy:
            return m["dark"]
        if d < r - 1:
            return None
        if (a - x0) % 4 == 0:
            return m["dark"]
        if abs(y - (fy + 2)) <= 0 and d > r - 1 and (a - x0) % 4 in (1, 2, 3) and abs(cz - coords(x, z)[1]) > 1:
            return m["glass"]
        return m["hull"]
    lo, hi = (x0, cz - r) if axis == "x" else (cz - r, x0)
    box = (x0, fy, cz - r, x1, fy + 2 * r, cz + r) if axis == "x" else (cz - r, fy, x0, cz + r, fy + 2 * r, x1)
    return Module(inside, shell, box)


def tube_module(x0, z0, x1, z1, fy, m):
    """A straight corridor (along x or z): 3 wide, 3 tall inside, bevelled roof, ribbed."""
    along_x = z0 == z1

    def local(x, z):
        return (x, z - z0) if along_x else (z, x - x0)

    def inside(x, y, z):
        a, c = local(x, z)
        lo, hi = (min(x0, x1), max(x0, x1)) if along_x else (min(z0, z1), max(z0, z1))
        return lo <= a <= hi and abs(c) <= 1 and fy < y <= fy + 3

    def shell(x, y, z):
        a, c = local(x, z)
        lo, hi = (min(x0, x1), max(x0, x1)) if along_x else (min(z0, z1), max(z0, z1))
        if not (lo <= a <= hi) or abs(c) > 2 or y < fy or y > fy + 4:
            return None
        if y == fy:
            return m["dark"]
        if abs(c) == 2 and y == fy + 4:
            return None
        rib = (a - lo) % 4 == 0
        if rib:
            return m["dark"]
        if abs(c) == 2 and y == fy + 2:
            return m["glass"]
        return m["hull"]
    if along_x:
        box = (min(x0, x1), fy, z0 - 2, max(x0, x1), fy + 4, z0 + 2)
    else:
        box = (x0 - 2, fy, min(z0, z1), x0 + 2, fy + 4, max(z0, z1))
    return Module(inside, shell, box)


def compose(g, modules, fill_below=None, ground=None):
    """Builds modules: interiors win over shells (so joined modules open into each other); floors of
    interiors get a deck; under every module the ground is filled down to the bottom layer."""
    xs0 = min(mo.box[0] for mo in modules); xs1 = max(mo.box[3] for mo in modules)
    ys0 = min(mo.box[1] for mo in modules); ys1 = max(mo.box[4] for mo in modules)
    zs0 = min(mo.box[2] for mo in modules); zs1 = max(mo.box[5] for mo in modules)
    for x in range(xs0, xs1 + 1):
        for z in range(zs0, zs1 + 1):
            for y in range(ys0, ys1 + 1):
                if any(mo.inside(x, y, z) for mo in modules):
                    g.set(x, y, z, AIR)
                    continue
                for mo in modules:
                    s = mo.shell(x, y, z)
                    if s:
                        g.set(x, y, z, s)
                        break


def foundation(g, x0, z0, x1, z1, top, m, pillars_only=False):
    """Fills under a footprint down to layer 0: tuff with a brick skirt (or pillars on a grid)."""
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = x in (x0, x1) or z in (z0, z1)
            if pillars_only and not ((x - x0) % 4 == 0 and (z - z0) % 4 == 0 or x in (x0, x1) and z in (z0, z1)):
                continue
            for y in range(0, top):
                g.set(x, y, z, S("tuff_bricks") if edge or pillars_only else S("tuff"))


def tower(g, x, y, z, h, m):
    """A square lattice tower: corner posts, cross bracing every 4, a platform on top."""
    for i in range(h):
        for ox, oz in ((-1, -1), (1, -1), (-1, 1), (1, 1)):
            g.set(x + ox, y + i, z + oz, m["dark_wall"])
        if i % 4 == 3:
            for ox in (-1, 0, 1):
                for oz in (-1, 0, 1):
                    if ox == 0 or oz == 0:
                        g.set(x + ox, y + i, z + oz, S("iron_bars") if ox or oz else m["dark"])
    for ox in range(-2, 3):
        for oz in range(-2, 3):
            g.set(x + ox, y + h - 1, z + oz, slab(m["dark_slab"], "top"))
    for ox, oz in ((-2, -2), (2, 2)):
        g.set(x + ox, y + h, z + oz, S("end_rod", facing="up"))


def silo(g, x, y, z, r, h, m):
    """A round silo: white body with dark bands, a stepped copper cap, a catwalk ring and a ladder."""
    for i in range(h + 3):
        for dx in range(-4, 5):
            for dz in range(-4, 5):
                rr = math.hypot(dx, dz)
                if i < h and r - 0.7 <= rr < r + 0.5:
                    s = m["dark"] if i % 4 == 0 else S("white_concrete") if (dx + dz) % 3 else S("light_gray_concrete")
                    g.set(x + dx, y + i, z + dz, s)
                elif i == h and rr < r + 0.5:
                    g.set(x + dx, y + i, z + dz, S("waxed_cut_copper"))
                    if r + 0.5 <= rr + 1.0:
                        g.set(x + dx, y + i + 1, z + dz, S("iron_bars") if rr > r - 0.2 else None)
                elif i == h + 1 and rr < r - 0.8:
                    g.set(x + dx, y + i, z + dz, S("waxed_cut_copper_slab", type="bottom") if rr > 0.5 else S("waxed_cut_copper"))
                elif i == h + 2 and rr < 0.5:
                    g.set(x + dx, y + i, z + dz, S("lightning_rod", facing="up"))
    for i in range(1, h):
        g.set(x, y + i, z + int(r + 1.5), S("ladder", facing="south"))


def solar_field(g, x0, y, z0, cols, rows, m):
    """Tilted ground-mount solar panels: rows of daylight detectors on stair backs, on posts."""
    for c in range(cols):
        for r in range(rows):
            bx, bz = x0 + c * 4, z0 + r * 5
            for dx in range(3):
                g.set(bx + dx, y, bz + 1, m["dark_wall"])
                g.set(bx + dx, y + 1, bz, stairs(m["dark_stairs"], "south", "top"))
                g.set(bx + dx, y + 1, bz + 1, slab(m["dark_slab"], "top"))
                g.set(bx + dx, y + 2, bz + 1, S("daylight_detector"))
                g.set(bx + dx, y + 2, bz, S("daylight_detector"))




def write_tiers(name, g, pressurized, beacon, ground=0):
    """Writes <name> (tier 1) and <name>_t2 / <name>_t3 (only what each tier adds) on one canvas."""
    write(name, g.only(1), pressurized.get(1, []), beacon, ground)
    for tier in (2, 3):
        write(f"{name}_t{tier}", g.only(tier), pressurized.get(tier, []), beacon, ground, lights=True)
