#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""画风保真度专项：找二次元/3D 风格在哪一层开始崩（用户核心需求）"""
import os
from PIL import Image, ImageDraw
ROOT = r"C:\Users\32202\Desktop\99999\qwen-image-edge"
OUT = os.path.join(ROOT, "exp1_out")
LAYERS = [36, 32, 28, 24, 20]

def sheet(pids, name, cell=290, title=""):
    rows = []
    for pid in pids:
        ims = []
        for l in LAYERS:
            p = os.path.join(OUT, f"L{l:02d}_{pid}.png")
            if os.path.exists(p):
                im = Image.open(p).convert("RGB"); im.thumbnail((cell, cell)); ims.append((l, im))
        if len(ims) == len(LAYERS):
            rows.append((pid, ims))
    if not rows:
        print(f"{name}: 图不全"); return
    cw = max(i.width for _, r in rows for _, i in r) + 8
    ch = max(i.height for _, r in rows for _, i in r) + 28
    sh = Image.new("RGB", (cw*len(LAYERS), ch*len(rows)+32), (255,255,255))
    d = ImageDraw.Draw(sh)
    d.text((10, 10), title, fill=(0,0,0))
    for ri,(pid,ims) in enumerate(rows):
        for ci,(l,im) in enumerate(ims):
            x,y = ci*cw+4, ri*ch+32
            sh.paste(im,(x,y+22))
            d.text((x,y+4), f"{pid} L{l}" + ("" if l==36 else f" 剪{36-l}层"), fill=(0,0,0))
    p = os.path.join(OUT, name); sh.save(p)
    print("写出:", p, f"{sh.size[0]}x{sh.size[1]}")

# 二次元 / 3D 风格保真
sheet(["A05","A06","A07","A08"], "画风保真_二次元.png", 290, "二次元四条（窗边/樱花/雨夜/草原）—— 从左 L36 → 右 L20")
sheet(["A09","A10","A11","A12"], "画风保真_3D.png", 290, "3D 四条（皮克斯/迪士尼/餐车/探险）—— 从左 L36 → 右 L20")
# 真人对照
sheet(["A01","A02","A03","A04"], "画风保真_真人对照.png", 290, "真人四条 —— 从左 L36 → 右 L20")
