"""Starwake hub designs (M9 overhaul, Star Citizen industrial style), built on tools/hub_templates.py helpers.

Shapes are signed-distance solids (tubes, rings, rounded halls, cylinders): a shell one block thick around an
interior, materials chosen per cell (ribs every few blocks along a tube, window bands, accent stripes). Every cell is
tagged with the tier that adds it (Grid.tier), so each hub writes its base build plus tier-2 and tier-3 expansions
that the game places onto the same origin when the hub grows (TradeHubs, only while no player is near).

Stations (orbit): a vertical core (reactor shaft, docking deck with the trade concourse, control deck, crown), arms to
enclosed hangars with lit mouths held by pressure fields. Port: T1 east + west hangars and roof solar wings; T2 a
habitation ring on four spokes and a north hangar; T3 a south hangar, long solar trusses and a comms array.
Refinery: T1 east + west hangars; T2 a north ore-processing hangar and spherical tanks; T3 a south hangar,
radiator fins and a tank farm.

Outposts (surface): boxy modular habs on a founded site. T1 a trade hall with airlock, a raised pad, comms mast,
containers, generator and perimeter lights; T2 a crew/workshop hab on a tube, a second pad and solar; T3 the
outpost's signature facility (settlement greenhouse dome, mining headframe and silos, research observatory and dish)
and a watchtower.
"""
import math
import random

from hub_templates import (AIR, Grid, S, container, dish, foundation, hazard, landing_marks, light_post, mast, silo, slab,
                           solar_field, stairs, style, terminal_bank, tower, write_tiers)


# ---------------------------------------------------------------------------------------------
# Distance-field solids
# ---------------------------------------------------------------------------------------------

class Solid:
    """A solid: sdf(x, y, z) < 0 inside; shell = cells with -1 <= sdf < 0.5 (outer skin); interior = sdf < -1.
    mat(x, y, z) picks the shell block; floor_y (optional) is the deck height inside; tier = the tier it belongs to."""

    def __init__(self, sdf, box, mat, floor_y=None, tier=1, hollow=True):
        self.sdf, self.box, self.mat, self.floor_y, self.tier, self.hollow = sdf, box, mat, floor_y, tier, hollow


def seg_sdf(a, b, r):
    ax, ay, az = a
    bx, by, bz = b
    dx, dy, dz = bx - ax, by - ay, bz - az
    ll = dx * dx + dy * dy + dz * dz

    def f(x, y, z):
        t = max(0.0, min(1.0, ((x - ax) * dx + (y - ay) * dy + (z - az) * dz) / ll))
        return math.sqrt((x - ax - t * dx) ** 2 + (y - ay - t * dy) ** 2 + (z - az - t * dz) ** 2) - r
    return f


def along(a, b):
    """Distance along the a->b axis (for ribs)."""
    ax, ay, az = a
    bx, by, bz = b
    dx, dy, dz = bx - ax, by - ay, bz - az
    ll = math.sqrt(dx * dx + dy * dy + dz * dz)
    return lambda x, y, z: ((x - ax) * dx + (y - ay) * dy + (z - az) * dz) / ll


def rbox_sdf(x0, y0, z0, x1, y1, z1, r=2.0):
    """Rounded box (chamfered edges): the classic SC hall."""
    cx, cy, cz = (x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2
    hx, hy, hz = (x1 - x0) / 2 - r, (y1 - y0) / 2 - r, (z1 - z0) / 2 - r

    def f(x, y, z):
        qx, qy, qz = abs(x - cx) - hx, abs(y - cy) - hy, abs(z - cz) - hz
        out = math.sqrt(max(qx, 0) ** 2 + max(qy, 0) ** 2 + max(qz, 0) ** 2)
        return out + min(max(qx, qy, qz), 0) - r
    return f


def cyl_sdf(cx, cz, r, y0, y1):
    def f(x, y, z):
        d = math.hypot(x - cx, z - cz) - r
        e = max(y0 - y, y - y1)
        return max(d, e) if d > 0 or e > 0 else max(d, e)
    return f


def torus_sdf(cx, cy, cz, R, r):
    return lambda x, y, z: math.hypot(math.hypot(x - cx, z - cz) - R, y - cy) - r


def build(g, solids, m):
    """Shells then interiors: a later solid's interior opens earlier shells (so tubes join halls); floors are laid at
    each solid's floor_y; each cell is tagged with its solid's tier."""
    for so in solids:
        g.tier = so.tier
        x0, y0, z0, x1, y1, z1 = so.box
        for x in range(max(0, x0), min(g.sx - 1, x1) + 1):
            for z in range(max(0, z0), min(g.sz - 1, z1) + 1):
                for y in range(max(0, y0), min(g.sy - 1, y1) + 1):
                    d = so.sdf(x, y, z)
                    if -1.0 <= d < 0.5 and (x, y, z) not in g.c:
                        g.set(x, y, z, so.mat(x, y, z))
    for so in solids:
        if not so.hollow:
            continue
        g.tier = so.tier
        x0, y0, z0, x1, y1, z1 = so.box
        for x in range(max(0, x0), min(g.sx - 1, x1) + 1):
            for z in range(max(0, z0), min(g.sz - 1, z1) + 1):
                for y in range(max(0, y0), min(g.sy - 1, y1) + 1):
                    if so.sdf(x, y, z) < -1.0:
                        if so.floor_y is not None and y < so.floor_y:
                            continue
                        if so.floor_y is not None and y == so.floor_y:
                            g.set(x, y, z, m["deck"])
                        else:
                            g.set(x, y, z, AIR)
    g.tier = 1


def tube_mat(m, a, b, rib=4, window_y=None):
    t = along(a, b)

    def f(x, y, z):
        if int(round(t(x, y, z))) % rib == 0:
            return m["dark"]
        if window_y is not None and y == window_y:
            return m["glass"]
        return m["hull"] if (x + z) % 7 else m["hull2"]
    return f


# ---------------------------------------------------------------------------------------------
# Station
# ---------------------------------------------------------------------------------------------

SX = SZ = 121
SY = 62
C = 60          # core centre x/z
DECK = 22       # docking deck (concourse) floor
UP = 32         # control deck floor


def station(name, m, variant):
    g = Grid(SX, SY, SZ)
    solids = []
    pressure = {1: [], 2: [], 3: []}

    # Core: reactor shaft + two decks, banded hull, window rings, crown.
    def core_mat(x, y, z):
        ang = math.degrees(math.atan2(z - C, x - C)) % 30
        if y in (DECK + 2, DECK + 3, UP + 2, UP + 3) and 4 < ang < 26:
            return m["glass"]
        if y in (DECK - 1, UP - 1, 43):
            return m["accent"]
        if ang < 3:
            return m["dark"]
        return m["hull"] if y % 6 else m["hull2"]
    solids.append(Solid(cyl_sdf(C, C, 13, 14, 44), (C - 15, 12, C - 15, C + 15, 46, C + 15), core_mat, floor_y=None))
    # Crown and keel tapers.
    solids.append(Solid(lambda x, y, z: math.sqrt((x - C) ** 2 + ((y - 44) * 1.6) ** 2 + (z - C) ** 2) - 12,
                        (C - 14, 44, C - 14, C + 14, 54, C + 14), lambda x, y, z: m["hull"] if y % 3 else m["dark"], hollow=False))
    solids.append(Solid(lambda x, y, z: math.sqrt((x - C) ** 2 + ((y - 14) * 1.4) ** 2 + (z - C) ** 2) - 11,
                        (C - 13, 4, C - 13, C + 13, 14, C + 13), lambda x, y, z: m["dark"] if (x + z) % 3 else m["hull2"], hollow=False))

    # Arms and hangars per direction: (dx, dz) unit, tier.
    arms = [((1, 0), 1), ((-1, 0), 1), ((0, -1), 2), ((0, 1), 3)]
    hangar_boxes = []
    for (dx, dz), tier in arms:
        a = (C + dx * 12, DECK + 3, C + dz * 12)
        b = (C + dx * 33, DECK + 3, C + dz * 33)
        solids.append(Solid(seg_sdf(a, b, 4.2), _box(a, b, 6), tube_mat(m, a, b, 4, DECK + 4), floor_y=DECK, tier=tier))
        # Hangar hall: 24 deep (from 34 to 58 out), 27 wide, floor DECK - 2, 16 tall.
        n0, n1 = 34, 58
        hx0, hx1 = (C + dx * n0, C + dx * n1) if dx else (C - 13, C + 13)
        hz0, hz1 = (C + dz * n0, C + dz * n1) if dz else (C - 13, C + 13)
        X0, X1, Z0, Z1 = min(hx0, hx1), max(hx0, hx1), min(hz0, hz1), max(hz0, hz1)
        Y0, Y1 = DECK - 3, DECK + 15

        def hangar_mat(x, y, z, dx=dx, dz=dz):
            u = x * dz + z * dx  # coordinate along the hangar's depth
            if y == Y1 - 1 or y == Y0 + 1:
                return m["dark"]
            if u % 6 == 0:
                return m["dark"]
            if y == DECK + 8:
                return m["accent"]
            return m["hull"] if (y + u) % 9 else m["hull2"]
        solids.append(Solid(rbox_sdf(X0, Y0, Z0, X1, Y1, Z1, 3.0), (X0, Y0, Z0, X1, Y1, Z1), hangar_mat, floor_y=DECK - 1, tier=tier))
        hangar_boxes.append(((dx, dz), tier, (X0, Y0, Z0, X1, Y1, Z1)))
        pressure[tier].append(_pbox(a, b, 3))
        pressure[tier].append([X0 + 1, DECK, Z0 + 1, X1 - 1, Y1 - 2, Z1 - 1])

    pressure[1].append([C - 12, 15, C - 12, C + 12, 43, C + 12])

    if variant == "port":
        # T2: habitation ring on four diagonal spokes.
        ry = UP + 5
        solids.append(Solid(torus_sdf(C, ry, C, 29, 4.2), (C - 35, ry - 6, C - 35, C + 35, ry + 6, C + 35),
                            lambda x, y, z: m["glass"] if y == ry + 1 and (x + z) % 5 else (m["dark"] if int(math.degrees(math.atan2(z - C, x - C))) % 15 == 0 else m["hull"]),
                            floor_y=ry - 2, tier=2))
        for ang in (45, 135, 225, 315):
            ca, sa = math.cos(math.radians(ang)), math.sin(math.radians(ang))
            a = (C + ca * 12, ry, C + sa * 12)
            b = (C + ca * 26, ry, C + sa * 26)
            solids.append(Solid(seg_sdf(a, b, 2.8), _box(a, b, 4), tube_mat(m, a, b, 3), floor_y=ry - 1, tier=2))
            pressure[2].append(_pbox(a, b, 2))
        pressure[2].append([C - 33, ry - 3, C - 33, C + 33, ry + 3, C + 33])
    else:
        # T2: four spherical tanks on trusses round the north arm.
        for ox, oz in ((-10, -24), (10, -24), (-10, -40), (10, -40)):
            cx, cz, cy = C + ox, C + oz, DECK + 20
            solids.append(Solid(lambda x, y, z, cx=cx, cy=cy, cz=cz: math.sqrt((x - cx) ** 2 + (y - cy) ** 2 + (z - cz) ** 2) - 5,
                                (cx - 6, cy - 6, cz - 6, cx + 6, cy + 6, cz + 6),
                                lambda x, y, z, cy=cy: m["accent"] if y == cy else (S("white_concrete") if (x + y + z) % 4 else m["hull2"]),
                                tier=2, hollow=False))

    build(g, solids, m)

    # --- Hangar mouths: open the outer end, lit frame, pressure-field glow, pads, catwalks ----------------------
    for (dx, dz), tier, (X0, Y0, Z0, X1, Y1, Z1) in hangar_boxes:
        g.tier = tier
        for x in range(X0, X1 + 1):
            for z in range(Z0, Z1 + 1):
                for y in range(DECK, Y1 - 2):
                    outer = (dx == 1 and x >= X1 - 1) or (dx == -1 and x <= X0 + 1) or (dz == 1 and z >= Z1 - 1) or (dz == -1 and z <= Z0 + 1)
                    if not outer:
                        continue
                    side = (z if dx else x)
                    lo, hi = (Z0, Z1) if dx else (X0, X1)
                    if lo + 3 <= side <= hi - 3:
                        frame = side in (lo + 3, hi - 3) or y == Y1 - 3
                        g.set(x, y, z, (hazard(x, z) if frame else AIR))
        # Mouth light strip above the opening and pressure-field light at the sill.
        for i in range(-10, 11):
            x = (X1 if dx == 1 else X0) if dx else C + i
            z = (Z1 if dz == 1 else Z0) if dz else C + i
            g.set(x, Y1 - 2, z, m["lamp"])
            g.set(x, DECK - 1, z, m["rim"])
        # Pads: two marked pads inside.
        for k in (8, 18):
            px = C + dx * (34 + k) if dx else C
            pz = C + dz * (34 + k) if dz else C
            landing_marks(g, px, DECK - 1, pz, 5, m, surface=m["deck"])
        # Catwalks along both side walls at DECK + 6 with rails, ceiling lights.
        for k in range(36, 57):
            for s in (-11, 11):
                x = C + dx * k if dx else C + s
                z = C + dz * k if dz else C + s
                g.set(x, DECK + 6, z, slab(m["dark_slab"], "top"))
                rx = x - (1 if s > 0 else -1) * (0 if dx else 1)
                rz = z - (1 if s > 0 else -1) * (1 if dx else 0)
                g.set(rx, DECK + 7, rz, S("iron_bars"))
            if k % 6 == 3:
                for s in (-6, 0, 6):
                    x = C + dx * k if dx else C + s
                    z = C + dz * k if dz else C + s
                    g.set(x, Y1 - 2, z, m["light"])
        # Containers by the inner wall.
        cx0 = C + dx * 36 if dx else C - 9
        cz0 = C + dz * 36 if dz else C - 9
        container(g, cx0 - (2 if dx < 0 else 0), DECK, cz0 + (6 if dx else 0), "orange" if variant == "refinery" else "light_gray",
                  axis="x" if dz else "z", length=5)

    # --- Core interior: reactor column, concourse with terminals, control deck ---------------------------------
    g.tier = 1
    for y in range(15, 44):
        for x in range(C - 2, C + 3):
            for z in range(C - 2, C + 3):
                r = math.hypot(x - C, z - C)
                if r <= 1.2:
                    g.set(x, y, z, m["core"] if y % 3 else m["dark"])
                elif r <= 2.5 and y in (DECK, UP):
                    g.set(x, y, z, S("tinted_glass"))
    for y in (DECK, UP):
        for x in range(C - 12, C + 13):
            for z in range(C - 12, C + 13):
                r = math.hypot(x - C, z - C)
                if 2.5 < r < 12:
                    g.set(x, y, z, m["deck"] if (x + z) % 2 else m["floor"])
                if 3.0 < r < 3.8:
                    g.set(x, y + 1, z, S("iron_bars"))
    # Terminal kiosks on the concourse between the arms (diagonals).
    kinds = [("commodity_terminal", (1, 1)), ("outfitter_terminal", (-1, 1)), ("contract_board", (-1, -1)), ("commodity_terminal", (1, -1))]
    for kind, (sx, sz) in kinds:
        for i in range(3):
            x, z = C + sx * (6 + i), C + sz * (8 - i)
            facing = "west" if sx > 0 else "east"
            g.set(x, DECK + 1, z, S(f"sw:{kind}", facing=facing))
            g.set(x, DECK + 2, z, m["screen"])
            g.set(x, DECK + 4, z, m["light"])
    # Stairs between decks (a straight flight on the south-west).
    for i in range(10):
        g.set(C - 9 + i, DECK + 1 + i, C + 4, stairs("polished_deepslate_stairs", "east"))
        for y in range(DECK + 2 + i, DECK + 5 + i):
            g.set(C - 9 + i, y, C + 4, AIR)
        g.set(C - 9 + i, UP, C + 4, AIR) if DECK + 1 + i >= UP - 1 else None
    # Ceiling lights on both decks.
    for ang in range(0, 360, 30):
        for y in (UP - 1, 42):
            g.set(C + round(9 * math.cos(math.radians(ang))), y, C + round(9 * math.sin(math.radians(ang))), m["light"])
    # Control deck furniture: consoles facing the windows.
    for ang in range(15, 360, 60):
        x, z = C + round(10 * math.cos(math.radians(ang))), C + round(10 * math.sin(math.radians(ang)))
        g.set(x, UP + 1, z, S("polished_deepslate_slab", type="top"))
        g.set(x, UP + 2, z, m["screen"])

    # --- Exterior details --------------------------------------------------------------------------------------
    g.tier = 1
    mast(g, C + 3, 54, C + 3, 7, m)
    dish(g, C - 4, 52, C - 4, 3, m, stalk=1)
    if variant == "port":
        # T1 roof solar wings.
        for s in (-1, 1):
            for k in range(14, 30):
                for w in range(-3, 4):
                    g.set(C + s * k, 46, C + w, S("daylight_detector") if w else m["dark"])
                    if w == 0:
                        g.set(C + s * k, 45, C, m["dark_wall"])
        # T3: long solar trusses above and below the north-south axis, comms array.
        g.tier = 3
        for s in (-1, 1):
            for k in range(14, 58):
                y = 50 if s > 0 else 8
                for w in range(-4, 5):
                    g.set(C + w, y, C + s * k, S("daylight_detector") if abs(w) > 0 and k % 8 else m["dark"])
                g.set(C, y - 1, C + s * k, m["dark_wall"])
        tower(g, C - 8, 46, C + 8, 12, m)
        dish(g, C - 8, 58, C + 8, 2, m, stalk=0)
    else:
        # T3: radiator fins along the east-west arms and a tank farm on the south hangar roof.
        g.tier = 3
        for s in (-1, 1):
            for k in range(14, 33, 3):
                for h in range(1, 12):
                    g.set(C + s * k, DECK + 7 + h, C, S("iron_bars") if h % 3 else m["dark"])
                    g.set(C + s * k, DECK - 2 - h, C, S("iron_bars") if h % 3 else m["dark"])
        for i, ox in enumerate((-8, 0, 8)):
            silo(g, C + ox, DECK + 15, C + 46, 2.6, 7, m)
    g.tier = 1

    beacon = [C + 58, DECK + 3, C]
    write_tiers(name, g, pressure, beacon)


def _box(a, b, pad):
    return (int(min(a[0], b[0]) - pad), int(min(a[1], b[1]) - pad), int(min(a[2], b[2]) - pad),
            int(max(a[0], b[0]) + pad), int(max(a[1], b[1]) + pad), int(max(a[2], b[2]) + pad))


def _pbox(a, b, r):
    return [int(min(a[0], b[0]) - r), int(min(a[1], b[1]) - r), int(min(a[2], b[2]) - r),
            int(max(a[0], b[0]) + r), int(max(a[1], b[1]) + r), int(max(a[2], b[2]) + r)]


# ---------------------------------------------------------------------------------------------
# Outposts
# ---------------------------------------------------------------------------------------------

OX = OZ = 64
OY = 32
G = 4          # first air layer at the surface
FY = G - 1     # floors replace the top terrain block


def hab(g, x0, z0, x1, z1, h, m, tier, solids):
    """A boxy SC-style hab module (chamfered roof edges, ribs every 4, a window band) on the site floor."""
    def mat(x, y, z):
        if (x - x0) % 4 == 0 or (z - z0) % 4 == 0 and (x in (x0, x1) or True) and y > FY + 1 and (z in (z0, z1)):
            return m["dark"]
        if y == FY + 3:
            return m["glass"]
        if y == FY + 1:
            return m["dark"]
        if y == FY + h:
            return m["accent"]
        return m["hull"] if (x + y + z) % 5 else m["hull2"]
    solids.append(Solid(rbox_sdf(x0, FY, z0, x1, FY + h + 2, z1, 1.6), (x0 - 1, FY, z0 - 1, x1 + 1, FY + h + 3, z1 + 1), mat,
                        floor_y=FY, tier=tier))
    return [x0 + 1, G, z0 + 1, x1 - 1, FY + h, z1 - 1]


def link(g, a, b, m, tier, solids):
    """A pressurised connector tube between two habs (axis-aligned)."""
    solids.append(Solid(seg_sdf(a, b, 2.4), _box(a, b, 4), tube_mat(m, a, b, 3), floor_y=FY, tier=tier))
    return _pbox(a, b, 1)


def pad(g, cx, cz, half, m):
    """A raised landing pad with bevelled edges, corner light posts and approach lights."""
    foundation(g, cx - half, cz - half, cx + half, cz + half, FY + 1, m, pillars_only=True)
    for x in range(cx - half, cx + half + 1):
        for z in range(cz - half, cz + half + 1):
            g.set(x, FY, z, m["dark"])
    landing_marks(g, cx, G, cz, half - 1, m)
    for x in range(cx - half, cx + half + 1):
        g.set(x, G, cz - half, stairs(m["dark_stairs"], "south"))
        g.set(x, G, cz + half, stairs(m["dark_stairs"], "north"))
    for z in range(cz - half, cz + half + 1):
        g.set(cx - half, G, z, stairs(m["dark_stairs"], "east"))
        g.set(cx + half, G, z, stairs(m["dark_stairs"], "west"))
    for x, z in ((cx - half, cz - half), (cx + half, cz - half), (cx - half, cz + half), (cx + half, cz + half)):
        light_post(g, x, G + 1, z, 2)


def airlock(g, x, z, facing, m):
    """Iron door with buttons, a lit header and a canopy (door in the wall cell at x, z)."""
    dx = {"east": 1, "west": -1}.get(facing, 0)
    dz = {"south": 1, "north": -1}.get(facing, 0)
    g.set(x, FY + 1, z, S(m["door"], half="lower", facing=facing, hinge="left"))
    g.set(x, FY + 2, z, S(m["door"], half="upper", facing=facing, hinge="left"))
    g.set(x, FY + 3, z, m["lamp"])
    side = (0, 1) if dx else (1, 0)
    g.set(x + dx + side[0], FY + 2, z + dz + side[1], S(m["button"], face="wall", facing=facing))
    g.set(x - dx - side[0], FY + 2, z - dz - side[1], S(m["button"], face="wall", facing={"east": "west", "west": "east", "north": "south", "south": "north"}[facing]))
    for s in (-2, -1, 0, 1, 2):
        cx, cz = x + dx + side[0] * s, z + dz + side[1] * s
        g.set(cx, FY + 4, cz, slab(m["dark_slab"], "top"))
        g.set(cx, FY, cz, m["floor2"])
        cx2, cz2 = x + 2 * dx + side[0] * s, z + 2 * dz + side[1] * s
        g.set(cx2, FY + 4, cz2, slab(m["dark_slab"], "top"))
        g.set(cx2, FY, cz2, m["floor2"])


def outpost(name, m, kind):
    g = Grid(OX, OY, OZ)
    rnd = random.Random(name)
    solids = []
    pressure = {1: [], 2: [], 3: []}

    # T1: trade hall (east-west), airlock on the west end.
    pressure[1].append(hab(g, 20, 20, 42, 32, 6, m, 1, solids))
    # T2: crew / workshop hab north, on a tube.
    pressure[2].append(hab(g, 22, 4, 40, 12, 5, m, 2, solids))
    pressure[2].append(link(g, (31, FY + 2, 12), (31, FY + 2, 20), m, 2, solids))
    # T3: signature facility.
    if kind == "settlement":
        cx, cz = 52, 26
        solids.append(Solid(lambda x, y, z: math.sqrt((x - cx) ** 2 + (y - FY) ** 2 + (z - cz) ** 2) - 9,
                            (cx - 10, FY, cz - 10, cx + 10, FY + 10, cz + 10),
                            lambda x, y, z: m["dark"] if (x + z) % 5 == 0 or y == FY + 1 else S("light_blue_stained_glass"),
                            floor_y=FY, tier=3))
        pressure[3].append([cx - 8, G, cz - 8, cx + 8, FY + 8, cz + 8])
        pressure[3].append(link(g, (42, FY + 2, 26), (44, FY + 2, 26), m, 3, solids))
    elif kind == "research":
        cx, cz = 52, 26
        solids.append(Solid(lambda x, y, z: math.sqrt((x - cx) ** 2 + (y - FY) ** 2 + (z - cz) ** 2) - 8,
                            (cx - 9, FY, cz - 9, cx + 9, FY + 9, cz + 9),
                            lambda x, y, z: m["dark"] if abs(z - cz) <= 1 and y > FY + 5 else (m["hull"] if (y + x) % 4 else m["hull2"]),
                            floor_y=FY, tier=3))
        pressure[3].append([cx - 7, G, cz - 7, cx + 7, FY + 7, cz + 7])
        pressure[3].append(link(g, (42, FY + 2, 26), (45, FY + 2, 26), m, 3, solids))
    else:
        pressure[3].append(hab(g, 46, 18, 58, 34, 7, m, 3, solids))
        pressure[3].append(link(g, (42, FY + 2, 26), (46, FY + 2, 26), m, 3, solids))

    for so in solids:
        b = so.box
        g.tier = so.tier
        foundation(g, b[0] + 1, b[2] + 1, b[3] - 1, b[5] - 1, FY, m)
    build(g, solids, m)

    # --- T1 details --------------------------------------------------------------------------------------------
    g.tier = 1
    airlock(g, 20, 26, "west", m)
    # Terminals along the hall's south wall, facing north; a holo table; benches.
    for i, kind_t in enumerate(["commodity_terminal", "commodity_terminal", "outfitter_terminal", "contract_board", "contract_board"]):
        x = 24 + i * 4
        g.set(x, FY + 1, 31, S(f"sw:{kind_t}", facing="north"))
        g.set(x, FY + 2, 31, m["screen"])
        g.set(x, FY + 4, 30, m["light"])
    for x in range(28, 34):
        g.set(x, FY + 1, 24, slab(m["dark_slab"], "top"))
    g.set(31, FY + 2, 24, S("end_rod", facing="up"))
    for x in (24, 38):
        g.set(x, FY + 1, 21, stairs("spruce_stairs", "south"))
        g.set(x + 1, FY + 1, 21, stairs("spruce_stairs", "south"))
        g.set(x + 2, FY + 1, 21, S("potted_fern"))
    for x in range(22, 41, 4):
        g.set(x, FY + 6, 26, m["light"])
    pad(g, 13, 46, 8, m)
    # Path pad -> airlock.
    for z in range(37, 26, -1):
        for x in (12, 13, 14):
            g.set(x, FY, z, m["floor2"] if x == 13 else S("andesite"))
    for x in range(14, 18):
        for z in (25, 26, 27):
            g.set(x, FY, z, m["floor2"])
    mast(g, 40, FY + 9, 22, 6, m)
    # Generator block with exhaust stacks and a fuel tank beside the hall.
    for x in range(44, 48):
        for z in range(36, 40):
            for y in range(G, G + 3):
                g.set(x, y, z, m["dark"] if y == G + 2 or x in (44, 47) else S("iron_block"))
    for x, z in ((45, 37), (46, 38)):
        for y in range(G + 3, G + 6):
            g.set(x, y, z, S("polished_deepslate_wall"))
        g.set(x, G + 6, z, S("lightning_rod", facing="up"))
    container(g, 26, G, 36, "orange", axis="x", length=6)
    container(g, 26, G + 3, 36, "light_gray", axis="x", length=6)
    container(g, 34, G, 36, "cyan", axis="x", length=6)
    # Perimeter lights.
    for x in range(4, 60, 9):
        for z in (2, 61):
            light_post(g, x, G, z, 2)
    for z in range(11, 60, 9):
        for x in (2, 61):
            light_post(g, x, G, z, 2)

    # --- T2 details --------------------------------------------------------------------------------------------
    g.tier = 2
    pad(g, 52, 50, 7, m)
    for x in range(44, 52):
        for z in (41, 42):
            g.set(x, FY, z, m["floor2"])
    solar_field(g, 4, G, 4, 3, 2, m)
    for x in (24, 28, 34, 37):
        g.set(x, FY + 1, 5, S("white_bed", part="head", facing="north"))
        g.set(x, FY + 1, 6, S("white_bed", part="foot", facing="north"))
        g.set(x, FY + 5, 8, m["light"])
    for i, s in enumerate([S("crafting_table"), S("smithing_table"), S("barrel", facing="north"), S("blast_furnace", facing="north"),
                           S("barrel", facing="north"), S("stonecutter", facing="north")]):
        g.set(24 + i * 2 + (2 if i >= 3 else 0), FY + 1, 11, s)
    container(g, 12, G, 26 + 8, "white", axis="z", length=5) if False else None

    # --- T3 details --------------------------------------------------------------------------------------------
    g.tier = 3
    tower(g, 8, G, 30, 14, m)
    if kind == "settlement":
        for dx in range(-6, 7):
            for dz in range(-6, 7):
                if math.hypot(dx, dz) > 6.4:
                    continue
                x, z = 52 + dx, 26 + dz
                if dz == 0:
                    g.set(x, FY, z, S("water") if abs(dx) < 6 else m["dark"])
                elif abs(dz) in (1, 2, 4, 5):
                    g.set(x, FY, z, S("farmland", moisture=7))
                    g.set(x, FY + 1, z, [S("wheat", age=7), S("carrots", age=7), S("potatoes", age=7)][(abs(dz) + (dx > 0)) % 3])
        g.set(52, FY + 8, 26, m["light"])
    elif kind == "research":
        g.set(52, FY + 1, 26, m["dark"])
        for i in range(5):
            g.set(52, FY + 2 + i, 26 - i // 2, S("iron_block") if i < 4 else S("tinted_glass"))
        dish(g, 54, G, 8, 6, m, stalk=4)
    else:
        hx, hz = 52, 48
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
                g.set(x, top, z, m["dark"] if abs(x - hx) == 3 or abs(z - hz) == 3 else slab(m["dark_slab"], "top"))
        for a in range(0, 360, 20):
            g.set(hx, top + 4 + round(2.6 * math.sin(math.radians(a))), hz + round(2.6 * math.cos(math.radians(a))), m["dark"])
        for i, sx in enumerate((48, 55)):
            silo(g, sx, G, 8, 2.6, 11, m)
        for x in range(47, 58):
            for z in (21, 31):
                g.set(x, FY + 7, z, m["light"])

    # Clear terrain just around what was built (2-block margin).
    used = {}
    for (x, y, z), st in g.c.items():
        if st != AIR and y >= FY:
            t = g.t.get((x, y, z), 1)
            used[(x, z)] = min(used.get((x, z), 9), t)
    for x in range(OX):
        for z in range(OZ):
            near = [used[(x + a, z + b)] for a in range(-2, 3) for b in range(-2, 3) if (x + a, z + b) in used]
            if not near:
                continue
            g.tier = min(near)
            for y in range(G, G + 18):
                if (x, y, z) not in g.c:
                    g.set(x, y, z, AIR)
    g.tier = 1
    write_tiers(name, g, pressure, beacon=[17, G + 2, 26], ground=G)


PORT_SC = style(hull=S("sw:station_panel"), hull2=S("light_gray_concrete"), accent=S("cyan_concrete"), glass=S("light_blue_stained_glass"),
                core=S("sea_lantern"), rim=S("sea_lantern"))
REFINERY_SC = style(hull=S("sw:station_panel"), hull2=S("gray_concrete"), accent=S("orange_concrete"), glass=S("light_gray_stained_glass"),
                    core=S("ochre_froglight"), rim=S("ochre_froglight"), deck=S("sw:station_floor"))


def main():
    station("station_port", PORT_SC, "port")
    station("station_refinery", REFINERY_SC, "refinery")
    outpost("outpost_settlement", style(accent=S("cyan_concrete"), glass=S("light_blue_stained_glass")), "settlement")
    outpost("outpost_mining", style(accent=S("orange_concrete"), glass=S("orange_stained_glass")), "mining")
    outpost("outpost_research", style(accent=S("purple_concrete"), glass=S("purple_stained_glass")), "research")


if __name__ == "__main__":
    main()
