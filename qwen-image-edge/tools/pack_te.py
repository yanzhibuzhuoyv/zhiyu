#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
打包文本编码器：裁层 + 删冗余，导出成独立的 safetensors
  变体 L36：保留全部 36 层，仅删 lm_head（生图不产生 logits）
  变体 L24：保留前 24 层（丢掉后 12 层）+ 删 lm_head

用自己实现的 safetensors 读取（safetensors 库在 16GiB 单文件上 mmap 会硬崩）
逐张量流式搬运，峰值内存 ≈ 最大张量
"""
import os, sys, json, struct, gc, time, argparse

SRC = r"C:\Users\32202\Desktop\99999\qwen-image-edge\models\Qwen-Image-2.1-Turbo\text_encoder"
DST_ROOT = r"C:\Users\32202\Desktop\99999\qwen-image-edge\models\te_packed"
SHARD_BYTES = 2 * 1024**3
BYTES = {"F64":8,"F32":4,"F16":2,"BF16":2,"I64":8,"I32":4,"I16":2,"I8":1,"U8":1,"BOOL":1}
import torch
from safetensors.torch import save_file
TORCH_DT = {"F64":torch.float64,"F32":torch.float32,"F16":torch.float16,"BF16":torch.bfloat16,
            "I64":torch.int64,"I32":torch.int32,"I16":torch.int16,"I8":torch.int8,
            "U8":torch.uint8,"BOOL":torch.bool}


def read_header(path):
    with open(path, "rb") as fh:
        n = struct.unpack("<Q", fh.read(8))[0]
        hdr = json.loads(fh.read(n).decode("utf-8"))
    return hdr, 8 + n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--layers", type=int, default=36, help="保留多少层")
    ap.add_argument("--keep-vision", type=int, default=1, help="是否保留视觉塔（编辑功能需要）")
    ap.add_argument("--name", default="", help="输出目录名")
    ap.add_argument("--src", default=SRC)
    args = ap.parse_args()
    keep = args.layers
    name = args.name or f"L{keep}" + ("" if args.keep_vision else "_novision")
    dst = os.path.join(DST_ROOT, name)
    os.makedirs(dst, exist_ok=True)

    # ---- 汇总所有分片的张量索引 ----
    shards = sorted([f for f in os.listdir(args.src)
                     if f.startswith("model-") and f.endswith(".safetensors")])
    if not shards:
        shards = [f for f in os.listdir(args.src) if f == "model.safetensors"]
    print(f"源分片 {len(shards)} 个", flush=True)
    index = {}   # key -> (shard_file, data_start, off, end, shape, dtype)
    t0 = time.time()
    for s in shards:
        p = os.path.join(args.src, s)
        hdr, ds = read_header(p)
        for k, e in hdr.items():
            if k == "__metadata__":
                continue
            index[k] = (s, ds, e["data_offsets"][0], e["data_offsets"][1], e["shape"], e["dtype"])
    print(f"共 {len(index)} 个张量，读头耗时 {time.time()-t0:.0f}s", flush=True)

    # ---- 选键 ----
    def keep_key(k):
        if k == "lm_head.weight":
            return False                       # 生图不需要 logits
        if k.startswith("model.visual."):
            return bool(args.keep_vision)
        if k.startswith("model.language_model.layers."):
            try:
                li = int(k.split(".")[3])
            except Exception:
                return True
            return li < keep
        return True                            # embed_tokens / norm 等一律保留

    keys = [k for k in index if keep_key(k)]
    dropped = [k for k in index if not keep_key(k)]
    total = sum((index[k][3]-index[k][2]) for k in keys)
    print(f"保留 {len(keys)} 个张量 / 丢弃 {len(dropped)} 个", flush=True)
    print(f"输出体积 {total/2**30:.2f} GiB（原 16.33 GiB）", flush=True)
    drop_by = {}
    for k in dropped:
        top = ".".join(k.split(".")[:2]) if k.startswith("model.") else k.split(".")[0]
        drop_by[top] = drop_by.get(top, 0) + (index[k][3]-index[k][2])
    for t, b in sorted(drop_by.items(), key=lambda x: -x[1]):
        print(f"   丢弃 {t:<34} {b/2**30:6.2f} GiB", flush=True)

    # ---- 分组到分片 ----
    shard_list, cur, curb = [], [], 0
    for k in keys:
        sz = index[k][3]-index[k][2]
        if cur and curb + sz > SHARD_BYTES:
            shard_list.append(cur); cur, curb = [], 0
        cur.append(k); curb += sz
    if cur:
        shard_list.append(cur)
    N = len(shard_list)

    # ---- 逐片搬运 ----
    hand = {}
    weight_map = {}
    t1 = time.time()
    for i, ks in enumerate(shard_list, 1):
        out_name = f"model-{i:05d}-of-{N:05d}.safetensors"
        out_path = os.path.join(dst, out_name)
        if os.path.exists(out_path) and os.path.getsize(out_path) > 0:
            print(f"  [{i}/{N}] 已存在，跳过", flush=True)
        else:
            d = {}
            for k in ks:
                s, ds, off, end, shape, dtype = index[k]
                if s not in hand:
                    hand[s] = open(os.path.join(args.src, s), "rb")
                fh = hand[s]
                nb = end - off
                buf = bytearray(nb)
                fh.seek(ds + off)
                if fh.readinto(buf) != nb:
                    raise IOError(f"{k} 读取不足")
                d[k] = torch.frombuffer(buf, dtype=TORCH_DT[dtype]).reshape(shape).clone()
                del buf
            save_file(d, out_path, metadata={"format": "pt"})
            print(f"  [{i}/{N}] {out_name}  {len(ks)} 张量  {os.path.getsize(out_path)/2**30:.2f} GiB", flush=True)
            d.clear(); del d; gc.collect()
        for k in ks:
            weight_map[k] = out_name
    for fh in hand.values():
        fh.close()

    idx = {"metadata": {"total_size": total}, "weight_map": weight_map}
    json.dump(idx, open(os.path.join(dst, "model.safetensors.index.json"), "w", encoding="utf-8"),
              indent=1)

    # ---- 拷配置 ----
    for f in ["config.json", "generation_config.json"]:
        srcf = os.path.join(args.src, f)
        if os.path.exists(srcf):
            cfg = json.load(open(srcf, encoding="utf-8"))
            if f == "config.json":
                cfg["_packed_note"] = (f"pruned to {keep} layers; lm_head removed; "
                                       f"vision {'kept' if args.keep_vision else 'removed'}")
            json.dump(cfg, open(os.path.join(dst, f), "w", encoding="utf-8"), indent=1)
    tot = sum(os.path.getsize(os.path.join(dst, f)) for f in os.listdir(dst) if f.endswith(".safetensors"))
    print(f"\n完成 -> {dst}", flush=True)
    print(f"  分片 {N} 个，合计 {tot/2**30:.2f} GiB  (bf16)", flush=True)
    print(f"  若量化到 int4 约 {tot/2**30/4:.2f} GiB", flush=True)
    print(f"  耗时 {time.time()-t1:.0f}s", flush=True)


if __name__ == "__main__":
    sys.exit(main())
