"""Compare already pulled phone features/conditions; never launch another phone job."""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import torch

from qwen_adapter_compare import ReferenceAdapter, metrics, read_features
from qwen_extract_student_compare import compare as compare_features


def verify(args):
    if args.report.exists():
        raise FileExistsError('Choose a new parity report')
    torch.set_num_threads(2)
    features, info = read_features(args.features)
    shape = (1, features.shape[1]-14, 4096)
    if args.conditions.stat().st_size != int(np.prod(shape))*4:
        raise ValueError('Phone condition byte count differs')
    actual = np.fromfile(args.conditions, dtype='<f4').reshape(shape)
    checkpoint = torch.load(args.model_root / 'distill/v3_adapter_0.6b/adapter_best.pt',
                            map_location='cpu', weights_only=True, mmap=True)
    if checkpoint['in_dim'] != 6144:
        raise ValueError('Original adapter dimensions differ')
    reference = ReferenceAdapter().eval()
    reference.load_state_dict(checkpoint['state_dict'], strict=True)
    with torch.inference_mode():
        expected = reference(torch.from_numpy(features)).numpy()
    parity = metrics(actual, expected)
    passed = parity['token_cosine_min'] >= 0.99999 and parity['relative_l2'] <= 0.003
    result = {'scope': 'same-input phone adapter numeric parity; not a picture quality claim',
              'condition_shape': list(shape), 'same_input_pytorch': parity,
              'condition_sha256': hashlib.sha256(args.conditions.read_bytes()).hexdigest(),
              'input_token_ids_sha256': info['token_ids_sha256'],
              'student_versus_reference': compare_features(args.reference, args.features),
              'adapter_passed': passed}
    with args.report.open('x', encoding='utf-8') as report:
        json.dump(result, report, indent=2)
    print(json.dumps(result))
    if not passed:
        raise RuntimeError('Phone adapter parity failed; report preserved')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('model-root', 'features', 'conditions', 'reference', 'report'):
        parser.add_argument('--'+name, type=Path, required=True)
    verify(parser.parse_args())


if __name__ == '__main__':
    main()
