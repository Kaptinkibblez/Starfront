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
  npcs: [{"pos": [x,y,z], "role": "guard"|"crew"}]
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
    def __init__(self, sx, sy, sz):
        self.sx, self.sy, self.sz = sx, sy, sz
        self.c = {}

    def inb(self, x, y, z):
        return 0 <= x < self.sx and 0 <= y < self.sy and 0 <= z < self.sz

    def set(self, x, y, z, s):
        if s is not None and self.inb(x, y, z):
            self.c[(x, y, z)] = s

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


def write(name, grid, pressurized, npcs, beacon, ground=0):
    os.makedirs(OUT, exist_ok=True)
    palette, layers = grid.encode()
    data = {"name": {"translate": f"hub_template.starwake.{name}"}, "palette": palette, "layers": layers,
            "pressurized": pressurized, "npcs": npcs, "beacon": beacon, "ground": ground}
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
# Orbital station
# ---------------------------------------------------------------------------------------------

def station(name, m, variant):
    """An orbital station: an octagonal hangar bay (open south mouth held by a pressure field) and an
    octagonal two-deck core (concourse with terminal kiosks round a reactor atrium; control room
    over the hangar, quarters and lounge above), a stepped roof with a mast and comm dish, and
    side wings (solar arrays for a port, tanks and radiators for a refinery)."""
    SX, SY, SZ = 107, 46, 87
    cx, F = 53, 12
    W, YB, YT, CB, CT = 17, 7, 31, 4, 7
    HZ0, HZ1 = 36, 82          # hangar interior z (mouth plane at HZ1)
    CZ, R, C = 18, 15, 6       # core centre z, half-size, chamfer
    D1, D2, ROOF = 12, 19, 26  # deck floors and roof
    g = Grid(SX, SY, SZ)
    rnd = random.Random(name)
    ribs = set(range(HZ0 + 4, HZ1, 6))
    hk = lambda u, y, k: oct_xy(u, y, k, W, YB, YT, CB, CT)

    # --- Core (walls, decks, belly, roof) ---------------------------------------------------------
    for dz in range(-R - 4, R + 5):
        for dx in range(-R - 4, R + 5):
            x, z = cx + dx, CZ + dz
            k = layer_of(lambda kk: oct_plan(dx, dz, kk, R, C), 3)
            if k is None:
                continue
            diag = abs(dx) < R and abs(dz) < R
            corner = k == 3 and ((abs(dx) == R + 3 and abs(dz) in (R - C, R - C + 1)) or (abs(dz) == R + 3 and abs(dx) in (R - C, R - C + 1)))
            for y in range(D1, ROOF + 1):
                if k == 0:
                    if y in (D1, D2):
                        g.set(x, y, z, m["floor"] if y == D1 else m["tile"])
                    elif y == ROOF:
                        g.set(x, y, z, m["hull"])
                elif k == 1:
                    band = m["dark"] if y in (D1 + 1, D2 + 1) else m["accent"] if y in (D2 - 1, ROOF - 1) else m["inner"]
                    g.set(x, y, z, band)
                elif k == 2:
                    g.set(x, y, z, m["hull"])
                elif k == 3:
                    if y in (D1, D2, ROOF):
                        g.set(x, y, z, m["dark"])
                    elif corner:
                        g.set(x, y, z, m["dark"])
            # Window bands: deck 1 on the diagonal faces, deck 2 all round (between mullions).
            if k in (1, 2):
                for y in (14, 15, 16):
                    if diag and dx % 3 != 0:
                        g.set(x, y, z, m["glass"])
                for y in (21, 22, 23):
                    south = dz > 0 and abs(dx) <= R - C + 2
                    pos = dz if abs(dx) >= R else dx
                    if not south and pos % 4 != 0:
                        g.set(x, y, z, m["glass"])
            # Belly: an inverted stepped taper with a reactor glow ring.
            for y in range(3, D1):
                rr = R + 2 - (D1 - y)
                if not oct_plan(dx, dz, 0, rr, min(C, rr)):
                    continue
                inner = oct_plan(dx, dz, 0, rr - 1, min(C, rr - 1))
                if y == 3:
                    s = m["lamp"] if max(abs(dx), abs(dz)) <= 1 else m["dark"]
                    g.set(x, y, z, s)
                elif not inner:
                    if y == 5:
                        s = m["rim"] if (dx + dz) % 2 == 0 else m["dark"]
                    elif y >= 9:
                        s = m["hull"]
                    else:
                        s = m["belly"]
                    g.set(x, y, z, s)
            # Stepped roof.
            for t in range(1, 5):
                y = ROOF + t
                rr = R + 2 - 3 * t
                if rr < 2 or not oct_plan(dx, dz, 0, rr, min(C, rr)):
                    continue
                edge = not oct_plan(dx, dz, 0, rr - 1, min(C, rr - 1))
                if edge:
                    if abs(dx) == rr and abs(dz) < rr - C + 1:
                        s = stairs(m["trim_stairs"], "west" if dx > 0 else "east")
                    elif abs(dz) == rr and abs(dx) < rr - C + 1:
                        s = stairs(m["trim_stairs"], "north" if dz > 0 else "south")
                    else:
                        s = slab(m["trim_slab"])
                else:
                    s = m["hull2"] if t % 2 else m["hull"]
                g.set(x, y, z, s)
    for dx in range(-1, 2):
        for dz in range(-1, 2):
            g.set(cx + dx, 2, CZ + dz, S("end_rod", facing="down") if dx and dz else None)

    # --- Hangar --------------------------------------------------------------------------------
    for z in range(HZ0 - 2, HZ1 + 3):
        for y in range(YB - 5, YT + 6):
            for u in range(-W - 5, W + 6):
                x = cx + u
                k = layer_of(lambda kk: hk(u, y, kk), 4)
                if k is None:
                    continue
                if z >= HZ1:  # the mouth: a flared, lit rim
                    depth = z - HZ1
                    if depth == 0 and k == 0 and y < F:
                        g.set(x, y, z, m["dark"])
                    if k >= 1 + depth and k <= 4:
                        if depth == 0 and k == 1:
                            s = m["rim"] if (u + y) % 3 == 0 else m["metal"]
                        else:
                            s = m["dark"] if k < 4 else slab(m["dark_slab"], "top" if y > F else "bottom")
                        g.set(x, y, z, s)
                    continue
                back = z < HZ0
                if back:
                    if k <= 2:
                        g.set(x, y, z, m["hull"] if z == HZ0 - 2 else m["inner"])
                    elif k == 3:
                        g.set(x, y, z, m["dark"])
                    continue
                rib = z in ribs
                if k == 0:
                    if y == F:
                        g.set(x, y, z, None)  # deck (below)
                    elif rib and y >= YT - CT and not hk(u, y, -1):
                        g.set(x, y, z, m["dark"])
                    continue
                if k == 1:
                    g.set(x, y, z, m["dark"] if y <= F else (m["dark"] if rib else m["inner"]))
                elif k == 2:
                    if y < F:
                        s = m["belly"]
                    elif y > YT:
                        s = m["hull2"]
                    elif abs(u) > W and F + 2 <= y <= F + 3:
                        s = m["accent"]
                    else:
                        s = m["hull"]
                    g.set(x, y, z, s)
                elif k == 3:
                    if rib:
                        g.set(x, y, z, m["dark"])
                    elif rnd.random() < 0.05:
                        top = y > YT + 2
                        side = "east" if u > 0 else "west"
                        choice = rnd.random()
                        if top:
                            s = (S("iron_trapdoor", facing="north", half="bottom") if choice < 0.5
                                 else S("lightning_rod", facing="up") if choice < 0.7 else slab(m["dark_slab"]))
                        elif y < F:
                            s = S("iron_bars") if choice < 0.5 else S("end_rod", facing="down")
                        elif abs(u) > W + 2:
                            s = S("iron_trapdoor", facing=side, half="bottom", open="true") if choice < 0.6 else S("lightning_rod", facing=side)
                        else:
                            s = None
                        g.set(x, y, z, s)
                # Side windows at catwalk height, mid-bay.
                if k in (1, 2) and abs(u) > W and y in (21, 22) and (z - HZ0) % 6 in (0, 1, 2) and not rib:
                    g.set(x, y, z, m["glass"])
    # Dorsal spine.
    for z in range(HZ0 - 1, HZ1 + 1):
        for u in range(-2, 3):
            g.set(cx + u, YT + 3, z, m["metal"] if abs(u) < 2 else stairs(m["dark_stairs"], "west" if u > 0 else "east"))
        if z in ribs:
            g.set(cx, YT + 4, z, S("lightning_rod", facing="up"))
            g.set(cx - 2, YT + 4, z, S("end_rod", facing="up"))
            g.set(cx + 2, YT + 4, z, S("end_rod", facing="up"))

    # Deck.
    for z in range(HZ0, HZ1):
        for u in range(-W, W + 1):
            x = cx + u
            if z >= HZ1 - 3:
                s = S("sw:landing_pad")
            elif abs(u) == W:
                s = hazard(x, z)
            elif z in ribs:
                s = m["seam"]
            elif u == 0 and z % 3 == 0:
                s = m["light"]
            else:
                s = m["deck"]
            g.set(x, F, z, s)
    for pu in (-9, 9):
        landing_marks(g, cx + pu, F, 66, 5, m)

    # Catwalks along both walls, with brackets, railings and stairs at the back.
    CW = F + 7
    for z in range(HZ0, HZ1 - 2):
        for sgn in (-1, 1):
            for u in (W - 1, W):
                g.set(cx + sgn * u, CW, z, slab(m["dark_slab"], "top"))
            if z > HZ0 + 1:
                g.set(cx + sgn * (W - 2), CW + 1, z, S("iron_bars"))
            if z in ribs:
                g.set(cx + sgn * W, CW - 1, z, stairs(m["dark_stairs"], "east" if sgn > 0 else "west", "top"))
                for y in range(F + 1, CW - 1):
                    g.set(cx + sgn * W, y, z, m["dark"])
            elif (z - HZ0) % 6 == 1:
                g.set(cx + sgn * (W + 1), CW - 3, z, m["lamp"])
    for sgn in (-1, 1):
        for z in (HZ0, HZ0 + 1):
            for i in range(7):
                u = 8 + i
                y = F + 1 + i
                g.set(cx + sgn * u, y, z, stairs(m["dark_stairs"], "east" if sgn > 0 else "west"))
                for yy in range(F + 1, y):
                    g.set(cx + sgn * u, yy, z, m["inner"])
            g.set(cx + sgn * (W - 2), CW, z, slab(m["dark_slab"], "top"))
        g.set(cx + sgn * (W - 2), CW + 1, HZ0 + 2, S("iron_bars"))
    # Ceiling lights, ceiling rails and a gantry crane.
    for z in range(HZ0, HZ1):
        if z not in ribs:
            for u in (-5, 5):
                g.set(cx + u, YT + 1, z, m["light"])
        for u in (-9, 9):
            g.set(cx + u, YT, z, m["dark"])
    for u in range(-9, 10):
        g.set(cx + u, YT - 1, 60, m["metal"] if abs(u) < 9 else m["dark"])
        g.set(cx + u, YT - 1, 61, stairs(m["dark_stairs"], "north", "top"))
    for dz in (59, 60, 61, 62):
        for u in (-4, -3, -2):
            g.set(cx + u, YT - 2, dz, m["metal"] if dz in (60, 61) else stairs(m["dark_stairs"], "south" if dz < 60 else "north", "top"))
    for y in range(YT - 8, YT - 2):
        g.set(cx - 3, y, 60, S("iron_chain", axis="y"))
    g.set(cx - 3, YT - 9, 60, S("lightning_rod", facing="down"))
    # Cargo: containers under the catwalks, barrels and crates.
    colours = ["orange", "blue", "light_gray", "red", "cyan", "lime", "white", "brown"]
    rnd.shuffle(colours)
    ci = 0
    for sgn in (-1, 1):
        for z0 in (41, 47):
            x0 = cx + sgn * 14 - (2 if sgn > 0 else 0)
            x0 = cx + 14 if sgn > 0 else cx - 16
            container(g, x0, F + 1, z0, colours[ci % 8], length=5)
            ci += 1
            if z0 == 41:
                container(g, x0, F + 4, z0, colours[ci % 8], length=5)
                ci += 1
        for z in (53, 54):
            for u in (14, 15, 16):
                g.set(cx + sgn * u, F + 1, z, S("barrel", facing="up"))
            g.set(cx + sgn * 16, F + 2, z, S("barrel", facing="up"))
    # Fuel point between the pads: a pump with a hose.
    for z in (58,):
        g.set(cx, F + 1, z, m["metal"])
        g.set(cx, F + 2, z, S("observer", facing="south"))
        g.set(cx, F + 3, z, slab(m["dark_slab"]))
        g.set(cx + 1, F + 2, z, S("iron_chain", axis="x"))
        g.set(cx - 1, F + 2, z, S("iron_chain", axis="x"))
    # Back wall: passage to the core, control-room window, signage.
    for u in range(-9, 10):
        for y in range(F + 1, YT + 1):
            for z in (HZ0 - 2, HZ0 - 1):
                if abs(u) <= 2 and y <= F + 4:
                    g.set(cx + u, y, z, AIR)
                elif abs(u) <= 7 and 21 <= y <= 23:
                    g.set(cx + u, y, z, m["glass"])
            if abs(u) == 3 and y <= F + 5 or abs(u) <= 3 and y == F + 5:
                g.set(cx + u, y, HZ0 - 1, m["metal"])
            if abs(u) == 8 and 20 <= y <= 24 or abs(u) <= 8 and y in (20, 24):
                g.set(cx + u, y, HZ0 - 1, m["dark"])
            if abs(u) <= 6 and y in (27, 28):
                g.set(cx + u, y, HZ0 - 1, m["accent"])
    for u in (-4, 4):
        g.set(cx + u, F + 3, HZ0, m["lamp"])

    # --- Core interior --------------------------------------------------------------------------
    # Atrium: a reactor column in a glass sleeve through both decks.
    for y in range(D1 + 1, ROOF):
        for dx in range(-1, 2):
            for dz in range(-1, 2):
                g.set(cx + dx, y, CZ + dz, m["core"] if dx == 0 and dz == 0 else m["glass"])
    for dx in range(-2, 3):
        for dz in range(-2, 3):
            if max(abs(dx), abs(dz)) == 2:
                g.set(cx + dx, D1 + 1, CZ + dz, slab(m["dark_slab"]))
    for dx in range(-4, 5):
        for dz in range(-4, 5):
            c = max(abs(dx), abs(dz))
            if c in (2, 3):
                g.set(cx + dx, D2, CZ + dz, AIR)
            elif c == 4:
                g.set(cx + dx, D2 + 1, CZ + dz, m["pane"])
    g.set(cx, ROOF, CZ, m["core"])
    # Concourse floor: a lit accent ring and quartz walkways.
    for dx in range(-R, R + 1):
        for dz in range(-R, R + 1):
            if not oct_plan(dx, dz, 0, R, C):
                continue
            c = max(abs(dx), abs(dz))
            if c == 6:
                g.set(cx + dx, D1, CZ + dz, m["light"] if (dx + dz) % 4 == 0 else m["accent"])
            elif c < 6 and (dx == 0 or dz == 0) and c > 2:
                g.set(cx + dx, D1, CZ + dz, m["trim"])
            elif c > 6 and (abs(dx) <= 1 or abs(dz) <= 1):
                g.set(cx + dx, D1, CZ + dz, m["trim"])
            if dx % 5 == 0 and dz % 5 == 0 and c > 4 and g.get(cx + dx, D2, CZ + dz) == m["tile"]:
                g.set(cx + dx, D2, CZ + dz, m["light"])
            if dx % 5 == 0 and dz % 5 == 0 and c > 4:
                g.set(cx + dx, ROOF, CZ + dz, m["light"])
    # Terminal kiosks: commodities west, outfitter east, contracts north.
    terminal_bank(g, m, cx - R, D1 + 1, CZ, "east", "commodity_terminal")
    terminal_bank(g, m, cx + R, D1 + 1, CZ - 3, "west", "outfitter_terminal")
    terminal_bank(g, m, cx, D1 + 1, CZ - R, "south", "contract_board")
    # Lounge tables and planters.
    for tx, tz in ((-7, -7), (7, -8), (-7, 7)):
        table(g, cx + tx, D1 + 1, CZ + tz, m)
    planter(g, cx + 9, D1 + 1, CZ + 5, 2, 2)
    planter(g, cx - 11, D1 + 1, CZ - 11, 2, 2)
    planter(g, cx + 8, D1 + 1, CZ - 12, 2, 2)
    for dx, dz in ((-3, 13), (3, 13)):
        g.set(cx + dx, D1 + 1, CZ + dz, S("potted_fern"))
    # Stairs to deck 2 along the south wall (ascending west).
    for dz in (13, 14):
        for i in range(7):
            dx = 10 - i
            y = D1 + 1 + i
            g.set(cx + dx, y, CZ + dz, stairs("polished_deepslate_stairs", "west"))
            for yy in range(D1 + 1, y):
                g.set(cx + dx, yy, CZ + dz, m["inner"])
            if dx >= 5:
                g.set(cx + dx, D2, CZ + dz, AIR if dx > 4 else None)
    for dx in range(5, 11):
        g.set(cx + dx, D2 + 1, CZ + 12, m["pane"])
    g.set(cx + 11, D2 + 1, CZ + 13, m["pane"])
    # Passage from the hangar.
    for dx in range(-2, 3):
        for y in range(D1 + 1, D1 + 5):
            g.set(cx + dx, y, CZ + R + 1, AIR)
            g.set(cx + dx, y, CZ + R + 2, AIR)
    # Deck 2: control room over the hangar.
    for dx in range(-9, -2):
        g.set(cx + dx, D2 + 1, CZ + 14, stairs(m["dark_stairs"], "south", "top"))
        g.set(cx + dx, D2 + 2, CZ + 14, S("daylight_detector"))
        if dx % 2:
            g.set(cx + dx, D2 + 1, CZ + 12, stairs("spruce_stairs", "north"))
    for dx in (-2, 2):
        g.set(cx + dx, D2 + 1, CZ + 14, S("observer", facing="north"))
    # Quarters along the north wall: bunks with lockers and lamps.
    for dx in (-8, -5, 5, 8):
        g.set(cx + dx, D2 + 1, CZ - 15, S("light_gray_bed", part="head", facing="north"))
        g.set(cx + dx, D2 + 1, CZ - 14, S("light_gray_bed", part="foot", facing="north"))
        for o in (-1, 1):
            if g.get(cx + dx + o, D2 + 1, CZ - 15) is None:
                g.set(cx + dx + o, D2 + 1, CZ - 15, S("barrel", facing="up"))
                g.set(cx + dx + o, D2 + 2, CZ - 15, S("lantern"))
    for dx in range(-2, 3):
        g.set(cx + dx, D2 + 1, CZ - 15, [S("smoker", facing="south"), S("barrel", facing="south"), S("cauldron"),
                                          S("barrel", facing="south"), S("brewing_stand")][dx + 2])
        g.set(cx + dx, D2 + 1, CZ - 13, slab(m["trim_slab"], "top"))
    # Lounge west: a sofa, coffee table and bookshelves.
    for dz in range(-4, 5):
        g.set(cx - 14, D2 + 1, CZ + dz, stairs("light_gray_wool" if False else "spruce_stairs", "west"))
        g.set(cx - 15, D2 + 1, CZ + dz, S("bookshelf") if dz % 2 else S("light_gray_wool"))
    for dz in (-4, 4):
        g.set(cx - 14, D2 + 1, CZ + dz, S("spruce_trapdoor", facing="north" if dz < 0 else "south", half="bottom", open="true"))
    for dz in (-1, 0, 1):
        g.set(cx - 11, D2 + 1, CZ + dz, slab("spruce_slab", "top"))
    g.set(cx - 11, D2 + 2, CZ, S("potted_azalea_bush"))
    # Engineering east: workbenches and stores.
    for dz, s in zip(range(-5, 6), [S("smithing_table"), S("crafting_table"), S("anvil", facing="north"),
                                     S("blast_furnace", facing="west"), S("barrel", facing="west"), S("barrel", facing="west"),
                                     S("barrel", facing="up"), S("grindstone", face="floor", facing="west"),
                                     S("stonecutter", facing="west"), S("cartography_table"), S("loom", facing="west")]):
        g.set(cx + 15, D2 + 1, CZ + dz, s)
    for dz in (-5, -3, 3, 5):
        g.set(cx + 15, D2 + 2, CZ + dz, S("barrel", facing="west"))

    # Deck 2 middle: tables and planters round the atrium.
    for tx, tz in ((-7, -6), (7, -6), (-7, 6)):
        table(g, cx + tx, D2 + 1, CZ + tz, m)
    planter(g, cx + 6, D2 + 1, CZ + 5, 2, 2)
    for dx, dz in ((0, -6), (0, 6), (-6, 0), (6, 0)):
        g.set(cx + dx, D2 + 1, CZ + dz, S("potted_fern") if dx else S("potted_azalea_bush"))
    # --- Roof: mast and comm dish ---------------------------------------------------------------
    mast(g, cx - 2, ROOF + 5, CZ - 1, 10, m)
    dish(g, cx + 7, ROOF + 2, CZ - 6, 4, m, stalk=4)

    # --- Wings ---------------------------------------------------------------------------------
    ty = 15
    for sgn in (-1, 1):
        x_from = cx + sgn * (R + 3)
        x_to = 2 if sgn < 0 else SX - 3
        for x in range(min(x_from, x_to), max(x_from, x_to) + 1):
            for oy in (-1, 1):
                for oz in (-1, 1):
                    g.set(x, ty + oy, CZ + oz, m["dark"])
            for oy in (-1, 0, 1):
                for oz in (-1, 0, 1):
                    if (oy == 0) != (oz == 0):
                        g.set(x, ty + oy, CZ + oz, S("iron_bars"))
            if (x - cx) % 8 == 0:
                for oy in (-1, 0, 1):
                    for oz in (-1, 0, 1):
                        g.set(x, ty + oy, CZ + oz, m["metal"] if oy or oz else m["lamp"])
        tip = x_to + sgn
        for oy in range(-1, 2):
            for oz in range(-1, 2):
                g.set(tip, ty + oy, CZ + oz, m["dark"])
        g.set(tip + sgn, ty, CZ, S("verdant_froglight") if sgn > 0 else S("pearlescent_froglight"))
        if variant == "port":
            for a0 in (R + 6, R + 21):
                xs = [cx + sgn * a for a in range(a0, a0 + 13)]
                for half in (-1, 1):
                    for y in (range(ty - 13, ty - 1) if half < 0 else range(ty + 2, ty + 14)):
                        for x in xs:
                            i, j = abs(x - xs[0]), abs(y - ty)
                            frame = i in (0, 12) or j in (2, 13)
                            line = i % 4 == 0 or (j - 2) % 4 == 0
                            g.set(x, y, CZ, m["panel_frame"] if frame else m["panel_line"] if line else m["panel"])
                    mid = xs[6]
                    g.set(mid, ty + 2 * half, CZ, m["metal"])
        else:
            if sgn > 0:
                # Tank farm: three copper tanks with bands, domes and pipes to the truss.
                for i, a in enumerate((R + 9, R + 19, R + 29)):
                    tx = cx + a
                    for y in range(ty - 9, ty + 10):
                        for dx in range(-5, 6):
                            for dz in range(-5, 6):
                                rr = math.hypot(dx, dz)
                                top = ty + 9 - y
                                bottom = y - (ty - 9)
                                lim = 4.6 if min(top, bottom) > 1 else (3.6 if min(top, bottom) == 1 else 2.4)
                                if rr <= lim and (rr > lim - 1.2 or min(top, bottom) == 0):
                                    band = (y - ty) % 5 == 0
                                    s = S("waxed_copper_block") if band else S("waxed_cut_copper")
                                    if min(top, bottom) == 0:
                                        s = S("waxed_cut_copper_slab", type="bottom" if top == 0 else "top")
                                    g.set(tx + dx, y, CZ + 6 + dz, s)
                    for z in range(CZ + 1, CZ + 2):
                        g.set(tx, ty, z, S("waxed_copper_bulb", lit="true"))
                    g.set(tx, ty + 11, CZ + 6, S("lightning_rod", facing="up"))
                    g.set(tx, ty + 12, CZ + 6, S("end_rod", facing="up"))
            else:
                # Radiator array: white fins hung off the truss, edged with bars.
                for a in range(R + 6, R + 40, 3):
                    x = cx - a
                    for y in range(ty - 10, ty + 11):
                        for z in range(CZ - 7, CZ + 8):
                            if abs(y - ty) <= 1 and abs(z - CZ) <= 1:
                                continue
                            edge = abs(z - CZ) == 7 or abs(y - ty) == 10
                            g.set(x, y, z, S("iron_bars") if edge else S("white_concrete") if (y + z) % 4 else S("light_gray_concrete"))
                    for y in range(ty - 10, ty + 11):
                        if abs(y - ty) > 1:
                            g.set(x, y, CZ, m["dark"])

    # --- Metadata -------------------------------------------------------------------------------
    pressurized = [[cx - W, F + 1, HZ0, cx + W, YT, HZ1], [cx - R, D1 + 1, CZ - R, cx + R, ROOF - 1, CZ + R],
                   [cx - 2, D1 + 1, CZ + R + 1, cx + 2, D1 + 4, CZ + R + 2]]
    npcs = [{"pos": [cx - 4, F + 1, HZ0 + 2], "role": "guard"}, {"pos": [cx + 4, F + 1, HZ0 + 2], "role": "guard"},
            {"pos": [cx, F + 1, HZ1 - 6], "role": "guard"},
            {"pos": [cx - 5, D1 + 1, CZ + 3], "role": "crew"}, {"pos": [cx + 5, D1 + 1, CZ - 3], "role": "crew"},
            {"pos": [cx - 3, D1 + 1, CZ - 9], "role": "crew"}, {"pos": [cx + 8, F + 1, 50], "role": "crew"},
            {"pos": [cx - 6, D2 + 1, CZ + 10], "role": "crew"}, {"pos": [cx, D2 + 1, CZ - 8], "role": "guard"}]
    write(name, g, pressurized, npcs, beacon=[cx, F + 8, HZ1])


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


def outpost(name, m, kind):
    """A surface outpost on a cleared, founded site: a raised landing pad with approach lights, an
    airlock capsule into a domed trade hall (terminal kiosks round a holo table), plus modules by
    kind: a settlement's greenhouse and hab, a mining camp's headframe, ore bins and silos, a
    research post's observatory and radio dish."""
    SX, SY, SZ = 51, 30, 51
    G = 4            # first air layer at the surface
    FY = G - 1       # floors replace the top terrain block
    g = Grid(SX, SY, SZ)
    rnd = random.Random(name)

    hall = dome_module(31, FY, 21, 10, m)
    lock = capsule_module(14, 21, FY, 21, 4, m)
    modules = [hall, lock]
    extra_pressure = []
    if kind == "settlement":
        green = dome_module(33, FY, 41, 7, m, all_glass=True, ribs=6)
        hab = capsule_module(23, 41, FY, 5, 4, m)
        modules += [tube_module(31, 31, 31, 34, FY, m), tube_module(31, 8, 31, 11, FY, m), green, hab]
        extra_pressure += [[27, G, 35, 39, G + 5, 47], [24, G, 2, 40, G + 4, 8], [30, G, 8, 32, G + 2, 34]]
    elif kind == "mining":
        shed = capsule_module(23, 41, FY, 5, 4, m)
        modules += [tube_module(31, 8, 31, 11, FY, m), shed]
        extra_pressure += [[24, G, 2, 40, G + 4, 8], [30, G, 8, 32, G + 2, 11]]
    else:
        obs = dome_module(36, FY, 41, 7, m, glass_band=(0.0, 0.0))
        lab = capsule_module(23, 41, FY, 5, 4, m)
        modules += [tube_module(33, 31, 33, 35, FY, m), tube_module(31, 8, 31, 11, FY, m), obs, lab]
        extra_pressure += [[30, G, 35, 42, G + 5, 47], [24, G, 2, 40, G + 4, 8], [30, G, 8, 34, G + 2, 34]]
    for mo in modules:
        b = mo.box
        foundation(g, b[0], b[2], b[3], b[5], FY, m)
    compose(g, modules)
    # Floors inside modules.
    for (x, y, z), s in list(g.c.items()):
        if s == AIR and y == FY + 1 and any(mo.inside(x, y, z) for mo in modules):
            g.set(x, FY, z, m["floor"])

    # Airlock: iron doors with buttons at the west end of the capsule.
    door_x, door_z = 14, 21
    for z in (20, 22):
        for y in range(FY + 1, FY + 4):
            g.set(door_x, y, z, m["dark"])
    g.set(door_x, FY + 1, door_z, S(m["door"], half="lower", facing="east", hinge="left"))
    g.set(door_x, FY + 2, door_z, S(m["door"], half="upper", facing="east", hinge="left"))
    g.set(door_x, FY + 3, door_z, m["lamp"])
    g.set(door_x - 1, FY + 2, door_z - 1, S(m["button"], face="wall", facing="west"))
    g.set(door_x + 1, FY + 2, door_z + 1, S(m["button"], face="wall", facing="east"))
    # Canopy and steps outside the airlock.
    for z in range(19, 24):
        g.set(door_x - 1, FY + 4, z, slab(m["dark_slab"], "top"))
        g.set(door_x - 2, FY + 4, z, slab(m["dark_slab"], "top"))
    for z in range(19, 24):
        g.set(door_x - 1, FY, z, m["floor2"])
        g.set(door_x - 2, FY, z, m["floor2"])
    # Inner airlock door into the hall, and lockers in the airlock.
    for y in range(FY + 1, FY + 4):
        for z in (19, 20, 22, 23):
            g.set(21, y, z, m["hull"] if y < FY + 3 else m["dark"])
    g.set(21, FY + 1, 21, S("waxed_copper_door", half="lower", facing="east", hinge="left"))
    g.set(21, FY + 2, 21, S("waxed_copper_door", half="upper", facing="east", hinge="left"))
    g.set(21, FY + 3, 21, m["dark"])
    for x in (16, 17, 19):
        g.set(x, FY + 1, 19, S("barrel", facing="south"))
        g.set(x, FY + 1, 23, S("barrel", facing="north"))
    for z in (19, 23):
        for y in range(G, FY + 4):
            g.set(door_x - 2, y, z, m["dark_wall"])

    # Trade hall interior: kiosks round the wall, holo table, seating, plants, ring lights.
    hx, hz, fy = 31, 21, FY + 1
    terminal_bank_ring(g, m, hx, fy, hz)
    for dx in range(-1, 2):
        for dz in range(-1, 2):
            g.set(hx + dx, fy, hz + dz, slab(m["dark_slab"], "top") if dx or dz else m["light"])
    g.set(hx, fy + 1, hz, S("end_rod", facing="up"))
    for d, (ox, oz) in (("north", (0, -3)), ("south", (0, 3)), ("west", (-3, 0)), ("east", (3, 0))):
        g.set(hx + ox, fy, hz + oz, stairs("spruce_stairs", d))
        if ox:
            g.set(hx + ox, fy, hz + oz - 1, stairs("spruce_stairs", d))
            g.set(hx + ox, fy, hz + oz + 1, stairs("spruce_stairs", d))
    for ox, oz in ((5, 5), (-5, 5), (5, -5), (-5, -5)):
        g.set(hx + ox, fy, hz + oz, S("potted_fern") if ox > 0 else S("potted_azalea_bush"))
    for ang in range(0, 360, 30):
        x = hx + round(7.5 * math.cos(math.radians(ang)))
        z = hz + round(7.5 * math.sin(math.radians(ang)))
        g.set(x, FY, z, m["light"])

    # Landing pad, raised one block, with bevelled edges, posts and approach lights.
    px, pz, ph = 13, 38, 7
    foundation(g, px - ph, pz - ph, px + ph, pz + ph, FY + 1, m, pillars_only=True)
    for x in range(px - ph, px + ph + 1):
        for z in range(pz - ph, pz + ph + 1):
            g.set(x, FY, z, m["dark"])
    landing_marks(g, px, G, pz, ph - 1, m)
    for x in range(px - ph, px + ph + 1):
        g.set(x, G, pz - ph, stairs(m["dark_stairs"], "south"))
        g.set(x, G, pz + ph, stairs(m["dark_stairs"], "north"))
    for z in range(pz - ph, pz + ph + 1):
        g.set(px - ph, G, z, stairs(m["dark_stairs"], "east"))
        g.set(px + ph, G, z, stairs(m["dark_stairs"], "west"))
    for x, z in ((px - ph, pz - ph), (px + ph, pz - ph), (px - ph, pz + ph), (px + ph, pz + ph)):
        light_post(g, x, G + 1, z, 2)
    for z in (pz + ph + 2, pz + ph + 4):
        for x in (px - 3, px + 3):
            light_post(g, x, G, z, 1)
    # Paths: pad -> airlock, airlock -> hall front.
    for z in range(pz - ph - 1, 18, -1):
        for x in (px - 1, px, px + 1):
            g.set(x, FY, z, m["floor2"] if x == px else S("andesite"))
    for z in range(pz - ph - 1, 24, -4):
        g.set(px - 2, G, z, S("polished_deepslate_wall"))
        g.set(px - 2, G + 1, z, S("lantern"))
        g.set(px + 2, G, z, S("polished_deepslate_wall"))
        g.set(px + 2, G + 1, z, S("lantern"))

    if kind == "settlement":
        settlement_extras(g, m, FY, G)
    elif kind == "mining":
        mining_extras(g, m, FY, G, rnd)
    else:
        research_extras(g, m, FY, G)

    # Comms mast on the hall's crown, beside the oculus.
    mast(g, hx + 3, FY + 10, hz + 3, 6, m)

    # Clear terrain just around what was built (2-block margin), so hills and trees don't cut
    # through, without excavating the whole site.
    used = {(x, z) for (x, y, z), st in g.c.items() if st != AIR and y >= FY}
    for x in range(SX):
        for z in range(SZ):
            if any((x + a, z + b) in used for a in range(-2, 3) for b in range(-2, 3)):
                for y in range(G, G + 14):
                    if (x, y, z) not in g.c:
                        g.set(x, y, z, AIR)

    pressurized = [[22, G, 12, 40, G + 8, 30], [15, G, 18, 21, G + 4, 24]] + extra_pressure
    npcs = [{"pos": [door_x - 2, G, 18], "role": "guard"}, {"pos": [px + 3, G + 1, pz - 3], "role": "guard"},
            {"pos": [hx - 4, fy, hz + 4], "role": "crew"}, {"pos": [hx + 4, fy, hz - 4], "role": "crew"},
            {"pos": [hx + 2, fy, hz + 6], "role": "crew"}, {"pos": [31, fy, 5], "role": "crew"}]
    write(name, g, pressurized, npcs, beacon=[door_x - 1, G + 2, door_z], ground=G)


def terminal_bank_ring(g, m, hx, fy, hz):
    """Terminals against the dome wall: commodities west-north, outfitter east, contracts north."""
    spots = [((-7, -3), "east", "commodity_terminal"), ((-7, 3), "east", "commodity_terminal"),
             ((7, -3), "west", "outfitter_terminal"), ((7, 3), "west", "outfitter_terminal"),
             ((-3, -7), "south", "contract_board"), ((3, -7), "south", "contract_board")]
    for (ox, oz), facing, kind in spots:
        x, z = hx + ox, hz + oz
        g.set(x, fy, z, m["dark"])
        g.set(x, fy + 1, z, S(f"sw:{kind}", facing=facing))
        g.set(x, fy + 2, z, m["screen"])
        back = {"east": (-1, 0), "west": (1, 0), "south": (0, -1)}[facing]
        g.set(x, fy + 3, z, m["light"])
        side = (0, 1) if facing in ("east", "west") else (1, 0)
        for s in (-1, 1):
            sx, sz = x + side[0] * s, z + side[1] * s
            g.set(sx, fy, sz, m["dark"])
            g.set(sx, fy + 1, sz, slab(m["trim_slab"]))


def settlement_extras(g, m, FY, G):
    # Greenhouse beds: farmland rows with a water channel, berry bushes and a small tree.
    gx, gz = 33, 41
    for dx in range(-5, 6):
        for dz in range(-5, 6):
            if math.hypot(dx, dz) > 5.3:
                continue
            x, z = gx + dx, gz + dz
            if dz == 0:
                g.set(x, FY, z, S("water") if abs(dx) < 5 else S("tuff_bricks"))
            elif abs(dz) in (1, 2, 4):
                g.set(x, FY, z, S("farmland", moisture=7))
                crop = [S("wheat", age=7), S("carrots", age=7), S("potatoes", age=7), S("beetroots", age=3)][(abs(dz) + (dx > 0)) % 4]
                g.set(x, FY + 1, z, crop)
            else:
                g.set(x, FY, z, m["floor2"])
    for dz in range(-5, 6):
        if g.get(gx, FY, gz + dz):
            g.set(gx, FY, gz + dz, m["floor2"])
            g.set(gx, FY + 1, gz + dz, AIR)
    furnish_capsule(g, FY, [S("barrel", facing="south"), S("lantern"), S("smoker", facing="south"), S("furnace", facing="south"),
                            S("crafting_table"), S("barrel", facing="south"), S("cauldron"), S("composter"),
                            S("barrel", facing="south"), S("bookshelf"), S("barrel", facing="south")])
    # Comms tower on the north-west corner: lattice with a dish.
    tower(g, 7, G, 7, 14, m)
    dish(g, 7, G + 14, 7, 3, m, stalk=1)
    # Solar field west of the hab.
    solar_field(g, 1, G, 13, 2, 2, m)


def mining_extras(g, m, FY, G, rnd):
    # Headframe over a shaft on the south-east: four legs braced with bars, a deck, a sheave wheel
    # and the hoist cable down the shaft.
    hx, hz = 41, 41
    for x in range(hx - 2, hx + 3):
        for z in range(hz - 2, hz + 3):
            for y in range(0, FY + 1):
                g.set(x, y, z, AIR if abs(x - hx) <= 1 and abs(z - hz) <= 1 else S("tuff_bricks"))
    top = G + 13
    for y in range(G, top):
        for ox, oz in ((-3, -3), (3, -3), (-3, 3), (3, 3)):
            g.set(hx + ox, y, hz + oz, m["dark"] if (y - G) % 4 == 0 else m["dark_wall"])
        if (y - G) % 4 == 2:
            for t in range(-2, 3):
                for a, b in ((t, -3), (t, 3), (-3, t), (3, t)):
                    g.set(hx + a, y, hz + b, S("iron_bars"))
    for x in range(hx - 3, hx + 4):
        for z in range(hz - 3, hz + 4):
            edge = abs(x - hx) == 3 or abs(z - hz) == 3
            g.set(x, top, z, m["dark"] if edge else slab(m["dark_slab"], "top"))
            if edge:
                g.set(x, top + 1, z, S("iron_bars"))
    for a in range(0, 360, 20):
        g.set(hx, top + 4 + round(2.6 * math.sin(math.radians(a))), hz + round(2.6 * math.cos(math.radians(a))), m["dark"])
    for y in range(top + 1, top + 4):
        g.set(hx, y, hz, m["dark_wall"])
    g.set(hx, top + 4, hz, S("iron_block"))
    for y in range(0, top):
        g.set(hx, y, hz + 1, S("iron_chain", axis="y"))
    g.set(hx, top - 1, hz + 1, S("iron_chain", axis="y"))
    furnish_capsule(g, FY, [S("blast_furnace", facing="south"), S("stonecutter", facing="south"), S("barrel", facing="south"),
                            S("smithing_table"), S("anvil", facing="east"), S("barrel", facing="south"), S("grindstone", face="floor", facing="south"),
                            S("blast_furnace", facing="south"), S("crafting_table"), S("barrel", facing="south"), S("barrel", facing="south")])
    # Ore bins and a rail conveyor to the shed.
    for i, ore in enumerate(["raw_iron_block", "raw_copper_block", "cobbled_deepslate", "coal_block"]):
        bx = 26 + i * 4
        for x in range(bx, bx + 3):
            for z in (32, 33, 34):
                edge = x in (bx, bx + 2) or z in (32, 34)
                g.set(x, G, z, m["dark"] if edge else S(ore))
                if edge:
                    g.set(x, G + 1, z, S("iron_bars"))
                else:
                    g.set(x, G + 1, z, S(ore))
        g.set(bx + 1, G + 2, 33, S("hopper", facing="down"))
    for x in range(26, 41):
        g.set(x, G, 36, m["dark"])
        g.set(x, G + 1, 36, S("rail", shape="east_west"))
    # Silos.
    for i, sx in enumerate((5, 12, 19)):
        silo(g, sx, G, 6, 2.6, 11, m)
    solar_field(g, 1, G, 13, 2, 2, m)


def research_extras(g, m, FY, G):
    # Observatory: a dome with an open slit running north from the crown, and a telescope on a mount.
    ox, oz = 36, 41
    for (x, y, z), st in list(g.c.items()):
        if abs(x - ox) <= 1 and z <= oz and y >= G + 3 and math.hypot(x - ox, z - oz) < 8 and st not in (None, AIR) and y < G + 9:
            if math.sqrt((x - ox) ** 2 + (y - FY) ** 2 + (z - oz) ** 2) >= 6.4:
                g.set(x, y, z, AIR)
    for y in range(G, G + 2):
        g.set(ox, y, oz, m["dark"])
    for i in range(5):
        g.set(ox, G + 2 + i, oz - i // 2, S("iron_block") if i < 4 else S("tinted_glass"))
    g.set(ox + 1, G, oz, S("lectern", facing="west"))
    # Big radio dish on the north-west.
    dish(g, 8, G, 8, 6, m, stalk=4)
    for ox2, oz2 in ((-2, -2), (2, -2), (-2, 2), (2, 2)):
        for y in range(G, G + 3):
            g.set(8 + ox2, y, 8 + oz2, m["dark_wall"])
    # Sensor array: posts with rods and sculk sensors.
    for i, (x, z) in enumerate(((44, 6), (44, 12), (44, 18), (47, 9), (47, 15))):
        g.set(x, G, z, m["dark"])
        g.set(x, G + 1, z, S("polished_deepslate_wall"))
        g.set(x, G + 2, z, S("calibrated_sculk_sensor", facing="west") if i % 2 == 0 else S("lightning_rod", facing="up"))
    furnish_capsule(g, FY, [S("brewing_stand"), S("cauldron"), S("lectern", facing="south"), S("barrel", facing="south"),
                            S("brewing_stand"), S("crafting_table"), S("cartography_table"), S("smithing_table"),
                            S("barrel", facing="south"), S("lectern", facing="south"), S("cauldron"), S("bookshelf")])
    solar_field(g, 1, G, 13, 2, 2, m)


def furnish_capsule(g, FY, items):
    """Bunks along the north side of the long capsule and a work counter along the south side,
    keeping the tube entrance (x 30..32) clear."""
    for x in (25, 28, 34, 37):
        g.set(x, FY + 1, 3, S("white_bed", part="head", facing="north"))
        g.set(x, FY + 1, 4, S("white_bed", part="foot", facing="north"))
        g.set(x + 1, FY + 1, 3, S("barrel", facing="up"))
        g.set(x + 1, FY + 2, 3, S("lantern"))
    xs = [x for x in range(25, 40) if not 29 <= x <= 33]
    for x, s in zip(xs, items):
        if g.get(x, FY + 1, 7) == AIR:
            g.set(x, FY + 1, 7, s)


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


def main():
    station("station_port", PORT, "port")
    station("station_refinery", REFINERY, "refinery")
    outpost("outpost_settlement", style(glass=S("light_blue_stained_glass")), "settlement")
    outpost("outpost_mining", style(glass=S("orange_stained_glass"), accent=S("orange_concrete")), "mining")
    outpost("outpost_research", style(glass=S("purple_stained_glass"), accent=S("purple_concrete")), "research")


if __name__ == "__main__":
    main()
