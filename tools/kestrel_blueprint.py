# Generates the Kestrel blueprint (an original light fighter in the Star Citizen style).
import json, sys
W, L, Hgt = 15, 18, 8          # x (west->east), z (nose->tail), y (bottom->top)
g = {}
def put(x, y, z, c): g[(x, y, z)] = c
def box(x0, x1, y0, y1, z0, z1, c):
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            for z in range(z0, z1 + 1): put(x, y, z, c)
# Nose
box(7, 7, 1, 2, 1, 1, 'H')
box(6, 8, 1, 3, 2, 2, 'H')
# Cockpit section z3..7: floor, walls, canopy
box(6, 8, 1, 1, 3, 3, 'H'); box(6, 8, 2, 2, 3, 3, 'H'); box(6, 8, 3, 4, 3, 3, 'G')   # windscreen
box(5, 9, 1, 1, 4, 7, 'H')                     # floor
for z in range(4, 8):
    for x in (5, 9):
        put(x, 2, z, 'H'); put(x, 3, z, 'G'); put(x, 4, z, 'G' if z < 7 else 'H')
box(6, 8, 5, 5, 3, 7, 'G')                     # bubble canopy roof
put(5, 5, 7, 'H'); put(9, 5, 7, 'H')
box(6, 8, 2, 4, 4, 7, '.')                     # cabin air
put(7, 2, 5, 'K')                              # helm, facing north
put(7, 2, 7, 'J')                              # crew seat
# Bulkhead and engineering z8..15
box(5, 9, 1, 5, 8, 8, 'H')
box(5, 9, 1, 5, 9, 10, 'H'); box(6, 8, 2, 4, 9, 10, '.')
box(5, 9, 1, 4, 11, 15, 'H'); box(6, 8, 2, 3, 11, 14, '.')
put(6, 2, 9, 'P'); put(7, 2, 9, 'P'); put(8, 2, 9, 'P')
put(6, 3, 9, 'C'); put(7, 3, 9, 'C'); put(8, 3, 9, 'C')
put(6, 2, 10, 'T'); put(7, 2, 10, 'Q'); put(8, 2, 10, 'T')
put(6, 3, 10, 'C'); put(7, 3, 10, 'S'); put(8, 3, 10, 'P')
# Main engines out the back
put(6, 3, 16, 'E'); put(8, 3, 16, 'E')
# Swept wings at y2
sweep = {8: (4, 4), 9: (3, 4), 10: (2, 4), 11: (1, 4), 12: (0, 4), 13: (0, 4)}
for z, (a, b) in sweep.items():
    for x in range(a, b + 1):
        put(x, 2, z, 'W'); put(14 - x, 2, z, 'W')
# Twin tail fins
for x in (5, 9):
    for z in range(12, 16):
        put(x, 5, z, 'F')
    for z in range(13, 16):
        put(x, 6, z, 'F')
# VTOL: main engines in the belly + thrusters under the wings
for x, z in ((6, 5), (8, 5), (6, 13), (8, 13)): put(x, 0, z, 'M')
for x in (2, 12): put(x, 1, 11, 'D')
# Maneuvering thrusters (RCS)
put(6, 2, 1, 'n'); put(8, 2, 1, 'n')                      # retros
put(5, 3, 2, 'w'); put(9, 3, 2, 'e')                      # nose yaw/strafe
put(4, 3, 14, 'w'); put(10, 3, 14, 'e')                   # tail yaw/strafe
put(7, 0, 2, 'd')                                        # nose pitch (none on top: it would block the view)
put(7, 5, 15, 'u'); put(7, 0, 15, 'd')                    # tail pitch
for x in (0, 14): put(x, 3, 12, 'u'); put(x, 1, 12, 'd')  # wingtip roll
palette = {
  "H": "starwake:hull_plating", "G": "minecraft:glass", "K": "starwake:helm[facing=north]", "J": "starwake:seat",
  "P": "starwake:power_plant", "C": "starwake:cooler", "S": "starwake:shield_generator", "Q": "starwake:quantum_drive",
  "T": "starwake:fuel_tank", "E": "starwake:main_engine[facing=south]", "M": "starwake:main_engine[facing=down]",
  "D": "starwake:thruster[facing=down]", "W": "starwake:wing[axis=y]", "F": "starwake:wing[axis=x]",
  "n": "starwake:maneuvering_thruster[facing=north]", "w": "starwake:maneuvering_thruster[facing=west]",
  "e": "starwake:maneuvering_thruster[facing=east]", "u": "starwake:maneuvering_thruster[facing=up]",
  "d": "starwake:maneuvering_thruster[facing=down]"}
layers = []
for y in range(Hgt):
    rows = []
    for z in range(L):
        rows.append(''.join(g.get((x, y, z), '.') for x in range(W)))
    layers.append(rows)
out = {"name": {"text": "Kestrel"}, "_comment": "Original light fighter, Star Citizen style. Nose north.",
       "palette": palette, "layers": layers}
json.dump(out, open(sys.argv[1], 'w'), indent=2)
print(sum(1 for v in g.values() if v != '.'), "blocks")
