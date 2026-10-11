"""Export bounded synthetic PyTorch conditions for native student comparison, offline CPU only."""
import argparse
import gc
import hashlib
import json
import os
from pathlib import Path
import struct
import time

os.environ['HF_HUB_OFFLINE'] = '1'
os.environ['TRANSFORMERS_OFFLINE'] = '1'
os.environ['TOKENIZERS_PARALLELISM'] = 'false'

import psutil
import torch
from transformers import AutoModel, AutoTokenizer

INDICES = (-1, -4, -7, -10, -13, -16)
TEMPLATE = ('<|im_start|>system\nComprehend and analyze the provided prompt.<|im_end|>\n'
            '<|im_start|>user\n{}<|im_end|>\n<|im_start|>assistant\n')
PROMPTS = (
    'A watercolor lakeside garden at sunrise, pale pink flowers, no people, no text.',
    '窗外下着小雨，书桌上放着温水和一盆绿植。画面中不要出现人物或文字。',
    'A quiet bookshop near the river at dusk. Warm lights shine on wooden shelves and a sleeping cat. '
    'The wet cobblestone path reflects the pale pink sky. No human figures and no printed text. ' * 5,
)


def export_reference(root, output, dtype):
    if output.exists() or output.is_relative_to(root):
        raise ValueError('Use a fresh directory outside original weights')
    if psutil.virtual_memory().available < 4 * 1024**3:
        raise RuntimeError('At least 4 GiB available desktop RAM required')
    torch.set_num_threads(4)
    tokenizer = AutoTokenizer.from_pretrained(root / 'models/Qwen-Image-2.1-Turbo/processor',
                                               local_files_only=True, trust_remote_code=False)
    model = AutoModel.from_pretrained(root / 'models/Qwen3-0.6B-Base',
                                     dtype=getattr(torch, dtype), local_files_only=True,
                                     trust_remote_code=False, use_safetensors=True,
                                     attn_implementation='eager').eval()
    if model.config.num_hidden_layers != 28 or model.config.hidden_size != 1024:
        raise ValueError('Expected 28-layer, 1024-dimensional Qwen3 student')
    output.mkdir(parents=True)
    cases = []
    for index, prompt in enumerate(PROMPTS, 1):
        inputs = tokenizer(TEMPLATE.format(prompt), return_tensors='pt', add_special_tokens=True)
        ids = inputs['input_ids'][0].tolist()
        if not 15 <= len(ids) <= 512:
            raise ValueError('Case token count outside bounded diagnostic range')
        started = time.perf_counter()
        with torch.inference_mode():
            result = model(**inputs, output_hidden_states=True, return_dict=True, use_cache=False)
            features = torch.cat([result.hidden_states[i] for i in INDICES], dim=-1).float()
        if not torch.isfinite(features).all().item():
            raise ValueError('Non-finite reference features')
        case = output / f'case-{index}'
        case.mkdir()
        token_bytes = struct.pack('<' + 'i' * len(ids), *ids)
        (case / 'tokens.i32').write_bytes(token_bytes)
        (case / 'features.f32').write_bytes(features.numpy().astype('<f4', copy=False).tobytes())
        info = {'case': index, 'synthetic_prompt': prompt, 'shape': list(features.shape),
                'dtype': 'float32', 'computation_dtype': dtype,
                'hf_hidden_state_indices': INDICES, 'tokens_dropped': 0,
                'token_ids_sha256': hashlib.sha256(token_bytes).hexdigest(),
                'forward_seconds': time.perf_counter() - started,
                'threads': 4, 'android_tested': False}
        (case / 'metadata.json').write_text(json.dumps(info, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        cases.append(info)
        del result, features
        gc.collect()
    summary = {'scope': 'offline CPU synthetic HF reference only', 'cases': cases,
               'desktop_peak_working_set_bytes': getattr(psutil.Process().memory_info(), 'peak_wset', None)}
    (output / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'cases': len(cases), 'computation_dtype': dtype,
                      'tokens': [c['shape'][1] for c in cases], 'output': str(output)}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model-root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--dtype', choices=('bfloat16', 'float32'), default='float32')
    args = parser.parse_args()
    export_reference(args.model_root.resolve(strict=True), args.output.resolve(), args.dtype)


if __name__ == '__main__':
    main()
