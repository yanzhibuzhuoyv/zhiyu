#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""面部/主体特写裁切对照（新建脚本，不改动现有文件）
把每条提示词各层的同一位置区域按原生像素裁出来并排，避免缩略图看不清画风漂移。
"""
import os, sys, json
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


def skin_mask(a):
    R, G, B = a[..., 0].astype(np.int16), a[..., 1].astype(np.int16), a[..., 2].astype(np.int16)
    mx = a.max(2).astype(np.int16); mn = a.min(2).astype(np.int16)
    return (R > 95) & (G > 40) & (B > 20) & ((mx - mn) > 15) & (np.abs(R - G) > 15) & (R > G) & (R > B)


def skin_box(a, box):
    """滑动窗口找肤色密度最高的 box×box 区域（=面部/主体所在），返回左上角坐标。
    仅在画面上部 75% 搜索，避免落到手/腿上。"""
    h, w = a.shape[:2]
    m = skin_mask(a).astype(np.float32)
    lim = int(h * 0.75)
    m = m[:lim]
    if m.sum() < 50:
        return (w - box) // 2, int(h * 0.12)
    ii = np.cumsum(np.cumsum(m, 0), 1)
    ii = np.pad(ii, ((1, 0), (1, 0)))
    bs = max(8, box // 2)                      # 粗搜索步长
    half = bs // 2
    best, bx, by = -1, 0, 0
    for y in range(0, max(1, lim - bs), max(1, bs // 4)):
        for x in range(0, max(1, w - bs), max(1, bs // 2)):
            s = ii[y + bs, x + bs] - ii[y, x + bs] - ii[y + bs, x] + ii[y, x]
            if s > best:
                best, bx, by = s, x, y
    cx, cy = bx + bs // 2, by + bs // 2
    x0 = int(min(max(cx - box // 2, 0), w - box))
    y0 = int(min(max(cy - box // 2, 0), h - box))
    return x0, y0


def build(ids, name, box=340, outdir=None, title="面部/主体特写对照 @1024²"):
    outdir = outdir or os.path.join(ROOT, "exp_1024")
    F_T, F_L, F_H = font(15), font(16), font(22)
    rows = []
    for pid in ids:
        cells = []
        for L in LAYERS:
            p = os.path.join(outdir, f"L{L:02d}_{pid}.png")
            if not os.path.exists(p):
                break
            im = Image.open(p).convert("RGB")
            a = np.asarray(im)
            x0, y0 = skin_box(a, box)
            cells.append(im.crop((x0, y0, x0 + box, y0 + box)))
        if len(cells) == len(LAYERS):
            rows.append((pid, cells))
        else:
            print("  缺图:", pid, file=sys.stderr)
    if not rows:
        return None
    W = box * len(LAYERS) + 8 * (len(LAYERS) - 1) + 24
    ch = box + 34
    sh = Image.new("RGB", (W, ch * len(rows) + 44), (252, 252, 252))
    d = ImageDraw.Draw(sh)
    d.text((12, 12), title, fill=(20, 20, 20), font=F_H)
    y = 44
    for pid, cells in rows:
        pr = {}
        for src in ["prompts_all.jsonl"]:
            fp = os.path.join(ROOT, "tools", src)
            if os.path.exists(fp):
                for l in open(fp, encoding="utf-8"):
                    if l.strip():
                        dd = json.loads(l)
                        if dd.get("id") == pid:
                            pr = dd
        d.text((12, y + 4), f"{pid}  {pr.get('style','')}/{pr.get('scene','')}  {pr.get('prompt','')[:46]}",
               fill=(150, 30, 30), font=F_T)
        for ci, (L, im) in enumerate(zip(LAYERS, cells)):
            x = 12 + ci * (box + 8)
            sh.paste(im, (x, y + 30))
            d.text((x + 2, y + 8), TAG[L], fill=COL[L], font=F_L)
        y += ch
    p = os.path.join(outdir, name)
    sh.save(p)
    print("写出:", p, f"{sh.size[0]}x{sh.size[1]}")
    return p


if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else "anime"
    if which == "anime":
        build(["A05", "A06", "A07", "A08"], "1024对照_10面部特写_二次元.png", box=340)
    elif which == "3d":
        build(["A09", "A10", "A11", "A12"], "1024对照_11面部特写_3D.png", box=340)
    elif which == "text":
        build(["A15"], "1024对照_12特写_中文文字.png", box=640)
    elif which == "hand":
        build(["A13"], "1024对照_13特写_手部.png", box=560)
    elif which == "multi":
        build(["A14"], "1024对照_14特写_多人.png", box=760)
