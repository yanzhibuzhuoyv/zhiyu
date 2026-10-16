#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
编辑路径编码：把参考图喂进 Qwen3-VL 的视觉塔，产出带 image_pad_mask 的 prompt_embeds
按 diffusers QwenImage21Pipeline._get_qwen_prompt_embeds 的 ti2i 分支逐行复刻
"""
import os, sys, json, time, argparse
os.environ.setdefault("HF_HUB_OFFLINE", "1")
os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"
import torch
from PIL import Image

SYS_PROMPT = "Comprehend and analyze the provided prompt."
T2I = (f"<|im_start|>system\n{SYS_PROMPT}<|im_end|>\n"
       f"<|im_start|>user\n{{}}<|im_end|>\n<|im_start|>assistant\n")
T_I2I = (f"<|im_start|>system\n{SYS_PROMPT}<|im_end|>\n"
         f"<|im_start|>user\n<image1><|vision_start|><|image_pad|><|vision_end|>{{}}<|im_end|>\n"
         f"<|im_start|>assistant\n")


def extract_masked(hs, mask):
    bm = mask.bool(); lens = bm.sum(dim=1); sel = hs[bm]
    return torch.split(sel, lens.tolist(), dim=0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\models\Qwen-Image-2.1-Turbo")
    ap.add_argument("--ref", required=True, help="参考图")
    ap.add_argument("--tasks", required=True, help="JSON 文件：[{id, prompt}]")
    ap.add_argument("--layers", default="36,28,24")
    ap.add_argument("--output-resolution", type=int, default=1024, help="须与管线 output_resolution 一致")
    ap.add_argument("--out", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\edit_embeds")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    layers = [int(x) for x in args.layers.split(",")]

    from transformers import AutoProcessor
    print("加载处理器（含视觉）...", flush=True)
    proc = AutoProcessor.from_pretrained(os.path.join(args.model, "processor"))
    img_tok = proc.tokenizer.encode("<|image_pad|>")[0]
    sys_tk = proc.apply_chat_template([{"role":"system","content":[{"type":"text","text":SYS_PROMPT}]}],
                                      tokenize=True, return_dict=False)
    drop_idx = len(sys_tk[0]) if sys_tk and isinstance(sys_tk[0], (list, tuple)) else len(sys_tk)
    print(f"  _drop_idx={drop_idx}  image_token_id={img_tok}", flush=True)

    print("加载文本编码器（4-bit, CPU）...", flush=True)
    from transformers import Qwen3VLForConditionalGeneration, BitsAndBytesConfig
    t0 = time.time()
    te = Qwen3VLForConditionalGeneration.from_pretrained(
        os.path.join(args.model, "text_encoder"),
        quantization_config=BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type="nf4",
                                               bnb_4bit_compute_dtype=torch.bfloat16,
                                               bnb_4bit_use_double_quant=True),
        device_map={"": "cpu"}, low_cpu_mem_usage=True)
    te.eval()
    print(f"  完成 {time.time()-t0:.0f}s", flush=True)
    lm = getattr(te.model, "language_model", te.model)

    ref = Image.open(args.ref).convert("RGBA")
    white = Image.new("RGB", ref.size, (255, 255, 255))
    white.paste(ref, mask=ref.getchannel("A"))
    ref_rgb = white
    # 关键：管线会把参考图缩放到 output_resolution² 像素后再喂 VAE 与视觉塔（默认 1024²）
    # 这里必须用同样的缩放，否则视觉塔 token 数（÷4 后）与 VAE 潜变量数对不上
    try:
        from diffusers.pipelines.qwenimage21.pipeline_qwenimage21 import calculate_dimensions
    except Exception:
        from diffusers.utils import calculate_dimensions
    iw, ih, _ = calculate_dimensions(args.output_resolution ** 2, ref_rgb.width / ref_rgb.height)
    ref_rgb = ref_rgb.resize((iw, ih), Image.LANCZOS)
    print(f"参考图 {args.ref}  {ref.size} → 缩放为 {ref_rgb.size}（视觉塔输入）", flush=True)

    tasks = json.load(open(args.tasks, encoding="utf-8"))
    index = {}
    for t in tasks:
        pid, pr = t["id"], t["prompt"]
        text = T_I2I.format(pr)
        mi = proc(text=[text], images=[ref_rgb], padding=True, padding_side="left", return_tensors="pt")
        fk = {"input_ids": mi.input_ids, "attention_mask": mi.attention_mask, "output_hidden_states": True}
        if hasattr(mi, "pixel_values"): fk["pixel_values"] = mi.pixel_values
        if hasattr(mi, "image_grid_thw"): fk["image_grid_thw"] = mi.image_grid_thw
        if hasattr(mi, "mm_token_type_ids"): fk["mm_token_type_ids"] = mi.mm_token_type_ids
        h = lm.norm.register_forward_hook(lambda m, a, o: a[0])
        try:
            with torch.no_grad():
                out = te(**fk)
        finally:
            h.remove()
        hs_all = out.hidden_states
        am = mi.attention_mask
        # image_pad_mask：哪些 token 是图像占位
        ipm = (mi.input_ids[am.bool()] == img_tok)
        ipm = ipm[drop_idx:]
        rec = {"id": pid, "prompt": pr, "ref": os.path.abspath(args.ref),
               "ntok": int(mi.input_ids.shape[1]), "n_img_tok": int(ipm.sum()), "layers": {}}
        for j in layers:
            if j >= len(hs_all): continue
            sp = [e[drop_idx:] for e in extract_masked(hs_all[j], am)]
            mx = max(e.size(0) for e in sp)
            emb = torch.stack([torch.cat([u, u.new_zeros(mx-u.size(0), u.size(1))]) for u in sp])
            msk = torch.stack([torch.cat([torch.ones(u.size(0), dtype=torch.long),
                                          torch.zeros(mx-u.size(0), dtype=torch.long)]) for u in sp])
            ip = torch.cat([ipm, torch.zeros(mx-ipm.size(0), dtype=torch.bool)]).unsqueeze(0)  # [1, seq]
            rec["layers"][j] = {"embeds": emb.to(torch.bfloat16), "mask": msk, "image_pad_mask": ip}
        torch.save(rec, os.path.join(args.out, f"ed_{pid}.pt"))
        index[pid] = {"file": f"ed_{pid}.pt", "prompt": pr, "ntok": rec["ntok"],
                      "n_img_tok": rec["n_img_tok"], "layers": sorted(rec["layers"].keys()),
                      "ref": os.path.abspath(args.ref)}
        print(f"  {pid}: token={rec['ntok']} 其中图像token={rec['n_img_tok']}", flush=True)
    json.dump(index, open(os.path.join(args.out, "index.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    print(f"完成 -> {args.out}", flush=True)


if __name__ == "__main__":
    sys.exit(main())
