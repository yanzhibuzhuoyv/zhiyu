#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把「学生编码器 + 适配器」的输出写成管线可直接消费的 embeds 格式
（与 tools/encode_prompts.py 的产物同构），这样可以直接复用 gen_from_embeds.py 出图对比
"""
import os, sys, json, glob, time, argparse
os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"
import torch
import torch.nn as nn

SYS_PROMPT = "Comprehend and analyze the provided prompt."
T2I = (f"<|im_start|>system\n{SYS_PROMPT}<|im_end|>\n"
       f"<|im_start|>user\n{{}}<|im_end|>\n<|im_start|>assistant\n")


class Adapter(nn.Module):
    def __init__(self, in_dim, out_dim=4096, hidden=4096):
        super().__init__()
        self.norm = nn.LayerNorm(in_dim)
        self.net = nn.Sequential(nn.Linear(in_dim, hidden), nn.GELU(), nn.Linear(hidden, out_dim))

    def forward(self, x):
        return self.net(self.norm(x))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--student", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\models\Qwen3-1.7B-Base")
    ap.add_argument("--tokdir", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\models\Qwen-Image-2.1-Turbo\processor")
    ap.add_argument("--adapter", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\distill\adapter\adapter_best.pt")
    ap.add_argument("--prompts", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\tools\prompts_all.jsonl")
    ap.add_argument("--tier", default="A")
    ap.add_argument("--layers-tag", type=int, default=36, help="写进 embeds 的层号标签（让管线以为这是第36层）")
    ap.add_argument("--student-layers", default="-1,-4,-7,-10,-13,-16")
    ap.add_argument("--drop-idx", type=int, default=14)
    ap.add_argument("--out", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\distill\embeds_student")
    ap.add_argument("--device", default="cuda", choices=["cuda", "cpu"])
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    dev = "cuda" if (args.device == "cuda" and torch.cuda.is_available()) else "cpu"

    ck = torch.load(args.adapter, weights_only=False)
    model = Adapter(ck["in_dim"]).to(dev)
    model.load_state_dict(ck["state_dict"]); model.eval()
    print(f"适配器载入 (val_cos {ck.get('val_cos')})", flush=True)

    from transformers import AutoTokenizer, AutoModelForCausalLM
    tok = AutoTokenizer.from_pretrained(args.tokdir)
    st = AutoModelForCausalLM.from_pretrained(args.student, torch_dtype=torch.bfloat16,
                                              device_map={"": 0 if dev == "cuda" else "cpu"},
                                              low_cpu_mem_usage=True)
    st.eval()
    nl = st.config.num_hidden_layers
    li = [int(x) if int(x) >= 0 else nl + 1 + int(x) for x in args.student_layers.split(",")]

    allp = [json.loads(l) for l in open(args.prompts, encoding="utf-8") if l.strip()]
    tiers = {t.strip().upper() for t in args.tier.split(",") if t.strip()}
    ps = allp if (not tiers or "ALL" in tiers) else [p for p in allp if str(p.get("tier", "")).upper() in tiers]
    print(f"提示词 {len(ps)} 条", flush=True)

    index = {}
    for p in ps:
        text = T2I.format(p["prompt"])
        enc = tok(text=[text], padding=True, padding_side="left", return_tensors="pt")
        ntok = int(enc["input_ids"].shape[1])
        inp = {k: v.to(dev) for k, v in enc.items()}
        with torch.no_grad():
            out = st(**inp, output_hidden_states=True)
            feats = torch.cat([out.hidden_states[i] for i in li], dim=-1).float()
            emb = model(feats.to(next(model.parameters()).device) if dev == "cpu" else feats)
        am = enc["attention_mask"][0].bool()
        v = emb[0].cpu()[am][args.drop_idx:]
        rec = {"id": p["id"], "prompt": p["prompt"], "ntok": ntok,
               "layers": {args.layers_tag: {"embeds": v.unsqueeze(0).to(torch.bfloat16),
                                            "mask": torch.ones(1, v.size(0), dtype=torch.long)}}}
        torch.save(rec, os.path.join(args.out, f"emb_{p['id']}.pt"))
        index[p["id"]] = {"file": f"emb_{p['id']}.pt", "ntok": ntok,
                          "layers": [args.layers_tag], "prompt": p["prompt"]}
        print(f"  {p['id']} token={ntok}", flush=True)
    json.dump(index, open(os.path.join(args.out, "index.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    print(f"完成 -> {args.out}（{len(index)} 条，层标签 {args.layers_tag}）", flush=True)


if __name__ == "__main__":
    sys.exit(main())
