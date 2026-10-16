#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""画风保真四列对照：L36 / L32 / L28 / L24 —— 8 条风格提示词，专测画风漂移"""
import os, json, textwrap
from PIL import Image, ImageDraw, ImageFont
ROOT = r"C:\Users\32202\Desktop\99999\qwen-image-edge"
OUT = os.path.join(ROOT, "exp1_out")
PL = os.path.join(ROOT, "tools", "prompts_all.jsonl")
COLS = [(36,"原版 L36",(20,20,20)),(32,"L32 剪4层",(20,90,160)),
        (28,"L28 剪8层",(150,90,10)),(24,"L24 剪12层",(20,120,20))]

def font(sz):
    for p in [r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\simhei.ttf"]:
        if os.path.exists(p):
            try: return ImageFont.truetype(p, sz)
            except Exception: pass
    return ImageFont.load_default()
F_TXT,F_TAG,F_HD = font(15),font(15),font(21)
prompts = {}
for l in open(PL, encoding="utf-8"):
    if l.strip():
        d=json.loads(l); prompts[d["id"]]=d

def build(ids,name,title,cell=240,textw=300):
    rows=[]
    for pid in ids:
        ims=[]
        for L,_,_ in COLS:
            p=os.path.join(OUT,f"L{L:02d}_{pid}.png")
            if not os.path.exists(p): break
            im=Image.open(p).convert("RGB"); im.thumbnail((cell,cell)); ims.append((L,im))
        if len(ims)==len(COLS): rows.append((pid,ims))
    if not rows: print(name,"图不全"); return
    ch=max(max(i.height for _,i in r) for _,r in rows)+60
    W=textw+cell*4+60
    sh=Image.new("RGB",(W,ch*len(rows)+44),(252,252,252)); d=ImageDraw.Draw(sh)
    d.text((12,12),title,fill=(20,20,20),font=F_HD)
    y=44
    for pid,ims in rows:
        pr=prompts.get(pid,{})
        d.text((12,y+4),f"{pid}  {pr.get('style','')}/{pr.get('scene','')}",fill=(150,30,30),font=F_TAG)
        yy=y+26
        for line in textwrap.wrap(pr.get("prompt",""),width=17)[:11]:
            d.text((12,yy),line,fill=(40,40,40),font=F_TXT); yy+=20
        for ci,(L,im) in enumerate(ims):
            x=textw+4+ci*(cell+6)
            sh.paste(im,(x,y+26)); d.text((x,y+6),COLS[ci][1],fill=COLS[ci][2],font=F_TAG)
        y+=ch
    p=os.path.join(OUT,name); sh.save(p); print("写出:",p,f"{sh.size[0]}x{sh.size[1]}")

style_ids=["A05","A06","A07","A08","A09","A10","A11","A12"]
build(style_ids[:4],"画风四列_二次元.png","二次元 4 条 —— L36 原版 → L32 → L28 → L24（看画风何时开始漂移）")
build(style_ids[4:],"画风四列_3D.png","3D 4 条 —— L36 原版 → L32 → L28 → L24")
