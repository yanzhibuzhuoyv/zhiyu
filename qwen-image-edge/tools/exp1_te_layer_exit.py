#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
实验 1：文本编码器「提前退出 / 剪层」可行性测试
================================================================
用户假设：参数太大 → 缩减。部分隐藏状态可以共用同一批权重矩阵。
本脚本不训练、不改权重，只做一件事：
    让 DiT 读取**第 j 层**的 hidden states（而不是第 36 层），看画质悬崖在哪。
这直接量化「剪掉最后 N 层能省多少参数而不毁画质」，是后续所有共享/剪枝的前提。

原理（依据 diffusers 源码 src/diffusers/pipelines/qwenimage21/pipeline_qwenimage21.py）：
  - DiT 吃的是 token 级 hidden_states[-1]（末层、final RMSNorm 之前）+ mask
  - HF 的 output.hidden_states 长度 = 层数+1（含 embedding 输出），故 hidden_states[j] = 第 j 层的输出
  - 把 out.hidden_states 截断成 [:j+1]，管线里的 [-1] 就自动变成第 j 层 —— 零侵入
  - 管线自己会用 forward hook 中和 final RMSNorm（transformers 5.x 的坑），我们不用管

产出：out/ 下 每个 layer 一张图 + 与基线的余弦相似度 + 耗时/显存记录

硬件假设：RTX 4060 Laptop 8GB VRAM + 16GB RAM → 必须 4-bit 量化 + 顺序 offload
          （bf16 的 TE 17.5GB 装不进 15.7GB 内存）
================================================================
"""
import argparse, json, os, time, gc, sys

import torch
from transformers import BitsAndBytesConfig

# 目标：用户机器是 8GB 显存 + 16GB 内存，只能用 4bit
BNB4 = dict(load_in_4bit=True, bnb_4bit_quant_type="nf4", bnb_4bit_compute_dtype=torch.bfloat16)


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def vram():
    if not torch.cuda.is_available():
        return "cpu"
    return f"{torch.cuda.max_memory_allocated()/2**30:.2f} GiB peak / {torch.cuda.memory_reserved()/2**30:.2f} GiB reserved"


def build_pipe(model_dir, quant=True):
    """4-bit 加载管线。diffusers 的 quantization_config 位置随版本变动，做了回退。"""
    from diffusers import QwenImage21Pipeline
    kwargs = {}
    if quant:
        try:
            from diffusers import BitsAndBytesConfig as DiffusersBNB
            kwargs["quantization_config"] = DiffusersBNB(**BNB4)
        except Exception:
            kwargs["quantization_config"] = BitsAndBytesConfig(**BNB4)
    try:
        pipe = QwenImage21Pipeline.from_pretrained(model_dir, **kwargs)
    except TypeError:
        # 老版本 diffusers 不接受 quantization_config，退回 bf16（16GB 内存机器会吃紧）
        log("!! 该 diffusers 版本不支持 quantization_config，退回非量化加载")
        pipe = QwenImage21Pipeline.from_pretrained(model_dir, torch_dtype=torch.bfloat16)
    # 顺序 offload：TE 与 DiT 不同时常驻显存 —— 这正是「串行加载」的实现
    pipe.enable_model_cpu_offload()
    return pipe


def get_embeds(pipe, prompt, layer_idx, capture):
    """截断 hidden_states 到第 layer_idx 层，调用管线自己的编码逻辑，抓取 prompt_embeds。"""
    te = pipe.text_encoder
    orig_forward = te.forward

    def patched(*a, **kw):
        out = orig_forward(*a, **kw)
        if layer_idx is not None:
            hs = out.hidden_states
            # hs[0]=embedding 输出, hs[j]=第 j 层输出；截断后管线取 [-1] 即第 layer_idx 层
            out.hidden_states = hs[: layer_idx + 1]
        return out

    te.forward = patched
    try:
        embeds, mask, img_pad = pipe._get_qwen_prompt_embeds(prompt, None, pipe._execution_device)
    finally:
        te.forward = orig_forward
    capture["embeds"] = embeds.detach().float().cpu()
    capture["mask"] = mask.detach().cpu()
    return embeds, mask


def cosine_vs(a, b, mask):
    """只在有效 token 上算余弦（padding 位置是 0，会污染结果）。"""
    m = mask.bool()
    a = a[m].flatten()
    b = b[m].flatten()
    return torch.nn.functional.cosine_similarity(a, b, dim=0).item()


def make_contact_sheet(out_dir, prompts, layers):
    """同一提示词的不同层横排、不同提示词竖排 → 一张总览对比图，方便肉眼判断画质悬崖。"""
    from PIL import Image, ImageDraw
    ls = sorted(set(layers))
    rows = []
    for p in prompts:
        imgs = []
        for L in ls:
            fp = os.path.join(out_dir, f"L{L:02d}_{p['id']}.png")
            if os.path.exists(fp):
                im = Image.open(fp).convert("RGB")
                im.thumbnail((300, 300))
                imgs.append((L, im))
        if imgs:
            rows.append((p, imgs))
    if not rows:
        return
    cw = max(im.width for _, r in rows for _, im in r)
    ch = max(im.height for _, r in rows for _, im in r) + 20
    sheet = Image.new("RGB", (cw * len(ls), ch * len(rows)), (250, 250, 250))
    d = ImageDraw.Draw(sheet)
    for ri, (p, imgs) in enumerate(rows):
        for ci, (L, im) in enumerate(imgs):
            x, y = ci * cw, ri * ch
            sheet.paste(im, (x + (cw - im.width) // 2, y + 20))
            d.text((x + 4, y + 4), f"{p['id']} L{L}" + ("" if L == 36 else " (剪)"), fill=(0, 0, 0))
    path = os.path.join(out_dir, "contact_sheet.png")
    sheet.save(path)
    print("联络对比表:", path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model-dir", default="Qwen/Qwen-Image-2.1-Turbo",
                    help="本地快照目录或 repo id（HF 不通时用 ModelScope 下载的本地路径）")
    ap.add_argument("--prompts", default="prompts_all.jsonl", help="每行一个 JSON: {\"id\":..,\"tier\":..,\"prompt\":..}")
    ap.add_argument("--tier", default="A", help="只跑哪些 tier（逗号分隔）：A / A,B / all")
    ap.add_argument("--only", default="", help="只跑 id 含这些子串的，逗号分隔（如 A13,A14）")
    ap.add_argument("--layers", default="36,32,28,24,20,16",
                    help="要测的层号；36 = 原始基线。注意 hidden_states 索引 = 层号")
    ap.add_argument("--size", type=int, default=512, help="输出边长（必须 32 的倍数）")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--out", default="out_exp1")
    ap.add_argument("--no-quant", action="store_true", help="禁用 4bit（需要 >30GB 内存）")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    allp = [json.loads(l) for l in open(args.prompts, encoding="utf-8") if l.strip()]
    tiers = {t.strip().upper() for t in args.tier.split(",") if t.strip()}
    if not tiers or "ALL" in tiers:
        prompts = allp
    else:
        prompts = [p for p in allp if str(p.get("tier", "")).upper() in tiers]
    if args.only:
        keys = [k.strip() for k in args.only.split(",") if k.strip()]
        prompts = [p for p in prompts if any(k in p["id"] for k in keys)]
    if not prompts:
        print("!! 没有匹配的提示词，检查 --tier / --only"); return
    layers = [int(x) for x in args.layers.split(",")]
    print(f"提示词 {len(prompts)} 条 × {len(layers)} 层 = {len(prompts)*len(layers)} 张图")
    print(f"   tier 分布: " + ", ".join(f"{t}:{sum(1 for p in prompts if str(p.get('tier','')).upper()==t)}" for t in sorted({str(p.get('tier','')).upper() for p in prompts})))

    log(f"加载管线: {args.model_dir}  4bit={not args.no_quant}")
    pipe = build_pipe(args.model_dir, quant=not args.no_quant)
    log(f"加载完成. VRAM {vram()}")

    results = []
    baseline = {}

    # ---- 1) 先跑基线（第 36 层），拿到参考 embedding ----
    for p in prompts:
        cap = {}
        t0 = time.time()
        emb, mask = get_embeds(pipe, p["prompt"], layer_idx=36, capture=cap)
        baseline[p["id"]] = (cap["embeds"], cap["mask"])
        log(f"基线嵌入 {p['id']}: seq={emb.shape[1]} tok  {time.time()-t0:.1f}s")

    # ---- 2) 逐层：编码 → 余弦 → 出图 ----
    for layer in layers:
        for p in prompts:
            rec = {"layer": layer, "id": p["id"], "prompt": p["prompt"]}
            cap = {}
            t0 = time.time()
            emb, mask = get_embeds(pipe, p["prompt"], layer_idx=(None if layer == 36 else layer), capture=cap)
            rec["encode_s"] = round(time.time() - t0, 2)
            if layer != 36:
                rec["cosine_vs_36"] = round(
                    cosine_vs(cap["embeds"], baseline[p["id"]][0], baseline[p["id"]][1]), 4)

            # 出图（只跑 DiT + VAE，跳过编码器）
            torch.cuda.reset_peak_memory_stats() if torch.cuda.is_available() else None
            emb_gpu = emb.to(pipe._execution_device, dtype=torch.bfloat16)
            mask_gpu = mask.to(pipe._execution_device)
            t1 = time.time()
            image = pipe(
                prompt_embeds=emb_gpu, prompt_embeds_mask=mask_gpu,
                width=args.size, height=args.size,
                generator=torch.Generator("cpu").manual_seed(args.seed),
                output_type="pil",
            ).images[0]
            rec["denoise_s"] = round(time.time() - t1, 2)
            rec["vram"] = vram()
            path = os.path.join(args.out, f"L{layer:02d}_{p['id']}.png")
            image.save(path)
            rec["file"] = path
            log(f"L{layer:02d} {p['id']}: cos={rec.get('cosine_vs_36','—')} "
                f"enc={rec['encode_s']}s gen={rec['denoise_s']}s -> {path}")
            results.append(rec)

    with open(os.path.join(args.out, "results.jsonl"), "w", encoding="utf-8") as f:
        for r in results:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    # ---- 3) 联络对比表 ----
    try:
        make_contact_sheet(args.out, prompts, layers)
    except Exception as e:
        print("联络表生成失败:", e)

    # ---- 4) 剪层的参数收益估算 ----
    print("\n=== 剪层收益（文本编码器主干 36 层 × 193M/层） ===")
    for layer in sorted(set(layers)):
        dropped = 36 - layer
        saved_b = dropped * 0.193
        print(f"用第 {layer:2d} 层输出：丢掉 {dropped:2d} 层 = 省 {saved_b:.2f}B 参数 "
              f"= bf16 {saved_b*2:.2f}GB / int4 {saved_b*0.5:.2f}GB")
    print(f"\n结果写入 {os.path.join(args.out, 'results.jsonl')}")


if __name__ == "__main__":
    main()
