#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
删除前的严格验证：单文件 model.safetensors 的所有张量，是否都被 index.json 覆盖的分片完整包含
（只读文件头，不读张量数据，秒级完成）
"""
import os, json, struct, sys

TE = r"C:\Users\32202\Desktop\99999\qwen-image-edge\models\Qwen-Image-2.1-Turbo\text_encoder"


def header(path):
    with open(path, "rb") as fh:
        n = struct.unpack("<Q", fh.read(8))[0]
        h = json.loads(fh.read(n).decode("utf-8"))
    return {k: v for k, v in h.items() if k != "__metadata__"}


single = os.path.join(TE, "model.safetensors")
index = os.path.join(TE, "model.safetensors.index.json")

if not os.path.exists(single):
    print("单文件不存在，无需处理"); sys.exit(0)
if not os.path.exists(index):
    print("❌ 没有 index.json，绝不能删单文件"); sys.exit(1)

print("读取单文件头...", flush=True)
hs = header(single)
print(f"  {len(hs)} 个张量，{os.path.getsize(single)/2**30:.2f} GiB")

print("读取 index.json...", flush=True)
idx = json.load(open(index, encoding="utf-8"))
wm = idx["weight_map"]
print(f"  索引 {len(wm)} 个张量，覆盖 {len(set(wm.values()))} 个分片")

# 分片头
shard_keys = {}
for s in sorted(set(wm.values())):
    p = os.path.join(TE, s)
    if not os.path.exists(p):
        print(f"❌ 缺分片 {s}"); sys.exit(1)
    for k, v in header(p).items():
        shard_keys[k] = (s, v["dtype"], tuple(v["shape"]), v["data_offsets"][1] - v["data_offsets"][0])

print(f"  分片合计 {len(shard_keys)} 个张量")
print()
missing = [k for k in hs if k not in shard_keys]
mismatch = []
for k in hs:
    if k in shard_keys:
        _, dt, sh, nb = shard_keys[k]
        if dt != hs[k]["dtype"] or sh != tuple(hs[k]["shape"]):
            mismatch.append(k)
extra = [k for k in shard_keys if k not in hs]

print(f"  单文件有而分片缺: {len(missing)}  {missing[:5]}")
print(f"  dtype/shape 不一致: {len(mismatch)}  {mismatch[:5]}")
print(f"  分片多出的张量  : {len(extra)}  {extra[:5]}")
print()
if not missing and not mismatch:
    print("✅ 分片完整覆盖单文件，可以安全删除单文件")
    sys.exit(0)
else:
    print("❌ 分片不完整，禁止删除单文件")
    sys.exit(2)
