#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""验证修复：显式指定 torch_dtype=bfloat16 加载 4-bit DiT，dtype 是否一致、是否还 NaN"""
import os, gc
os.environ.setdefault("HF_HUB_OFFLINE", "1")
import torch

ZDIR = r"C:\Users\32202\Desktop\99999\qwen-image-edge\models\Z-Image-Turbo"
from diffusers import ZImageTransformer2DModel
from diffusers import BitsAndBytesConfig as DBnB

db = DBnB(load_in_4bit=True, bnb_4bit_quant_type="nf4", bnb_4bit_compute_dtype=torch.bfloat16)

for tag, kw in [
    ("无 torch_dtype（原做法）", {}),
    ("torch_dtype=bfloat16", {"torch_dtype": torch.bfloat16}),
]:
    print(f"\n=== {tag} ===", flush=True)
    gc.collect(); torch.cuda.empty_cache()
    try:
        tr = ZImageTransformer2DModel.from_pretrained(
            os.path.join(ZDIR, "transformer"), quantization_config=db,
            device_map={"": 0}, low_cpu_mem_usage=True, **kw).eval()
        print(f"  transformer.dtype = {tr.dtype}   VRAM {torch.cuda.memory_allocated()/2**30:.2f} GiB", flush=True)
        lat = torch.randn(1, 16, 64, 64, dtype=torch.bfloat16, device="cuda")
        x = list(lat.unsqueeze(2).unbind(dim=0))
        cap = [torch.randn(26, 2560, dtype=torch.bfloat16, device="cuda") * 56.0]
        t = torch.tensor([0.0], device="cuda")
        with torch.no_grad():
            o = tr(x, t, cap, return_dict=False)
        out = o[0] if isinstance(o, (tuple, list)) else o
        v = out[0] if isinstance(out, (list, tuple)) else out
        f = v.float()
        print(f"  输出 {tuple(f.shape)} {v.dtype} mean {f.mean():+.4f} std {f.std():.4f} "
              f"NaN {bool(torch.isnan(f).any())} "
              f"{'✅ 修复' if not torch.isnan(f).any() else '❌ 仍 NaN'}", flush=True)
        del tr
    except Exception as e:
        print(f"  失败: {type(e).__name__}: {str(e)[:200]}", flush=True)
    gc.collect(); torch.cuda.empty_cache()
