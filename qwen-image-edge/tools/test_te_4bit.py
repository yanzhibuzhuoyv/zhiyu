#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
在内存紧张的机器上加载 4-bit 文本编码器 —— 逐个策略尝试
约束：物理 15.7GiB（90% 已用）、提交余量仅 ~5.9GiB、页文件上限 16GiB、无管理员权限
"""
import os, sys, time, gc
os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"
import torch, psutil

TE = r"C:\Users\32202\Desktop\99999\qwen-image-edge\models\Qwen-Image-2.1-Turbo\text_encoder"

def report(tag):
    v = psutil.virtual_memory()
    proc = psutil.Process()
    print(f"   [{tag}] 本进程 RSS {proc.memory_info().rss/2**30:.2f} GiB | "
          f"系统可用 {v.available/2**30:.2f} GiB | 提交已用 {psutil.swap_memory().used/2**30:.2f} GiB", flush=True)

from transformers import Qwen3VLForConditionalGeneration, BitsAndBytesConfig
bnb = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4",
                         bnb_4bit_compute_dtype=torch.bfloat16, bnb_4bit_use_double_quant=True)

# Qwen3-VL 的视觉塔对文生图完全没用，先记下来（后面可以裁掉省 ~1.15GB）
print("策略：4-bit + device_map。逐个尝试，记录峰值。", flush=True)
report("起始")

strategies = [
    ("全放 GPU (cuda:0)", dict(device_map={"": 0}, low_cpu_mem_usage=True)),
    ("全放 CPU",          dict(device_map={"": "cpu"}, low_cpu_mem_usage=True)),
]
for name, kw in strategies:
    print(f"\n=== 尝试：{name} ===", flush=True)
    report("前")
    t0 = time.time()
    try:
        te = Qwen3VLForConditionalGeneration.from_pretrained(TE, quantization_config=bnb, **kw)
        print(f"[成功] {name}  耗时 {time.time()-t0:.0f}s", flush=True)
        report("后")
        from collections import Counter
        print("   参数设备分布:", dict(Counter(str(p.device) for p in te.parameters())), flush=True)
        print("   参数量:", sum(p.numel() for p in te.parameters())/1e9, "B", flush=True)
        # 前向测试
        from transformers import AutoProcessor
        proc = AutoProcessor.from_pretrained(TE)
        msgs = [{"role": "system", "content": [{"type": "text", "text": "Describe the image."}]},
                {"role": "user", "content": [{"type": "text", "text": "一只在雨中弹吉他的柯基"}]}]
        text = proc.apply_chat_template(msgs, tokenize=False, add_generation_prompt=False)
        inp = proc(text=[text], padding=True, padding_side="left", return_tensors="pt")
        print(f"   token 数 {inp.input_ids.shape[1]}", flush=True)
        t1 = time.time()
        with torch.no_grad():
            out = te(**inp, output_hidden_states=True)
        hs = out.hidden_states[-1]
        print(f"[成功] 前向 {time.time()-t1:.1f}s  hidden_states[-1] {tuple(hs.shape)}", flush=True)
        os.makedirs(r"C:\Users\32202\Desktop\99999\qwen-image-edge\smoke_out", exist_ok=True)
        torch.save({"embeds": hs.detach().float().cpu(), "mask": inp["attention_mask"].cpu()},
                   r"C:\Users\32202\Desktop\99999\qwen-image-edge\smoke_out\te_probe.pt")
        print("[成功] 已保存 te_probe.pt —— 阶段一完成", flush=True)
        sys.exit(0)
    except Exception as e:
        print(f"[失败] {name}: {type(e).__name__}: {str(e)[:300]}", flush=True)
        gc.collect()
        try:
            del te
        except Exception:
            pass
        report("失败后")

print("\n所有策略均失败。需要释放内存或调大页文件。", flush=True)
sys.exit(1)
