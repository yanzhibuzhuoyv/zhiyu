#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""L36（原版） vs L20（剪 16 层）逐条对照图 —— 用于人工判断 L20 是否可用"""
import os
from PIL import Image, ImageDraw

ROOT = r"C:\Users\32202\Desktop\99999\qwen-image-edge"
OUT = os.path.join(ROOT, "exp1_out")

def sheet(pids, name, cell=380, title=""):
    cols = [36, 20]
    rows = []
    for pid in pids:
        ims = []
        for l in cols:
            p = os.path.join(OUT, f"L{l:02d}_{pid}.png")
            if os.path.exists(p):
                im = Image.open(p).convert("RGB"); im.thumbnail((cell, cell)); ims.append((l, im))
        if len(ims) == 2:
            rows.append((pid, ims))
    if not rows:
        print(f"{name}: 无图"); return
    cw = max(i.width for _, r in rows for _, i in r) + 10
    ch = max(i.height for _, r in rows for _, i in r) + 30
    sh = Image.new("RGB", (cw * 2, ch * len(rows) + 34), (255, 255, 255))
    d = ImageDraw.Draw(sh)
    d.text((10, 10), title, fill=(0, 0, 0))
    for ri, (pid, ims) in enumerate(rows):
        for ci, (l, im) in enumerate(ims):
            x, y = ci * cw + 5, ri * ch + 34
            sh.paste(im, (x, y + 24))
            tag = f"{pid}   L{l}" + ("（原版）" if l == 36 else "（剪16层）")
            d.text((x, y + 6), tag, fill=(0, 0, 0))
    p = os.path.join(OUT, name)
    sh.save(p)
    print("写出:", p, f"{sh.size[0]}x{sh.size[1]}")

# 从 prompts_all.jsonl 取中文提示词，放到图上更直观
import json
pr = {json.loads(l)["id"]: json.loads(l) for l in open(os.path.join(ROOT, "tools", "prompts_all.jsonl"), encoding="utf-8") if l.strip()}

sheet(["A01", "A02", "A05", "A06"], "L20对比_真人二次元.png", 360,
      "真人（A01客厅/A02校园）+ 二次元（A05窗边/A06樱花）—— 左：原版 L36   右：剪16层 L20")
sheet(["A09", "A10", "A13", "A14"], "L20对比_3D与难点.png", 360,
      "3D（A09皮克斯/A10迪士尼）+ 手部(A13) + 多人(A14) —— 左：L36   右：L20")
sheet(["A15", "A16", "A03", "A07"], "L20对比_文字与其余.png", 360,
      "中文招牌(A15) + 计数(A16) + 街头(A03) + 雨夜二次元(A07) —— 左：L36   右：L20")
print()
for pid in ["A01", "A05", "A09", "A13", "A14", "A15", "A16"]:
    print(f"  {pid}: {pr[pid]['prompt'][:70]}")
