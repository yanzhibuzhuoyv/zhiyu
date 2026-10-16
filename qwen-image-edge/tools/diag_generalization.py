#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
泛化诊断：蒸馏学生在「手写提示词」上的对齐余弦
  对比：训练分布（模板语料 val_cos） vs 手写短提示词 vs 手写长提示词
  这一步必须做 —— 只看验证集余弦会得出错误结论（v1 就是这样翻车的）
"""
import os, sys, json, glob, argparse, statistics
import torch

ROOT = r"C:\Users\32202\Desktop\99999\qwen-image-edge"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--teacher-embeds", default=os.path.join(ROOT, "embeds"),
                    help="教师侧（encode_prompts.py 产出）")
    ap.add_argument("--student-embeds", default=os.path.join(ROOT, "distill", "v3_embeds"),
                    help="学生侧（make_student_embeds.py 产出）")
    ap.add_argument("--layer", type=int, default=36)
    ap.add_argument("--val-cos", type=float, default=None, help="训练脚本报告的验证余弦，仅用于打印对照")
    args = ap.parse_args()

    ti = json.load(open(os.path.join(args.teacher_embeds, "index.json"), encoding="utf-8"))
    si = json.load(open(os.path.join(args.student_embeds, "index.json"), encoding="utf-8"))

    print("=" * 76)
    print(f"手写提示词逐条余弦  (教师 {os.path.basename(args.teacher_embeds)} vs 学生 {os.path.basename(args.student_embeds)})")
    print("=" * 76)
    short, long_, rows = [], [], []
    for pid in ti:
        if pid not in si:
            continue
        a = torch.load(os.path.join(args.teacher_embeds, ti[pid]["file"]), weights_only=False)["layers"][args.layer]
        b = torch.load(os.path.join(args.student_embeds, si[pid]["file"]), weights_only=False)["layers"][args.layer]
        ea, ma = a["embeds"].float(), a["mask"].bool()
        eb, mb = b["embeds"].float(), b["mask"].bool()
        va = ea[0][ma[0]]; vb = eb[0][mb[0]]
        n = min(va.shape[0], vb.shape[0])
        cos = torch.nn.functional.cosine_similarity(va[:n].flatten(), vb[:n].flatten(), dim=0).item()
        is_long = n > 60
        (long_ if is_long else short).append(cos)
        rows.append((pid, cos, n, is_long, ti[pid].get("prompt", "")[:24]))

    for pid, cos, n, isl, pr in sorted(rows, key=lambda x: x[1]):
        flag = "长" if isl else "短"
        print(f"  {pid:<5} {flag} cos={cos:.4f}  tok={n:>3}  {pr}")

    def stat(v, name):
        if not v:
            print(f"  {name}: 无样本"); return
        print(f"  {name}: n={len(v)}  平均 **{statistics.mean(v):.4f}**  中位 {statistics.median(v):.4f}  "
              f"最低 {min(v):.4f}  最高 {max(v):.4f}")

    print()
    print("=" * 76)
    print("汇总")
    print("=" * 76)
    if args.val_cos:
        print(f"  模板语料（训练分布）      : {args.val_cos:.4f}")
    stat(short, "手写短提示词（<60 tok） ")
    stat(long_, "手写长提示词（>60 tok） ")
    stat([r[1] for r in rows], "全部手写               ")

    print()
    ok_s = bool(short) and statistics.mean(short) >= 0.95
    ok_l = bool(long_) and statistics.mean(long_) >= 0.93
    print(f"  判定：短提示词 ≥0.95 {'✅' if ok_s else '❌'}    长提示词 ≥0.93 {'✅' if ok_l else '❌'}")
    print(f"  {'✅ 达标，可以出图验证' if (ok_s and ok_l) else '⚠️ 未达标，需继续优化（改语料或放开学生 LoRA）'}")


if __name__ == "__main__":
    main()
