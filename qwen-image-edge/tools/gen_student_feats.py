#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
提取学生模型（Qwen3-1.7B-Base）的多层隐状态，供蒸馏用
关键：喂给学生的文本与教师完全相同（同一套模板），因此 token 逐一对齐
取最后 K 层的隐状态拼接作为特征
"""
import os, sys, json, time, argparse
os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"
import torch

SYS_PROMPT = "Comprehend and analyze the provided prompt."
T2I = (f"<|im_start|>system\n{SYS_PROMPT}<|im_end|>\n"
       f"<|im_start|>user\n{{}}<|im_end|>\n<|im_start|>assistant\n")


def make_batches(rows, tok, template, token_budget=2048, max_bs=32):
    """按 token 预算分桶组 batch，避免长提示词把整批 padding 拖慢"""
    lens = [len(tok.encode(template.format(r["prompt"]), add_special_tokens=False)) for r in rows]
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
    ap.add_argument("--student", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\models\Qwen3-1.7B-Base")
    ap.add_argument("--tokdir", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\models\Qwen-Image-2.1-Turbo\processor")
    ap.add_argument("--corpus", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\distill\corpus.jsonl")
    ap.add_argument("--out", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\distill\student")
    ap.add_argument("--limit", type=int, default=3000)
    ap.add_argument("--skip", type=int, default=0)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--token-budget", type=int, default=2048)
    ap.add_argument("--max-tokens", type=int, default=256)
    ap.add_argument("--layers", default="-1,-4,-7,-10,-13,-16", help="取哪些层（负=从末尾数）")
    ap.add_argument("--drop-idx", type=int, default=14)
    ap.add_argument("--device", default="cuda", choices=["cuda", "cpu"])
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    rows = [json.loads(l) for l in open(args.corpus, encoding="utf-8") if l.strip()]
    rows = rows[args.skip: args.skip + args.limit]
    print(f"待处理 {len(rows)} 条", flush=True)

    from transformers import AutoTokenizer, AutoModelForCausalLM
    tok = AutoTokenizer.from_pretrained(args.tokdir)
    print(f"加载学生 {args.student}（bf16, {args.device}）...", flush=True)
    t0 = time.time()
    st = AutoModelForCausalLM.from_pretrained(args.student, torch_dtype=torch.bfloat16,
                                              device_map={"": 0 if args.device == "cuda" else "cpu"},
                                              low_cpu_mem_usage=True)
    st.eval()
    nl = st.config.num_hidden_layers
    print(f"  完成 {time.time()-t0:.0f}s | 层数 {nl} | hidden {st.config.hidden_size}", flush=True)
    li = [int(x) if int(x) >= 0 else nl + 1 + int(x) for x in args.layers.split(",")]
    print(f"  取层索引 {li}", flush=True)

    batches, lens_all = make_batches(rows, tok, T2I, args.token_budget, args.batch)
    print(f"  按 token 预算分桶: {len(batches)} 个 batch（预算 {args.token_budget}，"
          f"长度中位 {sorted(lens_all)[len(lens_all)//2]}，最长 {max(lens_all)}）", flush=True)

    t1 = time.time(); n = 0
    for bi, idxs in enumerate(batches):
        chunk = [rows[i] for i in idxs]
        texts = [T2I.format(r["prompt"]) for r in chunk]
        inp = tok(text=texts, padding=True, padding_side="left", truncation=True,
                  max_length=args.max_tokens, return_tensors="pt")
        inp = {k: v.to(st.device) for k, v in inp.items()}
        with torch.no_grad():
            out = st(**inp, output_hidden_states=True)
        hs = out.hidden_states                       # tuple(len = nl+1)
        feats = torch.cat([hs[i] for i in li], dim=-1).float().cpu()   # [B, T, K*H]
        del out, hs
        am = inp["attention_mask"].cpu()
        embs, masks, ids = [], [], []
        for i, r in enumerate(chunk):
            v = feats[i][am[i].bool()][args.drop_idx:]
            v = v[: args.max_tokens]
            embs.append(v); masks.append(torch.ones(v.size(0), dtype=torch.long)); ids.append(r["id"])
        mx = max(e.size(0) for e in embs)
        emb = torch.stack([torch.cat([e, e.new_zeros(mx-e.size(0), e.size(1))]) for e in embs]).to(torch.bfloat16)
        msk = torch.stack([torch.cat([m, torch.zeros(mx-m.size(0), dtype=torch.long)]) for m in masks])
        fn = f"s_{bi:05d}.pt"
        torch.save({"ids": ids, "feats": emb, "mask": msk, "layers": li}, os.path.join(args.out, fn))
        n += len(chunk)
        if bi % 10 == 0:
            el = time.time()-t1
            print(f"  {n}/{len(rows)}  {el:.0f}s  平均 {el/max(n,1)*1000:.0f} ms/条  "
                  f"批内长 {emb.shape[1]}  批大小 {len(chunk)}", flush=True)
    json.dump({"n": n, "layers": li, "drop_idx": args.drop_idx, "feat_dim": int(emb.shape[-1]),
               "seq_max": int(emb.shape[1])},
              open(os.path.join(args.out, "meta.json"), "w", encoding="utf-8"), indent=1)
    el = time.time()-t1
    print(f"\n完成 {n} 条，{el/60:.1f} 分钟，{el/n*1000:.0f} ms/条 -> {args.out}", flush=True)


if __name__ == "__main__":
    sys.exit(main())
