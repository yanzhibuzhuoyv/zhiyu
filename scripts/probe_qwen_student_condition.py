"""Offline CPU probe of the existing BF16 student; does not load the DiT or run on Android."""
import argparse
import gc
import json
import os
from pathlib import Path
import platform
import time

os.environ['HF_HUB_OFFLINE'] = '1'
os.environ['TRANSFORMERS_OFFLINE'] = '1'
os.environ['TOKENIZERS_PARALLELISM'] = 'false'

import psutil
import torch
from torch import nn
import transformers
from transformers import AutoModel, AutoTokenizer


LAYER_INDICES = (-1, -4, -7, -10, -13, -16)
DROP_TOKENS = 14
TEMPLATE = ('<|im_start|>system\nComprehend and analyze the provided prompt.<|im_end|>\n'
            '<|im_start|>user\n{}<|im_end|>\n<|im_start|>assistant\n')


class Adapter(nn.Module):
    def __init__(self):
        super().__init__()
        self.norm = nn.LayerNorm(6144)
        self.net = nn.Sequential(nn.Linear(6144, 4096), nn.GELU(), nn.Linear(4096, 4096))

    def forward(self, features):
        return self.net(self.norm(features))


def memory():
    info = psutil.Process().memory_info()
    return {'desktop_rss_bytes': info.rss,
            'desktop_peak_working_set_bytes': getattr(info, 'peak_wset', None)}


def encode(student, adapter, tokenizer, prompt):
    inputs = tokenizer([TEMPLATE.format(prompt)], padding=True, padding_side='left',
                       return_tensors='pt')
    if inputs['input_ids'].shape[1] > 512:
        raise ValueError('Diagnostic prompt exceeds the 512-token limit')
    started = time.perf_counter()
    with torch.inference_mode():
        output = student(**inputs, output_hidden_states=True, return_dict=True, use_cache=False)
        features = torch.cat([output.hidden_states[index] for index in LAYER_INDICES], dim=-1).float()
        student_seconds = time.perf_counter() - started
        started = time.perf_counter()
        projected = adapter(features)
        embeddings = projected[0][inputs['attention_mask'][0].bool()][DROP_TOKENS:].unsqueeze(0)
        adapter_seconds = time.perf_counter() - started
        if embeddings.shape[1] == 0 or embeddings.shape[2] != 4096:
            raise ValueError('Unexpected condition dimensions')
        if not torch.isfinite(embeddings).all().item():
            raise ValueError('Non-finite condition')
        result = embeddings.to(torch.bfloat16).clone()
    return result, {'input_tokens': inputs['input_ids'].shape[1],
                    'condition_shape': list(result.shape), 'finite': True,
                    'student_forward_seconds': student_seconds,
                    'adapter_seconds': adapter_seconds, **memory()}


def compare_cache(embeddings, cache_path):
    cached = torch.load(cache_path, map_location='cpu', weights_only=True)
    reference = cached['layers'][36]['embeds'].float()
    if reference.shape != embeddings.shape:
        raise ValueError('Stored BF16 condition shape differs')
    reference_mask = cached['layers'][36]['mask']
    if not torch.equal(reference_mask, torch.ones(embeddings.shape[:2], dtype=torch.long)):
        raise ValueError('Stored attention mask differs')
    actual = embeddings.float()
    return {'cached_file': str(cache_path), 'same_shape_and_mask': True,
            'mean_token_cosine': torch.nn.functional.cosine_similarity(actual, reference, dim=-1).mean().item(),
            'mean_absolute_difference': (actual - reference).abs().mean().item(),
            'scope': 'CPU BF16 output compared with existing BF16 cache; not Q4 equivalence'}


def run_cases(root, student, adapter, tokenizer):
    index = json.loads((root / 'distill/v3_embeds_0.6b/index.json').read_text(encoding='utf-8'))
    sample = index['A01']
    prompts = (sample['prompt'],
               'A watercolor lakeside garden at sunrise, pale pink flowers, no people, no text.',
               '凌晨的窗外下着小雨，厨房里放着一杯温水。下午去河边散步，经过花店和书店，'
               '傍晚回家给窗台的小植物浇水。画面只呈现书桌、植物和窗外的雨，不出现人物。' * 4)
    cases = []
    cached_comparison = None
    for number, prompt in enumerate(prompts):
        embeddings, info = encode(student, adapter, tokenizer, prompt)
        if number == 0:
            cached_comparison = compare_cache(embeddings, root / 'distill/v3_embeds_0.6b/emb_A01.pt')
        cases.append({'case': number + 1, 'synthetic_prompt': prompt, **info})
    return cases, cached_comparison


def probe(root):
    torch.set_num_threads(4)
    checkpoint_path = root / 'distill/v3_adapter_0.6b/adapter_best.pt'
    checkpoint = torch.load(checkpoint_path, map_location='cpu', weights_only=True, mmap=True)
    if checkpoint.get('in_dim') != 6144:
        raise ValueError('Expected the six-layer 0.6B adapter')
    adapter = Adapter()
    adapter.load_state_dict(checkpoint['state_dict'], strict=True)
    adapter.eval()
    del checkpoint
    gc.collect()
    tokenizer = AutoTokenizer.from_pretrained(root / 'models/Qwen-Image-2.1-Turbo/processor',
                                               local_files_only=True, trust_remote_code=False)
    started = time.perf_counter()
    # Qwen3Model returns the same hidden states as the causal model, without allocating unused logits.
    student = AutoModel.from_pretrained(root / 'models/Qwen3-0.6B-Base', dtype=torch.bfloat16,
                                       local_files_only=True, trust_remote_code=False,
                                       use_safetensors=True, attn_implementation='eager')
    student.eval()
    if student.config.model_type != 'qwen3' or student.config.hidden_size != 1024:
        raise ValueError('Unexpected student architecture')
    load_seconds = time.perf_counter() - started
    cases, cached_comparison = run_cases(root, student, adapter, tokenizer)
    return {'scope': 'offline Windows CPU BF16 student and FP32 adapter only',
            'platform': platform.platform(), 'torch': torch.__version__,
            'transformers': transformers.__version__, 'threads': 4,
            'student_load_seconds': load_seconds, 'layer_indices': LAYER_INDICES,
            'drop_tokens': DROP_TOKENS, 'cases': cases, 'bf16_cached_comparison': cached_comparison,
            'q4_tested': False, 'diffusion_tested': False, 'android_tested': False, **memory()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model-root', required=True, type=Path)
    parser.add_argument('--report', required=True, type=Path)
    args = parser.parse_args()
    root = args.model_root.resolve(strict=True)
    report = args.report.resolve()
    if report.exists() or report.is_relative_to(root):
        raise ValueError('Use a new report path outside the original model folder')
    if psutil.virtual_memory().available < 3 * 1024**3:
        raise RuntimeError('Insufficient available desktop RAM for the bounded CPU probe')
    result = probe(root)
    report.parent.mkdir(parents=True, exist_ok=True)
    with report.open('x', encoding='utf-8') as output:
        json.dump(result, output, ensure_ascii=False, indent=2)
        output.write('\n')
    print(json.dumps(result, ensure_ascii=True))


if __name__ == '__main__':
    main()
