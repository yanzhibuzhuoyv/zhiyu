#!/usr/bin/env python
# -*- coding: utf-8 -*-
import inspect
from diffusers import ZImageTransformer2DModel

sig = inspect.signature(ZImageTransformer2DModel.forward)
print("=== ZImageTransformer2DModel.forward 参数 ===")
for n, p in sig.parameters.items():
    d = "<必填>" if p.default is inspect._empty else p.default
    print(f"  {n:<26} {d}")

print("\n=== 前 40 行源码 ===")
src = inspect.getsource(ZImageTransformer2DModel.forward).split("\n")
for l in src[:40]:
    print("  " + l)
