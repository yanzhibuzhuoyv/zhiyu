#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""修正打包后的 config.json（层数必须与实际权重一致），并校验分片内容"""
import json, os, sys

ROOT = r"C:\Users\32202\Desktop\99999\qwen-image-edge\models\te_packed"

for v, target in [("L36", 36), ("L24", 24)]:
    p = os.path.join(ROOT, v, "config.json")
    cfg = json.load(open(p, encoding="utf-8"))
    tc = cfg.get("text_config", {})
    print(f"--- {v} ---")
    print(f"  原 num_hidden_layers = {tc.get('num_hidden_layers')}")
    print(f"  architectures        = {cfg.get('architectures')}")
    print(f"  tie_word_embeddings  = {cfg.get('tie_word_embeddings')}")
    tc["num_hidden_layers"] = target
    cfg["text_config"] = tc
    json.dump(cfg, open(p, "w", encoding="utf-8"), indent=1)
    print(f"  已改为 {target}")

    idx = json.load(open(os.path.join(ROOT, v, "model.safetensors.index.json"), encoding="utf-8"))
    wm = idx["weight_map"]
    layers = set()
    for k in wm:
        if k.startswith("model.language_model.layers."):
            layers.add(int(k.split(".")[3]))
    has_lmhead = "lm_head.weight" in wm
    has_vision = any(k.startswith("model.visual.") for k in wm)
    n_embed = sum(1 for k in wm if "embed_tokens" in k)
    n_norm = sum(1 for k in wm if k.endswith("language_model.norm.weight"))
    print(f"  分片实际层    : {min(layers)}..{max(layers)}  共 {len(layers)} 层")
    print(f"  含 lm_head    : {has_lmhead}")
    print(f"  含视觉塔      : {has_vision}")
    print(f"  embed/norm    : {n_embed}/{n_norm}")
    print(f"  张量总数      : {len(wm)}")
    print(f"  分片数        : {len(set(wm.values()))}")
