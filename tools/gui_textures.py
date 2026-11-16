"""Machine screen sprites (M9): a nine-slice panel, slot wells, gauges and progress sprites in Starwake's
dark-steel-and-cyan style. Written to assets/starwake/textures/gui/sprites/machine/ (the GUI atlas stitches them;
.mcmeta files set nine-slice scaling). Run: python3 tools/gui_textures.py"""
import json
import os
import random
from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "starwake", "textures", "gui", "sprites", "machine")


def rgb(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def save(im, name, nine=None):
    os.makedirs(ROOT, exist_ok=True)
    im.save(os.path.join(ROOT, name + ".png"))
    meta = os.path.join(ROOT, name + ".png.mcmeta")
    if nine:
        w, h, border = nine
        with open(meta, "w") as f:
            json.dump({"gui": {"scaling": {"type": "nine_slice", "width": w, "height": h, "border": border}}}, f)
    elif os.path.exists(meta):
        os.remove(meta)


def panel():
    n = 48
    rnd = random.Random(7)
    im = Image.new("RGBA", (n, n))
    for y in range(n):
        for x in range(n):
            t = y / n
            base = [int(0x1b + (0x14 - 0x1b) * t), int(0x21 + (0x19 - 0x21) * t), int(0x2a + (0x21 - 0x2a) * t)]
            if (x + y) % 4 == 0:
                base = [c + 3 for c in base]
            j = rnd.randint(-2, 2)
            im.putpixel((x, y), tuple(max(0, min(255, c + j)) for c in base) + (246,))
    # bevel: light top/left, dark bottom/right, then a thin outer rim
    for i in range(n):
        im.putpixel((i, 0), rgb("#0b0e12")); im.putpixel((i, n - 1), rgb("#0b0e12"))
        im.putpixel((0, i), rgb("#0b0e12")); im.putpixel((n - 1, i), rgb("#0b0e12"))
        im.putpixel((i, 1), rgb("#4a5666")); im.putpixel((1, i), rgb("#4a5666"))
        im.putpixel((i, n - 2), rgb("#0f1318")); im.putpixel((n - 2, i), rgb("#0f1318"))
    # cyan corner brackets
    c = rgb("#55d8ff")
    for k in range(2, 8):
        for (x, y) in ((k, 2), (2, k), (n - 1 - k, 2), (n - 3, k), (k, n - 3), (2, n - 1 - k), (n - 1 - k, n - 3), (n - 3, n - 1 - k)):
            im.putpixel((x, y), c)
    save(im, "panel", (n, n, 8))


def slot(name, edge):
    im = Image.new("RGBA", (18, 18))
    for y in range(18):
        for x in range(18):
            col = rgb("#0a0d11")
            if x == 0 or y == 0:
                col = rgb("#05070a")
            elif x == 17 or y == 17:
                col = rgb(edge)
            elif x == 1 or y == 1:
                col = rgb("#080a0e")
            elif (x + y) % 2 == 0:
                col = rgb("#0d1116")
            im.putpixel((x, y), col)
    save(im, name)


def gauge():
    im = Image.new("RGBA", (10, 16))
    for y in range(16):
        for x in range(10):
            col = rgb("#0a0d11")
            if x in (0, 9) or y in (0, 15):
                col = rgb("#3a4350")
            im.putpixel((x, y), col)
    save(im, "gauge", (10, 16, 2))


def arrow(name, fill):
    w, h = 24, 16
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    for y in range(h):
        for x in range(w):
            inside = (5 <= y <= 10 and x <= 15) or (x >= 15 and abs(y - 7.5) <= (23 - x) * 0.9)
            edge = inside and not ((6 <= y <= 9 and 1 <= x <= 15) or (x >= 16 and abs(y - 7.5) <= (22 - x) * 0.9 - 0.6))
            if inside:
                im.putpixel((x, y), rgb("#3a4350") if edge else rgb(fill) if fill else rgb("#141a21"))
    save(im, name)


def flame(name, lit):
    im = Image.new("RGBA", (14, 14), (0, 0, 0, 0))
    for y in range(14):
        for x in range(14):
            d = abs(x - 6.5) / 6.5
            top = 13 * (1 - (1 - d) ** 0.7)
            if y >= top:
                if lit:
                    t = (y - top) / max(1, 13 - top)
                    col = (255, int(200 - 110 * t), int(80 - 60 * t), 255)
                else:
                    col = rgb("#2a313b")
                im.putpixel((x, y), col)
    save(im, name)


def main():
    panel()
    slot("slot", "#3a4350")
    slot("slot_output", "#c79a3a")
    gauge()
    arrow("arrow", None)
    arrow("arrow_full", "#7cffb2")
    flame("flame", False)
    flame("flame_full", True)
    print("gui sprites written")


if __name__ == "__main__":
    main()
