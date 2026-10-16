#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""3D 组第二个 seed 的部分核对（A09@1024²，s42 vs s1234），数据来自中断前已完成的图。"""
import os, json
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = r"C:\Users\32202\Desktop\99999\qwen-image-edge"
LAYERS = [36, 32, 28, 24, 20]
TAG = {36: "L36 原版", 32: "L32 剪4层", 28: "L28 剪8层", 24: "L24 剪12层", 20: "L20 剪16层"}
COL = {36: (20, 20, 20), 32: (20, 90, 160), 28: (150, 90, 10), 24: (20, 120, 20), 20: (170, 20, 120)}


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


ROWS = [("A09 @1024² seed42", "exp_1024"), ("A09 @1024² seed1234", "exp_1024_s1234"),
        ("A09 @512² seed42", "exp1_out")]
cell = 280
F_T, F_L, F_H = font(15), font(16), font(21)
W = cell * len(LAYERS) + 8 * (len(LAYERS) - 1) + 24
ch = cell + 34
sh = Image.new("RGB", (W, ch * len(ROWS) + 44), (252, 252, 252))
d = ImageDraw.Draw(sh)
d.text((12, 12), "3D 画风：A09（皮克斯小男孩）跨 seed / 跨分辨率核对（s1234 的 1024² 图在中断前已完成）",
       fill=(20, 20, 20), font=F_H)
print("饱和度：")
y = 44
for label, sub in ROWS:
    d.text((12, y + 4), label, fill=(150, 30, 30), font=F_T)
    line = [label]
    for ci, L in enumerate(LAYERS):
        p = os.path.join(ROOT, sub, f"L{L:02d}_A09.png")
        if not os.path.exists(p):
            continue
        im = Image.open(p).convert("RGB")
        line.append(f"L{L}={sat(np.asarray(im)):.1f}")
        im.thumbnail((cell, cell))
        x = 12 + ci * (cell + 8)
        sh.paste(im, (x, y + 30))
        d.text((x + 2, y + 8), TAG[L], fill=COL[L], font=F_L)
    print("  " + "  ".join(line))
    y += ch
out = os.path.join(ROOT, "exp_1024", "1024对照_21_3D跨seed核查_A09.png")
sh.save(out)
print("写出:", out, sh.size)
