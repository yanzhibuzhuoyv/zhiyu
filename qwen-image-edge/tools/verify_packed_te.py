#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
验证打包后的文本编码器：
  用打包模型重新编码，与「原模型对应层」的隐状态逐元素比对
  对 L36 比 layer 35(索引) 对 L36 嵌入；对 L24 比 layer 23(索引) 对 L24 嵌入
"""
import os, sys, json, time, argparse
os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"
import torch

SYS_PROMPT = "Comprehend and analyze the provided prompt."
T2I = (f"<|im_start|>system\n{SYS_PROMPT}<|im_end|>\n"
       f"<|im_start|>user\n{{}}<|im_end|>\n<|im_start|>assistant\n")


def extract_masked(hs, mask):
    bm = mask.bool(); lens = bm.sum(dim=1); sel = hs[bm]
    return torch.split(sel, lens.tolist(), dim=0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--packed", required=True, help="打包后的编码器目录")
    ap.add_argument("--orig-proc", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\models\Qwen-Image-2.1-Turbo\processor")
    ap.add_argument("--embeds", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\embeds")
    ap.add_argument("--ids", default="A01,A05,A15")
    ap.add_argument("--expect-layer", type=int, required=True, help="打包模型最后一层对应的原始层号(1-based)")
    args = ap.parse_args()

    from transformers import AutoTokenizer
    tok = AutoTokenizer.from_pretrained(args.orig_proc)
    idx = json.load(open(os.path.join(args.embeds, "index.json"), encoding="utf-8"))

    print(f"加载打包模型 {args.packed}（4-bit, CPU）...", flush=True)
    from transformers import Qwen3VLForConditionalGeneration, BitsAndBytesConfig
    t0 = time.time()
    te = Qwen3VLForConditionalGeneration.from_pretrained(
        args.packed,
        quantization_config=BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4",
                                               bnb_4bit_compute_dtype=torch.bfloat16,
                                               bnb_4bit_use_double_quant=True),
        device_map={"": "cpu"}, low_cpu_mem_usage=True)
    te.eval()
    nl = te.config.text_config.num_hidden_layers
    print(f"  完成 {time.time()-t0:.0f}s | 模型自报层数 = {nl}", flush=True)
    lm = getattr(te.model, "language_model", te.model)
    sys_tk = tok.apply_chat_template([{"role":"system","content":[{"type":"text","text":SYS_PROMPT}]}],
                                     tokenize=True, return_dict=False)
    drop_idx = len(sys_tk[0]) if sys_tk and isinstance(sys_tk[0], (list, tuple)) else len(sys_tk)

    print(f"\n{'id':<8}{'层数':>5}{'token':>7}{'余弦':>12}{'最大绝对差':>14}{'平均绝对差':>14}", flush=True)
    ok_all = True
    for pid in [x.strip() for x in args.ids.split(",") if x.strip()]:
        ref = torch.load(os.path.join(args.embeds, idx[pid]["file"]), weights_only=False)
        if args.expect_layer not in ref["layers"]:
            print(f"  {pid}: 参考里没有第 {args.expect_layer} 层，跳过"); continue
        text = T2I.format(ref["prompt"])
        inp = tok(text=[text], padding=True, padding_side="left", return_tensors="pt")
        h = lm.norm.register_forward_hook(lambda m, a, o: a[0])
        try:
            with torch.no_grad():
                out = te(**inp, output_hidden_states=True)
        finally:
            h.remove()
        hs = out.hidden_states[-1]
        sp = [e[drop_idx:] for e in extract_masked(hs, inp.attention_mask)]
        mx = max(e.size(0) for e in sp)
        got = torch.stack([torch.cat([u, u.new_zeros(mx-u.size(0), u.size(1))]) for u in sp]).float()

        exp = ref["layers"][args.expect_layer]["embeds"].float()
        m = ref["layers"][args.expect_layer]["mask"].bool()
        d = (got[m] - exp[m]).abs()
        cos = torch.nn.functional.cosine_similarity(got[m].flatten(), exp[m].flatten(), dim=0).item()
        print(f"{pid:<8}{nl:>5}{got.shape[1]:>7}{cos:>12.6f}{d.max().item():>14.6f}{d.mean().item():>14.6f}", flush=True)
        if cos < 0.999:
            ok_all = False
    print(f"\n{'✅ 通过：打包模型与原模型对应层一致' if ok_all else '❌ 不一致，需要排查'}", flush=True)
    return 0 if ok_all else 1


if __name__ == "__main__":
    sys.exit(main())
