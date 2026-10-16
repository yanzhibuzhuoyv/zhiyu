#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""确认实验统计：每层 × 每任务成功率、总计、Fisher 精确检验。
用法: python analyze_confirm.py [verdicts.json]
"""
import os, sys, json
from math import comb

ROOT = r"C:\Users\32202\Desktop\99999\qwen-image-edge"
LAYERS = [36, 28, 24]
SEEDS = [42, 1234, 7, 99, 2024, 555]
TASKS = ["E1_fullbody", "E2_picnic"]
TNAME = {"E1_fullbody": "E1 扩图全身", "E2_picnic": "E2 草坪野餐"}


def fisher_two_sided(a, b, c, d):
    """2x2: [[a,b],[c,d]] 双尾 Fisher 精确检验 p 值"""
    n = a + b + c + d
    r1, r2, c1 = a + b, c + d, a + c
    def p_of(x):
        # x = 左上格
        return comb(r1, x) * comb(r2, c1 - x) / comb(n, c1)
    lo = max(0, c1 - r2); hi = min(r1, c1)
    p0 = p_of(a)
    return sum(p_of(x) for x in range(lo, hi + 1) if p_of(x) <= p0 + 1e-12)


def main():
    vp = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "tools", "verdicts_confirm.json")
    V = json.load(open(vp, encoding="utf-8"))

    counts = {L: {t: [0, 0] for t in TASKS} for L in LAYERS}   # [成功, 总数]
    per_seed = {L: {} for L in LAYERS}
    print("=" * 78)
    print("逐格明细（列=层, 行=seed）")
    for t in TASKS:
        print(f"\n[{TNAME[t]}]")
        print("  seed    " + "".join(f"  L{L:<8}" for L in LAYERS))
        for s in SEEDS:
            row = []
            for L in LAYERS:
                v = V.get(f"{t}|{L}|{s}")
                if v is None:
                    row.append("  -       "); counts[L][t][1] += 1; continue
                counts[L][t][0] += 1 if v["ok"] else 0
                counts[L][t][1] += 1
                per_seed[L][(t, s)] = v["ok"]
                row.append(("  成功√   " if v["ok"] else "  失败×   "))
            print(f"  {s:<7} " + "".join(row))

    print("\n" + "=" * 78)
    print("统计表（成功数/总数）")
    print(f"{'层':<6}{'E1 扩图全身':<16}{'E2 草坪野餐':<16}{'合计':<12}{'总成功率'}")
    for L in LAYERS:
        e1 = counts[L]["E1_fullbody"]; e2 = counts[L]["E2_picnic"]
        ok = e1[0] + e2[0]; tot = e1[1] + e2[1]
        print(f"L{L:<5}{f'{e1[0]}/{e1[1]}':<16}{f'{e2[0]}/{e2[1]}':<16}{f'{ok}/{tot}':<12}{ok/tot*100:.1f}%")

    print("\n" + "=" * 78)
    print("Fisher 精确检验（双尾）")
    def test(name, la, lb, task=None):
        if task:
            a, na = counts[la][task]; b, nb = counts[lb][task]
        else:
            a = sum(counts[la][t][0] for t in TASKS); na = sum(counts[la][t][1] for t in TASKS)
            b = sum(counts[lb][t][0] for t in TASKS); nb = sum(counts[lb][t][1] for t in TASKS)
        p = fisher_two_sided(a, na - a, b, nb - b)
        print(f"  {name:<34} L{la}: {a}/{na}  vs  L{lb}: {b}/{nb}   p = {p:.4f}"
              f"   {'显著(p<0.05)' if p < 0.05 else '不显著'}")
    for task in TASKS:
        print(f"\n  -- {TNAME[task]} --")
        test("L36 vs L28", 36, 28, task); test("L36 vs L24", 36, 24, task); test("L28 vs L24", 28, 24, task)
    print("\n  -- 合并(E1+E2) --")
    for a, b in [(36, 28), (36, 24), (28, 24)]:
        test(f"L{a} vs L{b}", a, b)


if __name__ == "__main__":
    main()
