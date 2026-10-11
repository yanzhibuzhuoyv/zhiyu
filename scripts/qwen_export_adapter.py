"""Export the existing adapter to a fixed, pickle-free native format outside the source model folder."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import sys
import uuid

HEADER = struct.Struct('<8s7I6i')
TENSORS = (('norm.weight', (6144,)), ('norm.bias', (6144,)),
           ('net.0.weight', (4096, 6144)), ('net.0.bias', (4096,)),
           ('net.2.weight', (4096, 4096)), ('net.2.bias', (4096,)))
LAYERS = (-1, -4, -7, -10, -13, -16)
EXPECTED_BYTES = 167_854_140


def write_weights(checkpoint, stream):
    import torch
    if checkpoint.get('in_dim') != 6144 or set(checkpoint['state_dict']) != {name for name, _ in TENSORS}:
        raise ValueError('Unexpected adapter checkpoint contract')
    stream.write(HEADER.pack(b'ZQADAP1\0', 1, 6144, 4096, 4096, 6, 14, 1, *LAYERS))
    for name, shape in TENSORS:
        tensor = checkpoint['state_dict'][name]
        if not isinstance(tensor, torch.Tensor) or tuple(tensor.shape) != shape or tensor.dtype != torch.float32:
            raise ValueError(f'Unexpected adapter tensor: {name}')
        flat = tensor.detach().contiguous().reshape(-1)
        for offset in range(0, flat.numel(), 1024 * 1024):
            block = flat[offset:offset + 1024 * 1024]
            if not torch.isfinite(block).all().item():
                raise ValueError(f'Non-finite adapter tensor: {name}')
            stream.write(block.numpy().astype('<f4', copy=False).tobytes())


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def export(root, output):
    import torch
    if sys.byteorder != 'little':
        raise RuntimeError('The native format requires little-endian weights')
    source = root / 'distill/v3_adapter_0.6b/adapter_best.pt'
    output = output.resolve()
    metadata = output.with_name(output.name + '.json')
    if output.is_relative_to(root) or output.exists() or metadata.exists():
        raise ValueError('Use a new output file outside the original model folder')
    torch.set_num_threads(2)
    source_sha = digest(source)
    checkpoint = torch.load(source, map_location='cpu', weights_only=True, mmap=True)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(output.name + '.partial-' + uuid.uuid4().hex)
    try:
        with temporary.open('xb') as stream:
            write_weights(checkpoint, stream)
            stream.flush()
            os.fsync(stream.fileno())
        if temporary.stat().st_size != EXPECTED_BYTES:
            raise ValueError('Unexpected exported adapter size')
        if digest(source) != source_sha:
            raise ValueError('Source checkpoint changed during export')
        result = {'format': 'zqadap1_f32_le', 'header_bytes': HEADER.size,
                  'path': str(output), 'bytes': EXPECTED_BYTES, 'sha256': digest(temporary),
                  'source_checkpoint_sha256': source_sha, 'source_checkpoint_bytes': source.stat().st_size,
                  'layers': LAYERS, 'drop_tokens': 14, 'input_dim': 6144, 'output_dim': 4096,
                  'tensor_order': [{'name': name, 'shape': shape} for name, shape in TENSORS],
                  'scope': 'adapter-only export; not an Android image generator',
                  'metadata_pair_atomic': False}
        # Same-volume exclusive publication: never overwrite an existing destination.
        os.link(temporary, output)
        with metadata.open('x', encoding='utf-8') as report:
            json.dump(result, report, ensure_ascii=False, indent=2)
            report.write('\n')
        return result
    finally:
        if temporary.exists():
            temporary.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model-root', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    result = export(args.model_root.resolve(strict=True), args.output)
    print(json.dumps({key: result[key] for key in ('format', 'bytes', 'sha256', 'scope')}))


if __name__ == '__main__':
    main()
