#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
教师信号生成：用打包后的编码器（GPU，0.4s/条）对语料批量算隐状态，存盘供蒸馏用
存的是「管线下游真正消费的形式」：按 attention_mask 抽有效 token、丢掉开头 _drop_idx 个 system token、
右侧补零对齐后的 [1, seq, 4096] —— 学生要匹配的就是这个
"""
import os, sys, json, time, argparse
os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"
import torch

SYS_PROMPT = "Comprehend and analyze the provided prompt."
T2I = (f"<|im_start|>system\n{SYS_PROMPT}<|im_end|>\n"
       f"<|im_start|>user\n{{}}<|im_end|>\n<|im_start|>assistant\n")


def ex_masked(hs, mask):
    bm = mask.bool(); lens = bm.sum(dim=1); sel = hs[bm]
    return torch.split(sel, lens.tolist(), dim=0)


def make_batches(rows, tok, template, token_budget=2048, max_bs=32):
    """
    按 token 预算组 batch：先按长度排序，再贪心累加，
    使 (batch 内最大长度 × 条数) 不超过预算 —— 避免长提示词把整批 padding 拖慢
    """
    lens = []
    for r in rows:
        lens.append(len(tok.encode(template.format(r["prompt"]), add_special_tokens=False)))
    order = sorted(range(len(rows)), key=lambda i: lens[i])
    batches, cur, cur_max = [], [], 0
    for i in order:
        L = lens[i]
        newmax = max(cur_max, L)
        if cur and (len(cur) + 1) * newmax > token_budget:
            batches.append(cur); cur, cur_max = [], 0
            newmax = L
        cur.append(i); cur_max = newmax
        if len(cur) >= max_bs:
            batches.append(cur); cur, cur_max = [], 0
    if cur:
        batches.append(cur)
    return batches, lens


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--packed", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\models\te_packed\L36")
    ap.add_argument("--proc", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\models\Qwen-Image-2.1-Turbo\processor")
    ap.add_argument("--corpus", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\distill\corpus.jsonl")
    ap.add_argument("--out", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\distill\teacher")
    ap.add_argument("--limit", type=int, default=3000)
    ap.add_argument("--skip", type=int, default=0, help="跳过前 N 条（用于切分 train/val）")
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--token-budget", type=int, default=2048, help="每个 batch 的 条数×最大长度 上限")
    ap.add_argument("--max-tokens", type=int, default=256, help="超过则截断")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    rows = [json.loads(l) for l in open(args.corpus, encoding="utf-8") if l.strip()]
    rows = rows[args.skip: args.skip + args.limit]
    print(f"待处理 {len(rows)} 条", flush=True)

    from transformers import Qwen3VLForConditionalGeneration, BitsAndBytesConfig, AutoTokenizer
    tok = AutoTokenizer.from_pretrained(args.proc)
    print("加载教师（打包 L36, 4-bit, GPU）...", flush=True)
    t0 = time.time()
    te = Qwen3VLForConditionalGeneration.from_pretrained(
        args.packed,
        quantization_config=BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4",
                                               bnb_4bit_compute_dtype=torch.bfloat16,
                                               bnb_4bit_use_double_quant=True),
        device_map={"": 0}, low_cpu_mem_usage=True)
    te.eval()
    lm = getattr(te.model, "language_model", te.model)
    sys_tk = tok.apply_chat_template([{"role":"system","content":[{"type":"text","text":SYS_PROMPT}]}],
                                     tokenize=True, return_dict=False)
    drop = len(sys_tk[0]) if sys_tk and isinstance(sys_tk[0], (list, tuple)) else len(sys_tk)
    print(f"  完成 {time.time()-t0:.0f}s  _drop_idx={drop}", flush=True)

    meta = {"drop_idx": drop, "layers": [36], "using": "te_packed/L36", "n": 0,
            "max_tokens": args.max_tokens, "files": []}
    batches, lens_all = make_batches(rows, tok, T2I, args.token_budget, args.batch)
    print(f"  按 token 预算分桶: {len(batches)} 个 batch（预算 {args.token_budget}，"
          f"长度中位 {sorted(lens_all)[len(lens_all)//2]}，最长 {max(lens_all)}）", flush=True)

    t1 = time.time()
    n_done = 0
    for bi, idxs in enumerate(batches):
        chunk = [rows[i] for i in idxs]
        texts = [T2I.format(r["prompt"]) for r in chunk]
        inp = tok(text=[t for t in texts], padding=True, padding_side="left",
                  truncation=True, max_length=args.max_tokens, return_tensors="pt")
        inp = {k: v.to(te.device) for k, v in inp.items()}
        h = lm.norm.register_forward_hook(lambda m, a, o: a[0])
        try:
            with torch.no_grad():
                out = te(**inp, output_hidden_states=True)
        finally:
            h.remove()
        hs = out.hidden_states[-1].float().cpu()
        del out
        am = inp["attention_mask"].cpu()
        embs, masks, ids = [], [], []
        for i, r in enumerate(chunk):
            sp = [e[drop:] for e in ex_masked(hs[i:i+1], am[i:i+1])][0]
            sp = sp[: args.max_tokens]
            masks.append(torch.ones(sp.size(0), dtype=torch.long))
            embs.append(sp)
            ids.append(r["id"])
        mx = max(e.size(0) for e in embs)
        emb = torch.stack([torch.cat([e, e.new_zeros(mx - e.size(0), e.size(1))]) for e in embs])
        msk = torch.stack([torch.cat([m, torch.zeros(mx - m.size(0), dtype=torch.long)]) for m in masks])
        fn = f"t_{bi:05d}.pt"
        torch.save({"ids": ids, "prompts": [r["prompt"] for r in chunk],
                    "embeds": emb.to(torch.bfloat16), "mask": msk, "layer": 36},
                   os.path.join(args.out, fn))
        meta["files"].append(fn); meta["n"] += len(chunk); n_done += len(chunk)
        if bi % 10 == 0:
            el = time.time() - t1
            print(f"  {n_done}/{len(rows)}  {el:.0f}s  平均 {el/max(n_done,1)*1000:.0f} ms/条  "
                  f"batch内长 {emb.shape[1]}  批大小 {len(chunk)}", flush=True)
    json.dump(meta, open(os.path.join(args.out, "meta.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    el = time.time() - t1
    print(f"\n完成 {n_done} 条，用时 {el/60:.1f} 分钟，{el/n_done*1000:.0f} ms/条 -> {args.out}", flush=True)


if __name__ == "__main__":
    sys.exit(main())
