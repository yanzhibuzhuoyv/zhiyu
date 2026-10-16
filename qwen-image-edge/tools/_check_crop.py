# -*- coding: utf-8 -*-
"""边界情形核查：把若干图的下半部分裁出来并排放大，判断画面底边裁在哪里。"""
import os
from PIL import Image, ImageDraw, ImageFont
ROOT = r"C:\Users\32202\Desktop\99999\qwen-image-edge"
D1 = os.path.join(ROOT, "edit_out"); D2 = os.path.join(ROOT, "edit_out_confirm")
CASES = [("E1_fullbody", 24, 99, 0.55), ("E1_fullbody", 24, 2024, 0.55),
         ("E1_fullbody", 24, 555, 0.55), ("E1_fullbody", 28, 2024, 0.55),
         ("E1_fullbody", 36, 7, 0.55)]
try:
    F = ImageFont.truetype(r"C:\Windows\Fonts\msyh.ttc", 20)
except Exception:
    F = ImageFont.load_default()
tiles = []
for task, L, s, frac in CASES:
    p = None
    for d in (D2, D1):
        q = os.path.join(d, f"{task}_L{L}_s{s}.png")
        if os.path.exists(q): p = q; break
    im = Image.open(p).convert("RGB")
    w, h = im.size
    top = int(h * (1 - frac))
    im = im.crop((0, top, w, h))
    sc = 420 / w
    im = im.resize((420, int(im.height * sc)))
    t = Image.new("RGB", (420, im.height + 28), (250, 250, 250))
    t.paste(im, (0, 28)); ImageDraw.Draw(t).text((6, 4), f"L{L} s{s} 下{int(frac*100)}%", fill=(180, 20, 20), font=F)
    tiles.append(t)
W = sum(t.width for t in tiles) + 10 * (len(tiles) - 1)
H = max(t.height for t in tiles)
sh = Image.new("RGB", (W, H), (255, 255, 255))
x = 0
for t in tiles:
    sh.paste(t, (x, 0)); x += t.width + 10
out = os.path.join(D2, "_边界核查_下段裁切.png")
sh.save(out); print("写出:", out, sh.size)
