#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
阶段二：从预先算好的 prompt_embeds 出图（不加载文本编码器）
  —— 与阶段一分开进程，因为本机 15.7GiB 物理内存放不下两者

管线手工构造：text_encoder=None + 一个分词器壳子（__init__ 里要用 processor.apply_chat_template）
DiT 用 4-bit 量化（diffusers.BitsAndBytesConfig）
"""
import os, sys, json, time, argparse, gc
os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"
import torch

MODEL = r"C:\Users\32202\Desktop\99999\qwen-image-edge\models\Qwen-Image-2.1-Turbo"
EMBEDS = r"C:\Users\32202\Desktop\99999\qwen-image-edge\embeds"


class TokShim:
    """管线 __init__ 会调 processor.apply_chat_template 和 processor.tokenizer.encode。
    processor 返回嵌套列表 [[ids...]]，而 tokenizer 返回扁平 [ids...] —— 这里统一成嵌套。"""
    def __init__(self, tok):
        self.tokenizer = tok
    def apply_chat_template(self, *a, **k):
        out = self.tokenizer.apply_chat_template(*a, **k)
        if out and not isinstance(out[0], (list, tuple)):
            return [out]
        return out


def build_pipe(model_dir, quant=True):
    from diffusers import (QwenImage21Pipeline, QwenImage21Transformer2DModel,
                           AutoencoderKLQwenImage21, FlowMatchEulerDiscreteScheduler)
    from diffusers import BitsAndBytesConfig as DBnB

    print("加载 VAE / scheduler ...", flush=True)
    vae = AutoencoderKLQwenImage21.from_pretrained(os.path.join(model_dir, "vae"), torch_dtype=torch.bfloat16)
    sched = FlowMatchEulerDiscreteScheduler.from_pretrained(os.path.join(model_dir, "scheduler"))

    kw = {}
    if quant:
        kw["quantization_config"] = DBnB(load_in_4bit=True, bnb_4bit_quant_type="nf4",
                                         bnb_4bit_compute_dtype=torch.bfloat16)
    print("加载 DiT（7.11B，4-bit）...", flush=True)
    t0 = time.time()
    try:
        tr = QwenImage21Transformer2DModel.from_pretrained(os.path.join(model_dir, "transformer"), **kw)
        print(f"  DiT 加载完成 {time.time()-t0:.0f}s", flush=True)
    except Exception as e:
        print(f"  4-bit 失败（{type(e).__name__}: {str(e)[:200]}），退回 bf16", flush=True)
        tr = QwenImage21Transformer2DModel.from_pretrained(os.path.join(model_dir, "transformer"),
                                                           torch_dtype=torch.bfloat16)

    from transformers import AutoTokenizer
    tok = AutoTokenizer.from_pretrained(os.path.join(model_dir, "processor"))
    mi = json.load(open(os.path.join(model_dir, "model_index.json"), encoding="utf-8"))
    ss = mi.get("sample_sigmas")

    pipe = QwenImage21Pipeline(scheduler=sched, vae=vae, text_encoder=None,
                               processor=TokShim(tok), transformer=tr, sample_sigmas=ss)
    print(f"  sample_sigmas = {ss}", flush=True)
    return pipe


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=MODEL)
    ap.add_argument("--embeds", default=EMBEDS)
    ap.add_argument("--ids", default="", help="只跑这些 id，逗号分隔；空=全部")
    ap.add_argument("--layers", default="36,32,28,24,20")
    ap.add_argument("--size", type=int, default=512)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--out", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\exp1_out")
    ap.add_argument("--no-quant", action="store_true")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    layers = [int(x) for x in args.layers.split(",")]

    idx = json.load(open(os.path.join(args.embeds, "index.json"), encoding="utf-8"))
    ids = [i.strip() for i in args.ids.split(",") if i.strip()] or list(idx.keys())
    print(f"将出图 {len(ids)} 条提示词 × {len(layers)} 层 = {len(ids)*len(layers)} 张", flush=True)

    pipe = build_pipe(args.model, quant=not args.no_quant)
    pipe.enable_model_cpu_offload()
    if torch.cuda.is_available():
        print(f"  VRAM 已用 {torch.cuda.memory_allocated()/2**30:.2f} GiB", flush=True)

    results = []
    for pid in ids:
        rec = torch.load(os.path.join(args.embeds, idx[pid]["file"]), weights_only=False)
        for L in layers:
            if L not in rec["layers"]:
                continue
            emb = rec["layers"][L]["embeds"].to(pipe._execution_device)
            msk = rec["layers"][L]["mask"].to(pipe._execution_device)
            torch.cuda.reset_peak_memory_stats() if torch.cuda.is_available() else None
            t0 = time.time()
            img = pipe(prompt_embeds=emb, prompt_embeds_mask=msk,
                       width=args.size, height=args.size, use_kv_cache=True,
                       generator=torch.Generator("cpu").manual_seed(args.seed),
                       output_type="pil").images[0]
            dt = time.time() - t0
            p = os.path.join(args.out, f"L{L:02d}_{pid}.png")
            img.save(p)
            peak = torch.cuda.max_memory_allocated()/2**30 if torch.cuda.is_available() else 0
            print(f"  {pid} L{L:2d}  seq={emb.shape[1]:4d}  {dt:6.1f}s  VRAM峰值 {peak:.2f} GiB -> {os.path.basename(p)}", flush=True)
            results.append({"id": pid, "layer": L, "seq": int(emb.shape[1]), "sec": round(dt,1),
                            "vram_peak_gib": round(peak,2), "file": os.path.basename(p)})
            del emb, msk, img
            gc.collect()

    with open(os.path.join(args.out, "results.jsonl"), "w", encoding="utf-8") as f:
        for r in results:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    # 联络表
    try:
        from PIL import Image, ImageDraw
        rows = []
        for pid in ids:
            ims = [(L, Image.open(os.path.join(args.out, f"L{L:02d}_{pid}.png")).convert("RGB"))
                   for L in layers if os.path.exists(os.path.join(args.out, f"L{L:02d}_{pid}.png"))]
            if ims:
                rows.append((pid, ims))
        if rows:
            cw = max(i.width for _, r in rows for _, i in r)
            ch = max(i.height for _, r in rows for _, i in r) + 20
            sheet = Image.new("RGB", (cw*len(layers), ch*len(rows)), (250, 250, 250))
            d = ImageDraw.Draw(sheet)
            for ri, (pid, ims) in enumerate(rows):
                for ci, (L, im) in enumerate(ims):
                    x, y = ci*cw, ri*ch
                    sheet.paste(im, (x + (cw-im.width)//2, y+20))
                    d.text((x+4, y+4), f"{pid}  L{L}" + ("" if L == 36 else "  [剪层]"), fill=(0, 0, 0))
            sp = os.path.join(args.out, "contact_sheet.png")
            sheet.save(sp)
            print(f"\n联络对比表: {sp}", flush=True)
    except Exception as e:
        print("联络表失败:", e, flush=True)
    print("完成", flush=True)


if __name__ == "__main__":
    sys.exit(main())
