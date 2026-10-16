#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""学生(小编码器+适配器) vs 教师(原编码器) 出图对照，带中文提示词"""
import os, json, textwrap, argparse
from PIL import Image, ImageDraw, ImageFont


def font(sz):
    for p in [r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\simhei.ttf"]:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, sz)
            except Exception:
                pass
    return ImageFont.load_default()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge")
    ap.add_argument("--teacher-dir", default="exp1_out")
    ap.add_argument("--student-dir", default="student_out")
    ap.add_argument("--layer", type=int, default=36)
    ap.add_argument("--ids", default="")
    ap.add_argument("--out", default="对比_学生vs教师.png")
    ap.add_argument("--cell", type=int, default=260)
    args = ap.parse_args()

    F_TXT, F_TAG, F_HD = font(15), font(15), font(20)
    pl = os.path.join(args.root, "tools", "prompts_all.jsonl")
    prompts = {}
    for l in open(pl, encoding="utf-8"):
        if l.strip():
            d = json.loads(l)
            prompts[d["id"]] = d

    ids = [x.strip() for x in args.ids.split(",") if x.strip()]
    if not ids:
        ids = ["A01", "A05", "A09", "A13", "A14", "A15", "A16", "L02", "L03"]

    rows = []
    for pid in ids:
        a = os.path.join(args.root, args.teacher_dir, f"L{args.layer:02d}_{pid}.png")
        b = os.path.join(args.root, args.student_dir, f"L{args.layer:02d}_{pid}.png")
        if os.path.exists(a) and os.path.exists(b):
            ia = Image.open(a).convert("RGB"); ia.thumbnail((args.cell, args.cell))
            ib = Image.open(b).convert("RGB"); ib.thumbnail((args.cell, args.cell))
            rows.append((pid, ia, ib))
    if not rows:
        print("没有可对比的图"); return

    textw = 300
    ch = max(max(a.height, b.height) for _, a, b in rows) + 58
    W = textw + args.cell * 2 + 40
    sh = Image.new("RGB", (W, ch * len(rows) + 44), (252, 252, 252))
    d = ImageDraw.Draw(sh)
    d.text((12, 12), "教师（原编码器 L36）  vs  学生（小编码器 + 适配器）", fill=(20, 20, 20), font=F_HD)
    y = 44
    for pid, ia, ib in rows:
        pr = prompts.get(pid, {})
        d.text((12, y + 4), f"{pid}  {pr.get('cat') or pr.get('style','')}", fill=(150, 30, 30), font=F_TAG)
        yy = y + 26
        for line in textwrap.wrap(pr.get("prompt", ""), width=17)[:11]:
            d.text((12, yy), line, fill=(45, 45, 45), font=F_TXT); yy += 20
        sh.paste(ia, (textw + 6, y + 26)); d.text((textw + 6, y + 6), "教师 L36", fill=(20, 20, 20), font=F_TAG)
        sh.paste(ib, (textw + args.cell + 18, y + 26)); d.text((textw + args.cell + 18, y + 6), "学生 0.6B+适配器", fill=(20, 110, 20), font=F_TAG)
        y += ch
    p = os.path.join(args.root, args.out)
    sh.save(p)
    print("写出:", p, f"{sh.size[0]}x{sh.size[1]}")


if __name__ == "__main__":
    main()
