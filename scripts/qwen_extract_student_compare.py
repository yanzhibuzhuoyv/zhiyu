"""Compare six-layer native features with a trusted same-token HF reference."""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np


def matrix(folder):
    metadata = json.loads((folder / 'metadata.json').read_text(encoding='utf-8'))
    shape = metadata['shape']
    if len(shape) != 3 or shape[0] != 1 or not 1 <= shape[1] <= 512 or shape[2] != 6144:
        raise ValueError('Expected bounded [1,tokens,6144] features')
    if metadata['dtype'] != 'float32' or metadata.get('tokens_dropped') != 0:
        raise ValueError('Expected untrimmed float32 features')
    raw = folder / 'features.f32'
    if raw.stat().st_size != shape[1] * 6144 * 4:
        raise ValueError('Feature byte count differs')
    array = np.fromfile(raw, dtype='<f4').reshape(shape[1], 6, 1024)
    if not np.isfinite(array).all():
        raise ValueError('Non-finite feature matrix')
    return array, metadata


def compare(reference, actual):
    left, left_meta = matrix(reference)
    right, right_meta = matrix(actual)
    if left.shape != right.shape:
        raise ValueError('Token count or feature shape differs')
    if left_meta['hf_hidden_state_indices'] != right_meta['hf_hidden_state_indices']:
        raise ValueError('Feature layer order differs')
    if left_meta['token_ids_sha256'] != right_meta['token_ids_sha256']:
        raise ValueError('Input token identities differ')
    denominator = np.linalg.norm(left, axis=-1) * np.linalg.norm(right, axis=-1)
    if (denominator == 0).any():
        raise ValueError('Zero-norm reference or actual layer vector')
    cosine = np.sum(left * right, axis=-1) / denominator
    return {'reference': str(reference), 'actual': str(actual), 'shape': [1, left.shape[0], 6144],
            'mean_layer_cosines': np.mean(cosine, axis=0).tolist(),
            'min_token_layer_cosines': np.min(cosine, axis=0).tolist(),
            'mean_layer_absolute_errors': np.abs(left - right).mean(axis=(0, 2)).tolist(),
            'hf_indices': left_meta['hf_hidden_state_indices'],
            'reference_features_sha256': hashlib.sha256((reference / 'features.f32').read_bytes()).hexdigest(),
            'actual_features_sha256': hashlib.sha256((actual / 'features.f32').read_bytes()).hexdigest(),
            'scope': 'hidden-state equivalence only; not image quality or Android performance'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', type=Path, required=True)
    parser.add_argument('--actual', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    result = compare(args.reference, args.actual)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    with args.report.open('x', encoding='utf-8') as output:
        json.dump(result, output, indent=2)
        output.write('\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
