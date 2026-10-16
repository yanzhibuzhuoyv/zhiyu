#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""检查 ModelScope 上 Z-Image / ERNIE-Image 等候选底座的可用性与体积"""
import urllib.request, json, sys

CANDIDATES = [
    "Tongyi-MAI/Z-Image-Turbo",
    "Tongyi-MAI/Z-Image",
    "Tongyi-MAI/Z-Image-Edit",
    "PaddlePaddle/ERNIE-Image-Turbo",
    "baidu/ERNIE-Image-Turbo",
    "AI-ModelScope/ERNIE-Image-Turbo",
    "zai-org/GLM-Image",
    "black-forest-labs/FLUX.2-klein",
]


def probe(name):
    url = f"https://modelscope.cn/api/v1/models/{name}/repo/files?Revision=master&Root="
    try:
        raw = urllib.request.urlopen(url, timeout=30).read()
        d = json.loads(raw)
    except Exception as e:
        return None, f"请求失败 {str(e)[:40]}"
    files = d.get("Data", {}).get("Files")
    if files is None:
        return None, f"无 Files 字段 (keys={list(d.get('Data',{}).keys())[:5]})"
    tot = 0
    big = []
    for f in files:
        sz = f.get("Size") or 0
        tot += sz
        if sz > 100 * 2**20:
            big.append((f.get("Path"), sz))
    big.sort(key=lambda x: -x[1])
    return {"total": tot, "n": len(files), "big": big[:8]}, None


for c in CANDIDATES:
    r, err = probe(c)
    if err:
        print(f"❌ {c:<32} {err}")
        continue
    print(f"✅ {c:<32} {r['n']:4d} 文件  {r['total']/2**30:7.2f} GiB")
    for p, s in r["big"]:
        print(f"      {s/2**30:7.2f} GiB  {p}")
