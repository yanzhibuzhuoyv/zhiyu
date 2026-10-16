#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""全量决策图：20 条提示词 × 4 个候选层（L36 原版 / L32 / L28 / L24）"""
import os, json, textwrap
from PIL import Image, ImageDraw, ImageFont
ROOT = r"C:\Users\32202\Desktop\99999\qwen-image-edge"
OUT = os.path.join(ROOT, "exp1_out")
PL = os.path.join(ROOT, "tools", "prompts_all.jsonl")
COLS = [(36,"原版 L36",(20,20,20)),(32,"L32 省0.99G",(20,90,160)),
        (28,"L28 省1.37G",(150,90,10)),(24,"L24 省1.76G",(20,120,20))]

def font(sz):
    for p in [r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\simhei.ttf"]:
        if os.path.exists(p):
            try: return ImageFont.truetype(p, sz)
            except Exception: pass
    return ImageFont.load_default()
F_TXT,F_TAG,F_HD = font(15),font(15),font(20)

prompts={}
for l in open(PL,encoding="utf-8"):
    if l.strip():
        d=json.loads(l); prompts[d["id"]]=d

def build(ids,name,title,cell=235,textw=290):
    rows=[]
    for pid in ids:
        ims=[]
        for L,_,_ in COLS:
            p=os.path.join(OUT,f"L{L:02d}_{pid}.png")
            if not os.path.exists(p): break
            im=Image.open(p).convert("RGB"); im.thumbnail((cell,cell)); ims.append((L,im))
        if len(ims)==len(COLS): rows.append((pid,ims))
        else: print("  缺图:",pid)
    if not rows: return
    ch=max(max(i.height for _,i in r) for _,r in rows)+58
    W=textw+cell*4+52
    sh=Image.new("RGB",(W,ch*len(rows)+42),(252,252,252)); d=ImageDraw.Draw(sh)
    d.text((12,11),title,fill=(20,20,20),font=F_HD)
    y=42
    for pid,ims in rows:
        pr=prompts.get(pid,{})
        d.text((12,y+4),f"{pid}  {pr.get('style','')}/{pr.get('scene','')}",fill=(150,30,30),font=F_TAG)
        yy=y+25
        for line in textwrap.wrap(pr.get("prompt",""),width=16)[:11]:
            d.text((12,yy),line,fill=(45,45,45),font=F_TXT); yy+=19
        for ci,(L,im) in enumerate(ims):
            x=textw+4+ci*(cell+6)
            sh.paste(im,(x,y+24)); d.text((x,y+5),COLS[ci][1],fill=COLS[ci][2],font=F_TAG)
        y+=ch
    p=os.path.join(OUT,name); sh.save(p); print("写出:",p,f"{sh.size[0]}x{sh.size[1]}")

ids=["A01","A02","A03","A04","A05","A06","A07","A08","A09","A10",
     "A11","A12","A13","A14","A15","A16","L01","L02","L03","L04"]
for i in range(0,len(ids),4):
    build(ids[i:i+4], f"决策_{i//4+1}.png", f"决策对照 {i+1}-{i+4}/20：原版 L36 → L32 → L28 → L24")
