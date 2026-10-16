#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""1024² 剪层客观代理指标（新建脚本，只读现有文件）
- 分辨率/坏图检测（全黑、纯色、NaN）
- 与同提示词 L36 的像素一致性（MSE / 直方图交 / 平均色差）
- 画风代理：HSV 饱和度均值、色相分布熵、边缘密度（线稿锐度）
- 构图代理：皮肤像素占比
输出 CSV + 控制台汇总表。
"""
import os, json, sys
import numpy as np
from PIL import Image

ROOT = r"C:\Users\32202\Desktop\99999\qwen-image-edge"
D1024 = os.path.join(ROOT, "exp_1024")
D512 = os.path.join(ROOT, "exp1_out")
LAYERS = [36, 32, 28, 24, 20]
IDS = [f"A{i:02d}" for i in range(1, 17)] + [f"L{i:02d}" for i in range(1, 5)]

STYLE_IDS = {"二次元": ["A05", "A06", "A07", "A08"], "3D": ["A09", "A10", "A11", "A12"]}


def rgb(path):
    return np.asarray(Image.open(path).convert("RGB")).astype(np.float32)


def hsv(a):
    x = a / 255.0
    mx = x.max(2); mn = x.min(2); df = mx - mn
    s = np.where(mx > 0, df / np.maximum(mx, 1e-6), 0.0)
    r, g, b = x[..., 0], x[..., 1], x[..., 2]
    h = np.zeros_like(mx)
    m = (df > 1e-6)
    idx = m & (mx == r); h[idx] = ((g - b)[idx] / df[idx]) % 6
    idx = m & (mx == g); h[idx] = ((b - r)[idx] / df[idx]) + 2
    idx = m & (mx == b); h[idx] = ((r - g)[idx] / df[idx]) + 4
    return h * 60.0, s, mx


def edge_density(a):
    g = a.mean(2)
    gx = np.abs(np.diff(g, axis=1))[:-1, :]
    gy = np.abs(np.diff(g, axis=0))[:, :-1]
    mag = np.sqrt(gx ** 2 + gy ** 2)
    return float((mag > 24).mean() * 100)


def skin_frac(a):
    R, G, B = a[..., 0], a[..., 1], a[..., 2]
    mx = a.max(2); mn = a.min(2)
    m = (R > 95) & (G > 40) & (B > 20) & ((mx - mn) > 15) & (np.abs(R - G) > 15) & (R > G) & (R > B)
    return float(m.mean() * 100)


def hist_sig(a, bins=16):
    out = []
    for c in range(3):
        h, _ = np.histogram(a[..., c], bins=bins, range=(0, 255), density=True)
        out.append(h)
    return np.concatenate(out)


def compare(a, b):
    """a/b 同尺寸；返回 MSE / PSNR / 直方图 L1 / 平均色差"""
    mse = float(((a - b) ** 2).mean())
    psnr = 10 * np.log10(255.0 ** 2 / max(mse, 1e-9))
    hl1 = float(np.abs(hist_sig(a) - hist_sig(b)).sum())
    mcd = float(np.abs(a.mean(2) - b.mean(2)).mean())
    return mse, psnr, hl1, mcd


def stat(a):
    h, s, v = hsv(a)
    hh, _ = np.histogram(h, bins=36, range=(0, 360), density=True)
    p = hh / max(hh.sum(), 1e-9)
    ent = float(-(p[p > 0] * np.log2(p[p > 0])).sum())
    return dict(mean=float(a.mean()), std=float(a.std()),
                sat=float(s.mean() * 100), val=float(v.mean() * 100),
                hue_ent=ent, edge=edge_density(a), skin=skin_frac(a))


def main():
    rows = []
    missing = []
    for pid in IDS:
        ref = os.path.join(D1024, f"L36_{pid}.png")
        if not os.path.exists(ref):
            missing.append(pid); continue
        A = rgb(ref)
        st = stat(A)
        rows.append(dict(pid=pid, layer=36, size=f"{A.shape[1]}x{A.shape[0]}",
                         mse=0.0, psnr=99.0, hist_l1=0.0, meancolordiff=0.0, **st))
        for L in LAYERS[1:]:
            p = os.path.join(D1024, f"L{L:02d}_{pid}.png")
            if not os.path.exists(p):
                continue
            B = rgb(p)
            mse, psnr, hl1, mcd = compare(A, B) if B.shape == A.shape else (float('nan'),) * 4
            rows.append(dict(pid=pid, layer=L, size=f"{B.shape[1]}x{B.shape[0]}",
                             mse=round(mse, 1), psnr=round(psnr, 2),
                             hist_l1=round(hl1, 3), meancolordiff=round(mcd, 2), **stat(B)))

    csv = os.path.join(ROOT, "exp_1024", "metrics.csv")
    keys = ["pid", "layer", "size", "mse", "psnr", "hist_l1", "meancolordiff",
            "mean", "std", "sat", "val", "hue_ent", "edge", "skin"]
    with open(csv, "w", encoding="utf-8-sig") as f:
        f.write(",".join(keys) + "\n")
        for r in rows:
            f.write(",".join(str(round(r[k], 3) if isinstance(r[k], float) else r[k]) for k in keys) + "\n")
    print("CSV:", csv, f"({len(rows)} 行)")
    if missing:
        print("!! 缺 L36 参考的提示词:", missing)

    print("\n=== 各层平均（相对同提示词 L36 的像素差异）===")
    print(" 层    n   MSE      PSNR   直方图L1  平均色差  饱和度%  边缘密度%  皮肤%  色相熵")
    for L in LAYERS:
        sub = [r for r in rows if r["layer"] == L]
        if not sub:
            continue
        def m(k):
            return np.nanmean([r[k] for r in sub])
        print(f" L{L:<3} {len(sub):3d} {m('mse'):8.1f} {m('psnr'):7.2f} {m('hist_l1'):9.3f} "
              f"{m('meancolordiff'):9.2f} {m('sat'):8.2f} {m('edge'):10.2f} {m('skin'):6.2f} {m('hue_ent'):7.3f}")

    print("\n=== 画风组（二次元 A05-A08 / 3D A09-A12）饱和度与边缘密度 ===")
    for gname, gids in STYLE_IDS.items():
        print(f"[{gname}]")
        for L in LAYERS:
            sub = [r for r in rows if r["layer"] == L and r["pid"] in gids]
            if sub:
                print(f"   L{L:<3} 饱和度 {np.mean([r['sat'] for r in sub]):6.2f}   "
                      f"边缘密度 {np.mean([r['edge'] for r in sub]):6.2f}   "
                      f"色相熵 {np.mean([r['hue_ent'] for r in sub]):5.3f}   "
                      f"PSNR(vs L36) {np.mean([r['psnr'] for r in sub]):6.2f}")

    # 512 vs 1024 统计对照（同层、同提示词）
    print("\n=== 512² vs 1024² 全局统计（同层同 seed，逐提示词平均）===")
    print(" 层   512饱和度  1024饱和度 | 512边缘  1024边缘 | 512皮肤  1024皮肤")
    for L in LAYERS:
        a512, a1024 = [], []
        for pid in IDS:
            p5 = os.path.join(D512, f"L{L:02d}_{pid}.png")
            p10 = os.path.join(D1024, f"L{L:02d}_{pid}.png")
            if os.path.exists(p5) and os.path.exists(p10):
                a512.append(stat(rgb(p5))); a1024.append(stat(rgb(p10)))
        if a512:
            f = lambda arr, k: np.mean([x[k] for x in arr])
            print(f" L{L:<3} {f(a512,'sat'):9.2f} {f(a1024,'sat'):10.2f} | "
                  f"{f(a512,'edge'):7.2f} {f(a1024,'edge'):9.2f} | "
                  f"{f(a512,'skin'):7.2f} {f(a1024,'skin'):8.2f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
