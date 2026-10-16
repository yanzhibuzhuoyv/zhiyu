#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""最小化隔离：safe_open → get_slice → get_tensor → save_file 到底哪一步崩"""
import os, sys, gc, traceback
import torch, safetensors
from safetensors import safe_open
from safetensors.torch import save_file

SRC = r"C:\Users\32202\Desktop\99999\qwen-image-edge\models\Qwen-Image-2.1-Turbo\text_encoder\model.safetensors"
OUT = r"C:\Users\32202\Desktop\99999\qwen-image-edge\_shard_test"
os.makedirs(OUT, exist_ok=True)

print("torch", torch.__version__, "| safetensors", safetensors.__version__, flush=True)
print("step 0: safe_open", flush=True)
try:
    f = safe_open(SRC, framework="pt", device="cpu")
    keys = list(f.keys())
    print(f"  [OK] 打开成功，{len(keys)} 个张量", flush=True)
except Exception as e:
    print("  [FAIL]", type(e).__name__, e); traceback.print_exc(); sys.exit(1)

print("step 1: get_slice（只读元数据）", flush=True)
try:
    s = f.get_slice(keys[0])
    print(f"  [OK] {keys[0]} shape={s.get_shape()} dtype={s.get_dtype()}", flush=True)
except Exception as e:
    print("  [FAIL]", type(e).__name__, e); traceback.print_exc(); sys.exit(2)

# 找一个最小的张量来读
print("step 2: 读一个最小张量", flush=True)
sizes = []
for k in keys[:60]:
    try:
        sl = f.get_slice(k); shp = sl.get_shape()
        n = 1
        for d in shp: n *= d
        sizes.append((n, k))
    except Exception:
        pass
sizes.sort()
n, k = sizes[0]
print(f"  选中最小的: {k}  元素数={n}", flush=True)
try:
    t = f.get_tensor(k)
    print(f"  [OK] 读出 tensor {tuple(t.shape)} {t.dtype} 占用 {t.numel()*t.element_size()/2**20:.2f} MiB", flush=True)
except Exception as e:
    print("  [FAIL]", type(e).__name__, e); traceback.print_exc(); sys.exit(3)

print("step 3: save_file 写出", flush=True)
try:
    p = os.path.join(OUT, "one.safetensors")
    save_file({k: t}, p, metadata={"format": "pt"})
    print(f"  [OK] 写出 {os.path.getsize(p)} 字节", flush=True)
except Exception as e:
    print("  [FAIL]", type(e).__name__, e); traceback.print_exc(); sys.exit(4)

print("step 4: 读一个 BF16 大张量（embed_tokens，约 1.16 GiB）", flush=True)
big = [kk for kk in keys if "embed_tokens" in kk]
if big:
    try:
        t2 = f.get_tensor(big[0])
        mb = t2.numel()*t2.element_size()/2**20
        print(f"  [OK] {big[0]} {tuple(t2.shape)} {t2.dtype} {mb:.0f} MiB", flush=True)
        p2 = os.path.join(OUT, "big.safetensors")
        save_file({big[0]: t2}, p2, metadata={"format": "pt"})
        print(f"  [OK] 写出 {os.path.getsize(p2)/2**20:.0f} MiB", flush=True)
        del t2; gc.collect()
    except Exception as e:
        print("  [FAIL]", type(e).__name__, e); traceback.print_exc(); sys.exit(5)
else:
    print("  没找到 embed_tokens", flush=True)

f.__exit__(None, None, None)
print("\n全部通过 —— 说明分片脚本的失败在别处（可能是累计内存）", flush=True)
