#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""逐像素比对：打包版本出的图 vs 原模型算出的既有图"""
import os, sys
import numpy as np
from PIL import Image

ROOT = r"C:\Users\32202\Desktop\99999\qwen-image-edge"
ids = sys.argv[1].split(",") if len(sys.argv) > 1 else ["A01", "A05", "A09", "A15", "L03"]

print(f"{'提示词':<8}{'L36最大差':>11}{'L36完全一致':>13}{'L24最大差':>11}{'L24完全一致':>13}")
print("-" * 60)
allok = True
for pid in ids:
    row = [f"{pid:<8}"]
    for L in (36, 24):
        a = os.path.join(ROOT, "exp1_out", f"L{L:02d}_{pid}.png")
        b = os.path.join(ROOT, f"packed_L{L}_out", f"L{L:02d}_{pid}.png")
        if os.path.exists(a) and os.path.exists(b):
            x = np.array(Image.open(a).convert("RGB")).astype("int16")
            y = np.array(Image.open(b).convert("RGB")).astype("int16")
            d = int(np.abs(x - y).max())
            row.append(f"{d:>11}")
            row.append(f"{'✅' if d == 0 else '差异':>13}")
            if d != 0:
                allok = False
        else:
            row.append(f"{'缺图':>11}")
            row.append(f"{'-':>13}")
            allok = False
    print("".join(row))
print()
print("✅ 两个打包版本端到端出图与原模型完全一致" if allok
      else "⚠️ 存在差异或缺失，见上表")
