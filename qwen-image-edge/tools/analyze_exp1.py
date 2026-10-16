#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
实验一收尾：① 各层隐状态对第36层的余弦相似度  ② 做几张可视对比图
"""
import os, json, torch
from PIL import Image, ImageDraw

ROOT = r"C:\Users\32202\Desktop\99999\qwen-image-edge"
EMB = os.path.join(ROOT, "embeds")
OUT = os.path.join(ROOT, "exp1_out")
LAYERS = [36, 32, 28, 24, 20, 16, 12, 8]

# ---------- 1) 余弦相似度（只在有效 token 上算） ----------
idx = json.load(open(os.path.join(EMB, "index.json"), encoding="utf-8"))
print("=== 各层隐状态 vs 第 36 层 的余弦相似度 ===")
print(f"{'id':<8}{'tok':>5}", "".join(f"{('L'+str(l)):>10}" for l in LAYERS if l != 36))
agg = {l: [] for l in LAYERS if l != 36}
rows = []
for pid in sorted(idx):
    rec = torch.load(os.path.join(EMB, idx[pid]["file"]), weights_only=False)
    base = rec["layers"][36]["embeds"].float()
    bm = rec["layers"][36]["mask"].bool()
    line = f"{pid:<8}{rec['ntok']:>5}"
    vals = {}
    for l in LAYERS:
        if l == 36:
            continue
        e = rec["layers"][l]["embeds"].float()
        m = rec["layers"][l]["mask"].bool() & bm
        c = torch.nn.functional.cosine_similarity(e[m].flatten(), base[m].flatten(), dim=0).item()
        vals[l] = c
        agg[l].append(c)
        line += f"{c:>10.4f}"
    rows.append((pid, vals))
    print(line)
print("-" * (13 + 10 * (len(LAYERS) - 1)))
print(f"{'平均':<8}{'':>5}", "".join(f"{sum(agg[l])/len(agg[l]):>10.4f}" for l in LAYERS if l != 36))
print()
print("=== 剪层收益（文本编码器主干，36 层 × 193M 参数/层）===")
for l in LAYERS:
    d = 36 - l
    print(f"  用第 {l:2d} 层：丢 {d:2d} 层 = 省 {d*0.193:.2f}B 参数 = bf16 {d*0.193*2:.2f}GB / int4 {d*0.193*0.5:.2f}GB"
          f"   余弦 {sum(agg[l])/len(agg[l]):.4f}" if l != 36 else f"  用第 36 层：基线")

# ---------- 2) 可视对比图 ----------
def sheet(pids, name, cell=300, title=""):
    rows = []
    for pid in pids:
        ims = []
        for l in LAYERS:
            p = os.path.join(OUT, f"L{l:02d}_{pid}.png")
            if os.path.exists(p):
                im = Image.open(p).convert("RGB"); im.thumbnail((cell, cell)); ims.append((l, im))
        if ims:
            rows.append((pid, ims))
    if not rows:
        return None
    cw = max(i.width for _, r in rows for _, i in r) + 8
    ch = max(i.height for _, r in rows for _, i in r) + 26
    sh = Image.new("RGB", (cw * len(LAYERS), ch * len(rows) + 30), (255, 255, 255))
    d = ImageDraw.Draw(sh)
    d.text((8, 8), title, fill=(0, 0, 0))
    for ri, (pid, ims) in enumerate(rows):
        for ci, (l, im) in enumerate(ims):
            x, y = ci * cw + 4, ri * ch + 30
            sh.paste(im, (x, y + 22))
            tag = f"{pid}  L{l}" + ("" if l == 36 else "  剪层")
            d.text((x, y + 4), tag, fill=(0, 0, 0))
    p = os.path.join(OUT, name)
    sh.save(p)
    print("写出:", p, f"{sh.size[0]}x{sh.size[1]}")
    return p

sheet(["A01", "A05", "A09"], "cmp_风格三类.png", 300, "真人 / 二次元 / 3D —— 从左到右：原版 L36 → 逐层剪枝")
sheet(["A13", "A14", "A15", "A16"], "cmp_难点四类.png", 300, "手部 / 多人 / 中文招牌 / 计数 —— 左：原版 L36 → 右：剪到 L20")
sheet(["L01", "L02"], "cmp_长提示词.png", 300, "长提示词（约140-148 token）")

# 深度剪枝对比：L36 / L24 / L16 / L12 / L8
DEEP = [36, 24, 16, 12, 8]
def deep_sheet(pids, name, cell=280, title=""):
    rows = []
    for pid in pids:
        ims = []
        for l in DEEP:
            p = os.path.join(OUT, f"L{l:02d}_{pid}.png")
            if os.path.exists(p):
                im = Image.open(p).convert("RGB"); im.thumbnail((cell, cell)); ims.append((l, im))
        if ims:
            rows.append((pid, ims))
    if not rows:
        print(f"  {name}: 无可用图"); return
    cw = max(i.width for _, r in rows for _, i in r) + 8
    ch = max(i.height for _, r in rows for _, i in r) + 26
    sh = Image.new("RGB", (cw * len(DEEP), ch * len(rows) + 30), (255, 255, 255))
    d = ImageDraw.Draw(sh)
    d.text((8, 8), title, fill=(0, 0, 0))
    for ri, (pid, ims) in enumerate(rows):
        for ci, (l, im) in enumerate(ims):
            x, y = ci * cw + 4, ri * ch + 30
            sh.paste(im, (x, y + 22))
            d.text((x, y + 4), f"{pid}  L{l}" + ("" if l == 36 else f"  剪{36-l}层"), fill=(0, 0, 0))
    p = os.path.join(OUT, name)
    sh.save(p)
    print("写出:", p, f"{sh.size[0]}x{sh.size[1]}")

print()
deep_sheet(["A01", "A05", "A09"], "cmp_深度剪枝_风格.png", 280, "深度剪枝：L36 → L24 → L16 → L12 → L8")
deep_sheet(["A13", "A14", "A15"], "cmp_深度剪枝_难点.png", 280, "深度剪枝下的 手部 / 多人 / 中文文字")
