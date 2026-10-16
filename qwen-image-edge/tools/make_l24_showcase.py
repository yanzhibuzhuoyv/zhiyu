#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""L24 成果展示：每条提示词（中文原文）+ 原版 L36 + 剪层 L24 并排"""
import os, json, textwrap
from PIL import Image, ImageDraw, ImageFont

ROOT = r"C:\Users\32202\Desktop\99999\qwen-image-edge"
OUT = os.path.join(ROOT, "exp1_out")
PL = os.path.join(ROOT, "tools", "prompts_all.jsonl")

def font(sz):
    for p in [r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\msyhl.ttc",
              r"C:\Windows\Fonts\simhei.ttf", r"C:\Windows\Fonts\simsun.ttc"]:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, sz)
            except Exception:
                pass
    return ImageFont.load_default()

F_TXT = font(17)
F_TAG = font(16)
F_HD  = font(22)

prompts = {}
for l in open(PL, encoding="utf-8"):
    if l.strip():
        d = json.loads(l)
        prompts[d["id"]] = d

def build(ids, name, title, cell=250, textw=340):
    rows = []
    for pid in ids:
        p36 = os.path.join(OUT, f"L36_{pid}.png")
        p24 = os.path.join(OUT, f"L24_{pid}.png")
        if not (os.path.exists(p36) and os.path.exists(p24)):
            continue
        i36 = Image.open(p36).convert("RGB"); i36.thumbnail((cell, cell))
        i24 = Image.open(p24).convert("RGB"); i24.thumbnail((cell, cell))
        rows.append((pid, i36, i24))
    if not rows:
        print(f"{name}: 无图"); return
    ch = max(max(a.height, b.height) for _, a, b in rows) + 60
    W = textw + cell * 2 + 40
    H = ch * len(rows) + 46
    sh = Image.new("RGB", (W, H), (252, 252, 252))
    d = ImageDraw.Draw(sh)
    d.text((12, 12), title, fill=(20, 20, 20), font=F_HD)
    y = 46
    for pid, i36, i24 in rows:
        pr = prompts.get(pid, {})
        cat = pr.get("cat") or f"{pr.get('style','')}/{pr.get('scene','')}"
        # 提示词文本（自动换行）
        d.text((12, y + 4), f"{pid}  {cat}", fill=(150, 30, 30), font=F_TAG)
        txt = pr.get("prompt", "")
        yy = y + 28
        for line in textwrap.wrap(txt, width=20)[:9]:
            d.text((12, yy), line, fill=(40, 40, 40), font=F_TXT)
            yy += 22
        x24 = textw + 8
        sh.paste(i36, (x24, y + 26)); d.text((x24, y + 6), "原版 L36", fill=(20, 20, 20), font=F_TAG)
        x20 = x24 + cell + 12
        sh.paste(i24, (x20, y + 26)); d.text((x20, y + 6), "剪到 L24（省 2.32B）", fill=(20, 90, 20), font=F_TAG)
        y += ch
    p = os.path.join(OUT, name); sh.save(p)
    print("写出:", p, f"{sh.size[0]}x{sh.size[1]}")

ids = ["A01","A02","A03","A04","A05","A06","A07","A08","A09","A10",
       "A11","A12","A13","A14","A15","A16","L01","L02","L03","L04"]
for i in range(0, len(ids), 5):
    build(ids[i:i+5], f"L24展示_{i//5+1}.png", f"L24 剪层成果（{i+1}-{i+5}/20）：左=提示词，中=原版 L36，右=剪到 L24")
