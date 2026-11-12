#!/usr/bin/env python3
"""Item textures for M8e-1 fabrication: silicon wafer, microchip and the sub-components.
Writes src/main/resources/assets/starwake/textures/item/<name>.png (16x16, hand-drawn in code)."""
import os
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "starwake", "textures", "item")


def hexrgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def new():
    return Image.new("RGBA", (16, 16), (0, 0, 0, 0))


def px(im, x, y, c):
    if 0 <= x < 16 and 0 <= y < 16:
        im.putpixel((x, y), hexrgb(c) + (255,))


def rect(im, x0, y0, x1, y1, c):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px(im, x, y, c)


def disc(im, cx, cy, r, c, rim=None):
    for y in range(16):
        for x in range(16):
            d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
            if d <= r:
                px(im, x, y, rim if rim and d > r - 1 else c)


def wafer():
    im = new()
    disc(im, 7.5, 7.5, 7, "#5a6a8a", "#8a96b0")
    for y in range(3, 13, 2):
        for x in range(3, 13, 2):
            if (x - 7.5) ** 2 + (y - 7.5) ** 2 < 28:
                px(im, x, y, "#9ab0d8")
    for x in range(6, 10):
        im.putpixel((x, 14), (0, 0, 0, 0))  # the orientation flat
    return im


def microchip():
    im = new()
    rect(im, 4, 4, 11, 11, "#202428")
    rect(im, 5, 5, 10, 10, "#30363c")
    px(im, 5, 5, "#8a8f96")
    for i in range(5, 11, 2):
        for (x, y) in ((i, 2), (i, 3), (i, 12), (i, 13), (2, i), (3, i), (12, i), (13, i)):
            px(im, x, y, "#c8c8c0")
    return im


def pressure_vessel():
    im = new()
    rect(im, 4, 3, 11, 12, "#9aa2ac")
    disc(im, 7.5, 3.5, 3.5, "#aab2bc")
    disc(im, 7.5, 12.5, 3.5, "#8a929c")
    rect(im, 5, 4, 5, 11, "#c8d0d8")
    rect(im, 7, 0, 8, 1, "#6a7078")
    return im


def structural_frame():
    im = new()
    for i in range(2, 14):
        px(im, i, 2, "#8a929c"); px(im, i, 13, "#8a929c"); px(im, 2, i, "#8a929c"); px(im, 13, i, "#8a929c")
        px(im, i, i, "#aab2bc"); px(im, i, 15 - i, "#7a828c")
    return im


def combustion_chamber():
    im = new()
    rect(im, 4, 2, 11, 9, "#8a7a6a")
    rect(im, 5, 3, 10, 8, "#c87a3a")
    rect(im, 6, 10, 9, 13, "#7a6a5a")
    rect(im, 5, 1, 10, 1, "#6a6a70")
    return im


def nozzle():
    im = new()
    for y in range(2, 15):
        half = 1 + (y - 2) * 0.5
        for x in range(16):
            if abs(x - 7.5) <= half:
                px(im, x, y, "#b8603a" if abs(x - 7.5) > half - 1 else "#5a3a2a")
    rect(im, 6, 0, 9, 1, "#8a8a90")
    return im


def turbopump():
    im = new()
    disc(im, 7.5, 7.5, 6, "#8a929c", "#6a727c")
    for a in range(6):
        import math
        t = a * math.pi / 3
        for r in range(2, 6):
            px(im, int(7.5 + r * math.cos(t + r * 0.25)), int(7.5 + r * math.sin(t + r * 0.25)), "#d8dde4")
    disc(im, 7.5, 7.5, 1.5, "#3a3e46")
    return im


def heat_exchanger():
    im = new()
    rect(im, 2, 3, 13, 12, "#5a6068")
    for y in range(4, 12, 2):
        rect(im, 3, y, 12, y, "#d88a4a")
    rect(im, 0, 5, 1, 6, "#9aa4b0"); rect(im, 14, 9, 15, 10, "#9aa4b0")
    return im


def magnet_coil():
    im = new()
    rect(im, 6, 1, 9, 14, "#5a5e66")
    for y in range(3, 13):
        rect(im, 3, y, 12, y, "#d8843a" if y % 2 else "#b86a2a")
    return im


def control_board():
    im = new()
    rect(im, 1, 3, 14, 12, "#2a6a3a")
    rect(im, 3, 5, 6, 8, "#202428")
    rect(im, 9, 5, 12, 8, "#202428")
    for x in range(2, 14, 2):
        px(im, x, 11, "#e8c860")
    rect(im, 7, 6, 8, 6, "#c8a050")
    rect(im, 4, 9, 11, 9, "#c8a050")
    return im


# M8h gear: grey heads/plates (tinted by the alloy's colour in game) and untinted wooden/grip handles.
def grey(v):
    return "#%02x%02x%02x" % (v, v, v)


def handle(points):
    im = new()
    for (x, y) in points:
        px(im, x, y, "#6a4a2a")
        px(im, x + 1, y, "#4a3420")
    return im


def diag(a, b):
    return [(a + i, b - i) for i in range(9)]


def pickaxe_head():
    im = new()
    for i in range(11):
        x, y = 2 + i, 3 + abs(5 - i) // 2
        px(im, x, y - 1, grey(230))
        px(im, x, y, grey(190))
    return im


def axe_head():
    im = new()
    for y in range(2, 9):
        for x in range(8, 14):
            if (x - 8) + (8 - y) // 2 < 6:
                px(im, x, y, grey(215 - (x - 8) * 8))
    return im


def shovel_head():
    im = new()
    for y in range(1, 7):
        for x in range(9, 15):
            if abs((x - 11.5) + (y - 3.5)) < 3:
                px(im, x, y, grey(220 - y * 6))
    return im


def sword_blade():
    im = new()
    for i in range(10):
        px(im, 5 + i, 10 - i, grey(235))
        px(im, 6 + i, 10 - i, grey(185))
    px(im, 4, 11, grey(160)); px(im, 6, 13, grey(160)); px(im, 3, 10, grey(160)); px(im, 7, 14, grey(160))
    return im


def armor(kind):
    im = new()
    shapes = {
        "helmet": [(x, y) for y in range(3, 10) for x in range(3, 13) if not (y > 6 and 5 < x < 10)],
        "chestplate": [(x, y) for y in range(2, 15) for x in range(2, 14) if not (y < 5 and 5 < x < 10) and not (y > 4 and (x < 4 or x > 11) and y > 8)],
        "leggings": [(x, y) for y in range(2, 15) for x in range(3, 13) if not (y > 5 and 6 < x < 9)],
        "boots": [(x, y) for y in range(7, 14) for x in range(2, 14) if not (6 < x < 9) and not (y < 10 and (x < 4 or x > 11))],
    }
    for (x, y) in shapes[kind]:
        px(im, x, y, grey(205 - (y % 4) * 8))
    return im


def main():
    os.makedirs(OUT, exist_ok=True)
    items = dict(silicon_wafer=wafer, microchip=microchip, pressure_vessel=pressure_vessel, structural_frame=structural_frame,
                 combustion_chamber=combustion_chamber, nozzle=nozzle, turbopump=turbopump, heat_exchanger=heat_exchanger,
                 magnet_coil=magnet_coil, control_board=control_board)
    items.update(material_pickaxe=pickaxe_head, material_axe=axe_head, material_shovel=shovel_head, material_sword=sword_blade,
                 material_helmet=lambda: armor("helmet"), material_chestplate=lambda: armor("chestplate"),
                 material_leggings=lambda: armor("leggings"), material_boots=lambda: armor("boots"))
    for name, fn in items.items():
        fn().save(os.path.join(OUT, name + ".png"))
    for name in ("material_pickaxe", "material_axe", "material_shovel"):
        handle(diag(2, 14)).save(os.path.join(OUT, name + "_handle.png"))
    im = new()
    for (x, y) in [(2, 13), (3, 12), (1, 14)]:
        px(im, x, y, "#4a3420")
    im.save(os.path.join(OUT, "material_sword_handle.png"))
    print(f"{len(items)} fabrication textures")


if __name__ == "__main__":
    main()
