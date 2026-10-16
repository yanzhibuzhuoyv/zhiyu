#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""三方对照：教师（原编码器） vs 1.7B 学生 vs 0.6B 学生"""
import os, json, textwrap
from PIL import Image, ImageDraw, ImageFont

ROOT = r"C:\Users\32202\Desktop\99999\qwen-image-edge"
COLS = [("exp1_out",        "教师 L36（原编码器）", (20, 20, 20)),
        ("student_v3_out",  "学生 1.7B+适配器",     (20, 90, 160)),
        ("student_06b_out", "学生 0.6B+适配器",     (20, 120, 20))]
IDS = ["A01", "A05", "A09", "A13", "A14", "A15", "A16", "L02", "L03"]


def font(sz):
    for p in [r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\simhei.ttf"]:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, sz)
            except Exception:
                pass
    return ImageFont.load_default()


F_TXT, F_TAG, F_HD = font(14), font(15), font(20)
prompts = {}
for l in open(os.path.join(ROOT, "tools", "prompts_all.jsonl"), encoding="utf-8"):
    if l.strip():
        d = json.loads(l)
        prompts[d["id"]] = d

cell, textw = 250, 280
rows = []
for pid in IDS:
    ims = []
    for d, tag, col in COLS:
        p = os.path.join(ROOT, d, f"L36_{pid}.png")
        if os.path.exists(p):
            im = Image.open(p).convert("RGB"); im.thumbnail((cell, cell))
            ims.append((tag, col, im))
    if len(ims) == len(COLS):
        rows.append((pid, ims))

ch = max(max(i.height for _, _, i in r) for _, r in rows) + 56
W = textw + cell * len(COLS) + 40
sh = Image.new("RGB", (W, ch * len(rows) + 44), (252, 252, 252))
d = ImageDraw.Draw(sh)
d.text((12, 12), "教师（原编码器 8.77B） vs 蒸馏学生 1.7B vs 蒸馏学生 0.6B   @512x512 同 seed",
       fill=(20, 20, 20), font=F_HD)
y = 44
for pid, ims in rows:
    pr = prompts.get(pid, {})
    d.text((12, y + 4), f"{pid}  {pr.get('style','')}/{pr.get('scene','')}",
           fill=(150, 30, 30), font=F_TAG)
    yy = y + 26
    for line in textwrap.wrap(pr.get("prompt", ""), width=16)[:12]:
        d.text((12, yy), line, fill=(45, 45, 45), font=F_TXT); yy += 19
    for ci, (tag, col, im) in enumerate(ims):
        x = textw + 6 + ci * (cell + 6)
        sh.paste(im, (x, y + 26))
        d.text((x, y + 6), tag, fill=col, font=F_TAG)
    y += ch
p = os.path.join(ROOT, "对比_教师_vs_1.7B_vs_0.6B.png")
sh.save(p)
print("写出:", p, f"{sh.size[0]}x{sh.size[1]}")
