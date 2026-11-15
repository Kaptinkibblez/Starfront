# Generates the Kestrel blueprint: an original light fighter (M9 pass 2 redesign).
#
# No wings (starships don't fly on wings): a sloped wedge fuselage shaped with auto-connecting Hull Slopes and slabs,
# a frameless Canopy Glass bubble, side sponsons carrying the strafe/retro thrusters far from the centre of mass
# (yaw authority), four belly main engines for VTOL lift around the centre of mass, two rear mains, RCS for roll
# and pitch, and enough power plants that the engines get full power at the default 60/20/20 split.
#
# Axes: x west->east (13 wide), y bottom->top, z nose(north)->tail. Run: python3 tools/kestrel_blueprint.py <out.json>
import json, sys

W, L, H = 13, 18, 7
g = {}


def put(x, y, z, c):
    g[(x, y, z)] = c


def box(x0, x1, y0, y1, z0, z1, c):
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            for z in range(z0, z1 + 1):
                put(x, y, z, c)


C = 6  # centre line

# --- Nose (z 0..2): a wedge sloping down and in toward the tip ---------------------------------------------------
put(C, 1, 0, 'H'); put(C, 2, 0, 'n')                       # tip, with a slope on top
box(C - 1, C + 1, 1, 2, 1, 1, 'H'); put(C - 1, 3, 1, 'n'); put(C, 3, 1, 'n'); put(C + 1, 3, 1, 'n')
put(C - 2, 1, 1, 'w'); put(C + 2, 1, 1, 'e')               # cheeks
box(C - 2, C + 2, 1, 2, 2, 2, 'H'); put(C - 2, 2, 2, 'w'); put(C + 2, 2, 2, 'e')
put(C - 1, 3, 2, 'n'); put(C, 3, 2, 'n'); put(C + 1, 3, 2, 'n')   # low sloped dash: clear forward view
put(C, 0, 1, 'v'); put(C - 1, 0, 2, 'v'); put(C, 0, 2, 'v'); put(C + 1, 0, 2, 'v')  # chin

# --- Cockpit (z 3..7): floor, sloped sills, bubble canopy -----------------------------------------------------------
box(C - 2, C + 2, 1, 1, 3, 7, 'H')                         # floor
for z in range(3, 8):
    put(C - 2, 2, z, 'H'); put(C + 2, 2, z, 'H')           # sills
    put(C - 2, 3, z, 'G'); put(C + 2, 3, z, 'G')           # side glass
    put(C - 2, 4, z, 'G'); put(C + 2, 4, z, 'G')
box(C - 1, C + 1, 2, 4, 3, 3, 'G')                         # windscreen
box(C - 1, C + 1, 5, 5, 3, 7, 'G')                         # canopy roof (2 clear blocks above the Helm)
box(C - 1, C + 1, 2, 4, 4, 7, '.')                         # cabin air
put(C, 2, 5, 'K')                                          # helm, facing north
put(C, 2, 7, 'J')                                          # crew seat
for z in range(3, 8):
    put(C - 3, 1, z, 'w'); put(C + 3, 1, z, 'e')           # lower flanks

# --- Engineering spine (z 8..15) ---------------------------------------------------------------------------------
box(C - 2, C + 2, 1, 4, 8, 15, 'H')
box(C - 1, C + 1, 2, 3, 8, 14, '.')                        # corridor + bay air (joins the cabin)
for z in range(8, 16):
    put(C - 2, 4, z, 'w'); put(C + 2, 4, z, 'e')           # sloped shoulders (the walls end a block lower)
    put(C, 5, z, 't')                                      # roof ridge (carries the dorsal fin)
put(C, 6, 12, 'f'); put(C, 6, 13, 'f'); put(C, 6, 14, 'H'); put(C, 6, 15, 'H')   # dorsal fin
put(C, 6, 11, 'n')
# Systems along the corridor walls (inside the hull, x = C-1 / C+1 at y 2..3)
for (x, y, z, c) in ((C - 1, 2, 9, 'P'), (C + 1, 2, 9, 'P'), (C - 1, 3, 9, 'C'), (C + 1, 3, 9, 'C'),
                     (C - 1, 2, 10, 'P'), (C + 1, 2, 10, 'P'), (C - 1, 3, 10, 'C'), (C + 1, 3, 10, 'C'),
                     (C - 1, 2, 12, 'P'), (C - 1, 3, 12, 'C'),
                     (C + 1, 2, 12, 'S'), (C + 1, 3, 12, 'Q'),
                     (C + 1, 2, 11, 'P'), (C + 1, 3, 11, 'C'),
                     (C - 1, 2, 13, 'T'), (C + 1, 2, 13, 'T')):
    put(x, y, z, c)
put(C, 2, 14, 'H'); put(C, 3, 14, 'H')                     # aft bulkhead
for y in (1, 2, 3):
    put(C - 2, y, 15, 'w'); put(C + 2, y, 15, 'e')         # tapered tail corners

# --- Sponsons (x 2..3 and 9..10, z 8..14): carry the strafe/retro thrusters out at the sides ---------------------
for x in (C - 4, C - 3, C + 3, C + 4):
    for z in range(8, 15):
        put(x, 1, z, 'b' if x in (C - 4, C + 4) else 'H')   # slab outer edge, hull inner
for x in (C - 3, C + 3):
    for z in range(8, 15):
        put(x, 2, z, 'w' if x < C else 'e')                # slopes up into the spine
# Strafe/yaw: medium thrusters at the sponson ends, pushing sideways (nozzles out)
put(C - 5, 1, 8, 'X'); put(C - 5, 1, 14, 'X')
put(C + 5, 1, 8, 'Y'); put(C + 5, 1, 14, 'Y')
# Retros: medium thrusters at the sponson fronts, nozzles forward
put(C - 4, 1, 7, 'R'); put(C + 4, 1, 7, 'R')
# Roll: RCS up/down at the sponson tips
put(C - 4, 2, 9, 'u'); put(C + 4, 2, 9, 'u')
put(C - 4, 0, 11, 'd'); put(C + 4, 0, 11, 'd')

# --- Engines --------------------------------------------------------------------------------------------------------
put(C - 1, 2, 16, 'E'); put(C + 1, 2, 16, 'E')             # rear mains (nozzles south)
box(C - 1, C + 1, 1, 1, 16, 16, 'H'); put(C, 2, 16, 'H'); box(C - 1, C + 1, 3, 3, 16, 16, 't')
for x, z in ((C - 1, 6), (C + 1, 6), (C - 1, 14), (C + 1, 14)):
    put(x, 0, z, 'M')                                      # belly VTOL mains (nozzles down)
# Pitch RCS: nose down + tail up/down (none on top of the nose: it would block the view)
put(C, 0, 0, 'm')
put(C, 0, 15, 'm'); put(C - 1, 5, 15, 'u'); put(C + 1, 5, 15, 'u')

# --- Landing gear: nose + two mains around the centre of mass -----------------------------------------------------
put(C, 0, 4, 'L'); put(C - 3, 0, 12, 'L'); put(C + 3, 0, 12, 'L')

palette = {
    "H": "starwake:hull_plating",
    "G": "starwake:canopy_glass",
    "b": "starwake:hull_plating_slab[type=bottom]",
    "t": "starwake:hull_plating_slab[type=bottom]",
    "s": "starwake:hull_plating_slab[type=top]",
    # Hull Slopes (M9 pass 4; auto-connecting, so corners form where runs meet). orient = up x front (low side):
    # n = low side toward the nose; w/e = low side west/east; v = hung under the chin (up = down).
    "n": "starwake:hull_slope[orient=4]",
    "w": "starwake:hull_slope[orient=7]",
    "e": "starwake:hull_slope[orient=5]",
    "v": "starwake:hull_slope[orient=0]",
    "f": "starwake:hull_plating_slab[type=bottom]",
    "K": "starwake:helm[facing=north]", "J": "starwake:seat",
    "P": "starwake:power_plant", "C": "starwake:cooler", "S": "starwake:shield_generator",
    "Q": "starwake:quantum_drive", "T": "starwake:fuel_tank",
    "E": "starwake:main_engine[facing=south]", "M": "starwake:main_engine[facing=down]",
    "X": "starwake:thruster[facing=west]", "Y": "starwake:thruster[facing=east]",
    "R": "starwake:thruster[facing=north]",
    "u": "starwake:maneuvering_thruster[facing=up]", "d": "starwake:maneuvering_thruster[facing=down]",
    "m": "starwake:maneuvering_thruster[facing=down]",
    "L": "starwake:landing_gear[deployed=true]",
}

xs = [p[0] for p in g]; assert min(xs) >= 0 and max(xs) < W, (min(xs), max(xs))
layers = []
for y in range(H):
    layers.append([''.join(g.get((x, y, z), '.') for x in range(W)) for z in range(L)])
out = {"name": {"text": "Kestrel"},
       "_comment": "Original light fighter (M9 redesign: no wings, sloped hull, canopy glass). Nose north. Generated by tools/kestrel_blueprint.py.",
       "palette": palette, "layers": layers}
json.dump(out, open(sys.argv[1], 'w'), indent=2)
print(sum(1 for v in g.values() if v != '.'), "blocks")
