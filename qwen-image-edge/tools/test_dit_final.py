#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""最终分离测试：4-bit vs bf16 DiT，用管线同款输入格式，判定谁产 NaN"""
import os, gc
os.environ.setdefault("HF_HUB_OFFLINE", "1")
import torch

ZDIR = r"C:\Users\32202\Desktop\99999\qwen-image-edge\models\Z-Image-Turbo"
from diffusers import ZImageTransformer2DModel
from diffusers import BitsAndBytesConfig as DBnB


def run(tr, tag, cap_scale=1.0, lat_scale=1.0):
    B, C, H, W, NT = 1, 16, 64, 64, 26
    lat = torch.randn(B, C, H, W, dtype=torch.bfloat16, device="cuda") * lat_scale
    x = list(lat.unsqueeze(2).unbind(dim=0))            # list of [16,1,64,64]
    cap = [torch.randn(NT, 2560, dtype=torch.bfloat16, device="cuda") * cap_scale]
    t = torch.tensor([0.0], device="cuda")
    print(f"  [{tag}] tr.dtype={tr.dtype}  x[0]{tuple(x[0].shape)} {x[0].dtype}  "
          f"cap[0]{tuple(cap[0].shape)} std {cap[0].float().std():.2f}", flush=True)
    try:
        with torch.no_grad():
            o = tr(x, t, cap, return_dict=False)
        out = o[0] if isinstance(o, (tuple, list)) else o
        v = out[0] if isinstance(out, (list, tuple)) else out
        f = v.float()
        print(f"  [{tag}] 输出 {tuple(f.shape)} {v.dtype} mean {f.mean():+.4f} std {f.std():.4f} "
              f"absmax {f.abs().max():.2f} NaN {bool(torch.isnan(f).any())} "
              f"{'✅' if not torch.isnan(f).any() else '❌'}", flush=True)
    except Exception as e:
        print(f"  [{tag}] 失败: {type(e).__name__}: {str(e)[:180]}", flush=True)


for mode in ["4bit", "bf16"]:
    print(f"\n=== DiT {mode} ===", flush=True)
    gc.collect(); torch.cuda.empty_cache()
    try:
        if mode == "4bit":
            tr = ZImageTransformer2DModel.from_pretrained(
                os.path.join(ZDIR, "transformer"),
                quantization_config=DBnB(load_in_4bit=True, bnb_4bit_quant_type="nf4",
                                         bnb_4bit_compute_dtype=torch.bfloat16),
                device_map={"": 0}).eval()
        else:
            tr = ZImageTransformer2DModel.from_pretrained(
                os.path.join(ZDIR, "transformer"), torch_dtype=torch.bfloat16,
                device_map={"": 0}, low_cpu_mem_usage=True).eval()
        run(tr, f"{mode}/正态输入", cap_scale=1.0)
        run(tr, f"{mode}/大cap(56)", cap_scale=56.0)
        del tr
    except Exception as e:
        print(f"  载入失败: {type(e).__name__}: {str(e)[:180]}", flush=True)
    gc.collect(); torch.cuda.empty_cache()
