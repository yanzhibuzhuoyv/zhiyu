#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
两个打包版本的端到端验证：
  用「打包后的 L36 / L24 编码器」独立加载 -> 编码 -> 出图
  再与「原模型算出的既有结果」做逐像素比对
这是交付物本身的验收测试（不是隐状态比对）
"""
import os, sys, json, time, argparse
os.environ.setdefault("HF_HUB_OFFLINE", "1")
import torch

ROOT = r"C:\Users\32202\Desktop\99999\qwen-image-edge"
SYS_PROMPT = "Comprehend and analyze the provided prompt."
T2I = (f"<|im_start|>system\n{SYS_PROMPT}<|im_end|>\n"
       f"<|im_start|>user\n{{}}<|im_end|>\n<|im_start|>assistant\n")


def ex_masked(hs, mask):
    bm = mask.bool(); lens = bm.sum(dim=1); sel = hs[bm]
    return torch.split(sel, lens.tolist(), dim=0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--packed", required=True)
    ap.add_argument("--layer-tag", type=int, required=True, help="写进 embeds 的层号")
    ap.add_argument("--ids", default="A01,A05,A09,A15,L03")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    from transformers import AutoTokenizer, Qwen3VLForConditionalGeneration, BitsAndBytesConfig
    tok = AutoTokenizer.from_pretrained(os.path.join(ROOT, "models", "Qwen-Image-2.1-Turbo", "processor"))
    print(f"加载打包编码器 {os.path.basename(args.packed)}（4-bit, GPU）...", flush=True)
    t0 = time.time()
    te = Qwen3VLForConditionalGeneration.from_pretrained(
        args.packed,
        quantization_config=BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4",
                                               bnb_4bit_compute_dtype=torch.bfloat16,
                                               bnb_4bit_use_double_quant=True),
        device_map={"": 0}, low_cpu_mem_usage=True).eval()
    print(f"  完成 {time.time()-t0:.0f}s  层数 {te.config.text_config.num_hidden_layers}  "
          f"VRAM {torch.cuda.memory_allocated()/2**30:.2f} GiB", flush=True)
    lm = getattr(te.model, "language_model", te.model)
    sys_tk = tok.apply_chat_template([{"role": "system", "content": [{"type": "text", "text": SYS_PROMPT}]}],
                                     tokenize=True, return_dict=False)
    drop = len(sys_tk[0]) if sys_tk and isinstance(sys_tk[0], (list, tuple)) else len(sys_tk)

    allp = {}
    for l in open(os.path.join(ROOT, "tools", "prompts_all.jsonl"), encoding="utf-8"):
        if l.strip():
            d = json.loads(l); allp[d["id"]] = d
    ids = [x.strip() for x in args.ids.split(",") if x.strip()]

    index = {}
    for pid in ids:
        text = T2I.format(allp[pid]["prompt"])
        inp = tok(text=[text], padding=True, padding_side="left", return_tensors="pt")
        ntok = int(inp["input_ids"].shape[1])
        inp = {k: v.to(te.device) for k, v in inp.items()}
        h = lm.norm.register_forward_hook(lambda m, a, o: a[0])
        try:
            with torch.no_grad():
                out = te(**inp, output_hidden_states=True)
        finally:
            h.remove()
        hs = out.hidden_states[-1]
        sp = [e[drop:] for e in ex_masked(hs, inp["attention_mask"])][0]
        emb = sp.unsqueeze(0).to(torch.bfloat16).cpu()
        rec = {"id": pid, "prompt": allp[pid]["prompt"], "ntok": ntok,
               "layers": {args.layer_tag: {"embeds": emb,
                                           "mask": torch.ones(1, emb.size(1), dtype=torch.long)}}}
        torch.save(rec, os.path.join(args.out, f"emb_{pid}.pt"))
        index[pid] = {"file": f"emb_{pid}.pt", "ntok": rec["ntok"],
                      "layers": [args.layer_tag], "prompt": allp[pid]["prompt"]}
        print(f"  {pid}: token={rec['ntok']}", flush=True)
    json.dump(index, open(os.path.join(args.out, "index.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    print(f"完成 -> {args.out}", flush=True)


if __name__ == "__main__":
    sys.exit(main())
