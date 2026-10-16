#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""L36（原版） vs L28（剪8层） vs L24（剪12层）三列对照 —— 带中文提示词"""
import os, json, textwrap
from PIL import Image, ImageDraw, ImageFont

ROOT = r"C:\Users\32202\Desktop\99999\qwen-image-edge"
OUT = os.path.join(ROOT, "exp1_out")
PL = os.path.join(ROOT, "tools", "prompts_all.jsonl")
COLS = [(36, "原版 L36", (20,20,20)), (28, "L28 剪8层", (20,90,160)), (24, "L24 剪12层", (20,120,20))]

def font(sz):
    for p in [r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\simhei.ttf", r"C:\Windows\Fonts\simsun.ttc"]:
        if os.path.exists(p):
            try: return ImageFont.truetype(p, sz)
            except Exception: pass
    return ImageFont.load_default()

F_TXT, F_TAG, F_HD = font(16), font(15), font(21)
prompts = {}
for l in open(PL, encoding="utf-8"):
    if l.strip():
        d = json.loads(l); prompts[d["id"]] = d

def build(ids, name, title, cell=250, textw=330):
    rows = []
    for pid in ids:
        ims = []
        for L, _, _ in COLS:
            p = os.path.join(OUT, f"L{L:02d}_{pid}.png")
            if not os.path.exists(p): break
            im = Image.open(p).convert("RGB"); im.thumbnail((cell, cell)); ims.append((L, im))
        if len(ims) == len(COLS):
            rows.append((pid, ims))
    if not rows:
        print(f"{name}: 图不全"); return
    ch = max(max(i.height for _, i in r) for _, r in rows) + 62
    W = textw + cell * 3 + 50
    sh = Image.new("RGB", (W, ch * len(rows) + 44), (252, 252, 252))
    d = ImageDraw.Draw(sh)
    d.text((12, 12), title, fill=(20, 20, 20), font=F_HD)
    y = 44
    for pid, ims in rows:
        pr = prompts.get(pid, {})
        cat = pr.get("cat") or f"{pr.get('style','')}/{pr.get('scene','')}"
        d.text((12, y + 4), f"{pid}  {cat}", fill=(150, 30, 30), font=F_TAG)
        yy = y + 26
        for line in textwrap.wrap(pr.get("prompt", ""), width=19)[:10]:
            d.text((12, yy), line, fill=(40, 40, 40), font=F_TXT); yy += 21
        for ci, (L, im) in enumerate(ims):
            x = textw + 6 + ci * (cell + 8)
            sh.paste(im, (x, y + 26))
            d.text((x, y + 6), COLS[ci][1], fill=COLS[ci][2], font=F_TAG)
        y += ch
    p = os.path.join(OUT, name); sh.save(p)
    print("写出:", p, f"{sh.size[0]}x{sh.size[1]}")

ids = ["A01","A02","A03","A04","A05","A06","A07","A08","A09","A10",
       "A11","A12","A13","A14","A15","A16","L01","L02","L03","L04"]
for i in range(0, len(ids), 4):
    build(ids[i:i+4], f"L28vs24_{i//4+1}.png",
          f"L36 原版 / L28 剪8层 / L24 剪12层 对照（{i+1}-{i+4}/20）")
