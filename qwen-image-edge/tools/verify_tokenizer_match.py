#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""实测：教师与学生对同一批提示词的分词 id 是否逐一致（决定能否逐 token 对齐）"""
import os, json
os.environ.setdefault("HF_HUB_OFFLINE", "1")
from transformers import AutoTokenizer

ROOT = r"C:\Users\32202\Desktop\99999\qwen-image-edge"
TE_TOK = os.path.join(ROOT, "models", "Qwen-Image-2.1-Turbo", "processor")
ST_TOK = os.path.join(ROOT, "models", "Qwen3-1.7B-Base")

t = AutoTokenizer.from_pretrained(TE_TOK)
s = AutoTokenizer.from_pretrained(ST_TOK)

tests = [
    "一个女生坐在客厅沙发上微笑看镜头，午后阳光从窗户照进来，生活照",
    "动漫风格少女坐在窗边看书，午后光斑落在书页上，日式赛璐璐上色",
    "咖啡馆木质招牌特写，上面用手写中文字写着“深夜咖啡”",
    "a photo of a young woman sitting on a sofa, warm afternoon light",
    "皮克斯风格 3D 渲染的小男孩坐在客厅地毯上玩积木，开心大笑",
]
ok = 0
for x in tests:
    a = t.encode(x, add_special_tokens=False)
    b = s.encode(x, add_special_tokens=False)
    same = a == b
    ok += same
    print(f"{'✅' if same else '❌'} len={len(a)}/{len(b)}  {x[:34]}")
    if not same:
        for i, (u, v) in enumerate(zip(a, b)):
            if u != v:
                print(f"    首个差异 @{i}: teacher={u}({t.decode([u])!r})  student={v}({s.decode([v])!r})")
                break
print(f"\n一致 {ok}/{len(tests)}")
print(f"pad_token: teacher={t.pad_token!r}({t.pad_token_id})  student={s.pad_token!r}({s.pad_token_id})")
print(f"eos: {t.eos_token_id}/{s.eos_token_id}   bos: {t.bos_token_id}/{s.bos_token_id}")
