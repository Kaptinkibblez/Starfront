# Generates the greeble and ship light textures (M9 pass 3): 16x16 pixel art in the hull plating's palette.
# Run: python3 tools/greeble_textures.py
import os, random
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "starwake", "textures", "block")
random.seed(7)


def img():
    return Image.new("RGBA", (16, 16), (0, 0, 0, 0))


def noise(c, n=6):
    d = random.randint(-n, n)
    return tuple(max(0, min(255, v + d)) for v in c[:3]) + (c[3] if len(c) > 3 else 255,)


def fill(im, c, n=5):
    for x in range(16):
        for y in range(16):
            im.putpixel((x, y), noise(c, n))


def save(im, name):
    im.save(os.path.join(OUT, name + ".png"))


DARK = (58, 62, 70, 255)
MID = (96, 102, 112, 255)
LIGHT = (150, 156, 166, 255)

# Dark metal: panel with a bevel and two rivets
im = img(); fill(im, DARK, 4)
for i in range(16):
    im.putpixel((i, 0), (74, 79, 88, 255)); im.putpixel((0, i), (74, 79, 88, 255))
    im.putpixel((i, 15), (44, 47, 54, 255)); im.putpixel((15, i), (44, 47, 54, 255))
for (x, y) in [(3, 3), (12, 12)]:
    im.putpixel((x, y), (100, 106, 116, 255))
save(im, "greeble_dark")

# Vent: horizontal slats
im = img(); fill(im, (40, 43, 50, 255), 3)
for y in range(1, 15, 3):
    for x in range(1, 15):
        im.putpixel((x, y), noise(LIGHT, 4)); im.putpixel((x, y + 1), noise(MID, 4))
save(im, "greeble_vent")

# Pipe: brushed steel with a coupling band
im = img(); fill(im, (132, 138, 148, 255), 6)
for x in range(16):
    for y in range(16):
        if y % 4 == 0:
            im.putpixel((x, y), noise((160, 166, 176, 255), 3))
for y in range(16):
    for x in (6, 7):
        im.putpixel((x, y), noise((84, 90, 100, 255), 3))
save(im, "greeble_pipe")

# Conduit: dark with orange marker stripes
im = img(); fill(im, (66, 70, 78, 255), 4)
for y in range(16):
    for x in (2, 13):
        im.putpixel((x, y), noise((200, 120, 40, 255), 6))
save(im, "greeble_conduit")

# Grate: see-through mesh (cutout)
im = img()
for x in range(16):
    for y in range(16):
        if x % 4 == 0 or y % 4 == 0 or x == 15 or y == 15:
            im.putpixel((x, y), noise(MID, 6))
save(im, "greeble_grate")

# Hazard trim: yellow/black diagonal stripes
im = img()
for x in range(16):
    for y in range(16):
        im.putpixel((x, y), noise((222, 180, 30, 255) if ((x + y) // 4) % 2 == 0 else (30, 30, 34, 255), 5))
save(im, "greeble_hazard")

# Tank: pale painted metal with a band and a label stripe
im = img(); fill(im, (176, 182, 188, 255), 5)
for y in range(16):
    for x in (3, 12):
        im.putpixel((x, y), noise((110, 116, 126, 255), 3))
for x in range(5, 11):
    im.putpixel((x, 7), (60, 120, 170, 255)); im.putpixel((x, 8), (60, 120, 170, 255))
save(im, "greeble_tank")

# Light lenses: bright centre, soft edge; "off" is a dim glass
LENS = {"white": (250, 248, 236), "red": (255, 70, 60), "green": (80, 255, 110), "amber": (255, 180, 50), "blue": (90, 170, 255),
        "off": (70, 74, 82)}
for name, c in LENS.items():
    im = img()
    for x in range(16):
        for y in range(16):
            d = max(abs(x - 7.5), abs(y - 7.5)) / 8
            k = 1.0 - 0.35 * d * d
            im.putpixel((x, y), noise(tuple(int(v * k) for v in c) + (255,), 3))
    save(im, "ship_light_" + name)
print("ok")
