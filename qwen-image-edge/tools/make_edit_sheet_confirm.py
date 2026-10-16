#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""确认实验对照图：行=seed，列=层(L36/L28/L24)，最左为参考图，格内标注判定。
用法: python make_edit_sheet_confirm.py [verdicts.json]
"""
import os, sys, json
from PIL import Image, ImageDraw, ImageFont

ROOT = r"C:\Users\32202\Desktop\99999\qwen-image-edge"
DIRS = [os.path.join(ROOT, "edit_out"), os.path.join(ROOT, "edit_out_confirm")]
OUTDIR = os.path.join(ROOT, "edit_out_confirm")
REF = os.path.join(ROOT, "exp1_out", "L36_A01.png")
LAYERS = [36, 28, 24]
SEEDS = [42, 1234, 7, 99, 2024, 555]


def font(sz, bold=False):
    for p in ([r"C:\Windows\Fonts\msyhbd.ttc"] if bold else []) + [r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\simhei.ttf"]:
        if os.path.exists(p):
            try: return ImageFont.truetype(p, sz)
            except Exception: pass
    return ImageFont.load_default()


F_TITLE, F_HDR, F_TAG, F_WHY = font(30, True), font(24, True), font(19, True), font(15)


def find(task, L, s):
    for d in DIRS:
        p = os.path.join(d, f"{task}_L{L}_s{s}.png")
        if os.path.exists(p): return p
    return None


def sheet(task, verdicts, cell_w=300, cell_h=430):
    rows = []
    for s in SEEDS:
        cells = []
        for L in LAYERS:
            p = find(task, L, s)
            cells.append((L, s, p))
        rows.append((s, cells))

    # 计算实际缩略图尺寸
    thumbs = {}
    for s, cells in rows:
        for L, ss, p in cells:
            if p:
                im = Image.open(p).convert("RGB")
                im.thumbnail((cell_w, cell_h))
                thumbs[(L, ss)] = im
    ref = Image.open(REF).convert("RGB"); ref.thumbnail((cell_w, cell_h))
    cw = max([ref.width] + [i.width for i in thumbs.values()]) + 16
    ch = max([ref.height] + [i.height for i in thumbs.values()]) + 74

    W, H = cw * (len(LAYERS) + 1), ch * len(rows) + 96
    sh = Image.new("RGB", (W, H), (250, 250, 250))
    d = ImageDraw.Draw(sh)
    d.text((16, 14), f"{task} —— 行=seed，列=层（最左：参考图）　判定标准见报告", fill=(15, 15, 15), font=F_TITLE)
    hdr = ["参考图 (ref)"] + [f"L{L}" for L in LAYERS]
    for ci, t in enumerate(hdr):
        d.text((ci * cw + 12, 62), t, fill=(20, 60, 140) if ci else (150, 30, 30), font=F_HDR)
    y = 96
    for s, cells in rows:
        # 行标签
        d.text((8, y + 4), f"seed={s}", fill=(20, 20, 20), font=F_HDR)
        sh.paste(ref, (12, y + 34))
        for ci, (L, ss, p) in enumerate(cells, start=1):
            x = ci * cw + 8
            if not p:
                d.text((x, y + 40), "缺失", fill=(200, 30, 30), font=F_HDR); continue
            sh.paste(thumbs[(L, ss)], (x, y + 34))
            v = verdicts.get(f"{task}|{L}|{ss}")
            if v is None:
                tag, col = "未判读", (120, 120, 120)
            elif v["ok"]:
                tag, col = "成功", (10, 130, 40)
            else:
                tag, col = "失败", (200, 30, 30)
            d.text((x, y + 8), f"L{L} seed={ss}  {tag}", fill=col, font=F_TAG)
            if v and v.get("why"):
                d.text((x, y + 34 + thumbs[(L, ss)].height + 4), v["why"][:34], fill=(70, 70, 70), font=F_WHY)
        y += ch
    out = os.path.join(OUTDIR, f"确认对照_{task}.png")
    sh.save(out)
    print("写出:", out, f"{sh.size[0]}x{sh.size[1]}")
    return out


if __name__ == "__main__":
    vp = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "tools", "verdicts_confirm.json")
    verdicts = json.load(open(vp, encoding="utf-8")) if os.path.exists(vp) else {}
    for t in ["E1_fullbody", "E2_picnic"]:
        sheet(t, verdicts)
