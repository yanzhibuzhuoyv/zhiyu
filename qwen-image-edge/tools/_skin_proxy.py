import os
import numpy as np
from PIL import Image
ROOT = r"C:\Users\32202\Desktop\99999\qwen-image-edge"
D1, D2 = os.path.join(ROOT,"edit_out"), os.path.join(ROOT,"edit_out_confirm")
SEEDS=[42,1234,7,99,2024,555]

def skin_frac(p):
    a=np.asarray(Image.open(p).convert("RGB")).astype(np.int16)
    R,G,B=a[...,0],a[...,1],a[...,2]
    mx=a.max(2); mn=a.min(2)
    m=(R>95)&(G>40)&(B>20)&((mx-mn)>15)&(abs(R-G)>15)&(R>G)&(R>B)
    return m.mean()*100

for task in ["E1_fullbody","E2_picnic"]:
    print(f"\n[{task}] 皮肤像素占比 %（越大=人物在画面里越大=构图越紧）")
    print("  seed   " + "".join(f"  L{L:<7}" for L in (36,28,24)))
    agg={36:[],28:[],24:[]}
    for s in SEEDS:
        row=[]
        for L in (36,28,24):
            p=None
            for d in (D1,D2):
                q=os.path.join(d,f"{task}_L{L}_s{s}.png")
                if os.path.exists(q): p=q;break
            v=skin_frac(p); agg[L].append(v); row.append(f"  {v:6.2f} ")
        print(f"  {s:<7}" + "".join(row))
    print("  均值   " + "".join(f"  {np.mean(agg[L]):6.2f} " for L in (36,28,24)))
ref=os.path.join(ROOT,"exp1_out","L36_A01.png")
print(f"\n参考图皮肤占比: {skin_frac(ref):.2f}%")
