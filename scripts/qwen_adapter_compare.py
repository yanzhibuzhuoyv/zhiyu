"""Compare native FP32 adapter and BF16 output with the original safe checkpoint."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

import numpy as np
import torch
from torch import nn


class ReferenceAdapter(nn.Module):
    def __init__(self):
        super().__init__()
        self.norm = nn.LayerNorm(6144)
        self.net = nn.Sequential(nn.Linear(6144, 4096), nn.GELU(), nn.Linear(4096, 4096))

    def forward(self, features):
        return self.net(self.norm(features))[:, 14:].to(torch.bfloat16).float()


def metrics(actual, expected):
    if actual.shape != expected.shape or not np.isfinite(actual).all():
        raise ValueError('Invalid condition shape or non-finite output')
    a, b = actual.astype(np.float64), expected.astype(np.float64)
    cosine = (a * b).sum(axis=-1) / np.maximum(np.linalg.norm(a, axis=-1) * np.linalg.norm(b, axis=-1), 1e-30)
    error = np.abs(a - b)
    return {'token_cosine_min': float(cosine.min()), 'token_cosine_mean': float(cosine.mean()),
            'mae': float(error.mean()), 'max_absolute_error': float(error.max()),
            'relative_l2': float(np.linalg.norm(a-b) / max(np.linalg.norm(b), 1e-30)),
            'identical_bf16_fraction': float((actual == expected).mean())}


def read_features(directory):
    info = json.loads((directory / 'metadata.json').read_text(encoding='utf-8'))
    shape = info['shape']
    if shape[0] != 1 or shape[2] != 6144 or not 15 <= shape[1] <= 512:
        raise ValueError('Unsupported features')
    data = (directory / 'features.f32').read_bytes()
    if len(data) != int(np.prod(shape)) * 4:
        raise ValueError('Truncated features')
    tokens = (directory / 'tokens.i32').read_bytes() if (directory / 'tokens.i32').exists() else None
    if tokens is not None and hashlib.sha256(tokens).hexdigest() != info['token_ids_sha256']:
        raise ValueError('Token SHA differs')
    features = np.frombuffer(data, dtype='<f4').reshape(shape).copy()
    if not np.isfinite(features).all():
        raise ValueError('Non-finite features')
    return features, info


def compare(args):
    output = args.output.resolve()
    if output.exists() or output.is_relative_to(args.model_root.resolve()):
        raise ValueError('Use a fresh output outside the original model')
    output.mkdir(parents=True)
    torch.set_num_threads(2)
    reference = ReferenceAdapter().eval()
    checkpoint = torch.load(args.model_root / 'distill/v3_adapter_0.6b/adapter_best.pt',
                            weights_only=True, map_location='cpu', mmap=True)
    if checkpoint['in_dim'] != 6144:
        raise ValueError('Adapter dimensions differ')
    state = checkpoint['state_dict']
    reference.load_state_dict(state, strict=True)
    del state
    env = dict(os.environ)
    env['PATH'] = str(args.compiler_runtime) + os.pathsep + env['PATH']
    reports = []
    for case in (1, 2, 3):
        hf_features, hf_info = read_features(args.features_root / f'hf-reference-bf16/case-{case}')
        with torch.inference_mode():
            hf_condition = reference(torch.from_numpy(hf_features)).numpy()
        for precision in ('f16', 'q4'):
            directory = args.features_root / f'native-{precision}-final-case-{case}'
            features, info = read_features(directory)
            if info['token_ids_sha256'] != hf_info['token_ids_sha256']:
                raise ValueError('Native and HF token IDs differ')
            target = output / f'{precision}-case-{case}.f32'
            started = time.perf_counter()
            result = subprocess.run([str(args.executable), str(args.adapter), str(directory / 'features.f32'),
                                     str(features.shape[1]), str(target)], env=env, capture_output=True, text=True)
            if result.returncode:
                raise RuntimeError(result.stderr)
            condition = np.fromfile(target, dtype='<f4').reshape(1, features.shape[1]-14, 4096)
            with torch.inference_mode():
                same_input = reference(torch.from_numpy(features)).numpy()
            parity = metrics(condition, same_input)
            if parity['token_cosine_min'] < 0.99999 or parity['relative_l2'] > 0.003:
                raise ValueError(f'Native adapter parity failed: {parity}')
            reports.append({'case': case, 'precision': precision, 'input_tokens': features.shape[1],
                            'condition_shape': list(condition.shape), 'finite': True,
                            'native_process_seconds': time.perf_counter()-started,
                            'native_stdout': result.stdout.strip(), 'same_input_pytorch': parity,
                            'versus_original_bf16_student': metrics(condition, hf_condition),
                            'conditions_sha256': hashlib.sha256(target.read_bytes()).hexdigest()})
    report = {'scope': 'desktop CPU numerical parity only; no image or Android runtime claim',
              'android_tested': False, 'cases': reports}
    (output / 'report.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({'cases': len(reports), 'parity_passed': True, 'report': str(output / 'report.json')}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('model-root', 'features-root', 'adapter', 'executable', 'compiler-runtime', 'output'):
        parser.add_argument('--'+name, type=Path, required=True)
    compare(parser.parse_args())


if __name__ == '__main__':
    main()
