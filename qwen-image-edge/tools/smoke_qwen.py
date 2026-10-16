#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
冒烟测试：在本机（RTX 4060 Laptop 8GB + 16GB RAM）用 4-bit 跑通 Qwen-Image-2.1-Turbo
目的：验证端到端能出图，并记录耗时与显存 —— 这是"手机端可行性"的桌面参照点

策略：4-bit 量化 + 顺序 offload（TE 与 DiT 不同时常驻，正是"串行加载"）
"""
import os, sys, time, gc, argparse

os.environ.setdefault("HF_HUB_OFFLINE", "1")          # 只用本地权重，别去联网
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")

import torch
from PIL import Image


def mem(tag):
    if torch.cuda.is_available():
        print(f"  [{tag}] VRAM 已分配 {torch.cuda.memory_allocated()/2**30:.2f} GiB / "
              f"峰值 {torch.cuda.max_memory_allocated()/2**30:.2f} GiB", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\models\Qwen-Image-2.1-Turbo")
    ap.add_argument("--prompt", default="一只在雨中弹吉他的柯基，写实摄影，雨滴清晰可见")
    ap.add_argument("--size", type=int, default=512)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--out", default="smoke_out")
    ap.add_argument("--4bit", dest="q4", action="store_true", default=True)
    ap.add_argument("--no-4bit", dest="q4", action="store_false")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    print(f"[1/4] 加载管线  4bit={args.q4}  {args.model}", flush=True)
    t0 = time.time()
    from diffusers import QwenImage21Pipeline
    from transformers import BitsAndBytesConfig

    kwargs = {}
    if args.q4:
        bnb = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4",
                                 bnb_4bit_compute_dtype=torch.bfloat16)
        # diffusers 的量化配置入口随版本变化，逐个尝试
        try:
            from diffusers import BitsAndBytesConfig as DB
            kwargs["quantization_config"] = DB(load_in_4bit=True, bnb_4bit_quant_type="nf4",
                                               bnb_4bit_compute_dtype=torch.bfloat16)
        except Exception:
            kwargs["quantization_config"] = bnb
    try:
        pipe = QwenImage21Pipeline.from_pretrained(args.model, **kwargs)
        print(f"      用 quantization_config 加载成功", flush=True)
    except Exception as e:
        print(f"      quantization_config 失败（{type(e).__name__}: {e}）", flush=True)
        print("      退回 bf16 + 顺序 offload", flush=True)
        pipe = QwenImage21Pipeline.from_pretrained(args.model, torch_dtype=torch.bfloat16)

    print(f"[2/4] 启用顺序 offload（TE 与 DiT 串行驻留显存）", flush=True)
    pipe.enable_model_cpu_offload()
    print(f"      加载+设置耗时 {time.time()-t0:.1f}s", flush=True)
    mem("加载后")

    # 报告采样表是否被 Turbo 固化
    ss = getattr(pipe.config, "sample_sigmas", None)
    print(f"      sample_sigmas = {ss}", flush=True)
    print(f"      transformer.causal_condition = {pipe.transformer.config.causal_condition}", flush=True)

    print(f"[3/4] 生成 {args.size}x{args.size}  seed={args.seed}", flush=True)
    torch.cuda.reset_peak_memory_stats()
    t1 = time.time()
    img = pipe(
        prompt=args.prompt,
        width=args.size, height=args.size,
        use_kv_cache=True,
        generator=torch.Generator("cpu").manual_seed(args.seed),
        output_type="pil",
    ).images[0]
    dt = time.time() - t1
    p = os.path.join(args.out, f"smoke_{args.size}_{args.seed}.png")
    img.save(p)
    print(f"      出图耗时 {dt:.1f}s  ->  {p}", flush=True)
    mem("生成后")
    try:
        free, total = torch.cuda.mem_get_info()
        print(f"      显存剩余 {free/2**30:.2f} / 总 {total/2**30:.2f} GiB", flush=True)
    except Exception:
        pass

    print(f"[4/4] 完成。图: {p}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
