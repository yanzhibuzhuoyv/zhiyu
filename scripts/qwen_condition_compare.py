"""Same-token condition diagnostics; no equivalence threshold or image-quality claim."""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
from qwen_adapter_compare import metrics


def compare(args):
    if args.report.exists():
        raise FileExistsError('Choose a new comparison report')
    left_tokens = (args.reference / 'tokens.i32').read_bytes()
    right_tokens = args.tokens.read_bytes()
    count = len(left_tokens)//4
    if left_tokens != right_tokens or len(left_tokens) != count*4 or not 15 <= count <= 512:
        raise ValueError('Reference and actual token identities differ')
    shape = (1, count-14, 4096)
    files = [args.reference / 'conditions.f32', args.conditions]
    if any(path.stat().st_size != int(np.prod(shape))*4 for path in files):
        raise ValueError('Condition byte count differs')
    expected, actual = [np.fromfile(path, dtype='<f4').reshape(shape) for path in files]
    if not all(np.isfinite(matrix).all() for matrix in (expected, actual)):
        raise ValueError('Non-finite reference or actual')
    result = {'scope': 'same-token diagnostics against BF16 original teacher; not image quality',
              'condition_shape': list(shape), 'metrics': metrics(actual, expected),
              'reference_sha256': hashlib.sha256(files[0].read_bytes()).hexdigest(),
              'actual_sha256': hashlib.sha256(files[1].read_bytes()).hexdigest(),
              'token_ids_sha256': hashlib.sha256(left_tokens).hexdigest()}
    with args.report.open('x', encoding='utf-8') as report:
        json.dump(result, report, indent=2)
    print(json.dumps(result))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('reference', 'tokens', 'conditions', 'report'):
        parser.add_argument('--'+name, type=Path, required=True)
    compare(parser.parse_args())


if __name__ == '__main__':
    main()
