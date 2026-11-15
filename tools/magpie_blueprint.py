# Generates the Magpie blueprint (M9 pass 3e): an original heavy salvage ship at true scale (1 block = 1 m, ~150 m).
#
# Same class and scale as the big salvage haulers of space games, but its own design: a long slab hull with chamfered
# edges; an off-centre bridge island on the port shoulder; an exposed lattice spine with ribs along the top; a hinged
# salvage claw (boom and two jaws) under the bow that swings down for work when the gear is down and folds up under the
# chin in flight; two gear-linked engine pods on the flanks (pointing down for VTOL with gear down, swinging back for
# cruise); a processing bay through the middle of the hull with catwalks, cargo racks and lights; tractor rotors under
# the bow; a stern engine block; systems decks; RCS clusters; running lights and beacons; hazard trim; landing gear.
#
# Axes: x west->east, y bottom->top, z nose (north) -> tail. Run: python3 tools/magpie_blueprint.py <out.json>
import json, sys

L, W, H = 152, 40, 34
g = {}

# --- palette helpers ------------------------------------------------------------------------------------------------
DIRS = ["down", "up", "north", "south", "west", "east"]
STEP = {"down": (0, -1, 0), "up": (0, 1, 0), "north": (0, 0, -1), "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0)}
AXIS = {"down": "y", "up": "y", "north": "z", "south": "z", "west": "x", "east": "x"}
CW = {"y": {"north": "east", "east": "south", "south": "west", "west": "north"},
      "x": {"down": "south", "south": "up", "up": "north", "north": "down"},
      "z": {"down": "west", "west": "up", "up": "east", "east": "down"}}


def orient(up, front):
    """PanelGeometry.orient: up x the four fronts around it (first perpendicular in Direction order, then clockwise)."""
    base = DIRS.index(up) * 4
    f = next(d for d in DIRS if AXIS[d] != AXIS[up])
    for k in range(4):
        if f == front:
            return base + k
        f = CW[AXIS[up]][f]
    raise ValueError((up, front))


def panel(shape, up="up", front="north"):
    return f"starwake:hull_panel[shape={shape},orient={orient(up, front)}]"


def greeble(shape, up="up", front="north"):
    return f"starwake:greeble[shape={shape},orient={orient(up, front)}]"


def light(kind, color="white", up="up", front="north"):
    return f"starwake:ship_light[type={kind},color={color},orient={orient(up, front)}]"


HULL = "starwake:hull_plating"
AIR = "minecraft:air"
GLASS = "starwake:canopy_glass"


KEEP_CLEAR = []  # (x0, x1, y0, y1, z0, z1) boxes nothing may touch: gaps that keep hinged parts free


def put(x, y, z, state, force=False):
    if not force:
        for (a, b, c, d, e, f) in KEEP_CLEAR:
            if a <= x <= b and c <= y <= d and e <= z <= f:
                return
    if 0 <= x < W and 0 <= y < H and 0 <= z < L:
        g[(x, y, z)] = state


def box(x0, x1, y0, y1, z0, z1, state):
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            for z in range(z0, z1 + 1):
                put(x, y, z, state)


def shell(x0, x1, y0, y1, z0, z1, state=HULL, inside=AIR):
    box(x0, x1, y0, y1, z0, z1, state)
    if x1 - x0 >= 2 and y1 - y0 >= 2 and z1 - z0 >= 2:
        box(x0 + 1, x1 - 1, y0 + 1, y1 - 1, z0 + 1, z1 - 1, inside)


# --- main hull: x 6..33, y 4..22, z 14..134, a tapered hexagonal section (narrow keel, bevelled shoulders) ------------
HX0, HX1, HY0, HY1, HZ0, HZ1 = 6, 33, 4, 22, 14, 134
# Gaps beside the hinged engine pods (z 76..92) and under the bow claw: only the hinges cross them.
POD_Z0, POD_Z1 = 76, 92
KEEP_CLEAR.append((HX0 - 1, HX0 - 1, 0, H, POD_Z0, POD_Z1))
KEEP_CLEAR.append((HX1 + 1, HX1 + 1, 0, H, POD_Z0, POD_Z1))
INSET = {HY0: 4, HY0 + 1: 3, HY0 + 2: 2, HY0 + 3: 1, HY1 - 2: 1, HY1 - 1: 2, HY1: 3}


def row(y):
    """The hull's west and east wall x at height y."""
    i = INSET.get(y, 0)
    return HX0 + i, HX1 - i


def ring(z, fill=AIR):
    for y in range(HY0, HY1 + 1):
        x0, x1 = row(y)
        if y in (HY0, HY1):
            box(x0, x1, y, y, z, z, HULL)
        else:
            put(x0, y, z, HULL); put(x1, y, z, HULL)
            box(x0 + 1, x1 - 1, y, y, z, z, fill)
        # 45-degree bevels where the section steps in (keel below, shoulders above)
        if y < HY0 + 4:
            put(x0 - 1, y, z, panel("slope", "down", "west")); put(x1 + 1, y, z, panel("slope", "down", "east"))
        if y > HY1 - 3:
            put(x0 - 1, y, z, panel("slope", "up", "west")); put(x1 + 1, y, z, panel("slope", "up", "east"))


for z in range(HZ0, HZ1 + 1):
    ring(z)
# Side armour: ribbed belt panels and sponsons fore and aft of the engine pods
for z in range(HZ0 + 4, HZ1 - 3):
    if z % 2 == 0:
        for y in range(HY0 + 11, HY0 + 14):
            put(HX0 - 1, y, z, greeble("ribbed_panel", "west", "up")); put(HX1 + 1, y, z, greeble("ribbed_panel", "east", "up"))
for (z0, z1) in ((48, 68), (102, 124)):
    for side, xs in (("west", range(HX0 - 3, HX0)), ("east", range(HX1 + 1, HX1 + 4))):
        for x in xs:
            box(x, x, HY0 + 5, HY0 + 9, z0, z1, HULL)
            put(x, HY0 + 10, z0, panel("corner", "up", "north" if side == "west" else "west"))
            for z in range(z0, z1 + 1):
                put(x, HY0 + 10, z, panel("slope", "up", side))
                put(x, HY0 + 4, z, panel("slope", "down", side))
            for y in range(HY0 + 5, HY0 + 10):
                put(x, y, z0 - 1, panel("slope", side, "north")); put(x, y, z1 + 1, panel("slope", side, "south"))
        outer = HX0 - 4 if side == "west" else HX1 + 4
        for z in range(z0 + 2, z1 - 1, 4):
            put(outer, HY0 + 7, z, greeble("vent", side, "up"))

# Bow: the hull narrows and slopes down to a blunt prow (z 4..13) over the claw bay
for i, z in enumerate(range(13, 3, -1)):
    inset = 1 + i // 2
    top = HY1 - 1 - i
    if top <= HY0 + 3:
        break
    box(HX0 + inset, HX1 - inset, HY0 + 3, HY0 + 3, z, z, HULL)          # bow deck floor over the claw bay
    for x in range(HX0 + inset, HX1 - inset + 1):
        put(x, top, z, panel("slope", "up", "north"))                     # sloped bow roof
    box(HX0 + inset, HX0 + inset, HY0 + 3, top - 1, z, z, HULL)          # bow cheeks
    box(HX1 - inset, HX1 - inset, HY0 + 3, top - 1, z, z, HULL)
    box(HX0 + inset + 1, HX1 - inset - 1, HY0 + 4, top - 1, z, z, AIR)
    put(HX0 + inset, top, z, panel("corner", "up", "north"))
    put(HX1 - inset, top, z, panel("corner", "up", "west"))
# Prow plate and hazard trim around the claw mouth
box(HX0 + 6, HX1 - 6, HY0 + 3, HY0 + 7, 3, 3, HULL)
for x in range(HX0 + 6, HX1 - 5):
    put(x, HY0 + 8, 3, panel("slope", "up", "north"))
    put(x, HY0 + 2, 4, greeble("hazard_trim", "down", "north"))

# Stern: an engine block that steps down toward the nozzles (z 135..149)
shell(HX0 + 2, HX1 - 2, HY0 + 2, HY1 - 2, 135, 147)
for z in range(135, 148):
    put(HX0 + 2, HY1 - 2, z, panel("quarter_round", "up", "west"))
    put(HX1 - 2, HY1 - 2, z, panel("quarter_round", "up", "east"))

# --- processing bay: the hull's middle third, open inside, with catwalks, racks, lights and a belly door ------------
BZ0, BZ1 = 44, 104
for z in range(BZ0, BZ1 + 1):
    for y in (HY0 + 7, HY0 + 13):                                          # two catwalk levels along both walls
        for x in (HX0 + 1, HX0 + 2, HX1 - 2, HX1 - 1):
            put(x, y, z, greeble("catwalk"))
        put(HX0 + 3, y + 1, z, greeble("railing", "up", "west"))
        put(HX1 - 3, y + 1, z, greeble("railing", "up", "east"))
    if z % 6 == 0:
        put((HX0 + HX1) // 2, HY1 - 1, z, light("panel", "white", "down", "north"))
        for x in (row(HY0 + 1)[0] + 1, row(HY0 + 1)[1] - 1):
            put(x, HY0 + 1, z, greeble("cargo_rack"))
    if z % 4 == 0:
        put(HX0 + 1, HY1 - 4, z, greeble("pipe", "east", "north"))
        put(HX1 - 1, HY1 - 4, z, greeble("pipe", "west", "north"))
# Processing line down the bay floor: heatsinks, tanks, conduits between bulkhead frames
for z in range(BZ0 + 4, BZ1 - 3, 8):
    for x in range(HX0 + 9, HX1 - 8):
        put(x, HY0 + 1, z, greeble("conduit", "up", "north"))
    box(HX0 + 10, HX1 - 10, HY0 + 1, HY0 + 1, z + 2, z + 4, greeble("heatsink"))
    put(HX0 + 9, HY0 + 1, z + 3, greeble("tank", "up", "north"))
    put(HX1 - 9, HY0 + 1, z + 3, greeble("tank", "up", "north"))
for z in (BZ0, BZ1):
    for y in range(HY0 + 1, HY1):
        box(row(y)[0] + 1, row(y)[1] - 1, y, y, z, z, panel("frame"))
    box(HX0 + 8, HX1 - 8, HY0 + 1, HY0 + 9, z, z, AIR)                      # bulkhead openings
# Belly door outline: hazard trim on the outside of the bay floor
for z in range(BZ0 + 2, BZ1 - 1):
    put(HX0 + 8, HY0 - 1, z, greeble("hazard_trim", "down", "north"))
    put(HX1 - 8, HY0 - 1, z, greeble("hazard_trim", "down", "north"))

# --- exposed spine: open lattice truss with ribs over the hull top (z 30..128) ---------------------------------------
SX0, SX1 = 16, 23
for z in range(30, 129):
    for x in (SX0, SX1):
        put(x, HY1 + 1, z, panel("lattice", "up", "north"))
        put(x, HY1 + 4, z, panel("lattice", "up", "north"))
    if z % 7 == 2:
        for y in range(HY1 + 1, HY1 + 5):
            put(SX0, y, z, panel("frame")); put(SX1, y, z, panel("frame"))
        for x in range(SX0 + 1, SX1):
            put(x, HY1 + 4, z, panel("rib", "west", "up"))
    if z % 7 == 5:
        for x in range(SX0 + 2, SX1 - 1):
            put(x, HY1 + 1, z, greeble("pipe_pair", "up", "west"))
for z in range(36, 128, 12):
    put((SX0 + SX1) // 2, HY1 + 1, z, greeble("vent"))
    put(SX0 - 3, HY1 + 1, z, greeble("vent")); put(SX1 + 3, HY1 + 1, z, greeble("vent"))

# --- bridge island: an off-centre (port) superstructure with a sloped glass front ------------------------------------
BX0, BX1, BY0, BY1, BZ_0, BZ_1 = 7, 15, HY1, HY1 + 7, 24, 38
shell(BX0, BX1, BY0, BY1, BZ_0, BZ_1)
box(BX0 + 1, BX1 - 1, BY0 + 2, BY1 - 1, BZ_0, BZ_0, GLASS)                   # windscreen
for z in range(BZ_0 + 1, BZ_0 + 7):
    put(BX0, BY0 + 3, z, GLASS); put(BX0, BY0 + 4, z, GLASS)                 # side windows
    put(BX1, BY0 + 3, z, GLASS); put(BX1, BY0 + 4, z, GLASS)
for x in range(BX0, BX1 + 1):
    put(x, BY1, BZ_0, panel("slope", "up", "north"))
    put(x, BY1 + 1, BZ_0 + 2, panel("slope_2_low", "up", "north"))
HELM = (11, BY0 + 1, BZ_0 + 3)
put(*HELM, "starwake:helm[facing=north]")
for (x, z) in ((9, BZ_0 + 5), (13, BZ_0 + 5), (9, BZ_0 + 8), (13, BZ_0 + 8)):
    put(x, BY0 + 1, z, "starwake:seat")
for z in range(BZ_0 + 2, BZ_1 - 1, 3):
    put(11, BY1 - 1, z, light("strip", "white", "down", "west"))
# Mast, antennas and beacons on the island roof
for y in range(BY1 + 1, BY1 + 6):
    put(11, y, BZ_1 - 3, panel("lattice", "north", "up"))
put(11, BY1 + 6, BZ_1 - 3, light("beacon", "red"))
put(9, BY1 + 1, BZ_1 - 1, greeble("antenna")); put(13, BY1 + 1, BZ_1 - 1, greeble("antenna"))
put(11, BY1 + 1, BZ_1 - 6, "starwake:rotor[facing=up,speed=1]")
put(11, BY1 + 2, BZ_1 - 6, greeble("heatsink", "up", "north"))                # radar plate on the rotor

# --- systems: power, cooling, fuel, shields, quantum drive in the aft decks ------------------------------------------
SYS = []
for z in range(108, 132, 2):
    for x in range(HX0 + 5, HX1 - 4, 2):
        SYS.append((x, HY0 + 1, z))                                           # on the hull floor
        SYS.append((x, HY0 + 5, z))                                           # on the deck above
kinds = (["starwake:power_plant_mk3"] * 50 + ["starwake:cooler_mk3"] * 17 + ["starwake:fuel_tank_mk3"] * 30
         + ["starwake:shield_generator_mk3"] * 6 + ["starwake:quantum_drive_mk3"] * 2)
for pos, kind in zip(SYS, kinds):
    put(*pos, kind)
for z in range(108, 132):
    box(row(HY0 + 4)[0] + 1, row(HY0 + 4)[1] - 1, HY0 + 4, HY0 + 4, z, z, greeble("catwalk"))  # decks between system rows

# --- lift: fixed belly engines (two banks fore and aft of the bay) + engine pods on hinges ---------------------------
LIFT = []
for z in list(range(22, 38, 4)) + list(range(108, 132, 4)):
    for x in range(HX0 + 5, HX1 - 4, 4):
        LIFT.append((x, HY0 - 1, z))
for (x, y, z) in LIFT:
    put(x, y, z, "starwake:main_engine_mk3[facing=down]")

# Engine pods: 3 wide, built pointing down (gear-down pose); the hinges swing them back for cruise.
for side, hx, pod_x, facing, up_angle in (("west", HX0 - 1, range(HX0 - 4, HX0 - 1), "west", 6),
                                         ("east", HX1 + 1, range(HX1 + 2, HX1 + 5), "east", 18)):
    hy, hz = 13, 84
    put(hx, hy, hz, f"starwake:hinge[facing={facing},down_angle=0,up_angle={up_angle}]", force=True)
    for x in pod_x:
        for y in range(hy - 6, hy + 5):
            for z in range(hz - 7, hz + 8):
                put(x, y, z, HULL)
        for z in range(hz - 7, hz + 8):
            put(x, hy - 7, z, "starwake:main_engine_mk3[facing=down]")         # VTOL nozzles
            put(x, hy + 5, z, panel("half_round", "up", "north"))
        for y in range(hy - 6, hy + 5):
            put(x, y, hz - 8, panel("quarter_round", "north", "up" if y > hy else "down"))
    outer = min(pod_x) if side == "west" else max(pod_x)
    for z in range(hz - 6, hz + 7, 3):
        put(outer + (-1 if side == "west" else 1), hy, z, greeble("vent", side, "up"))
    put(outer + (-1 if side == "west" else 1), hy + 3, hz - 7, light("running", "red" if side == "west" else "green", side, "up"))

# --- cruise and manoeuvring --------------------------------------------------------------------------------------------
for x in range(HX0 + 5, HX1 - 4, 2):
    for y in range(HY0 + 4, HY1 - 3, 2):
        put(x, y, 148, "starwake:main_engine_mk3[facing=south]")              # stern mains
for y in range(HY0 + 4, HY0 + 16, 2):
    put(HX0 - 1, y, HZ0, "starwake:main_engine_mk3[facing=north]")           # retro mains on the bow shoulders
    put(HX1 + 1, y, HZ0, "starwake:main_engine_mk3[facing=north]")
for z in (16, 18, 20, 22, 126, 128, 130, 132):
    for y in (HY0 + 4, HY0 + 6, HY0 + 8, HY0 + 12, HY0 + 14):
        put(HX0 - 1, y, z, "starwake:thruster_mk3[facing=west]")             # strafe / yaw
        put(HX1 + 1, y, z, "starwake:thruster_mk3[facing=east]")
    for x in (HX0 + 4, HX1 - 4):
        put(x, HY1 + 1, z, "starwake:thruster_mk3[facing=up]")                # pitch
        put(x, HY0 - 1, z, "starwake:thruster_mk3[facing=down]")
for z in range(60, 90, 6):
    put(HX0 - 1, HY1 - 4, z, "starwake:maneuvering_thruster_mk3[facing=west]") # roll helpers
    put(HX1 + 1, HY1 - 4, z, "starwake:maneuvering_thruster_mk3[facing=east]")
    put(HX0 + 4, HY1 + 1, z, "starwake:maneuvering_thruster_mk3[facing=up]")
    put(HX1 - 4, HY1 + 1, z, "starwake:maneuvering_thruster_mk3[facing=up]")

# --- salvage claw: a boom and two jaws under the bow, on a hinge facing north --------------------------------------
CX, CY = (HX0 + HX1) // 2, HY0 + 1
put(CX, CY, 5, "starwake:hinge[facing=north,down_angle=0,up_angle=4]")
for z in range(0, 5):
    box(CX - 1, CX + 1, CY - 1, CY + 1, z, z, HULL)                          # boom
for z in range(0, 3):
    for x in (CX - 4, CX - 3, CX + 3, CX + 4):
        put(x, CY - 2, z, HULL)                                               # jaw arms
    put(CX - 2, CY - 1, z, HULL); put(CX + 2, CY - 1, z, HULL)
for x in (CX - 4, CX + 4):
    for y in (CY - 3, CY - 4, CY - 5):
        put(x, y, 0, HULL)                                                    # jaw teeth
    put(x, CY - 6, 0, panel("slope", "down", "north"))
for x in range(CX - 4, CX + 5):
    put(x, CY - 2, 3, greeble("hazard_trim", "south", "up"))
put(CX - 4, CY - 2, 2, light("flood", "white", "down", "north")); put(CX + 4, CY - 2, 2, light("flood", "white", "down", "north"))

# --- tractor mounts: rotors under the bow shoulders carrying emitter heads ----------------------------------------
for x in (HX0 + 4, HX1 - 4):
    put(x, HY0 - 1, 18, "starwake:rotor[facing=down,speed=2]")
    put(x, HY0 - 2, 18, greeble("antenna", "down", "north"))
    put(x, HY0 - 3, 18, light("running", "blue", "down", "north"))

# --- landing gear: struts in two rows along the belly ---------------------------------------------------------------
for z in (20, 44, 70, 96, 122, 133):
    for x in (HX0 + 5, HX1 - 5):
        put(x, HY0 - 1, z, "starwake:landing_gear_mk3[deployed=true]")
    put(HX0 + 5, HY0 - 1, z + 2, light("landing", "white", "down", "north"))
    put(HX1 - 5, HY0 - 1, z + 2, light("landing", "white", "down", "north"))

# --- livery details: running lights, beacons, hull greebles ----------------------------------------------------------
for z in range(20, 134, 10):
    put(HX0 - 1, HY0 + 15, z, light("running", "red", "west", "up"))
    put(HX1 + 1, HY0 + 15, z, light("running", "green", "east", "up"))
    put(HX0 - 1, HY0 + 5, z + 3, greeble("conduit", "west", "up"))
    put(HX1 + 1, HY0 + 5, z + 3, greeble("conduit", "east", "up"))
for z in (50, 100, 132):
    put(SX0 + 3, HY1 + 5, z, light("beacon", "white"))
for z in range(16, 134, 3):
    put(HX0 - 1, HY0 + 10, z, greeble("seam", "west", "up"))
    put(HX1 + 1, HY0 + 10, z, greeble("seam", "east", "up"))
for x in range(HX0 + 4, HX1 - 3, 3):
    put(x, HY1 + 1, 136, greeble("hazard_trim"))

# --- emit ------------------------------------------------------------------------------------------------------------
pool = [chr(c) for c in range(0x100, 0x2000) if chr(c).isprintable()]
chars = {}
palette = {}
for state in sorted(set(g.values())):
    ch = "_" if state == AIR else pool[len(chars)]
    chars[state] = ch
    palette[ch] = state
layers = []
for y in range(H):
    layers.append(["".join(chars[g[(x, y, z)]] if (x, y, z) in g else "." for x in range(W)) for z in range(L)])
out = {"name": {"text": "Magpie"},
       "_comment": "Original heavy salvage ship (M9 pass 3e). Nose north. Generated by tools/magpie_blueprint.py.",
       "palette": palette, "layers": layers}
json.dump(out, open(sys.argv[1], "w"), ensure_ascii=False)
solid = sum(1 for v in g.values() if v != AIR)
lift = sum(1 for v in g.values() if v == "starwake:main_engine_mk3[facing=down]")
print(solid, "blocks;", lift, "lift engines; helm", HELM)
