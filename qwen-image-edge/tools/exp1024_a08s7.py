#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""补齐判定：A08 @1024² seed7 的 L36 / L24 / L20 三联（原生分辨率）"""
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = r"C:\Users\32202\Desktop\99999\qwen-image-edge"
D = os.path.join(ROOT, "exp_1024_s7")
PICK = [(36, "L36 原版（基线）"), (24, "L24 剪12层"), (20, "L20 剪16层")]
COL = {36: (20, 20, 20), 24: (20, 120, 20), 20: (170, 20, 120)}


def font(sz):
    for p in [r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\simhei.ttf"]:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, sz)
            except Exception:
                pass
    return ImageFont.load_default()


def sat(a):
    x = a.astype(np.float32) / 255.0
    mx = x.max(2); mn = x.min(2)
    return float(np.where(mx > 0, (mx - mn) / np.maximum(mx, 1e-6), 0).mean() * 100)


cell = 420
F_L, F_H = font(18), font(21)
sh = Image.new("RGB", (cell * 3 + 16 + 24, cell + 78), (252, 252, 252))
d = ImageDraw.Draw(sh)
d.text((12, 12), "A08 动漫少女草原奔跑 @1024² seed=7 —— 判定 L24/L20 是否写实化", fill=(20, 20, 20), font=F_H)
for i, (L, tag) in enumerate(PICK):
    p = os.path.join(D, f"L{L:02d}_A08.png")
    im = Image.open(p).convert("RGB")
    im.thumbnail((cell, cell))
    x = 12 + i * (cell + 8)
    sh.paste(im, (x, 40))
    d.text((x + 2, 46 + cell - 18), f"{tag}   饱和度 {sat(np.asarray(Image.open(p).convert('RGB'))):.1f}%",
           fill=COL[L], font=F_L)
out = os.path.join(ROOT, "exp_1024", "1024对照_22_A08_seed7三联.png")
sh.save(out)
print("写出:", out, sh.size)
