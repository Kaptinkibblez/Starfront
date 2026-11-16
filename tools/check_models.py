#!/usr/bin/env python3
"""Finds same-direction faces of different elements that lie in one plane and overlap (z-fighting)."""
import json, sys, glob, itertools
AX = {"north": (2, 0), "south": (2, 1), "west": (0, 0), "east": (0, 1), "down": (1, 0), "up": (1, 1)}
bad = 0
for path in sys.argv[1:]:
    els = json.load(open(path))["elements"]
    faces = []
    for i, e in enumerate(els):
        if "rotation" in e: continue
        for d, f in e["faces"].items():
            a, side = AX[d]
            plane = e["to"][a] if side else e["from"][a]
            o = [k for k in range(3) if k != a]
            rect = [(e["from"][k], e["to"][k]) for k in o]
            faces.append((i, d, round(plane, 4), rect, e.get("from"), e.get("to")))
    for f1, f2 in itertools.combinations(faces, 2):
        if f1[0] == f2[0] or f1[1] != f2[1] or f1[2] != f2[2]: continue
        if f1[4] == f2[4] and f1[5] == f2[5]: continue   # intentional identical overlay
        ov = all(min(r1[1], r2[1]) - max(r1[0], r2[0]) > 1e-3 for r1, r2 in zip(f1[3], f2[3]))
        if ov:
            bad += 1
            print(path.split("/")[-1], f1[1], "plane", f1[2], "elements", f1[0], f2[0])
print("overlaps:", bad)
