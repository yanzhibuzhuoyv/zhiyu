#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把 16.33 GiB 的 safetensors 切成 ~2 GiB 小分片
关键：不用 safetensors.safe_open（它在超大文件上 mmap 会硬崩溃），
      改为自己按 safetensors 格式做普通文件 I/O 流式读取：
      [8字节 header_len][header JSON][raw data]，按 data_offsets seek+readinto
写入用 safetensors.torch.save_file（已验证可用）

峰值内存 ≈ 单张量大小（最大 1.24 GiB）
"""
import os, sys, json, struct, gc, time
import torch
from safetensors.torch import save_file

SRC = r"C:\Users\32202\Desktop\99999\qwen-image-edge\models\Qwen-Image-2.1-Turbo\text_encoder\model.safetensors"
SHARD_BYTES = 2 * 1024**3
OUT_DIR = os.path.dirname(SRC)

TORCH_DT = {
    "F64": torch.float64, "F32": torch.float32, "F16": torch.float16, "BF16": torch.bfloat16,
    "I64": torch.int64, "I32": torch.int32, "I16": torch.int16, "I8": torch.int8,
    "U8": torch.uint8, "BOOL": torch.bool,
}
BYTES = {"F64": 8, "F32": 4, "F16": 2, "BF16": 2, "I64": 8, "I32": 4, "I16": 2, "I8": 1, "U8": 1, "BOOL": 1}

t0 = time.time()
print(f"源: {SRC}  ({os.path.getsize(SRC)/2**30:.2f} GiB)", flush=True)

# ---------- 1) 读头 ----------
with open(SRC, "rb") as fh:
    hlen = struct.unpack("<Q", fh.read(8))[0]
    hdr = json.loads(fh.read(hlen).decode("utf-8"))
DATA_START = 8 + hlen
keys = [k for k in hdr.keys() if k != "__metadata__"]
print(f"头长度 {hlen:,} 字节 | 张量 {len(keys)} 个 | 数据起点 {DATA_START:,}", flush=True)

def nbytes(k):
    e = hdr[k]
    n = 1
    for d in e["shape"]:
        n *= d
    return n * BYTES[e["dtype"]]

# ---------- 2) 分组 ----------
shards, cur, cur_b = [], [], 0
for k in keys:
    sz = nbytes(k)
    if cur and cur_b + sz > SHARD_BYTES:
        shards.append(cur); cur, cur_b = [], 0
    cur.append(k); cur_b += sz
if cur:
    shards.append(cur)
N = len(shards)
print(f"切成 {N} 片，每片 <= {SHARD_BYTES/2**30:.0f} GiB", flush=True)

# ---------- 3) 逐片读出+写出 ----------
weight_map = {}
with open(SRC, "rb") as fh:
    for i, ks in enumerate(shards, 1):
        name = f"model-{i:05d}-of-{N:05d}.safetensors"
        path = os.path.join(OUT_DIR, name)
        if os.path.exists(path) and os.path.getsize(path) > 0:
            print(f"  [{i}/{N}] {name} 已存在，跳过", flush=True)
        else:
            d = {}
            for k in ks:
                e = hdr[k]
                off, end = e["data_offsets"]
                nb = end - off
                buf = bytearray(nb)                       # 一次分配，readinto 不额外拷贝
                fh.seek(DATA_START + off)
                got = fh.readinto(buf)
                if got != nb:
                    raise IOError(f"{k} 读取不足 {got}/{nb}")
                t = torch.frombuffer(buf, dtype=TORCH_DT[e["dtype"]]).reshape(e["shape"])
                d[k] = t.clone()                          # clone 让 tensor 拥有独立内存，便于释放 buf
                del buf
            save_file(d, path, metadata={"format": "pt"})
            tot = os.path.getsize(path)
            print(f"  [{i}/{N}] {name}  {len(ks)} 张量  {tot/2**30:.2f} GiB", flush=True)
            d.clear(); del d; gc.collect()
        for k in ks:
            weight_map[k] = name

# ---------- 4) 索引 ----------
idx = {"metadata": {"total_size": sum(nbytes(k) for k in keys)}, "weight_map": weight_map}
ip = os.path.join(OUT_DIR, "model.safetensors.index.json")
with open(ip, "w", encoding="utf-8") as f:
    json.dump(idx, f, indent=1)
print(f"\n索引: {os.path.basename(ip)}  total_size={idx['metadata']['total_size']/2**30:.3f} GiB", flush=True)

# ---------- 5) 校验 ----------
miss = [f"model-{i:05d}-of-{N:05d}.safetensors" for i in range(1, N+1)
        if not os.path.exists(os.path.join(OUT_DIR, f"model-{i:05d}-of-{N:05d}.safetensors"))]
tot = sum(os.path.getsize(os.path.join(OUT_DIR, f"model-{i:05d}-of-{N:05d}.safetensors")) for i in range(1, N+1))
print(f"分片合计 {tot/2**30:.3f} GiB | 缺失 {len(miss)} 片", flush=True)
print(f"耗时 {time.time()-t0:.0f}s", flush=True)
