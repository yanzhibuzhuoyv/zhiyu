#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""递归统计 ModelScope 模型仓库各子目录体积"""
import urllib.request, json, sys

def listdir(name, root=""):
    u = (f"https://modelscope.cn/api/v1/models/{name}/repo/files"
         f"?Revision=master&Root={urllib.parse.quote(root)}")
    d = json.loads(urllib.request.urlopen(u, timeout=40).read())
    return d["Data"]["Files"]

def walk(name, root="", depth=0, out=None):
    if out is None:
        out = {}
    try:
        files = listdir(name, root)
    except Exception as e:
        print(f"  {'  '*depth}! {root}: {str(e)[:40]}")
        return out
    tot = 0
    for f in files:
        p, t, sz = f.get("Path"), f.get("Type"), (f.get("Size") or 0)
        if t == "tree":
            sub = walk(name, p, depth + 1, out)
            tot += sub
        else:
            tot += sz
            if sz > 200 * 2**20:
                out[p] = sz
    if depth <= 1:
        print(f"  {'  '*depth}{root or '(根)'}: {tot/2**30:.2f} GiB")
    return tot

if __name__ == "__main__":
    import urllib.parse
    for name in sys.argv[1:]:
        print("=" * 72)
        print(name)
        big = {}
        total = walk(name, "", 0, big)
        print(f"  >>> 合计 {total/2**30:.2f} GiB")
        for p, s in sorted(big.items(), key=lambda x: -x[1])[:10]:
            print(f"      {s/2**30:7.2f} GiB  {p}")
