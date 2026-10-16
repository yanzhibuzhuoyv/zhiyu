#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""编辑稳定性对照：行=层(L36/L28/L24)，列=seed；最左为参考图"""
import os, json, sys
from PIL import Image, ImageDraw, ImageFont
ROOT = r"C:\Users\32202\Desktop\99999\qwen-image-edge"
OUT = os.path.join(ROOT, "edit_out")

def font(sz):
    for p in [r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\simhei.ttf"]:
        if os.path.exists(p):
            try: return ImageFont.truetype(p, sz)
            except Exception: pass
    return ImageFont.load_default()
F_TAG, F_HD = font(17), font(22)

def sheet(task, ref, layers=(36,28,24), seeds=(42,1234), cell=330, name=None):
    rows = []
    for L in layers:
        ims = []
        for s in seeds:
            p = os.path.join(OUT, f"{task}_L{L}_s{s}.png")
            if os.path.exists(p):
                im = Image.open(p).convert("RGB"); im.thumbnail((cell, cell)); ims.append((f"L{L} seed={s}", im))
        if ims: rows.append(ims)
    if not rows:
        print(f"{task}: 无图"); return
    refim = Image.open(ref).convert("RGB"); refim.thumbnail((cell, cell))
    ncol = 1 + max(len(r) for r in rows)
    cw = max([refim.width] + [i.width for r in rows for _, i in r]) + 12
    ch = max([refim.height] + [i.height for r in rows for _, i in r]) + 32
    sh = Image.new("RGB", (cw*ncol, ch*len(rows)+40), (252,252,252))
    d = ImageDraw.Draw(sh)
    d.text((12,10), f"{task} —— 左：参考图   右：各层 × 各 seed", fill=(20,20,20), font=F_HD)
    y = 40
    for ri, ims in enumerate(rows):
        sh.paste(refim, (6, y+26)); d.text((6, y+5), "参考图(ref)", fill=(150,30,30), font=F_TAG)
        for ci, (tag, im) in enumerate(ims):
            x = (ci+1)*cw + 6
            sh.paste(im, (x, y+26)); d.text((x, y+5), tag, fill=(20,90,20), font=F_TAG)
        y += ch
    p = os.path.join(OUT, name or f"对比_{task}.png"); sh.save(p)
    print("写出:", p, f"{sh.size[0]}x{sh.size[1]}")

ref = os.path.join(ROOT, "exp1_out", "L36_A01.png")
for t in ["E1_fullbody", "E2_picnic", "E3_elevator"]:
    sheet(t, ref)
# 汇总：所有已完成图一览
allp = sorted([f for f in os.listdir(OUT) if f.endswith(".png") and not f.startswith("对比_")])
print("\n已完成", len(allp), "张：")
for f in allp: print("  ", f)
