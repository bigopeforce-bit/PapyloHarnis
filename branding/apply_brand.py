#!/usr/bin/env python3
"""Apply O2OAgent branding: start label + standalone logo assets."""
import os, re, math
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

# --- 1. Rebrand the "Start harness" label (resource id unchanged) ---
START_LABELS = {
    "values": "Start O2OAgent",
    "values-ar": "\u0628\u062f\u0621 O2OAgent",
    "values-b+fil": "Simulan ang O2OAgent",
    "values-b+zh+Hant": "\u555f\u52d5 O2OAgent",
    "values-bn": "O2OAgent \u09b6\u09c1\u09b0\u09c1",
    "values-de": "O2OAgent starten",
    "values-es": "Iniciar O2OAgent",
    "values-fa": "\u0634\u0631\u0648\u0639 O2OAgent",
    "values-fr": "D\u00e9marrer O2OAgent",
    "values-hi": "O2OAgent \u0936\u0941\u0930\u0942 \u0915\u0930\u0947\u0902",
    "values-in": "Mulai O2OAgent",
    "values-ja": "O2OAgent \u3092\u958b\u59cb",
    "values-ko": "O2OAgent \uc2dc\uc791",
    "values-ms": "Mulakan O2OAgent",
    "values-pt-rBR": "Iniciar O2OAgent",
    "values-ru": "\u0417\u0430\u043f\u0443\u0441\u0442\u0438\u0442\u044c O2OAgent",
    "values-si": "O2OAgent \u0d86\u0dbb\u0db4\u0dca\u0db7 \u0d9a\u0dbb\u0db1\u0dca\u0db1",
    "values-ta": "O2OAgent \u0ba4\u0bca\u0b9f\u0b99\u0bcd\u0b95\u0bb5\u0bc1\u0bae\u0bcd",
    "values-th": "\u0e40\u0e23\u0e34\u0e48\u0e21 O2OAgent",
    "values-tr": "O2OAgent'i ba\u015flat",
    "values-vi": "B\u1eaft \u0111\u1ea7u O2OAgent",
    "values-zh-rCN": "\u542f\u52a8 O2OAgent",
}

changed = []
for loc, value in START_LABELS.items():
    path = os.path.join(RES, loc, "ui_strings.xml")
    if not os.path.isfile(path):
        print("MISSING", path)
        continue
    with open(path, encoding="utf-8") as f:
        text = f.read()
    pattern = re.compile(r'(<string name="ui_start_harness">).*?(</string>)', re.S)
    new, n = pattern.subn(lambda m: m.group(1) + '"' + value + '"' + m.group(2), text, count=1)
    if n == 1 and new != text:
        with open(path, "w", encoding="utf-8") as f:
            f.write(new)
        changed.append(loc)
print("Start labels updated in", len(changed), "locales")

# --- 2. Standalone logo geometry (two interlocking O rings + bridge dot) ---
# 512 canvas, safe area 108..404. Ring outer r=76, inner r=48.
S = 512
CX1, CX2, CY = 185, 327, 256
RO, RI = 76, 48
BRIDGE_R = 24
BG = "#101426"
CYAN = "#22D3EE"
INDIGO = "#6366F1"
WHITE = "#FFFFFF"

def ring_path(cx, cy, ro, ri):
    # evenOdd annulus: outer circle + inner circle
    return (f"M{cx-ro},{cy} a{ro},{ro} 0 1,0 {2*ro},0 a{ro},{ro} 0 1,0 {-2*ro},0 "
            f"M{cx-ri},{cy} a{ri},{ri} 0 1,1 {2*ri},0 a{ri},{ri} 0 1,1 {-2*ri},0")

def circle_path(cx, cy, r):
    return f"M{cx-r},{cy} a{r},{r} 0 1,0 {2*r},0 a{r},{r} 0 1,0 {-2*r},0"

svg = f'''<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {S} {S}" width="{S}" height="{S}" role="img" aria-label="O2OAgent logo">
  <title>O2OAgent</title>
  <rect width="{S}" height="{S}" rx="{int(S*0.22)}" fill="{BG}"/>
  <path fill-rule="evenodd" fill="{CYAN}" d="{ring_path(CX1, CY, RO, RI)}"/>
  <path fill-rule="evenodd" fill="{INDIGO}" d="{ring_path(CX2, CY, RO, RI)}"/>
  <circle cx="{(CX1+CX2)//2}" cy="{CY}" r="{BRIDGE_R}" fill="{WHITE}"/>
</svg>
'''
brand_dir = os.path.join(ROOT, "branding")
os.makedirs(brand_dir, exist_ok=True)
with open(os.path.join(brand_dir, "o2oagent-logo.svg"), "w", encoding="utf-8") as f:
    f.write(svg)
print("Wrote o2oagent-logo.svg")

# Render PNG with supersampling for smooth edges (PIL, no external tools).
SS = 3
img = Image.new("RGB", (S * SS, S * SS), BG)
d = ImageDraw.Draw(img)
d.rounded_rectangle((0, 0, S*SS - 1, S*SS - 1), radius=int(S*SS*0.22), fill=BG)

def sc(v): return v * SS

def draw_ring(draw, cx, cy, ro, ri, color):
    box_o = (sc(cx-ro), sc(cy-ro), sc(cx+ro), sc(cy+ro))
    box_i = (sc(cx-ri), sc(cy-ri), sc(cx+ri), sc(cy+ri))
    draw.ellipse(box_o, fill=color)
    draw.ellipse(box_i, fill=BG)

draw_ring(d, CX1, CY, RO, RI, CYAN)
draw_ring(d, CX2, CY, RO, RI, INDIGO)
mx = (CX1 + CX2) // 2
d.ellipse((sc(mx-BRIDGE_R), sc(CY-BRIDGE_R), sc(mx+BRIDGE_R), sc(CY+BRIDGE_R)), fill=WHITE)

img = img.resize((S, S), Image.LANCZOS)
img.save(os.path.join(brand_dir, "o2oagent-logo.png"))
print("Wrote o2oagent-logo.png")
