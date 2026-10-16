#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
诊断：蒸馏学生在「手写提示词」上的对齐余弦 vs 在「模板语料」上的余弦
若两者差距大 => 适配器过拟合到模板语料，泛化失败
"""
import os, sys, json, glob
import torch

ROOT = r"C:\Users\32202\Desktop\99999\qwen-image-edge"
TE = os.path.join(ROOT, "embeds")                       # 教师（原编码器 L36）
ST = os.path.join(ROOT, "distill", "embeds_student")    # 学生+适配器
CORP_T = os.path.join(ROOT, "distill", "teacher")
CORP_S = os.path.join(ROOT, "distill", "student")

print("=" * 74)
print("一、手写提示词（Tier A + L）逐条余弦")
print("=" * 74)
ti = json.load(open(os.path.join(TE, "index.json"), encoding="utf-8"))
si = json.load(open(os.path.join(ST, "index.json"), encoding="utf-8"))
rows = []
for pid in ti:
    if pid not in si:
        continue
    a = torch.load(os.path.join(TE, ti[pid]["file"]), weights_only=False)["layers"][36]
    b = torch.load(os.path.join(ST, si[pid]["file"]), weights_only=False)["layers"][36]
    ea = a["embeds"].float(); ma = a["mask"].bool()
    eb = b["embeds"].float(); mb = b["mask"].bool()
    n = min(ea.shape[1], eb.shape[1])
    # 两边都按各自 mask 取有效 token 再对齐
    va = ea[0][ma[0]][:n]; vb = eb[0][mb[0]][:n]
    n2 = min(va.shape[0], vb.shape[0])
    cos = torch.nn.functional.cosine_similarity(
        va[:n2].flatten(), vb[:n2].flatten(), dim=0).item()
    rows.append((pid, cos, n2, ti[pid]["prompt"][:26]))
    print(f"  {pid:<5} cos={cos:.4f}  tok={n2:>3}  {ti[pid]['prompt'][:26]}")

import statistics
cs = [r[1] for r in rows]
print(f"\n  手写提示词：n={len(cs)}  平均 {statistics.mean(cs):.4f}  中位 {statistics.median(cs):.4f}  "
      f"最低 {min(cs):.4f} ({rows[cs.index(min(cs))][0]})  最高 {max(cs):.4f}")

print()
print("=" * 74)
print("二、模板语料（训练/验证同分布）")
print("=" * 74)
tf = sorted(glob.glob(os.path.join(CORP_T, "*.pt")))
sf = sorted(glob.glob(os.path.join(CORP_S, "*.pt")))
nval = max(1, len(tf) // 10)
acc, n = 0.0, 0
for ft, fs in list(zip(tf, sf))[-nval:]:
    T = torch.load(ft, weights_only=False); S = torch.load(fs, weights_only=False)
    m = T["mask"].bool() & S["mask"].bool()
    a = T["embeds"].float()[m]; b = S["feats"].float()[m]  # 注意：学生缓存的是 feats，这里只做形状检查
    acc += 0; n += 1
print(f"  （模板语料的余弦由训练脚本报告：val_cos ≈ 0.9890）")
print(f"  验证分片数 {nval}")

print()
print("=" * 74)
print("三、结论")
print("=" * 74)
m = statistics.mean(cs)
if m > 0.95:
    print("  手写与模板余弦接近 -> 适配器泛化没问题，出图错误另有原因（DiT 对微小扰动敏感）")
elif m > 0.85:
    print("  手写余弦明显低于模板 -> 存在一定过拟合，但不算崩")
else:
    print("  手写余弦远低于模板 -> **适配器过拟合到模板语料**，泛化失败，需重做语料")
