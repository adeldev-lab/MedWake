#!/usr/bin/env python3
"""Build the 1024x500 store feature graphic from the real alarm screenshot."""
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import os

HERE = os.path.dirname(os.path.abspath(__file__))
W, H = 1024, 500

# ---- background -------------------------------------------------------------
top, bottom = (7, 22, 61), (21, 94, 239)
bg = Image.new("RGB", (W, H))
px = bg.load()
for y in range(H):
    for x in range(W):
        t = (x / W) * 0.55 + (y / H) * 0.45
        r = int(top[0] + (bottom[0] - top[0]) * t)
        g = int(top[1] + (bottom[1] - top[1]) * t)
        b = int(top[2] + (bottom[2] - top[2]) * t)
        px[x, y] = (r, g, b)

# soft glow behind the phone
glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
gd = ImageDraw.Draw(glow)
gd.ellipse((700, -80, 1180, 620), fill=(80, 150, 255, 70))
glow = glow.filter(ImageFilter.GaussianBlur(60))
bg = Image.alpha_composite(bg.convert("RGBA"), glow)

draw = ImageDraw.Draw(bg)
BOLD = "/usr/share/fonts/abattis-cantarell-fonts/Cantarell-Bold.otf"
REG = "/usr/share/fonts/abattis-cantarell-fonts/Cantarell-Regular.otf"
f_word = ImageFont.truetype(BOLD, 62)
f_tag = ImageFont.truetype(REG, 29)
f_badge = ImageFont.truetype(BOLD, 15)
f_note = ImageFont.truetype(REG, 17)

# icon
icon = Image.open(os.path.join(HERE, "icon.png")).convert("RGBA")
icon = icon.resize((76, 76), Image.LANCZOS)
bg.alpha_composite(icon, (64, 42))

draw.text((160, 44), "MedWake", font=f_word, fill=(255, 255, 255, 255))
draw.rounded_rectangle((66, 144, 126, 149), radius=3, fill=(137, 248, 206, 255))
draw.text((68, 166), "A medication alarm that", font=f_tag, fill=(231, 240, 255, 255))
draw.text((68, 202), "waits until you wake up.", font=f_tag, fill=(231, 240, 255, 255))

# badges
badges = ["100% OFFLINE", "OPEN SOURCE", "GPL-3.0"]
bx = 68
for label in badges:
    tw = draw.textlength(label, font=f_badge)
    w = int(tw) + 30
    draw.rounded_rectangle((bx, 286, bx + w, 320), radius=17,
                           fill=(5, 18, 48, 150), outline=(137, 248, 206, 160), width=1)
    draw.text((bx + 15, 294), label, font=f_badge, fill=(182, 255, 226, 255))
    bx += w + 10

draw.text((68, 428), "No account  ·  No tracking  ·  No network permission",
          font=f_note, fill=(174, 198, 255, 255))

# ---- phone ------------------------------------------------------------------
shot = Image.open(os.path.join(HERE, "raw-alarm.png")).convert("RGB")
pw, ph = 216, 480
shot = shot.resize((pw, ph), Image.LANCZOS)
mask = Image.new("L", (pw, ph), 0)
ImageDraw.Draw(mask).rounded_rectangle((0, 0, pw - 1, ph - 1), radius=26, fill=255)

# shadow
shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
sd = ImageDraw.Draw(shadow)
sd.rounded_rectangle((754, 18, 754 + pw + 8, 18 + ph + 8), radius=30, fill=(0, 8, 24, 170))
shadow = shadow.filter(ImageFilter.GaussianBlur(18))
bg = Image.alpha_composite(bg, shadow)

layer = Image.new("RGBA", (pw, ph), (0, 0, 0, 0))
layer.paste(shot, (0, 0), mask)
# thin light border so the phone reads against the dark right side
border = Image.new("RGBA", (pw + 6, ph + 6), (0, 0, 0, 0))
ImageDraw.Draw(border).rounded_rectangle((0, 0, pw + 5, ph + 5), radius=29,
                                         outline=(255, 255, 255, 120), width=2)
bg.alpha_composite(border, (752, 12))
bg.alpha_composite(layer, (755, 15))

bg.convert("RGB").save(os.path.join(HERE, "feature-graphic.png"), "PNG", optimize=True)
print("wrote feature-graphic.png")
