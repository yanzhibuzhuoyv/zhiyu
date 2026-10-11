"""Read tensor headers and exported-file sizes; never loads diffusion weights or writes to the model folder."""
import argparse
from collections import Counter
import json
import math
from pathlib import Path
import struct

DTYPE_BYTES = {'BF16': 2, 'F16': 2, 'F32': 4, 'F64': 8, 'I8': 1, 'U8': 1,
               'I16': 2, 'U16': 2, 'I32': 4, 'U32': 4, 'I64': 8, 'U64': 8, 'BOOL': 1}


def audit_safetensors(path):
    with path.open('rb') as stream:
        raw = stream.read(8)
        if len(raw) != 8:
            raise ValueError('Missing safetensors header')
        length = struct.unpack('<Q', raw)[0]
        if not 2 <= length <= 16 * 1024 * 1024:
            raise ValueError('Unexpected header size')
        header = json.loads(stream.read(length))
    formats = Counter()
    parameters = 0
    payload = 0
    offsets = []
    for name, tensor in header.items():
        if name == '__metadata__':
            continue
        shape = tensor['shape']
        if not isinstance(shape, list) or not all(type(size) is int and 0 <= size <= 2**31 for size in shape):
            raise ValueError('Invalid tensor dimensions')
        count = math.prod(shape)
        begin, end = tensor['data_offsets']
        if type(begin) is not int or type(end) is not int or not 0 <= begin <= end <= path.stat().st_size - 8 - length:
            raise ValueError('Invalid tensor offsets')
        dtype = tensor['dtype']
        if dtype not in DTYPE_BYTES or end - begin != count * DTYPE_BYTES[dtype]:
            raise ValueError('Tensor dtype/shape does not match its payload bytes')
        offsets.append((begin, end))
        parameters += count
        formats[tensor['dtype']] += count
        payload += end - begin
    ordered = sorted(offsets)
    if ordered and (ordered[0][0] != 0 or any(right[0] != left[1] for left, right in zip(ordered, ordered[1:]))):
        raise ValueError('Unexpected payload gaps/overlaps')
    if payload + 8 + length != path.stat().st_size:
        raise ValueError('File size does not match tensor payload')
    return {'path': str(path), 'file_bytes': path.stat().st_size, 'tensors': len(offsets),
            'parameters': parameters, 'dtype_parameters': dict(formats), 'tensor_bytes': payload,
            'tensor_names': [name for name in header if name != '__metadata__']}


def aggregate(items):
    formats = Counter()
    for item in items:
        formats.update(item['dtype_parameters'])
    return {'files': items, 'file_bytes': sum(item['file_bytes'] for item in items),
            'parameters': sum(item['parameters'] for item in items), 'dtype_parameters': dict(formats)}


def audit_transformer(folder):
    paths = sorted(folder.glob('*.safetensors'))
    if not paths:
        raise ValueError('Missing required diffusion transformer weights')
    index_path = folder / 'diffusion_pytorch_model.safetensors.index.json'
    if len(paths) > 1 and not index_path.is_file():
        raise ValueError('Multi-shard transformer requires its index')
    items = [audit_safetensors(path) for path in paths]
    if index_path.is_file():
        index = json.loads(index_path.read_text(encoding='utf-8'))
        weights = index.get('weight_map')
        if not isinstance(weights, dict) or not weights:
            raise ValueError('Missing transformer weight map')
        if any(not isinstance(name, str) or not isinstance(filename, str)
               or Path(filename).name != filename for name, filename in weights.items()):
            raise ValueError('Invalid transformer index paths')
        if set(weights.values()) != {path.name for path in paths}:
            raise ValueError('Transformer shard files differ from index')
        for item in items:
            expected = {name for name, filename in weights.items() if filename == Path(item['path']).name}
            if expected != set(item['tensor_names']):
                raise ValueError('Transformer tensor names differ from index')
        total = index.get('metadata', {}).get('total_size')
        if total is not None and total != sum(item['tensor_bytes'] for item in items):
            raise ValueError('Transformer payload bytes differ from index')
    return aggregate(items)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model-root', required=True, type=Path)
    parser.add_argument('--report', required=True, type=Path)
    args = parser.parse_args()
    root = args.model_root.resolve(strict=True)
    args.report = args.report.resolve()
    if args.report.exists() or args.report.is_relative_to(root):
        raise ValueError('Use a new report path outside the original model folder')
    base = root / 'models/Qwen-Image-2.1-Turbo'
    dit = audit_transformer(base / 'transformer')
    vae = aggregate([audit_safetensors(base / 'vae/diffusion_pytorch_model.safetensors')])
    student = aggregate([audit_safetensors(root / 'models/Qwen3-0.6B-Base/model.safetensors')])
    small_gguf = root / 'distill/gguf/qwen3-0.6b-base-Q4_K_M.gguf'
    adapter = root / 'distill/v3_adapter_0.6b/adapter_best.pt'
    android_exports = [str(path.relative_to(root)) for folder in (root / 'models', root / 'distill')
                       for path in folder.rglob('*') if path.is_file() and path.suffix.lower() in ('.onnx', '.mnn', '.ncnn', '.tflite', '.pte', '.so')]
    gguf_files = [{'path': str(path.relative_to(root)), 'file_bytes': path.stat().st_size}
                  for folder in (root / 'models', root / 'distill') for path in folder.rglob('*.gguf')]
    result = {
        'model_root': str(root), 'device_ram_gb': 12, 'scope': 'read-only tensor headers and file sizes',
        'dit': dit, 'vae': vae, 'student_bf16': student,
        'student_q4_gguf_bytes': small_gguf.stat().st_size, 'adapter_fp32_checkpoint_bytes': adapter.stat().st_size,
        'dit_config': json.loads((base / 'transformer/config.json').read_text(encoding='utf-8')),
        'android_exports': android_exports,
        'checked_export_roots': ['models', 'distill'], 'gguf_files': gguf_files,
        'existing_minimum_files_bytes': dit['file_bytes'] + vae['file_bytes'] + small_gguf.stat().st_size + adapter.stat().st_size,
        'ideal_dit_int4_tensor_bytes': math.ceil(dit['parameters'] / 2),
        'int4_dit_vae_fp16_student_q4_adapter_fp32_floor_bytes': math.ceil(dit['parameters'] / 2) +
            vae['parameters'] * 2 + small_gguf.stat().st_size + adapter.stat().st_size,
        'floor_excludes': ['quantization scales/unquantized tensors', 'tokenizers', 'inference activations', 'driver/workspace', 'Android/app/other models'],
        'checked_dit_safetensors_are_all_bf16': dit['dtype_parameters'] == {'BF16': dit['parameters']},
        'android_execution_tested': False,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    with args.report.open('x', encoding='utf-8') as output:
        output.write(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({key: result[key] for key in ('dit', 'vae', 'student_q4_gguf_bytes', 'adapter_fp32_checkpoint_bytes',
                                                  'existing_minimum_files_bytes', 'int4_dit_vae_fp16_student_q4_adapter_fp32_floor_bytes', 'android_exports')},
                     ensure_ascii=False))


if __name__ == '__main__':
    main()
