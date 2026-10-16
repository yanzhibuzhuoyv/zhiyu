#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""多样本（3 seed）画风安全线验证（新建脚本，只读现有图片）
- 512² 与 1024² 各 3 个 seed（42 / 1234 / 7）× 4 条二次元提示词 × 5 层
- 输出饱和度/边缘密度表（写实化 → 饱和度下降）
- 生成按 seed 分行的视觉对照表
"""
import os, sys, json
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = r"C:\Users\32202\Desktop\99999\qwen-image-edge"
LAYERS = [36, 32, 28, 24, 20]
GROUPS = {"anime": ("二次元", ["A05", "A06", "A07", "A08"]),
          "3d": ("3D卡通", ["A09", "A10", "A11", "A12"])}
GRP = (sys.argv[1] if len(sys.argv) > 1 else "anime").lower()
if GRP not in GROUPS:
    GRP = "anime"
GNAME, ANIME = GROUPS[GRP]
SEEDS = [42, 1234, 7]
DIRS = {
    (1024, 42): "exp_1024", (1024, 1234): "exp_1024_s1234", (1024, 7): "exp_1024_s7",
    (512, 42): "exp1_out", (512, 1234): "exp_512_s1234", (512, 7): "exp_512_s7",
}
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


def sat_edge(a):
    x = a.astype(np.float32) / 255.0
    mx = x.max(2); mn = x.min(2); df = mx - mn
    s = np.where(mx > 0, df / np.maximum(mx, 1e-6), 0.0)
    g = a.astype(np.float32).mean(2)
    gx = np.abs(np.diff(g, axis=1))[:-1, :]; gy = np.abs(np.diff(g, axis=0))[:, :-1]
    edge = float((np.sqrt(gx ** 2 + gy ** 2) > 24).mean() * 100)
    return float(s.mean() * 100), edge


def path(res, seed, L, pid):
    d = os.path.join(ROOT, DIRS[(res, seed)])
    p = os.path.join(d, f"L{L:02d}_{pid}.png")
    return p if os.path.exists(p) else None


def main():
    print(f"=== {GNAME} 4 条 饱和度均值（写实化 → 下降）===")
    for res in (512, 1024):
        print(f"\n[{res}²]  层      " + "".join(f"  seed{s:<6}" for s in SEEDS) + "   三层均值")
        for L in LAYERS:
            vals = []
            for s in SEEDS:
                v = [sat_edge(np.asarray(Image.open(path(res, s, L, p)).convert("RGB")))[0]
                     for p in ANIME if path(res, s, L, p)]
                vals.append(np.mean(v) if v else float('nan'))
            print(f"       L{L:<3} " + "".join(f"  {v:8.2f}" for v in vals) + f"   {np.nanmean(vals):8.2f}")

    print("\n=== 相对同分辨率 L36 的饱和度降幅（%）===")
    for res in (512, 1024):
        base = {}
        for s in SEEDS:
            v = [sat_edge(np.asarray(Image.open(path(res, s, 36, p)).convert("RGB")))[0]
                 for p in ANIME if path(res, s, 36, p)]
            base[s] = np.mean(v)
        print(f"\n[{res}²]  层   " + "".join(f"  seed{s:<6}" for s in SEEDS))
        for L in LAYERS[1:]:
            out = []
            for s in SEEDS:
                v = [sat_edge(np.asarray(Image.open(path(res, s, L, p)).convert("RGB")))[0]
                     for p in ANIME if path(res, s, L, p)]
                out.append(100 * (np.mean(v) - base[s]) / base[s] if v else float('nan'))
            print(f"       L{L:<3}" + "".join(f"  {v:+7.1f}%" for v in out))

    # 视觉对照表：每个 seed 一张（行=提示词，列=层）
    F_T, F_L, F_H = font(15), font(16), font(21)
    prompts = {}
    for l in open(os.path.join(ROOT, "tools", "prompts_all.jsonl"), encoding="utf-8"):
        if l.strip():
            d = json.loads(l); prompts[d["id"]] = d
    cell = 300
    for res in (512, 1024):
        for s in SEEDS:
            ids = [p for p in ANIME if path(res, s, 36, p)]
            if not ids:
                continue
            W = cell * len(LAYERS) + 8 * (len(LAYERS) - 1) + 24
            ch = cell + 34
            sh = Image.new("RGB", (W, ch * len(ids) + 44), (252, 252, 252))
            d = ImageDraw.Draw(sh)
            d.text((12, 12), f"{GNAME}画风安全线 @{res}²  seed={s}  （行=提示词，列=层）",
                   fill=(20, 20, 20), font=F_H)
            y = 44
            for pid in ids:
                d.text((12, y + 4), f"{pid}  {prompts.get(pid,{}).get('prompt','')[:52]}",
                       fill=(150, 30, 30), font=F_T)
                for ci, L in enumerate(LAYERS):
                    p = path(res, s, L, pid)
                    if not p:
                        continue
                    im = Image.open(p).convert("RGB"); im.thumbnail((cell, cell))
                    x = 12 + ci * (cell + 8)
                    sh.paste(im, (x, y + 30))
                    d.text((x + 2, y + 8), TAG[L], fill=COL[L], font=F_L)
                y += ch
            out = os.path.join(ROOT, "exp_1024", f"1024对照_20多seed_{GRP}_{res}_s{s}.png")
            sh.save(out); print("写出:", out, f"{sh.size[0]}x{sh.size[1]}")


if __name__ == "__main__":
    sys.exit(main())

