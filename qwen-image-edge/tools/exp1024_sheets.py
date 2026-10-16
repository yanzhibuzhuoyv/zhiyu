#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""1024x1024 剪层对照图生成器（新建脚本，不改动任何现有文件）

行 = 一条提示词，列 = 一层（L36 原版 → L20 剪16层），左侧带中文提示词原文。
中文字体：C:\\Windows\\Fonts\\msyh.ttc
"""
import os, json, textwrap, sys
from PIL import Image, ImageDraw, ImageFont

ROOT = r"C:\Users\32202\Desktop\99999\qwen-image-edge"
OUT = os.path.join(ROOT, "exp_1024")          # 1024² 结果目录
OUT512 = os.path.join(ROOT, "exp1_out")       # 512² 基线目录（只读）
PL = os.path.join(ROOT, "tools", "prompts_all.jsonl")

COLS = [(36, "原版 L36", (20, 20, 20)),
        (32, "L32 剪4层", (20, 90, 160)),
        (28, "L28 剪8层", (150, 90, 10)),
        (24, "L24 剪12层", (20, 120, 20)),
        (20, "L20 剪16层", (170, 20, 120))]


def font(sz):
    for p in [r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\simhei.ttf"]:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, sz)
            except Exception:
                pass
    return ImageFont.load_default()


prompts = {}
for l in open(PL, encoding="utf-8"):
    if l.strip():
        d = json.loads(l)
        prompts[d["id"]] = d


def load(pid, L, outdir=OUT):
    p = os.path.join(outdir, f"L{L:02d}_{pid}.png")
    return Image.open(p).convert("RGB") if os.path.exists(p) else None


def build(ids, name, title, cell=300, textw=330, outdir=OUT, cols=None,
          wrap=18, maxlines=12, fsz=(15, 15, 21), note=None):
    cols = cols or COLS
    F_TXT, F_TAG, F_HD = font(fsz[0]), font(fsz[1]), font(fsz[2])
    F_NOTE = font(14)
    rows = []
    for pid in ids:
        ims = []
        for L, _, _ in cols:
            im = load(pid, L, outdir)
            if im is None:
                break
            im = im.copy()
            im.thumbnail((cell, cell))
            ims.append((L, im))
        if len(ims) == len(cols):
            rows.append((pid, ims))
        else:
            print("  缺图，跳过:", pid, file=sys.stderr)
    if not rows:
        print("  [跳过]", name, "（无完整行）")
        return None
    pad_top = 26
    ch = max(max(i.height for _, i in r) for _, r in rows) + pad_top + 30
    W = textw + cell * len(cols) + 20
    sh = Image.new("RGB", (W, ch * len(rows) + 48), (252, 252, 252))
    d = ImageDraw.Draw(sh)
    d.text((12, 13), title, fill=(20, 20, 20), font=F_HD)
    if note:
        d.text((12, 40), note, fill=(120, 120, 120), font=F_NOTE)
    y = 48
    for pid, ims in rows:
        pr = prompts.get(pid, {})
        d.text((12, y + 2), f"{pid}  {pr.get('style','')}/{pr.get('scene','')}",
               fill=(150, 30, 30), font=F_TAG)
        yy = y + 26
        for line in textwrap.wrap(pr.get("prompt", ""), width=wrap)[:maxlines]:
            d.text((12, yy), line, fill=(40, 40, 40), font=F_TXT)
            yy += 20
        for ci, (L, im) in enumerate(ims):
            x = textw + ci * (cell + 4)
            sh.paste(im, (x + (cell - im.width) // 2, y + pad_top))
            d.text((x + 4, y + 6), cols[ci][1], fill=cols[ci][2], font=F_TAG)
        d.line([(0, y + ch - 2), (W, y + ch - 2)], fill=(225, 225, 225))
        y += ch
    p = os.path.join(outdir, name)
    sh.save(p)
    print("写出:", p, f"{sh.size[0]}x{sh.size[1]}")
    return p


if __name__ == "__main__":
    only = sys.argv[1] if len(sys.argv) > 1 else "all"

    GROUPS = [
        ("A01 A02 A03 A04", "1024对照_01真人4条.png", "真人生活照 4 条 @1024²  行=提示词，列=文本编码器层退出深度"),
        ("A05 A06 A07 A08", "1024对照_02二次元4条.png", "二次元画风 4 条 @1024²  —— 看画风从哪一层开始褪成写实"),
        ("A09 A10 A11 A12", "1024对照_03_3D4条.png", "3D 卡通画风 4 条 @1024²  —— 看画风保真度"),
        ("A13 A14 A15 A16", "1024对照_04难点4条.png", "难点 4 条 @1024²  A13手部 / A14五人计数 / A15中文『深夜咖啡』/ A16两个挂钟"),
        ("L01 L02 L03 L04", "1024对照_05长提示4条.png", "长提示词 4 条 @1024²  —— 看细节跟随是否随剪层流失"),
    ]

    for idstr, name, title in GROUPS:
        if only != "all" and only not in name:
            continue
        build(idstr.split(), name, title, cell=300, textw=330, wrap=18)

    # 全量缩略总览：20 行 × 5 列
    if only == "all":
        allids = [f"A{i:02d}" for i in range(1, 17)] + [f"L{i:02d}" for i in range(1, 5)]
        build(allids, "1024对照_00总览20条.png",
              "1024² 剪层总览：20 条提示词 × 5 层（L36→L20）",
              cell=190, textw=300, wrap=16, maxlines=12, fsz=(12, 13, 22))

        # 中文文字专项：A15 大图
        build(["A15"], "1024对照_06中文文字A15大图.png",
              "中文图内文字专项 A15 @1024²（应写『深夜咖啡』）",
              cell=560, textw=360, wrap=16, fsz=(16, 17, 24))

        # 手部专项：A13 大图
        build(["A13"], "1024对照_07手部A13大图.png",
              "手部专项 A13 @1024²（双手捧马克杯）",
              cell=560, textw=360, wrap=16, fsz=(16, 17, 24))

        # 多人专项：A14 大图
        build(["A14"], "1024对照_08多人A14大图.png",
              "多人计数专项 A14 @1024²（五个同事）",
              cell=560, textw=360, wrap=16, fsz=(16, 17, 24))

    # 512² vs 1024² 同层跨分辨率对照（列 = L36@512, L36@1024, L24@512, L24@1024, L20@512, L20@1024）
    if only in ("all", "cross"):
        cols = [(36, "L36 @512", (20, 20, 20)), (36, "L36 @1024", (20, 20, 20)),
                (28, "L28 @512", (150, 90, 10)), (28, "L28 @1024", (150, 90, 10)),
                (24, "L24 @512", (20, 120, 20)), (24, "L24 @1024", (20, 120, 20)),
                (20, "L20 @512", (170, 20, 120)), (20, "L20 @1024", (170, 20, 120))]

        def build_cross(ids, name, title, cell=185, textw=310):
            F_TXT, F_TAG, F_HD = font(13), font(13), font(21)
            rows = []
            for pid in ids:
                ims = []
                for L, _, _ in cols:
                    src = OUT512 if "@512" in _ else OUT
                    im = load(pid, L, src)
                    if im is None:
                        break
                    im = im.copy(); im.thumbnail((cell, cell)); ims.append(im)
                if len(ims) == len(cols):
                    rows.append((pid, ims))
                else:
                    print("  跨分辨率缺图:", pid, file=sys.stderr)
            if not rows:
                print("  [跳过]", name); return
            ch = cell + 48
            W = textw + (cell + 4) * len(cols) + 16
            sh = Image.new("RGB", (W, ch * len(rows) + 46), (252, 252, 252))
            d = ImageDraw.Draw(sh)
            d.text((12, 12), title, fill=(20, 20, 20), font=F_HD)
            y = 46
            for pid, ims in rows:
                pr = prompts.get(pid, {})
                d.text((12, y + 2), f"{pid}  {pr.get('style','')}/{pr.get('scene','')}",
                       fill=(150, 30, 30), font=F_TAG)
                yy = y + 24
                for line in textwrap.wrap(pr.get("prompt", ""), width=15)[:10]:
                    d.text((12, yy), line, fill=(40, 40, 40), font=F_TXT); yy += 18
                for ci, im in enumerate(ims):
                    x = textw + ci * (cell + 4)
                    sh.paste(im, (x + (cell - im.width) // 2, y + 26))
                    d.text((x + 2, y + 6), cols[ci][1], fill=cols[ci][2], font=F_TAG)
                y += ch
            p = os.path.join(OUT, name)
            sh.save(p); print("写出:", p, f"{sh.size[0]}x{sh.size[1]}")

        cross_ids = ["A05", "A07", "A09", "A13", "A15", "L02"]
        build_cross(cross_ids, "1024对照_09跨分辨率_风格与难点.png",
                    "跨分辨率对照：@512² vs @1024²，同层同 seed（42）")
