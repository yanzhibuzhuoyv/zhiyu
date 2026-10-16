#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
编辑出图：覆写 encode_prompt 注入预算好的 (embeds, mask, image_pad_mask)，
参考图通过 __call__(image=...) 传给 VAE 做潜变量条件。
"""
import os, sys, json, time, argparse, gc
os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"
import torch
from PIL import Image

MODEL = r"C:\Users\32202\Desktop\99999\qwen-image-edge\models\Qwen-Image-2.1-Turbo"


class TokShim:
    def __init__(self, tok): self.tokenizer = tok
    def apply_chat_template(self, *a, **k):
        o = self.tokenizer.apply_chat_template(*a, **k)
        return [o] if (o and not isinstance(o[0], (list, tuple))) else o


def build_pipe(model_dir):
    from diffusers import (QwenImage21Pipeline, QwenImage21Transformer2DModel,
                           AutoencoderKLQwenImage21, FlowMatchEulerDiscreteScheduler)
    from diffusers import BitsAndBytesConfig as DBnB
    from transformers import AutoTokenizer
    vae = AutoencoderKLQwenImage21.from_pretrained(os.path.join(model_dir, "vae"), torch_dtype=torch.bfloat16)
    sched = FlowMatchEulerDiscreteScheduler.from_pretrained(os.path.join(model_dir, "scheduler"))
    print("加载 DiT（4-bit）...", flush=True)
    t0 = time.time()
    tr = QwenImage21Transformer2DModel.from_pretrained(
        os.path.join(model_dir, "transformer"),
        quantization_config=DBnB(load_in_4bit=True, bnb_4bit_quant_type="nf4",
                                 bnb_4bit_compute_dtype=torch.bfloat16))
    print(f"  完成 {time.time()-t0:.0f}s", flush=True)
    tok = AutoTokenizer.from_pretrained(os.path.join(model_dir, "processor"))
    ss = json.load(open(os.path.join(model_dir, "model_index.json"), encoding="utf-8")).get("sample_sigmas")
    return QwenImage21Pipeline(scheduler=sched, vae=vae, text_encoder=None,
                               processor=TokShim(tok), transformer=tr, sample_sigmas=ss)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=MODEL)
    ap.add_argument("--embeds", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\edit_embeds")
    ap.add_argument("--layers", default="36,28,24")
    ap.add_argument("--seeds", default="42,1234", help="多个 seed 用于稳定性测试")
    ap.add_argument("--size", default="512x512", help="输出尺寸 WxH")
    ap.add_argument("--size-map", default='{"E1_fullbody":"512x768"}', help="按任务覆盖尺寸")
    ap.add_argument("--out", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\edit_out")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    layers = [int(x) for x in args.layers.split(",")]
    seeds = [int(x) for x in args.seeds.split(",")]
    size_map = json.loads(args.size_map)

    idx = json.load(open(os.path.join(args.embeds, "index.json"), encoding="utf-8"))
    pipe = build_pipe(args.model)
    pipe.enable_model_cpu_offload()

    # 覆写 encode_prompt：把预算好的三元组直接返回
    STASH = {}
    def patched_encode_prompt(*a, **kw):
        return STASH["embeds"], STASH["mask"], STASH["img_mask"]
    pipe.encode_prompt = patched_encode_prompt

    results = []
    for pid, meta in idx.items():
        rec = torch.load(os.path.join(args.embeds, meta["file"]), weights_only=False)
        ref = Image.open(meta["ref"]).convert("RGB")
        sz = size_map.get(pid, args.size)
        w, h = [int(x) for x in sz.lower().split("x")]
        for L in layers:
            if L not in rec["layers"]: continue
            dev = pipe._execution_device
            STASH["embeds"] = rec["layers"][L]["embeds"].to(dev)
            STASH["mask"] = rec["layers"][L]["mask"].to(dev)
            ipm = rec["layers"][L]["image_pad_mask"]
            if ipm.dim() == 1:                      # 管线要 [batch, seq]
                ipm = ipm.unsqueeze(0)
            STASH["img_mask"] = ipm.to(dev)
            for sd in seeds:
                torch.cuda.reset_peak_memory_stats()
                t0 = time.time()
                img = pipe(prompt_embeds=STASH["embeds"], prompt_embeds_mask=STASH["mask"],
                           image=ref, width=w, height=h, use_kv_cache=True,
                           generator=torch.Generator("cpu").manual_seed(sd),
                           output_type="pil").images[0]
                dt = time.time() - t0
                name = f"{pid}_L{L}_s{sd}.png"
                img.save(os.path.join(args.out, name))
                peak = torch.cuda.max_memory_allocated()/2**30
                print(f"  {pid} L{L} seed={sd} {w}x{h} {dt:5.1f}s VRAM {peak:.2f}GiB -> {name}", flush=True)
                results.append({"id": pid, "layer": L, "seed": sd, "w": w, "h": h,
                                "sec": round(dt,1), "vram": round(peak,2), "file": name})
                del img; gc.collect()
    json.dump(results, open(os.path.join(args.out, "results.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    print("完成", flush=True)


if __name__ == "__main__":
    sys.exit(main())
