#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""测试打包后的 L24 编码器能否全放 GPU（蒸馏要靠它快速产教师信号）"""
import os, sys, time
os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"
import torch, psutil

PACKED = r"C:\Users\32202\Desktop\99999\qwen-image-edge\models\te_packed\L24"
PROC = r"C:\Users\32202\Desktop\99999\qwen-image-edge\models\Qwen-Image-2.1-Turbo\processor"

def rss(): return psutil.Process().memory_info().rss/2**30

from transformers import Qwen3VLForConditionalGeneration, BitsAndBytesConfig, AutoTokenizer
bnb = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4",
                         bnb_4bit_compute_dtype=torch.bfloat16, bnb_4bit_use_double_quant=True)

for label, kw in [("全放 GPU", dict(device_map={"": 0})),
                  ("auto",     dict(device_map="auto"))]:
    print(f"\n=== 尝试 {label} ===", flush=True)
    print(f"   起始 RSS {rss():.2f} GiB | VRAM 已用 {torch.cuda.memory_allocated()/2**30:.2f} GiB", flush=True)
    t0 = time.time()
    try:
        te = Qwen3VLForConditionalGeneration.from_pretrained(
            PACKED, quantization_config=bnb, low_cpu_mem_usage=True, **kw)
        te.eval()
        from collections import Counter
        dev = Counter(str(p.device) for p in te.parameters())
        print(f"   [OK] 加载 {time.time()-t0:.0f}s  设备分布 {dict(dev)}", flush=True)
        print(f"   RSS {rss():.2f} GiB | VRAM {torch.cuda.memory_allocated()/2**30:.2f} GiB", flush=True)

        tok = AutoTokenizer.from_pretrained(PROC)
        lm = getattr(te.model, "language_model", te.model)
        sys_tk = tok.apply_chat_template([{"role":"system","content":[{"type":"text","text":"Comprehend and analyze the provided prompt."}]}],
                                         tokenize=True, return_dict=False)
        drop = len(sys_tk[0]) if sys_tk and isinstance(sys_tk[0], (list,tuple)) else len(sys_tk)
        T2I = ("<|im_start|>system\nComprehend and analyze the provided prompt.<|im_end|>\n"
               "<|im_start|>user\n{}<|im_end|>\n<|im_start|>assistant\n")
        prompts = ["一个女生坐在客厅沙发上微笑看镜头，午后阳光从窗户照进来，生活照",
                   "动漫风格少女坐在窗边看书，午后光斑落在书页上，日式赛璐璐上色",
                   "咖啡馆木质招牌特写，上面用手写中文字写着“深夜咖啡”"] * 4
        inp = tok(text=[T2I.format(p) for p in prompts], padding=True, padding_side="left", return_tensors="pt")
        inp = {k: v.to(te.device) for k, v in inp.items()}
        h = lm.norm.register_forward_hook(lambda m, a, o: a[0])
        try:
            with torch.no_grad():
                out = te(**inp, output_hidden_states=True)
        finally:
            h.remove()
        hs = out.hidden_states[-1]
        t1 = time.time()
        with torch.no_grad():
            for _ in range(3):
                out = te(**inp, output_hidden_states=True)
        dt = (time.time()-t1)/3
        print(f"   [OK] 前向 batch={len(prompts)} 耗时 {dt:.2f}s  => {dt/len(prompts)*1000:.0f} ms/条", flush=True)
        print(f"   hidden {tuple(hs.shape)}", flush=True)
        print(f"   峰值 VRAM {torch.cuda.max_memory_allocated()/2**30:.2f} GiB", flush=True)
        print(f"\n>>> {label} 可用，蒸馏数据生成建议用这个方式", flush=True)
        sys.exit(0)
    except Exception as e:
        print(f"   [FAIL] {type(e).__name__}: {str(e)[:220]}", flush=True)
print("\n两种都不行", flush=True)
sys.exit(1)
