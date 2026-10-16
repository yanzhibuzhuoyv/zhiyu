#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
阶段一：文本编码（把 8.77B 编码器放 CPU 上 4-bit 跑，产出 prompt_embeds 存盘）
　—— 与阶段二分开进程，因为两者不能同时驻留内存（本机物理内存仅 15.7GiB）

按 diffusers QwenImage21Pipeline._get_qwen_prompt_embeds 的逻辑逐行复刻：
  模板 = <|im_start|>system\\n{sys}<|im_end|>\\n<|im_start|>user\\n{p}<|im_end|>\\n<|im_start|>assistant\\n
  取 hidden_states[j]（第 j 层输出），按 attention_mask 抽有效 token，丢掉开头 _drop_idx 个 system token，
  右侧补零对齐，同时产出 encoder_attention_mask
"""
import os, sys, json, time, argparse
os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"
import torch

SYS_PROMPT = "Comprehend and analyze the provided prompt."
T2I_TEMPLATE = (
    f"<|im_start|>system\n{SYS_PROMPT}<|im_end|>\n"
    f"<|im_start|>user\n{{}}<|im_end|>\n"
    f"<|im_start|>assistant\n"
)


def extract_masked_hidden(hs, mask):
    bm = mask.bool()
    lens = bm.sum(dim=1)
    sel = hs[bm]
    return torch.split(sel, lens.tolist(), dim=0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\models\Qwen-Image-2.1-Turbo")
    ap.add_argument("--prompts", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\tools\prompts_all.jsonl")
    ap.add_argument("--tier", default="A")
    ap.add_argument("--only", default="")
    ap.add_argument("--limit", type=int, default=0, help="只处理前 N 条（0=全部）")
    ap.add_argument("--layers", default="36,32,28,24,20", help="要保存哪些层的隐状态")
    ap.add_argument("--out", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\embeds")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    te_dir = os.path.join(args.model, "text_encoder")
    proc_dir = os.path.join(args.model, "processor")

    # 选提示词
    allp = [json.loads(l) for l in open(args.prompts, encoding="utf-8") if l.strip()]
    tiers = {t.strip().upper() for t in args.tier.split(",") if t.strip()}
    ps = allp if (not tiers or "ALL" in tiers) else [p for p in allp if str(p.get("tier", "")).upper() in tiers]
    if args.only:
        ks = [k.strip() for k in args.only.split(",") if k.strip()]
        ps = [p for p in ps if any(k in p["id"] for k in ks)]
    if args.limit:
        ps = ps[: args.limit]
    layers = [int(x) for x in args.layers.split(",")]
    print(f"提示词 {len(ps)} 条 × 层 {layers}", flush=True)

    # 分词器（很小）。注意：不要用 AutoProcessor —— 它会连带加载 Qwen3VLVideoProcessor 而要求 torchvision，
    # 而本流程只用文本路径，AutoTokenizer 完全够用，且 tokenize 结果与 processor 的文本路径一致。
    from transformers import AutoTokenizer
    processor = AutoTokenizer.from_pretrained(proc_dir)
    sys_tokens = processor.apply_chat_template(
        [{"role": "system", "content": [{"type": "text", "text": SYS_PROMPT}]}],
        tokenize=True, return_dict=False)
    # processor 返回 [[...]]，tokenizer 返回 [...]，两种都兼容
    if sys_tokens and isinstance(sys_tokens[0], (list, tuple)):
        drop_idx = len(sys_tokens[0])
    else:
        drop_idx = len(sys_tokens)
    print(f"_drop_idx = {drop_idx}", flush=True)

    # 文本编码器：4-bit 全放 CPU（GPU 路径在本机 8GB 显存下会触发 bnb 卸载限制）
    print("加载文本编码器（4-bit, CPU）...", flush=True)
    from transformers import Qwen3VLForConditionalGeneration, BitsAndBytesConfig
    t0 = time.time()
    te = Qwen3VLForConditionalGeneration.from_pretrained(
        te_dir,
        quantization_config=BitsAndBytesConfig(
            load_in_4bit=True, bnb_4bit_quant_type="nf4",
            bnb_4bit_compute_dtype=torch.bfloat16, bnb_4bit_use_double_quant=True),
        device_map={"": "cpu"}, low_cpu_mem_usage=True,
    )
    te.eval()
    print(f"  加载完成 {time.time()-t0:.0f}s", flush=True)

    text_model = getattr(te.model, "language_model", te.model)

    index = {}
    for n, p in enumerate(ps, 1):
        pid, prompt = p["id"], p["prompt"]
        t1 = time.time()
        text = T2I_TEMPLATE.format(prompt)
        inp = processor(text=[text], padding=True, padding_side="left", return_tensors="pt")
        handle = text_model.norm.register_forward_hook(lambda m, a, o: a[0])  # 中和最终 RMSNorm
        try:
            with torch.no_grad():
                out = te(**inp, output_hidden_states=True)
        finally:
            handle.remove()
        hs_all = out.hidden_states          # tuple，长度 = 层数+1
        rec = {"id": pid, "prompt": prompt, "ntok": int(inp.input_ids.shape[1]), "layers": {}}
        am = inp.attention_mask
        for j in layers:
            if j >= len(hs_all):
                continue
            split = list(extract_masked_hidden(hs_all[j], am))
            split = [e[drop_idx:] for e in split]
            mx = max(e.size(0) for e in split)
            emb = torch.stack([torch.cat([u, u.new_zeros(mx - u.size(0), u.size(1))]) for u in split])
            msk = torch.stack([torch.cat([torch.ones(u.size(0), dtype=torch.long),
                                          torch.zeros(mx - u.size(0), dtype=torch.long)]) for u in split])
            rec["layers"][j] = {"embeds": emb.to(torch.bfloat16), "mask": msk}
        torch.save(rec, os.path.join(args.out, f"emb_{pid}.pt"))
        index[pid] = {"file": f"emb_{pid}.pt", "ntok": rec["ntok"],
                      "layers": sorted(rec["layers"].keys()), "prompt": prompt}
        print(f"  [{n}/{len(ps)}] {pid}  token={rec['ntok']}  {time.time()-t1:.1f}s", flush=True)
        del out, hs_all, rec

    with open(os.path.join(args.out, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=1)
    print(f"\n完成，嵌入存于 {args.out}（{len(index)} 条）", flush=True)


if __name__ == "__main__":
    sys.exit(main())
