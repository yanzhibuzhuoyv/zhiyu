#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""检查教师(Qwen3-VL)与学生(Qwen3)的 tokenizer 是否一致 —— 蒸馏逐 token 对齐的前提"""
import hashlib, os, json

ROOT = r"C:\Users\32202\Desktop\99999\qwen-image-edge"
paths = {
    "teacher(Qwen3-VL)": os.path.join(ROOT, "models", "Qwen-Image-2.1-Turbo", "processor", "tokenizer.json"),
    "student(Qwen3-1.7B)": os.path.join(ROOT, "models", "Qwen3-1.7B-Base", "tokenizer.json"),
    "student(Qwen3-0.6B)": os.path.join(ROOT, "models", "Qwen3-0.6B-Base", "tokenizer.json"),
}
info = {}
for k, p in paths.items():
    if not os.path.exists(p):
        print(f"{k:<22} 不存在（还没下完）")
        continue
    b = open(p, "rb").read()
    j = json.loads(b)
    vocab = j.get("model", {}).get("vocab", {})
    added = j.get("added_tokens", [])
    h = hashlib.sha256(b).hexdigest()[:16]
    info[k] = (h, len(vocab), len(added))
    print(f"{k:<22} {len(b)/2**20:6.2f} MiB  sha256={h}  vocab={len(vocab)}  added={len(added)}")

if len(info) >= 2:
    vals = list(info.values())
    same_hash = len({v[0] for v in vals}) == 1
    same_vocab = len({v[1] for v in vals}) == 1
    print()
    print(f"文件哈希一致: {same_hash}")
    print(f"词表大小一致: {same_vocab}")
    if same_vocab:
        print("=> 词表规模相同，可以逐 token 对齐（若哈希不同，仍建议实测同一句话的 token id 是否一致）")
